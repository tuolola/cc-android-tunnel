package dev.ccdesk.nativeapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.UUID

/** Explicitly opted-in acceptance against the already configured service.
 * Credentials are copied only in memory into an isolated encrypted vault. The regular app's
 * preferences, login and existing sessions are never changed. Only sessions created here are mutated.
 */
@RunWith(AndroidJUnit4::class)
class LiveWorkbenchAcceptanceTest {
    @Test fun realServiceSessionLifecycleStreamingCancellationContinuationHistoryAndReconnect() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Real service acceptance requires -e runLive true", arguments.getString("runLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AndroidWorkbenchTransport(context)
        val isolated = IsolatedContext(context)
        val transport = AndroidWorkbenchTransport(isolated)
        val controlled = ClosableSocketTransport(transport)
        val repository = WorkbenchRepository(controlled)
        val stages = JSONArray()
        val report = json("passed" to false, "stages" to stages)
        var createdId: String? = null
        var transientId: String? = null
        fun record(name: String) { stages.put(json("stage" to name, "passed" to true, "at" to now()));context.cacheDir.resolve("native-live-acceptance.json").writeText(report.toString(2)) }
        suspend fun online() { withTimeout(30_000) { repository.state.first { it.status == "online" } } }
        suspend fun awaitIdle(id: String, timeout: Long = 180_000) {
            withTimeout(timeout) { repository.state.first { state -> state.sessions.any { it.id == id && it.activeRun == null } } }
        }
        try {
            val endpoint = original.getEndpoint()
            require(endpoint.startsWith("wss://")) { "This acceptance test expects the configured direct WSS service" }
            require(URI(endpoint).host == arguments.getString("expectedHost")) { "Explicit expectedHost must match the configured service" }
            val pin = original.fingerprint(endpoint)
            val credential = original.credential(endpoint)
            require(credential.isNotBlank()) { "Connect the native app before opting into live acceptance" }
            transport.setEndpoint(endpoint)
            transport.setFingerprint(endpoint, pin)
            transport.saveCredential(endpoint, credential)
            repository.start(); online()
            record("encrypted login restored against expected real service")

            val name = "Native acceptance " + now().replace(':', '-')
            val id = repository.createSession(name)
            createdId = id
            report.put("sessionId", id).put("title", name)
            repository.command("session.rename", json("sessionId" to id, "title" to "$name verified"))
            withTimeout(10_000) { repository.state.first { it.sessions.any { row -> row.id == id && row.title == "$name verified" } } }
            record("create and rename dedicated English session")

            repository.togglePin(id)
            withTimeout(5_000) { repository.state.first { id in it.pins } }
            withTimeout(5_000) { while(id !in transport.readPreferences(endpoint).pins) delay(50) }
            repository.togglePin(id)
            withTimeout(5_000) { repository.state.first { id !in it.pins } }
            record("pin persisted and unpinned in isolated preferences")

            repository.loadModelCatalog(id)
            val session = repository.state.value.sessions.first { it.id == id }
            val available = repository.state.value.modelCatalogs[session.device?.id ?: id]
            val choice = available?.models?.firstOrNull { it.value != "default" }
            requireNotNull(choice) { "Service did not return a selectable model" }
            val effort = choice.supportedEffortLevels.firstOrNull()
            repository.configureModel(id, choice.value, effort)
            withTimeout(10_000) { repository.state.first { it.sessions.any { row -> row.id == id && row.model == choice.value && row.effort == effort } } }
            repository.configureModel(id, null, null)
            repository.command("session.configure", json("sessionId" to id, "permissionMode" to "default"))
            record("real model and effort configuration saved and reset")

            repository.sendMessage("For a native Android streaming and cancellation test, write exactly 120 numbered lines, each containing a different short English sentence. Do not use tools, read files, run commands, or modify anything.")
            val running = withTimeout(90_000) { repository.state.first { it.sessions.any { row -> row.id == id && row.activeRun != null } } }.sessions.first { it.id == id }.activeRun!!
            withTimeout(120_000) { repository.state.first { state ->
                state.histories[id]?.let { h -> (h.fragments + h.events).any { it.runId == running.id && it.payload.optString("type") == "text.delta" && it.payload.optString("text").isNotBlank() } } == true
            } }
            record("actual English stream received")
            check(repository.state.value.sessions.first { it.id == id }.activeRun != null) { "Stream completed before cancellation could be exercised" }
            repository.command("run.cancel", json("sessionId" to id, "runId" to running.id))
            awaitIdle(id, 30_000)
            withTimeout(10_000) { repository.state.first { it.histories[id]?.events?.any { event -> event.runId == running.id && event.payload.optString("type") == "run.status" && event.payload.optString("status") == "cancelled" } == true } }
            record("active real generation cancelled")

            repository.command("run.continue", json("sessionId" to id))
            withTimeout(30_000) { repository.state.first { it.sessions.any { row -> row.id == id && row.activeRun != null } } }
            awaitIdle(id)
            val history = repository.state.value.histories.getValue(id)
            assertTrue(history.events.any { it.payload.optString("type") == "run.status" && it.payload.optString("status") == "completed" })
            record("cancelled generation continued to completion")

            val sequences = history.events.map { it.sequence }
            val page = repository.command("session.history", json("sessionId" to id, "beforeSequence" to sequences.last()))
            assertEquals("session.snapshot", page.optString("type"))
            assertEquals("prepend", page.optString("mode"))
            assertTrue((page.optJSONArray("events")?.length() ?: 0) > 0)
            val merged = repository.state.value.histories.getValue(id).events.map { it.sequence }
            assertEquals(merged.distinct(), merged)
            assertTrue(merged.containsAll(sequences))
            report.put("historyPageEventCount", page.optJSONArray("events")?.length())
            record("real earlier history request merged without duplicate sequences")

            val sockets = controlled.connections
            controlled.disconnect()
            // OkHttp allows a peer up to 60 seconds to acknowledge a normal close handshake.
            withTimeout(90_000) { while(controlled.connections == sockets) delay(50) }
            online()
            withTimeout(30_000) { repository.state.first { state -> state.histories[id]?.let{ !it.loading && it.events.map{event->event.sequence}.containsAll(sequences) } == true } }
            record("real WSS closed and reauthenticated with history cursor recovery")

            val beforeCompact = repository.state.value.histories.getValue(id).events.lastOrNull()?.sequence ?: 0
            repository.command("session.compact", json("sessionId" to id))
            record("real context compaction command accepted")
            withTimeout(180_000) { repository.state.first { state -> state.histories[id]?.events?.any { it.sequence > beforeCompact && it.payload.optString("type") == "native.compact" } == true } }
            awaitIdle(id)
            repository.command("session.status", json("sessionId" to id, "quota" to true))
            record("real context compaction completion event and refreshed status received")

            transientId = repository.createSession("Native acceptance disposable session")
            repository.command("session.delete", json("sessionId" to transientId))
            withTimeout(10_000) { repository.state.first { state -> state.sessions.none { it.id == transientId } } }
            transientId = null
            repository.selectSession(id)
            record("second test session deleted and evidence session reopened")

            repository.logout()
            assertFalse(repository.state.value.authenticated)
            assertTrue(transport.credential(endpoint).isEmpty())
            assertEquals(endpoint, original.getEndpoint())
            assertEquals(credential, original.credential(endpoint))
            record("real logout clears isolated credential while preserving regular app login")
            report.put("passed", true)
        } catch (error: Throwable) {
            // Do not record exception bodies from a remote service: they could contain user data.
            report.put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
          withContext(NonCancellable) {
            if(repository.state.value.status == "online") {
                for(id in listOfNotNull(createdId, transientId)) {
                    repository.state.value.sessions.find { it.id == id }?.activeRun?.let { run -> runCatching { withTimeout(10_000) { repository.command("run.cancel", json("sessionId" to id, "runId" to run.id)) } } }
                }
                transientId?.let { runCatching { withTimeout(10_000) { repository.command("session.delete", json("sessionId" to it)) } } }
            }
            withContext(Dispatchers.Main.immediate) { repository.dispose() }; repository.awaitPersistenceDrain(); transport.awaitClosed()
            original.close(); original.awaitClosed()
            isolated.clean()
            context.cacheDir.resolve("native-live-acceptance.json").writeText(report.toString(2))
          }
        }
    }

    private class ClosableSocketTransport(private val transport: WorkbenchTransport) : WorkbenchTransport by transport {
        @Volatile var connections = 0
        @Volatile private var latest: SocketConnection? = null
        override suspend fun socket(endpoint: String, observer: SocketObserver): SocketConnection = transport.socket(endpoint,observer).also { latest = it; connections++ }
        fun disconnect() { checkNotNull(latest).close() }
    }
    private class IsolatedContext(private val base: Context) : ContextWrapper(base) {
        private val prefix = "live-native-" + UUID.randomUUID()
        private val names = mutableSetOf<String>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            synchronized(names) { names += isolated }
            return base.getSharedPreferences(isolated, mode)
        }
        fun clean() { synchronized(names) { names.forEach { base.deleteSharedPreferences(it) } } }
    }
}
