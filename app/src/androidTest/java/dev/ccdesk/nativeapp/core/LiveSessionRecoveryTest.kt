package dev.ccdesk.nativeapp.core

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Read-only opt-in regression: no messages, session mutations or changes to the app's login. */
@RunWith(AndroidJUnit4::class)
class LiveSessionRecoveryTest {
    @Test fun existingHistoryLoadsAndForegroundKeepsTheHealthyConnection() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AndroidWorkbenchTransport(context)
        val isolated = object : ContextWrapper(context) {
            val names = mutableSetOf<String>()
            val prefix = "session-recovery-${UUID.randomUUID()}-"
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val key = prefix + name
                names += key
                return context.getSharedPreferences(key, mode)
            }
        }
        val transport = AndroidWorkbenchTransport(isolated)
        val failures = CopyOnWriteArrayList<String>()
        val snapshots = CopyOnWriteArrayList<String>()
        val received = CopyOnWriteArrayList<String>()
        var connections = 0
        val observed = object : WorkbenchTransport by transport {
            override suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection {
                connections++
                return transport.socket(endpoint, direct, object : SocketObserver by observer {
                    override fun failed(cause: Throwable) {
                        failures += "Socket: ${cause.javaClass.simpleName}: ${cause.message}"
                        observer.failed(cause)
                    }
                    override fun closed(code: Int, reason: String) {
                        failures += "Closed: $code $reason"
                        observer.closed(code, reason)
                    }
                    override fun message(text: String) {
                        val frame = JSONObject(text)
                        received += frame.optString("type")
                        if (frame.optString("type") == "session.snapshot") {
                            snapshots += frame.optString("requestId")
                            runCatching {
                                Session.from(frame.getJSONObject("session"))
                                frame.optJSONArray("events").objects().forEach { DeskEvent.from(it) }
                            }.onFailure { failures += "Snapshot decode: ${it.javaClass.simpleName}: ${it.message}" }
                        }
                        if (frame.optString("type") == "response" && !frame.optBoolean("ok")) failures += frame.optString("message")
                        observer.message(text)
                    }
                })
            }
        }
        val repository = WorkbenchRepository(observed)
        try {
            val endpoint = original.getEndpoint()
            require(URI(endpoint).host == args.getString("expectedHost"))
            require(endpoint.startsWith("wss://"))
            val credential = original.credential(endpoint)
            require(credential.isNotBlank()) { "Log in on the test device first" }
            transport.setEndpoint(endpoint)
            transport.setFingerprint(endpoint, original.fingerprint(endpoint))
            transport.saveCredential(endpoint, credential)
            repository.start()
            withTimeout(30_000) { repository.state.first { it.status == "online" } }
            val preflight = runCatching { withTimeout(10_000) { repository.command("usage.summary") } }
            assertTrue("Preflight failed: ${preflight.exceptionOrNull()?.message}; frames=$received; errors=$failures", preflight.isSuccess)
            val title = requireNotNull(args.getString("sessionTitle"))
            val target = repository.state.value.sessions.single { it.title == title }
            val emptyPage = runCatching { withTimeout(10_000) { repository.command("session.history", json("sessionId" to target.id, "beforeSequence" to 1)) } }
            assertTrue("Small history page failed: ${emptyPage.exceptionOrNull()?.message}; frames=$received; errors=$failures", emptyPage.isSuccess)
            val readStartedAt = System.currentTimeMillis()
            val result = runCatching { withTimeout(100_000) { repository.selectSession(target.id) } }
            val readMillis = System.currentTimeMillis() - readStartedAt
            assertTrue("History read failed: ${result.exceptionOrNull()?.message}; decode=$failures; snapshots=${snapshots.size}; frames=$received; status=${repository.state.value.status}; error=${repository.state.value.error}", result.isSuccess)
            val history = repository.state.value.histories.getValue(target.id)
            assertTrue("Expected a history snapshot; decode=$failures", history.loaded)
            assertFalse(history.loading)
            assertNull(history.loadError)
            val before = connections
            repository.foreground(false)
            delay(250)
            repository.foreground(true)
            delay(1_000)
            assertEquals("A healthy socket must survive a foreground round trip; failures=$failures; frames=$received; state=${repository.state.value.status}; error=${repository.state.value.error}; historyMs=$readMillis", before, connections)
            assertEquals("online", repository.state.value.status)
            assertEquals(history.events, repository.state.value.histories.getValue(target.id).events)
            context.cacheDir.resolve("native-session-recovery.json").writeText(json("passed" to true,
                "historyEvents" to history.events.size, "historyReadMillis" to readMillis,
                "connectionsBeforeForeground" to before, "connectionsAfterForeground" to connections).toString(2))
        } finally {
            withContext(Dispatchers.Main.immediate) { repository.dispose() }
            repository.awaitPersistenceDrain()
            transport.awaitClosed()
            original.close()
            original.awaitClosed()
            isolated.names.forEach { context.deleteSharedPreferences(it) }
        }
    }
}
