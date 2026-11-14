package dev.ccdesk.nativeapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.net.Socket
import java.net.InetSocketAddress
import java.net.InetAddress
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Opt-in physical Wi-Fi loss/recovery against an existing, explicitly named test session.
 * The outgoing protocol guard allows authentication and history subscription only.
 */
@RunWith(AndroidJUnit4::class)
class RealNetworkRecoveryTest {
    @Test fun wifiLossPreservesHistoryAndReauthenticatesWithoutDuplicateEvents() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Real network acceptance requires -e runRealNetwork true", arguments.getString("runRealNetwork") == "true")
        val requestedSession = arguments.getString("sessionId")?.takeIf { it.isNotBlank() }
            ?: error("An existing test sessionId is required")
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val original = AndroidWorkbenchTransport(context)
        val isolated = IsolatedContext(context)
        val transport = AndroidWorkbenchTransport(isolated)
        val readOnly = ReadOnlyTransport(transport,requestedSession)
        val repository = WorkbenchRepository(readOnly)
        val report = json("passed" to false, "startedAtEpochMs" to System.currentTimeMillis(),
            "wifiInitiallyEnabled" to false, "networkLossObserved" to false,
            "nonOnlineObserved" to false, "historyPreservedOffline" to false,
            "wifiRestored" to false, "reconnected" to false, "noDuplicateSequences" to false,
            "readOnlyProtocolEnforced" to true)
        var restoreRequired = false
        var endpoint: String? = null
        var credential: String? = null
        var primaryFailure: Throwable? = null
        var recoveryRouteProbe: (suspend () -> Boolean)? = null
        val started = SystemClock.elapsedRealtime()
        fun save() { context.cacheDir.resolve("native-real-network-acceptance.json").writeText(report.toString(2)) }
        suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
            // Every caller uses a constant command; no host/session/user text enters a shell.
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
        fun wifiIsActive(): Boolean = connectivity.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } == true
        suspend fun enableWifi() { withTimeout(10_000) { shell("svc wifi enable") } }
        try {
            endpoint = original.getEndpoint()
            require(endpoint.startsWith("wss://")) { "This acceptance requires the configured direct WSS service" }
            require(URI(endpoint).host == arguments.getString("expectedHost")) { "expectedHost must explicitly match the configured service" }
            credential = original.credential(endpoint)
            require(credential.isNotBlank()) { "Connect the native app before opting into network acceptance" }
            transport.setEndpoint(endpoint)
            transport.setFingerprint(endpoint, original.fingerprint(endpoint))
            transport.saveCredential(endpoint, credential)
            repository.start()
            withTimeout(30_000) { repository.state.first { it.status == "online" } }
            val session = repository.state.value.sessions.firstOrNull { it.id == requestedSession }
                ?: error("The specified test session does not exist")
            require(session.title.startsWith("Native acceptance ")) { "Only a prior Native acceptance test session may be read" }
            require(session.activeRun == null) { "Select an idle acceptance session; this test never cancels or modifies runs" }
            report.put("testSessionTitleVerified", true)
            withTimeout(60_000) { repository.selectSession(requestedSession) }
            val before = repository.state.value.histories[requestedSession]
                ?: error("The selected test session has no loaded history")
            require(before.loaded && !before.loading && before.events.isNotEmpty()) { "Loaded durable test history is required" }
            val originalSequences = before.events.map { it.sequence }.toSet()
            check(originalSequences.size == before.events.size) { "Initial history contains duplicate sequences" }
            val initialSubscriptions = readOnly.acknowledgedSubscriptions.get()
            val initialConnections = readOnly.connections.get()
            report.put("initialDurableEventCount", originalSequences.size)
                .put("initialAcknowledgedSubscriptions", initialSubscriptions)
            val wifiOn = withTimeout(5_000) { shell("settings get global wifi_on").trim() }
            require(wifiOn == "1") { "Wi-Fi must already be enabled; no user-disabled radio will be changed" }
            require(wifiIsActive()) { "The active network must be Wi-Fi without a cellular or VPN bypass" }
            val target=URI(endpoint)
            // Resolve before changing the radio. A connect timeout does not bound DNS, and
            // an offline resolver failure alone must not be presented as TCP route loss.
            val addresses=withContext(Dispatchers.IO){InetAddress.getAllByName(target.host).distinctBy{it.hostAddress}}
            val port=if(target.port>0)target.port else 443
            suspend fun tcpReachable(address:InetAddress):Boolean=withContext(Dispatchers.IO){
                coroutineContext.ensureActive()
                try {Socket().use{it.connect(InetSocketAddress(address,port),1500)};true}
                catch (_:IOException){false}
            }
            val probeAddresses=addresses.filter{tcpReachable(it)}
            require(probeAddresses.isNotEmpty()) { "The configured service must be reachable before disabling Wi-Fi" }
            suspend fun reachable():Boolean {
                for(address in probeAddresses){coroutineContext.ensureActive();if(tcpReachable(address))return true}
                return false
            }
            recoveryRouteProbe = { reachable() }
            report.put("initialServiceTcpReachable",true).put("resolvedAddressCount",addresses.size)
                .put("initialReachableAddressCount",probeAddresses.size)
            report.put("wifiInitiallyEnabled", true).put("initialWifiTransportVerified", true)
            restoreRequired = true // Set before executing disable, including a partially failed command.
            val disabledAt = SystemClock.elapsedRealtime()
            save()
            withTimeout(10_000) { shell("svc wifi disable") }
            withTimeout(75_000) {
                var continuousLossAt: Long? = null
                var confirmedLoss = false
                while (true) {
                    val currentTime = SystemClock.elapsedRealtime()
                    val radioOff=shell("settings get global wifi_on").trim()=="0"
                    val serviceReachable=reachable()
                    val capabilities=connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
                    val bypass=capabilities?.let{it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}==true
                    check(!bypass) { "An alternate network became active during Wi-Fi loss" }
                    report.put("wifiOffObserved",radioOff).put("serviceReachableWhileWifiOff",serviceReachable)
                        .put("activeNetworkReportedWhileWifiOff",connectivity.activeNetwork!=null)
                    // Some emulators keep a cached activeNetwork even after their route disappears.
                    // Verify actual transport loss as well as the radio setting, without sending app data.
                    if (radioOff&&!serviceReachable) {
                        continuousLossAt = continuousLossAt ?: currentTime
                        if (!confirmedLoss && currentTime - continuousLossAt!! >= 1_000) {
                            confirmedLoss = true
                            report.put("networkLossObserved", true)
                                .put("networkLossDelayMs", currentTime - disabledAt)
                            save()
                        }
                    } else {
                        check(!confirmedLoss) { "The service became reachable again while Wi-Fi was disabled" }
                        continuousLossAt = null
                    }
                    save()
                    if (confirmedLoss && repository.state.value.status != "online") {
                        val offline = repository.state.value.histories[requestedSession]
                            ?: error("Loaded history disappeared during network loss")
                        val retained = offline.events.map { it.sequence }
                        check(retained.toSet().containsAll(originalSequences)) { "Durable history was lost while offline" }
                        check(retained.size == retained.distinct().size) { "Offline history has duplicate sequences" }
                        check(offline.fragments.isEmpty()) { "Transient stream fragments were not cleared on disconnect" }
                        report.put("nonOnlineObserved", true)
                            .put("offlineDelayMs", currentTime - disabledAt)
                            .put("historyPreservedOffline", true)
                            .put("offlineDurableEventCount", retained.size)
                        save()
                        break
                    }
                    delay(50)
                }
            }
            val restoredAt = SystemClock.elapsedRealtime()
            enableWifi()
            withTimeout(60_000) {
                while (true) {
                    val state = repository.state.value
                    val history = state.histories[requestedSession]
                    val sequences = history?.events?.map { it.sequence }.orEmpty()
                    if (wifiIsActive() && state.status == "online" &&
                        state.selectedId == requestedSession && history?.loaded == true && !history.loading &&
                        readOnly.acknowledgedSubscriptions.get() > initialSubscriptions &&
                        readOnly.currentSubscriptionRestored &&
                        sequences.toSet().containsAll(originalSequences)) {
                        check(sequences.size == sequences.distinct().size) { "Recovered history contains duplicate sequences" }
                        report.put("wifiRestored", true).put("reconnected", true)
                            .put("recoveryDelayMs", SystemClock.elapsedRealtime() - restoredAt)
                            .put("recoveredDurableEventCount", sequences.size)
                            .put("newSocketCount", readOnly.connections.get() - initialConnections)
                            .put("newAcknowledgedSubscriptionCount", readOnly.acknowledgedSubscriptions.get() - initialSubscriptions)
                            .put("currentSocketSubscriptionAcknowledged",true)
                            .put("noDuplicateSequences", true).put("allOriginalSequencesRetained", true)
                        break
                    }
                    delay(50)
                }
            }
            check(readOnly.rejectedWrites.get() == 0) { "A non-read-only protocol command was attempted" }
            check(original.getEndpoint() == endpoint && original.credential(endpoint) == credential) { "Regular application login changed unexpectedly" }
            report.put("regularLoginPreserved", true).put("passed", true)
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("failed", true)
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupComplete = true
                if (restoreRequired) {
                    // Do not toggle an already recovered radio a second time. Some emulators
                    // restart connectivity even for a redundant enable. Read the actual global
                    // setting through Android rather than a shell pipe after the ADB route changed.
                    val verificationStarted=SystemClock.elapsedRealtime()
                    report.put("finallyWifiEnableAttempted", false)
                    val restored = runCatching {
                        val alreadyEnabled=Settings.Global.getString(context.contentResolver,"wifi_on")=="1"
                        report.put("finallyWifiAlreadyEnabled",alreadyEnabled)
                        if(!alreadyEnabled){report.put("finallyWifiEnableAttempted",true);enableWifi()}
                        withTimeout(30_000) {
                            var stableSince:Long?=null
                            var stableChecks=0
                            while(true){
                                val enabled=Settings.Global.getString(context.contentResolver,"wifi_on")=="1"
                                val active=wifiIsActive()
                                val reachable=enabled&&active&&recoveryRouteProbe?.invoke()==true
                                report.put("finallyWifiSettingEnabled",enabled)
                                    .put("finallyWifiTransportActive",active)
                                    .put("finallyServiceTcpReachable",reachable)
                                val checkedAt=SystemClock.elapsedRealtime()
                                if(enabled&&active&&reachable){
                                    stableSince=stableSince?:checkedAt;stableChecks++
                                    if(stableChecks>=2&&checkedAt-stableSince!!>=500){
                                        report.put("finallyStableConnectivityChecks",stableChecks)
                                        break
                                    }
                                }else{stableSince=null;stableChecks=0}
                                delay(250)
                            }
                        }
                    }.isSuccess
                    report.put("finallyWifiRestored", restored)
                        .put("finallyWifiVerificationMs",SystemClock.elapsedRealtime()-verificationStarted)
                    cleanupComplete = restored
                }
                withContext(Dispatchers.Main.immediate) { repository.dispose() }
                cleanupComplete = runCatching {
                    withTimeout(10_000) { repository.awaitPersistenceDrain(); transport.awaitClosed() }
                }.isSuccess && cleanupComplete
                if (endpoint != null && credential != null) {
                    val preserved = runCatching {
                        withTimeout(5_000) { original.getEndpoint() == endpoint && original.credential(endpoint!!) == credential }
                    }.getOrDefault(false)
                    report.put("regularLoginPreserved", preserved)
                    cleanupComplete = preserved && cleanupComplete
                }
                original.close()
                cleanupComplete = runCatching { withTimeout(10_000) { original.awaitClosed() } }.isSuccess && cleanupComplete
                cleanupComplete = runCatching { isolated.clean() }.isSuccess && cleanupComplete
                report.put("isolatedCleanupComplete", cleanupComplete)
                    .put("rejectedWriteCount", readOnly.rejectedWrites.get())
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                if (!cleanupComplete) report.put("passed", false)
                save()
                if (!cleanupComplete && primaryFailure == null) error("Network acceptance cleanup or Wi-Fi restoration did not complete")
            }
        }
    }

    private class ReadOnlyTransport(private val delegate: WorkbenchTransport,private val targetSessionId:String) : WorkbenchTransport by delegate {
        val connections = AtomicInteger()
        val acknowledgedSubscriptions = AtomicInteger()
        val rejectedWrites = AtomicInteger()
        private val subscriptions = ConcurrentHashMap<String,Int>()
        private val acknowledgedConnection=AtomicInteger()
        private val snapshotConnection=AtomicInteger()
        val currentSubscriptionRestored get()=connections.get().let{it>0&&acknowledgedConnection.get()==it&&snapshotConnection.get()==it}
        override suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection {
            val connectionId=connections.incrementAndGet()
            val wrapped = object : SocketObserver {
                override fun opened() = observer.opened()
                override fun message(text: String) {
                    runCatching {
                        val frame = JSONObject(text)
                        if(connectionId==connections.get()) {
                            if(frame.optString("type")=="session.snapshot"&&frame.optJSONObject("session")?.optString("id")==targetSessionId) snapshotConnection.set(connectionId)
                            if (frame.optString("type") == "response" && frame.optBoolean("ok") &&
                                subscriptions.remove(frame.optString("requestId"))==connectionId) {
                                acknowledgedSubscriptions.incrementAndGet();acknowledgedConnection.set(connectionId)
                            }
                        }
                    }
                    observer.message(text)
                }
                override fun closed(code: Int, reason: String) = observer.closed(code,reason)
                override fun failed(cause: Throwable) = observer.failed(cause)
            }
            val connection = delegate.socket(endpoint,direct,wrapped)
            return object : SocketConnection {
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    val type = frame.optString("type")
                    if (type !in setOf("auth","device","session.subscribe")) {
                        rejectedWrites.incrementAndGet()
                        error("Only authentication and read-only history subscription are permitted")
                    }
                    if (type == "session.subscribe") {
                        check(frame.optString("sessionId")==targetSessionId) { "Only the explicitly selected test session may be subscribed" }
                        subscriptions[frame.getString("requestId")]=connectionId
                    }
                    return connection.send(text)
                }
                override fun close() = connection.close()
            }
        }
    }
    private class IsolatedContext(private val base: Context) : ContextWrapper(base) {
        private val prefix = "real-network-native-" + UUID.randomUUID()
        private val names = mutableSetOf<String>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "$prefix-$name"
            synchronized(names) { names += scoped }
            return base.getSharedPreferences(scoped,mode)
        }
        fun clean() { synchronized(names) { names.forEach { check(base.deleteSharedPreferences(it)) { "Unable to remove isolated preferences" } } } }
    }
}
