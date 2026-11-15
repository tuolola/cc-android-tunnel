package dev.ccdesk.nativeapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Android networking and Keystore against a local, disposable protocol-25 fixture.
 * No application fixture switch, real account, external service, or stored user data is used.
 */
@RunWith(AndroidJUnit4::class)
class NativeTlsIntegrationTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "native-tls-test-${UUID.randomUUID()}-"
        private val opened = ConcurrentHashMap<String,SharedPreferences>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name,mode).also { opened[name] = it }
        fun clear() { opened.values.forEach { check(it.edit().clear().commit()) } }
    }
    private data class ObservedRequest(val path: String, val origin: String?, val cookie: String?, val body: String)
    private data class ObservedFrame(val connection: Int, val frame: JSONObject)

    private class TlsWorkbench : Closeable {
        val certificate: HeldCertificate = HeldCertificate.Builder().commonName("Native integration fixture")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").build()
        val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        private val server = MockWebServer()
        val requests = CopyOnWriteArrayList<ObservedRequest>()
        val frames = MutableStateFlow<List<ObservedFrame>>(emptyList())
        val upgrades = AtomicInteger()
        @Volatile var upgradeBlock: Pair<CountDownLatch,CountDownLatch>? = null
        private val history = CopyOnWriteArrayList<JSONObject>()
        private val liveSockets = CopyOnWriteArrayList<WebSocket>()
        @Volatile private var live: WebSocket? = null
        val origin get() = "https://127.0.0.1:${server.port}"
        val legacyHttpsEndpoint get() = "$origin/workbench/"
        val serviceEndpoint get() = "wss://127.0.0.1:${server.port}/ws"
        val serviceToken = "instrumentation-only-service-token-0123456789"

        init {
            history += event(1,"History loaded.",type = "native.notice")
            val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            server.useHttps(certificates.sslSocketFactory(),false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val body = request.body.clone().readUtf8()
                    requests += ObservedRequest(path,request.getHeader("Origin"),request.getHeader("Cookie"),body)
                    return if (path == "/ws") {
                        upgradeBlock?.let { (entered, release) -> entered.countDown(); release.await(10, TimeUnit.SECONDS) }
                        upgrade()
                    } else response(json("error" to "Unsupported fixture route"), 404)
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"),0)
        }
        private fun response(body: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status).addHeader("Content-Type","application/json").setBody(body.toString())
        private fun session() = json("id" to "session-one","title" to "Integration session","projectPath" to "/fixture/project","permissionMode" to "default",
            "createdAt" to "2026-01-01T00:00:00Z","updatedAt" to "2026-01-01T00:00:00Z","device" to json("id" to "fixture-server","name" to "Fixture server"),"activeRun" to null)
        fun event(sequence: Long, text: String, type: String = "text.delta") = json("sessionId" to "session-one","runId" to "run-one","sequence" to sequence,
            "createdAt" to "2026-01-01T00:00:00Z","payload" to json("type" to type,"messageId" to "reply-one","text" to text))
        private fun upgrade(): MockResponse {
            val connection = upgrades.incrementAndGet()
            return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { live = webSocket; liveSockets += webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JSONObject(text)
                    frames.update { it + ObservedFrame(connection,frame) }
                    when (frame.optString("type")) {
                        "auth" -> {
                            val valid = frame.optInt("protocolVersion") == 25 && frame.optString("token") == serviceToken && !frame.optBoolean("tunnel",true)
                            if (!valid) { webSocket.close(4001,"Invalid fixture authentication"); return }
                            val ready = json("type" to "ready","protocolVersion" to 25,"version" to "instrumentation-fixture","sessions" to JSONArray().put(session()))
                            webSocket.send(ready.toString())
                            webSocket.send(json("type" to "devices","devices" to JSONArray().put(json("id" to "fixture-server","name" to "Fixture server","server" to true,"ready" to true))).toString())
                        }
                        "session.subscribe" -> {
                            val after = frame.optLong("afterSequence")
                            val events = JSONArray(history.filter { it.getLong("sequence") > after })
                            webSocket.send(json("type" to "session.snapshot","requestId" to frame.getString("requestId"),"session" to session(),"mode" to if (after == 0L) "replace" else "append","hasEarlier" to false,"events" to events).toString())
                            acknowledge(webSocket,frame)
                        }
                        "message.send" -> acknowledge(webSocket,frame)
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code,reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { liveSockets.remove(webSocket) }
            })
        }
        private fun acknowledge(socket: WebSocket, frame: JSONObject) { socket.send(json("type" to "response","requestId" to frame.getString("requestId"),"ok" to true).toString()) }
        fun fragment(text: String) { check(live?.send(json("type" to "session.fragment","sessionId" to "session-one","runId" to "run-one","payload" to json("type" to "text.delta","messageId" to "reply-one","text" to text)).toString()) == true) }
        fun durable(sequence: Long, text: String, broadcast: Boolean = true) {
            val event = event(sequence,text)
            history += event
            if (broadcast) check(live?.send(json("type" to "session.event","event" to event).toString()) == true)
        }
        fun disconnect() { check(live?.close(1001,"Fixture reconnect test") == true) }
        suspend fun frame(type: String, connection: Int? = null): JSONObject = withTimeout(10_000) {
            frames.first { all -> all.any { it.frame.optString("type") == type && (connection == null || it.connection == connection) } }
                .first { it.frame.optString("type") == type && (connection == null || it.connection == connection) }.frame
        }
        override fun close() {
            // MockWebServer's server-side WebSocket has no client Call; cancel() would
            // dereference that absent Call and mask a test assertion during cleanup.
            liveSockets.forEach { it.close(1000,"Fixture shutdown") }
            server.shutdown()
        }
    }

    private fun isolatedContext() = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private suspend fun awaitState(repo: WorkbenchRepository, predicate: (WorkbenchState) -> Boolean) = withTimeout(10_000) { repo.state.first(predicate) }
    private suspend fun dispose(repo: WorkbenchRepository, transport: AndroidWorkbenchTransport) = withContext(NonCancellable) {
        withContext(Dispatchers.Main.immediate) { repo.dispose() }
        // Assert that disposal really completes its TLS close on the owned IO scope,
        // even though repository.dispose() has already cancelled the repository scope.
        withTimeout(10_000) { repo.awaitPersistenceDrain(); transport.awaitClosed() }
    }

    @Test fun legacyHttpsLoginIsRejectedWithoutConnectingOrSendingCredentials() = runBlocking {
        val context = isolatedContext()
        val fixture = TlsWorkbench()
        val transport = AndroidWorkbenchTransport(context)
        val repo = WorkbenchRepository(transport)
        try {
            // Simulate an installation that still has a pre-WSS-only saved endpoint.
            check(context.getSharedPreferences("ccdesk-native-v1", Context.MODE_PRIVATE).edit()
                .putString("endpoint", fixture.legacyHttpsEndpoint).commit())
            repo.start()
            assertEquals("setup", repo.state.value.status)
            assertFalse(repo.state.value.authenticated)
            assertTrue(repo.state.value.error.orEmpty().contains("wss", ignoreCase = true))
            assertTrue(runCatching { repo.setEndpoint(fixture.legacyHttpsEndpoint) }.isFailure)
            assertTrue(runCatching { repo.connectService(fixture.legacyHttpsEndpoint, fixture.serviceToken) }.isFailure)
            assertTrue(fixture.requests.isEmpty())
            assertTrue(fixture.frames.value.isEmpty())
            assertEquals(0, fixture.upgrades.get())
        } finally { dispose(repo, transport); fixture.close(); context.clear() }
    }

    @Test fun directPinnedWssStreamsSettlesReconnectsFromDurableCursorAndDoesNotReplayMessages() = runBlocking {
        val context = isolatedContext()
        val fixture = TlsWorkbench()
        var transport = AndroidWorkbenchTransport(context)
        var repo = WorkbenchRepository(transport)
        try {
            repo.setFingerprint(fixture.serviceEndpoint,fixture.fingerprint)
            repo.connectService(fixture.serviceEndpoint,fixture.serviceToken)
            awaitState(repo) { it.status == "online" }
            val auth = fixture.frame("auth",1)
            assertEquals(25,auth.getInt("protocolVersion")); assertEquals(fixture.serviceToken,auth.getString("token")); assertFalse(auth.getBoolean("tunnel"))
            assertEquals("CC Android Tunnel",auth.getString("deviceName"))
            val identity = fixture.frame("device",1).getString("id")
            assertTrue(identity.isNotBlank())
            assertNull(fixture.requests.single { it.path == "/ws" }.origin)
            assertNull(fixture.requests.single { it.path == "/ws" }.cookie)
            assertTrue(fixture.requests.none { it.path.contains("/api/") })
            repo.selectSession("session-one")
            repo.command("message.send",json("sessionId" to "session-one","text" to "Integration stream request."))

            fixture.fragment("hel")
            awaitState(repo) { it.histories["session-one"]?.fragments?.singleOrNull()?.payload?.optString("text") == "hel" }
            fixture.fragment("lo")
            awaitState(repo) { it.histories["session-one"]?.fragments?.singleOrNull()?.payload?.optString("text") == "hello" }
            fixture.durable(2,"hello")
            awaitState(repo) { it.histories["session-one"]?.let { h -> h.events.lastOrNull()?.sequence == 2L && h.fragments.isEmpty() } == true }
            fixture.fragment("unconfirmed stream fragment")
            awaitState(repo) { it.histories["session-one"]?.fragments?.isNotEmpty() == true }
            fixture.durable(3,"Recovered durable response.",broadcast = false)
            fixture.disconnect()
            awaitState(repo) { it.status == "offline" }
            assertTrue(repo.state.value.histories.getValue("session-one").fragments.isEmpty())
            awaitState(repo) { it.status == "online" && it.histories["session-one"]?.events?.lastOrNull()?.sequence == 3L }
            assertEquals(2L,fixture.frame("session.subscribe",2).getLong("afterSequence"))
            assertEquals(listOf(1L,2L,3L),repo.state.value.histories.getValue("session-one").events.map { it.sequence })
            assertEquals(1,fixture.frames.value.count { it.frame.optString("type") == "message.send" })

            dispose(repo,transport)
            transport = AndroidWorkbenchTransport(context)
            repo = WorkbenchRepository(transport)
            repo.start()
            awaitState(repo) { it.status == "online" }
            assertEquals(identity,fixture.frame("device",3).getString("id"))
            assertEquals(fixture.serviceToken,transport.credential(fixture.serviceEndpoint))
            repo.logout()
            assertEquals("setup",repo.state.value.status); assertEquals("",transport.getEndpoint())
            assertEquals("",transport.credential(fixture.serviceEndpoint))
            assertEquals(fixture.fingerprint,transport.fingerprint(fixture.serviceEndpoint))
        } finally { dispose(repo,transport); fixture.close(); context.clear() }
    }

    @Test fun wrongPinRejectsBeforeAuthAndChangingTrustRequiresFreshCredential() = runBlocking {
        val context = isolatedContext()
        val fixture = TlsWorkbench()
        val transport = AndroidWorkbenchTransport(context)
        val repo = WorkbenchRepository(transport)
        try {
            repo.setFingerprint(fixture.serviceEndpoint,"00".repeat(32))
            repo.connectService(fixture.serviceEndpoint,fixture.serviceToken)
            awaitState(repo) { it.status == "offline" }
            assertFalse(repo.state.value.authenticated)
            assertEquals(0,fixture.upgrades.get()); assertTrue(fixture.frames.value.isEmpty())
            repo.foreground(false); repo.foreground(true)
            delay(1_200)
            assertEquals("offline",repo.state.value.status); assertEquals(0,fixture.upgrades.get())

            repo.setFingerprint(fixture.serviceEndpoint,fixture.fingerprint)
            assertEquals("",transport.credential(fixture.serviceEndpoint))
            repo.connectService(fixture.serviceEndpoint,"")
            assertEquals("setup",repo.state.value.status); assertEquals(0,fixture.upgrades.get())
            repo.connectService(fixture.serviceEndpoint,fixture.serviceToken)
            awaitState(repo) { it.status == "online" }
            assertEquals(fixture.serviceToken,fixture.frame("auth").getString("token"))
            repo.logout()
            assertEquals("",transport.credential(fixture.serviceEndpoint))
        } finally { dispose(repo,transport); fixture.close(); context.clear() }
    }

    @Test fun systemCaModeRejectsSelfSignedFixtureWithoutSendingCredentials() = runBlocking {
        val context = isolatedContext()
        val fixture = TlsWorkbench()
        val transport = AndroidWorkbenchTransport(context)
        val repo = WorkbenchRepository(transport)
        try {
            assertEquals("",transport.fingerprint(fixture.serviceEndpoint))
            repo.connectService(fixture.serviceEndpoint,fixture.serviceToken)
            awaitState(repo) { it.status == "offline" }
            assertFalse(repo.state.value.authenticated)
            assertEquals(0,fixture.upgrades.get()); assertTrue(fixture.frames.value.isEmpty())
            repo.foreground(false); repo.foreground(true)
            delay(1_200)
            assertEquals("offline",repo.state.value.status); assertEquals(0,fixture.upgrades.get())
        } finally { dispose(repo,transport); fixture.close(); context.clear() }
    }

    @Test fun switchingOriginsDoesNotWaitForAnUnresponsivePreviousWssUpgrade() = runBlocking {
        val context = isolatedContext()
        val slow = TlsWorkbench()
        val next = TlsWorkbench()
        val transport = AndroidWorkbenchTransport(context)
        val repo = WorkbenchRepository(transport)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        slow.upgradeBlock = entered to release
        try {
            repo.setFingerprint(slow.serviceEndpoint, slow.fingerprint)
            repo.setFingerprint(next.serviceEndpoint, next.fingerprint)
            repo.connectService(slow.serviceEndpoint, slow.serviceToken)
            withContext(Dispatchers.IO) { assertTrue("The first TLS upgrade must be in flight", entered.await(5, TimeUnit.SECONDS)) }
            withTimeout(2_500) { repo.connectService(next.serviceEndpoint, next.serviceToken) }
            awaitState(repo) { it.status == "online" }
            assertEquals(next.serviceEndpoint, repo.state.value.endpoint)
            assertEquals(1L, release.count)
            assertEquals(1, next.requests.count { it.path == "/ws" })
            release.countDown()
            delay(300)
            assertEquals(next.serviceEndpoint, repo.state.value.endpoint)
            assertEquals("online", repo.state.value.status)
            assertTrue("The superseded connection must not receive authentication", slow.frames.value.isEmpty())
        } finally { release.countDown(); dispose(repo, transport); slow.close(); next.close(); context.clear() }
    }
}
