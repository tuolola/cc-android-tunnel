package dev.ccdesk.nativeapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket

/** A real TLS peer that authenticates and stays open while deliberately ignoring pings.
 * The fixture does not synthesize transport errors or close the first connection itself.
 */
@RunWith(AndroidJUnit4::class)
class SilentPeerRecoveryTest {
    @Test fun missingPongRetiresSilentTlsSocketAndResubscribesFromDurableCursor() = runBlocking<Unit> {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val peer = SilentTlsPeer()
        val transport = AndroidWorkbenchTransport(context)
        val firstFailure = AtomicReference<Throwable?>()
        val firstFailureAt = AtomicLong()
        val observed = object : WorkbenchTransport by transport {
            override suspend fun socket(endpoint: String, observer: SocketObserver): SocketConnection {
                val listener = object : SocketObserver {
                    override fun opened() = observer.opened()
                    override fun message(text: String) = observer.message(text)
                    override fun closed(code: Int, reason: String) = observer.closed(code,reason)
                    override fun failed(cause: Throwable) {
                        if (firstFailure.compareAndSet(null,cause)) firstFailureAt.set(SystemClock.elapsedRealtime())
                        observer.failed(cause)
                    }
                }
                return transport.socket(endpoint,listener)
            }
        }
        val repository = WorkbenchRepository(observed)
        val offlineSeen = AtomicInteger()
        val collecting = launch {
            repository.state.collect { if (it.status == "offline") offlineSeen.incrementAndGet() }
        }
        try {
            repository.setFingerprint(peer.endpoint,peer.fingerprint)
            repository.connectService(peer.endpoint,peer.token)
            withTimeout(10_000) { repository.state.first { it.status == "online" } }
            repository.selectSession("silent-session")
            assertEquals(listOf(7L),repository.state.value.histories.getValue("silent-session").events.map { it.sequence })
            withTimeout(80_000) {
                repository.state.first { state ->
                    state.status == "online" && state.histories["silent-session"]?.let {
                        !it.loading && it.events.map { event -> event.sequence } == listOf(7L,8L)
                    } == true
                }
            }
            assertTrue("The authenticated first connection must receive a real ping",peer.ignoredPings.get() > 0)
            assertTrue("The timeout must come from an unanswered control ping",firstFailure.get() is SocketTimeoutException)
            assertTrue(firstFailure.get()?.message.orEmpty().contains("pong",ignoreCase=true))
            val detectionTime = firstFailureAt.get() - peer.firstAuthenticatedAt.get()
            assertTrue("A silent connection should fail in about two 30-second intervals",detectionTime in 45_000L..75_000L)
            assertTrue("Repository must expose offline before reconnecting",offlineSeen.get() > 0)
            val subscriptions = peer.requests.filter { it.frame.optString("type") == "session.subscribe" }
            assertEquals(2,subscriptions.size)
            assertEquals(0,subscriptions[0].frame.getInt("afterSequence"))
            assertEquals(7,subscriptions[1].frame.getInt("afterSequence"))
            assertEquals(2,peer.requests.count { it.frame.optString("type") == "auth" })
            assertFalse(peer.requests.any { it.frame.optString("type") in setOf("message.send","run.continue","approval.reply") })
            // The recovered peer replies to pings. Observe that normal control-frame traffic
            // is accepted without generating any application-level heartbeat command.
            withTimeout(40_000) { while (peer.returnedPongs.get() == 0) delay(50) }
            assertEquals("online",repository.state.value.status)
            assertEquals(2,peer.connections.get())
            assertEquals(listOf(7L,8L),repository.state.value.histories.getValue("silent-session").events.map { it.sequence })
        } finally {
            withContext(NonCancellable) {
                try {
                    collecting.cancelAndJoin()
                    withContext(Dispatchers.Main.immediate) { repository.dispose() }
                    withTimeout(10_000) { repository.awaitPersistenceDrain(); transport.awaitClosed() }
                } finally {
                    peer.close()
                    context.clean()
                }
            }
        }
    }

