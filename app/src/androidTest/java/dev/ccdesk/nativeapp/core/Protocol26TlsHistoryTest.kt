package dev.ccdesk.nativeapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Protocol 26 wire integration: real OkHttp/TLS, isolated preferences, no external service. */
@RunWith(AndroidJUnit4::class)
class Protocol26TlsHistoryTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "protocol26-tls-${UUID.randomUUID()}-"
        private val opened = ConcurrentHashMap<String, SharedPreferences>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode).also { opened[name] = it }
        fun clear() { opened.values.forEach { check(it.edit().clear().commit()) } }
    }

    private data class Observed(val connection: Int, val frame: JSONObject)
    private class Transfer(val socket: WebSocket, val request: JSONObject, val chunks: List<String>, val paused: Boolean) {
        var sent = 0
        var acknowledged = 0
    }

    private class Peer(private val pauseFirstSubscription: Boolean = false) : Closeable {
        private val certificate = HeldCertificate.Builder().commonName("Protocol 26 fixture")
            .addSubjectAlternativeName("127.0.0.1").build()
        private val server = MockWebServer()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        val token = "protocol26-local-fixture-token-0123456789"
        val endpoint get() = "wss://127.0.0.1:${server.port}/ws"
        val observed = MutableStateFlow<List<Observed>>(emptyList())
        val connections = AtomicInteger()
        val transfers = ConcurrentHashMap<String, Transfer>()
        private val activeTransfers = ConcurrentHashMap<String, Transfer>()
        private val sockets = CopyOnWriteArrayList<WebSocket>()
        @Volatile private var latest: WebSocket? = null
        val unicodeText = "中文🙂é𠮷 — 分片保留完整文本。".repeat(400)

        init {
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.path == "/ws") upgrade() else MockResponse().setResponseCode(404)
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        private fun session(id: String) = json("id" to id, "title" to "分片历史 $id", "projectPath" to "/fixture/project",
            "permissionMode" to "default", "device" to json("id" to "fixture-server", "name" to "Fixture server"), "activeRun" to null)
        private fun event(id: String, sequence: Long, text: String) = json("sessionId" to id, "runId" to "fixture-run", "sequence" to sequence,
            "createdAt" to "2026-01-01T00:00:00Z", "payload" to json("type" to "text.delta", "messageId" to "reply-$sequence", "text" to text))

        private fun upgrade(): MockResponse {
            val connection = connections.incrementAndGet()
            return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { latest = webSocket; sockets += webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JSONObject(text)
                    observed.update { it + Observed(connection, frame) }
                    when (frame.optString("type")) {
                        "auth" -> {
                            if (frame.optInt("protocolVersion") != 26 || frame.optString("token") != token || frame.optBoolean("tunnel", true)) {
                                webSocket.close(4001, "Fixture authentication rejected"); return
                            }
                            send(webSocket, json("type" to "ready", "protocolVersion" to 26,
                                "sessions" to JSONArray().put(session("one")).put(session("two"))))
                            send(webSocket, json("type" to "devices", "devices" to JSONArray().put(json("id" to "fixture-server", "server" to true, "ready" to true))))
                        }
                        "session.subscribe", "session.history" -> start(webSocket, frame, connection)
                        "session.history.next" -> activeTransfers[frame.getString("requestId")]?.let { transfer ->
                            check(transfer.acknowledged < transfer.sent) { "ACK without an outstanding part" }
                            transfer.acknowledged++
                            if (transfer.acknowledged == transfer.chunks.size) {
                                activeTransfers.remove(frame.getString("requestId"))
                                response(webSocket, transfer.request)
                            } else if (!transfer.paused) pump(transfer)
                        }
                        "session.history.cancel" -> activeTransfers.remove(frame.getString("requestId"))?.let {
                            send(webSocket, json("type" to "response", "requestId" to frame.getString("requestId"), "ok" to false,
                                "code" to "history_cancelled", "message" to "History delivery cancelled"))
                        }
                        else -> if (frame.has("requestId")) response(webSocket, frame)
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { sockets.remove(webSocket) }
            })
        }

        private fun start(socket: WebSocket, request: JSONObject, connection: Int) {
            val id = request.getString("sessionId")
            val older = request.optString("type") == "session.history"
            val after = request.optLong("afterSequence")
            val events = when {
                older -> JSONArray().put(event(id, 1, "更早的记录一")).put(event(id, 2, "更早的记录二"))
                id == "two" -> JSONArray().put(event(id, 1, "第二个会话完整历史"))
                after > 0 -> JSONArray().put(event(id, 6, "断线恢复后的历史🙂"))
                else -> JSONArray().put(event(id, 3, unicodeText)).put(event(id, 4, "当前页末尾"))
            }
            val snapshot = json("type" to "session.snapshot", "requestId" to request.getString("requestId"), "session" to session(id),
                "mode" to if (older) "prepend" else if (after > 0) "append" else "replace", "events" to events,
                "hasEarlier" to (!older && id == "one" && after == 0L),
                "firstSequence" to events.getJSONObject(0).getLong("sequence"), "lastSequence" to events.getJSONObject(events.length() - 1).getLong("sequence"))
            val body = JSONArray().put(snapshot).toString()
            val emoji = body.indexOf("🙂")
            // Deliberately split a UTF-16 surrogate pair, as JS slice may do at a
            // 32 Ki-code-unit boundary. JSON escapes preserve each half on the wire.
            val firstEnd = if (emoji >= 0) emoji + 1 else minOf(97, body.length)
            val chunks = listOf(body.substring(0, firstEnd)) + body.substring(firstEnd).chunked(1536)
            val paused = pauseFirstSubscription && id == "one" && connection == 1 && !older
            val transfer = Transfer(socket, request, chunks, paused)
            val requestId = request.getString("requestId")
            transfers[requestId] = transfer; activeTransfers[requestId] = transfer
            if (paused) part(transfer, transfer.sent++) else pump(transfer)
            if (id == "one" && !older && after == 0L && !paused) {
                // Real events may overtake the remaining history parts. Applying the
                // final replace snapshot must not erase this already-received event.
                send(socket, json("type" to "session.event", "event" to event(id, 5, "分片之间到达的实时事件")))
            }
        }

        private fun pump(transfer: Transfer) {
            while (transfer.sent < transfer.chunks.size && transfer.sent - transfer.acknowledged < 4) part(transfer, transfer.sent++)
        }
        private fun part(transfer: Transfer, index: Int) = send(transfer.socket, json("type" to "session.history.part",
            "requestId" to transfer.request.getString("requestId"), "sessionId" to transfer.request.getString("sessionId"),
            "data" to transfer.chunks[index], "last" to (index == transfer.chunks.lastIndex)))
        private fun response(socket: WebSocket, request: JSONObject) = send(socket, json("type" to "response", "requestId" to request.getString("requestId"), "ok" to true))
        private fun send(socket: WebSocket, frame: JSONObject) {
            val escaped = buildString { frame.toString().forEach { ch -> if (ch in '\uD800'..'\uDFFF') append("\\u%04x".format(ch.code)) else append(ch) } }
            check(socket.send(escaped))
        }
        fun sendLateRemainder(requestId: String) {
            val transfer = transfers.getValue(requestId)
            for (index in transfer.sent until transfer.chunks.size) part(transfer, index)
            response(transfer.socket, transfer.request)
        }
        fun disconnect() { check(latest?.close(1001, "Fixture reconnect") == true) }
        suspend fun frame(type: String, connection: Int? = null): JSONObject = withTimeout(10_000) {
            observed.first { frames -> frames.any { it.frame.optString("type") == type && (connection == null || it.connection == connection) } }
                .first { it.frame.optString("type") == type && (connection == null || it.connection == connection) }.frame
        }
        override fun close() { sockets.forEach { it.close(1000, "Fixture complete") }; server.shutdown() }
    }

    private suspend fun awaitState(repo: WorkbenchRepository, predicate: (WorkbenchState) -> Boolean) = withTimeout(10_000) { repo.state.first(predicate) }
    private suspend fun dispose(repo: WorkbenchRepository, transport: AndroidWorkbenchTransport) = withContext(NonCancellable) {
        withContext(Dispatchers.Main.immediate) { repo.dispose() }
        withTimeout(10_000) { repo.awaitPersistenceDrain(); transport.awaitClosed() }
    }

    @Test fun tlsHistoryAcknowledgesUnicodePartsPrependsPagesAndRecoversFromDurableCursor() = runBlocking {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val peer = Peer(); val transport = AndroidWorkbenchTransport(context); val repo = WorkbenchRepository(transport)
        try {
            repo.setFingerprint(peer.endpoint, peer.fingerprint); repo.connectService(peer.endpoint, peer.token)
            awaitState(repo) { it.status == "online" }
            repo.selectSession("one")
            awaitState(repo) { it.histories["one"]?.events?.lastOrNull()?.sequence == 5L }
            val current = repo.state.value.histories.getValue("one")
            assertEquals(listOf(3L, 4L, 5L), current.events.map { it.sequence })
            assertEquals(peer.unicodeText, current.events.first().payload.getString("text"))
            assertTrue(current.hasEarlier)
            repo.loadEarlier("one")
            val paged = repo.state.value.histories.getValue("one")
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), paged.events.map { it.sequence })
            assertFalse(paged.hasEarlier)
            assertEquals(3L, peer.frame("session.history").getLong("beforeSequence"))
            peer.disconnect()
            awaitState(repo) { it.status == "online" && it.histories["one"]?.events?.lastOrNull()?.sequence == 6L }
            assertEquals(5L, peer.frame("session.subscribe", 2).getLong("afterSequence"))
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), repo.state.value.histories.getValue("one").events.map { it.sequence })
            for ((requestId, transfer) in peer.transfers) {
                withTimeout(10_000) { peer.observed.first { frames -> frames.count {
                    it.frame.optString("type") == "session.history.next" && it.frame.optString("requestId") == requestId
                } == transfer.chunks.size } }
                assertEquals("Every part, including last, needs one ACK", transfer.chunks.size,
                    peer.observed.value.count { it.frame.optString("type") == "session.history.next" && it.frame.optString("requestId") == requestId })
            }
            assertFalse(peer.observed.value.any { it.frame.optString("type") in setOf("message.send", "server.session.create", "session.configure") })
        } finally { dispose(repo, transport); peer.close(); context.clear() }
    }

    @Test fun switchingSessionsCancelsPartialTlsHistoryAndIgnoresLateParts() = runBlocking {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val peer = Peer(pauseFirstSubscription = true); val transport = AndroidWorkbenchTransport(context); val repo = WorkbenchRepository(transport)
        try {
            repo.setFingerprint(peer.endpoint, peer.fingerprint); repo.connectService(peer.endpoint, peer.token)
            awaitState(repo) { it.status == "online" }
            repo.openSession("one")
            val firstRequest = peer.frame("session.history.next").getString("requestId")
            assertTrue(repo.state.value.histories.getValue("one").loading)
            assertTrue(repo.state.value.histories.getValue("one").events.isEmpty())
            repo.selectSession("two")
            assertEquals(firstRequest, peer.frame("session.history.cancel").getString("requestId"))
            assertEquals("two", repo.state.value.selectedId)
            assertEquals("第二个会话完整历史", repo.state.value.histories.getValue("two").events.single().payload.getString("text"))
            peer.sendLateRemainder(firstRequest)
            // A later response on the same socket proves all stale parts ahead of it
            // were processed; no fixed sleep or synthetic transport callback is used.
            repo.command("session.status", json("sessionId" to "two"))
            assertEquals("two", repo.state.value.selectedId)
            assertTrue(repo.state.value.histories.getValue("one").events.isEmpty())
            assertFalse(repo.state.value.histories.getValue("one").loading)
            assertEquals(1, peer.observed.value.count { it.frame.optString("type") == "session.history.next" && it.frame.optString("requestId") == firstRequest })
            assertEquals(1, repo.state.value.histories.getValue("two").events.size)
            assertNull(repo.state.value.error)
        } finally { dispose(repo, transport); peer.close(); context.clear() }
    }
}
