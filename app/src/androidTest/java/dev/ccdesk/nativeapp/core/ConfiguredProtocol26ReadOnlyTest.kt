package dev.ccdesk.nativeapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt-in verification of the configured service. No business writes or user data in reports. */
@RunWith(AndroidJUnit4::class)
class ConfiguredProtocol26ReadOnlyTest {
    @Test fun configuredServiceLoadsExistingHistoryAndKeepsItsConnection() = runBlocking<Unit> {
        assumeTrue("Requires -e runLive true", InstrumentationRegistry.getArguments().getString("runLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Decryption failure may remove an invalid saved secret. Read through a memory snapshot so
        // even that recovery path cannot change the user's actual preferences or saved credentials.
        val saved = MemoryContext(context, copySaved = true)
        val isolated = MemoryContext(context, copySaved = false)
        val original = AndroidWorkbenchTransport(saved)
        val transport = AndroidWorkbenchTransport(isolated)
        val counts = FrameCounts()
        val mismatch = AtomicBoolean()
        val readyProtocol = AtomicInteger()
        val existingSessions = ConcurrentHashMap.newKeySet<String>()
        val serviceVersion = AtomicReference<String?>()
        val report = json("status" to "running", "clientProtocol" to PROTOCOL_VERSION,
            "historyLoaded" to false, "earlierPageStatus" to "notChecked",
            "foregroundStatus" to "notChecked", "cleanupComplete" to false)
        val reportFile = context.cacheDir.resolve("protocol26-live-readonly.json")
        fun save() {
            report.put("serviceProtocol", readyProtocol.get().takeIf { it > 0 } ?: JSONObject.NULL)
                .put("serviceVersion", serviceVersion.get() ?: JSONObject.NULL)
                .put("frameCounts", counts.json())
            reportFile.writeText(report.toString(2))
        }
        val observed = object : WorkbenchTransport by transport {
            override suspend fun deviceId(): String = original.deviceId()
            override suspend fun socket(endpoint: String, observer: SocketObserver): SocketConnection {
                counts.connections.incrementAndGet()
                val socket = transport.socket(endpoint, object : SocketObserver by observer {
                    override fun opened() { counts.opened.incrementAndGet(); observer.opened() }
                    override fun message(text: String) {
                        counts.incoming.incrementAndGet()
                        runCatching { JSONObject(text) }.getOrNull()?.let { frame ->
                            when (frame.optString("type")) {
                                "ready" -> {
                                    readyProtocol.set(frame.optInt("protocolVersion"))
                                    // Only a release number can enter the report, never arbitrary server text.
                                    serviceVersion.set(frame.optString("version").takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) })
                                    frame.optJSONArray("sessions").objects().forEach { session ->
                                        session.stringOrNull("id")?.let(existingSessions::add)
                                    }
                                }
                                "connection.error" -> if (frame.optString("code") == "version_mismatch") {
                                    mismatch.set(true)
                                    serviceVersion.set(frame.optString("service").takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) })
                                }
                                "session.snapshot" -> counts.snapshots.incrementAndGet()
                                "session.history.part" -> counts.parts.incrementAndGet()
                                "session.event" -> counts.events.incrementAndGet()
                                "session.fragment" -> counts.fragments.incrementAndGet()
                                "response" -> {
                                    counts.responses.incrementAndGet()
                                    if (!frame.optBoolean("ok")) counts.rejectedResponses.incrementAndGet()
                                }
                            }
                        }
                        observer.message(text)
                    }
                    override fun closed(code: Int, reason: String) {
                        counts.closed.incrementAndGet()
                        observer.closed(code, reason)
                    }
                    override fun failed(cause: Throwable) {
                        counts.failed.incrementAndGet()
                        observer.failed(cause)
                    }
                })
                val identity = original.deviceId()
                return object : SocketConnection {
                    override fun send(text: String): Boolean {
                        val frame = runCatching { JSONObject(text) }.getOrNull()
                        val type = frame?.optString("type")
                        val permitted = frame != null && when (type) {
                            "auth" -> frame.optInt("protocolVersion") == 26 && !frame.optBoolean("tunnel", true)
                            "device" -> frame.optString("id") == identity
                            "session.subscribe", "session.history", "session.status" -> frame.optString("sessionId") in existingSessions
                            "session.history.next", "session.history.cancel" -> true
                            else -> false
                        }
                        if (!permitted) { counts.blocked.incrementAndGet(); return false }
                        counts.outgoing.incrementAndGet()
                        when (type) {
                            "auth" -> counts.auth.incrementAndGet()
                            "device" -> counts.device.incrementAndGet()
                            "session.subscribe" -> counts.subscriptions.incrementAndGet()
                            "session.history" -> counts.historyRequests.incrementAndGet()
                            "session.status" -> counts.statusRequests.incrementAndGet()
                            "session.history.next" -> counts.acks.incrementAndGet()
                            "session.history.cancel" -> counts.cancels.incrementAndGet()
                        }
                        return socket.send(text)
                    }
                    override fun close() = socket.close()
                }
            }
        }
        val repository = WorkbenchRepository(observed)
        var stage = "configuration"
        var failure: String? = null
        fun stop(status: String): Nothing { report.put("status", status); save(); throw ReadOnlyFailure(status) }
        fun protocolMismatch(): Boolean = mismatch.get() || repository.state.value.error.orEmpty().let {
            it.contains("协议") || it.contains("protocol", ignoreCase = true) || it.contains("version", ignoreCase = true)
        }
        try {
            val endpoint = original.getEndpoint()
            val savedIdentity = saved.getSharedPreferences("ccdesk-native-v1", Context.MODE_PRIVATE).getString("device", null)
            if (!endpoint.startsWith("wss://") || savedIdentity.isNullOrBlank()) stop("notConfigured")
            val credential = original.credential(endpoint)
            if (credential.isBlank()) stop("notConfigured")
            // These setters write only the in-memory isolated Context, never disk preferences.
            transport.setEndpoint(endpoint)
            transport.setFingerprint(endpoint, original.fingerprint(endpoint))
            transport.saveCredential(endpoint, credential)
            stage = "connection"
            repository.start()
            val connected = withTimeout(35_000) {
                repository.state.first { it.status == "online" || it.status == "offline" || it.status == "setup" }
            }
            if (connected.status != "online") stop(if (protocolMismatch()) "protocolMismatch" else "offline")
            if (readyProtocol.get() != 26) stop("protocolMismatch")
            report.put("status", "online").put("sessionCount", connected.sessions.size)
            save()

            stage = "history"
            val serverDevices = connected.serverDeviceIds()
            val candidates = connected.sessions.sortedWith(compareBy<Session>(
                { if (it.activeRun == null) 0 else 1 },
                { if (it.device == null || it.device.server || it.device.id in serverDevices || it.device.id == connected.localDeviceId) 0 else 1 },
            ).thenByDescending { it.updatedAt }).take(12)
            var selected: Session? = null
            var initial: History? = null
            var sampled = 0
            withTimeout(180_000) {
                for (candidate in candidates) {
                    sampled++
                    repository.selectSession(candidate.id)
                    val history = repository.state.value.histories[candidate.id]
                    if (history == null || !history.loaded || history.loading || history.loadError != null) stop("historyFailed")
                    if (history.events.isNotEmpty()) { selected = candidate; initial = history; break }
                }
            }
            report.put("sessionsSampled", sampled)
            val session = selected ?: stop(if (sampled < connected.sessions.size) "noHistoryInSample" else "noHistoryAvailable")
            val history = initial ?: stop("historyFailed")
            report.put("historyLoaded", true).put("initialEventCount", history.events.size)
                .put("initialFragmentCount", history.fragments.size)
            val sequences = history.events.map { it.sequence }.toSet()
            if (sequences.size != history.events.size) stop("duplicateHistory")
            if (history.hasEarlier) {
                stage = "earlierPage"
                withTimeout(120_000) { repository.loadEarlier(session.id) }
                val earlier = repository.state.value.histories[session.id] ?: stop("earlierPageFailed")
                val expanded = earlier.events.map { it.sequence }.toSet()
                if (!earlier.loaded || earlier.loading || earlier.loadError != null ||
                    !expanded.containsAll(sequences) || expanded.size != earlier.events.size ||
                    (earlier.events.firstOrNull()?.sequence ?: Long.MAX_VALUE) >= history.events.first().sequence) stop("earlierPageFailed")
                report.put("earlierPageStatus", "passed").put("expandedEventCount", earlier.events.size)
            } else report.put("earlierPageStatus", "notAvailable")

            stage = "foreground"
            val before = counts.connections.get()
            repository.foreground(false)
            delay(250)
            repository.foreground(true)
            delay(1_000)
            if (repository.state.value.status != "online" || counts.connections.get() != before) stop("foregroundFailed")
            if (counts.blocked.get() != 0) stop("outgoingGuardFailed")
            report.put("foregroundStatus", "passed").put("status", "passed")
            save()
        } catch (error: Throwable) {
            // Do not attach causes or server-provided messages: they may include account content.
            val status = when {
                protocolMismatch() -> "protocolMismatch"
                error is ReadOnlyFailure -> error.status
                stage == "connection" || repository.state.value.status == "offline" -> "offline"
                else -> "failed"
            }
            failure = "Configured protocol 26 read-only verification did not pass ($status)."
            report.put("status", status).put("failedStage", stage)
            save()
        } finally {
            withContext(NonCancellable) {
                var clean = runCatching {
                    withContext(Dispatchers.Main.immediate) { repository.dispose() }
                    withTimeout(10_000) { repository.awaitPersistenceDrain(); transport.awaitClosed() }
                }.isSuccess
                original.close()
                clean = runCatching { withTimeout(10_000) { original.awaitClosed() } }.isSuccess && clean
                isolated.clear()
                saved.clear()
                report.put("cleanupComplete", clean)
                if (!clean) {
                    report.put("status", "cleanupFailed")
                    failure = "Configured protocol 26 read-only verification cleanup did not complete."
                }
                save()
            }
        }
        failure?.let { throw AssertionError(it) }
    }

    private class ReadOnlyFailure(val status: String) : RuntimeException()

    private class FrameCounts {
        val connections = AtomicInteger(); val opened = AtomicInteger(); val closed = AtomicInteger(); val failed = AtomicInteger()
        val incoming = AtomicInteger(); val outgoing = AtomicInteger(); val blocked = AtomicInteger()
        val snapshots = AtomicInteger(); val parts = AtomicInteger(); val events = AtomicInteger(); val fragments = AtomicInteger()
        val responses = AtomicInteger(); val rejectedResponses = AtomicInteger()
        val auth = AtomicInteger(); val device = AtomicInteger(); val subscriptions = AtomicInteger(); val historyRequests = AtomicInteger()
        val statusRequests = AtomicInteger(); val acks = AtomicInteger(); val cancels = AtomicInteger()
        fun json() = json("connections" to connections.get(), "opened" to opened.get(), "closed" to closed.get(), "failed" to failed.get(),
            "incoming" to incoming.get(), "outgoing" to outgoing.get(), "blocked" to blocked.get(), "snapshots" to snapshots.get(),
            "parts" to parts.get(), "events" to events.get(), "fragments" to fragments.get(), "responses" to responses.get(),
            "rejectedResponses" to rejectedResponses.get(), "auth" to auth.get(), "device" to device.get(),
            "subscriptions" to subscriptions.get(), "historyRequests" to historyRequests.get(), "statusRequests" to statusRequests.get(),
            "acks" to acks.get(), "cancels" to cancels.get())
    }

    /** Nothing written by these transports reaches the application's persisted preferences. */
    private class MemoryContext(base: Context, private val copySaved: Boolean) : ContextWrapper(base) {
        private val stores = ConcurrentHashMap<String, MemoryPreferences>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) {
            val values = if (!copySaved) emptyMap() else super.getSharedPreferences(name, mode).all.filterKeys { key ->
                when (name) {
                    "ccdesk-native-v1" -> key == "endpoint" || key == "device" || key.startsWith("certificate:")
                    "workbench-native-secrets-v1" -> key.startsWith("device.")
                    else -> false
                }
            }
            MemoryPreferences(values)
        }
        fun clear() { stores.values.forEach { it.edit().clear().commit() }; stores.clear() }
    }

    private class MemoryPreferences(initial: Map<String, *>) : SharedPreferences {
        private val values: MutableMap<String, Any?> = initial.toMutableMap()
        @Synchronized override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        @Synchronized override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        @Synchronized override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()
        @Synchronized override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        @Synchronized override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        @Synchronized override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        @Synchronized override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean = synchronized(this@MemoryPreferences) {
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                true
            }
            override fun apply() { commit() }
        }
    }
}