    @Test fun slowHistoryFrameDoesNotTriggerAFalseHeartbeatDisconnect() = runBlocking<Unit> {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val peer = SilentTlsPeer(slowHistory = true)
        val transport = AndroidWorkbenchTransport(context)
        val failure = AtomicReference<Throwable?>()
        val observed = object : WorkbenchTransport by transport {
            override suspend fun socket(endpoint: String, observer: SocketObserver) = transport.socket(endpoint,object : SocketObserver by observer {
                override fun failed(cause: Throwable) { failure.set(cause); observer.failed(cause) }
            })
        }
        val repository = WorkbenchRepository(observed)
        try {
            repository.setFingerprint(peer.endpoint, peer.fingerprint)
            repository.connectService(peer.endpoint, peer.token)
            withTimeout(10_000) { repository.state.first { it.status == "online" } }
            val read = runCatching { withTimeout(55_000) { repository.selectSession("silent-session") } }
            assertTrue("Slow frame read failed: ${read.exceptionOrNull()}; transport=${failure.get()}", read.isSuccess)
            val history = repository.state.value.histories.getValue("silent-session")
            assertTrue(history.loaded)
            assertFalse(history.loading)
            assertTrue(history.events.single().payload.getString("text").length > 400_000)
            withTimeout(10_000) { while (peer.returnedPongs.get() == 0) delay(50) }
            assertEquals("online", repository.state.value.status)
            assertEquals("The slow snapshot must not restart the authenticated connection", 1, peer.connections.get())
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) { repository.dispose() }
                repository.awaitPersistenceDrain(); transport.awaitClosed()
                peer.close(); context.clean()
            }
        }
    }

    private class IsolatedContext(private val base: Context) : ContextWrapper(base) {
        private val prefix = "silent-peer-test-" + UUID.randomUUID()
        private val names = ConcurrentHashMap.newKeySet<String>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "$prefix-$name"
            names.add(scoped)
            return base.getSharedPreferences(scoped,mode)
        }
        fun clean() { names.forEach { check(base.deleteSharedPreferences(it)) } }
    }

    private data class ObservedRequest(val connection: Int,val frame: JSONObject)
    private class SilentTlsPeer(private val slowHistory: Boolean = false) {
        private val certificate = HeldCertificate.Builder().commonName("Silent TLS fixture")
            .addSubjectAlternativeName("127.0.0.1").build()
        private val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val server = (certificates.sslContext().serverSocketFactory.createServerSocket() as SSLServerSocket)
            .apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0)) }
        private val executor = Executors.newCachedThreadPool()
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        val connections = AtomicInteger()
        val ignoredPings = AtomicInteger()
        val returnedPongs = AtomicInteger()
        val firstAuthenticatedAt = AtomicLong()
        val requests = CopyOnWriteArrayList<ObservedRequest>()
        val endpoint = "wss://127.0.0.1:${server.localPort}/ws"
        val token = "silent-peer-test-token-0123456789"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        @Volatile private var closed = false

        init {
            executor.execute {
                while (!closed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    val connection = connections.incrementAndGet()
                    clients.add(socket)
                    executor.execute {
                        try { serve(socket,connection) }
                        catch (_: EOFException) { /* Client watchdog cancellation ends the silent connection. */ }
                        catch (_: java.io.IOException) { /* Client close/reset during cleanup is expected. */ }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        finally { clients.remove(socket); runCatching { socket.close() } }
                    }
                }
            }
        }

        private fun serve(socket: Socket,connection: Int) {
            socket.soTimeout = 150_000 // Only the client's heartbeat should retire a silent peer.
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val requestLine = line(input)
            require(requestLine.startsWith("GET /ws "))
            var key: String? = null
            while (true) {
                val header = line(input)
                if (header.isEmpty()) break
                if (header.substringBefore(':').equals("Sec-WebSocket-Key",ignoreCase=true)) key = header.substringAfter(':').trim()
            }
            val accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((requireNotNull(key) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),Base64.NO_WRAP)
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            while (!closed) {
                val (opcode,payload) = readFrame(input)
                when (opcode) {
                    1 -> {
                        val frame = JSONObject(payload.toString(Charsets.UTF_8))
                        requests.add(ObservedRequest(connection,frame))
                        when (frame.optString("type")) {
                            "auth" -> {
                                require(frame.optInt("protocolVersion") == 29 && frame.optString("token") == token)
                                if (connection == 1) firstAuthenticatedAt.set(SystemClock.elapsedRealtime())
                                text(output,json("type" to "ready","protocolVersion" to 29,"sessions" to JSONArray().put(session())))
                            }
                            "session.subscribe" -> {
                                val after = frame.optLong("afterSequence")
                                val event = json("sessionId" to "silent-session","runId" to "fixture-run",
                                    "sequence" to if (connection == 1) 7 else 8,"createdAt" to "2026-01-01T00:00:00Z",
                                    "payload" to json("type" to "text.delta","messageId" to "fixture-reply","text" to if(slowHistory)"Slow history record. ".repeat(22_000) else "Durable fixture reply."))
                                val snapshot = json("type" to "session.snapshot","requestId" to frame.getString("requestId"),
                                    "session" to session(),"mode" to if(after == 0L)"replace" else "append",
                                    "hasEarlier" to false,"events" to JSONArray().put(event))
                                writeFrame(output,1,snapshot.toString().toByteArray(Charsets.UTF_8),if(slowHistory)35_000 else 0)
                                text(output,json("type" to "response","requestId" to frame.getString("requestId"),"ok" to true))
                            }
                        }
                    }
                    9 -> if (connection == 1 && !slowHistory) ignoredPings.incrementAndGet() else {
                        writeFrame(output,10,payload);returnedPongs.incrementAndGet()
                    }
                    8 -> { writeFrame(output,8,payload);return }
                }
            }
        }
        private fun session() = json("id" to "silent-session","title" to "Silent peer fixture",
            "device" to json("id" to "fixture-server"),"permissionMode" to "default","activeRun" to null)
        private fun text(output: OutputStream,frame: JSONObject) = writeFrame(output,1,frame.toString().toByteArray(Charsets.UTF_8))
        private fun writeFrame(output: OutputStream,opcode: Int,payload: ByteArray,delayMillis: Long = 0) {
            output.write(0x80 or opcode)
            if (payload.size < 126) output.write(payload.size)
            else if (payload.size <= 65535) { output.write(126);output.write(payload.size ushr 8);output.write(payload.size and 255) }
            else { output.write(127);for(shift in 7 downTo 0)output.write(((payload.size.toLong() ushr (shift*8)) and 255).toInt()) }
            if (delayMillis > 0) {
                val half = payload.size / 2
                output.write(payload,0,half);output.flush()
                Thread.sleep(delayMillis) // A pong can only follow the end of this WebSocket frame.
                output.write(payload,half,payload.size-half)
            } else output.write(payload)
            output.flush()
        }
        private fun readFrame(input: InputStream): Pair<Int,ByteArray> {
            val first = input.read().also { if(it < 0) throw EOFException() }
            val second = input.read().also { if(it < 0) throw EOFException() }
            var length = (second and 127).toLong()
            if (length == 126L) length = ((byte(input) shl 8) or byte(input)).toLong()
            else if (length == 127L) { length=0;repeat(8) { length=(length shl 8) or byte(input).toLong() } }
            require(length in 0L..1_048_576L && second and 128 != 0) { "Invalid fixture client frame" }
            val mask = ByteArray(4).also { fully(input,it) }
            val payload = ByteArray(length.toInt()).also { fully(input,it) }
            payload.indices.forEach { payload[it]=(payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            return (first and 15) to payload
        }
        private fun byte(input: InputStream) = input.read().also { if(it < 0) throw EOFException() }
        private fun fully(input: InputStream,bytes: ByteArray) {
            var offset=0
            while(offset < bytes.size) { val count=input.read(bytes,offset,bytes.size-offset);if(count < 0) throw EOFException();offset+=count }
        }
        private fun line(input: InputStream): String {
            val line=StringBuilder()
            while(true) { val next=byte(input);if(next==10)return line.toString().removeSuffix("\r");line.append(next.toChar());require(line.length < 16_384) }
        }
        fun close() {
            closed=true
            runCatching { server.close() }
            clients.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
