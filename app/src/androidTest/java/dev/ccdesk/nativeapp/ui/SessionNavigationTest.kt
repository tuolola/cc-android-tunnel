package dev.ccdesk.nativeapp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class SessionNavigationTest {
    @get:Rule val compose = createComposeRule()

    /** A real protocol exchange, with only the history delivery delayed until the test releases it. */
    private class DelayedHistory(val fixture: FixtureTransport = FixtureTransport(json("id" to "offline-computer", "name" to "Offline computer"))) : WorkbenchTransport by fixture {
        private val held = CopyOnWriteArrayList<String>()
        private lateinit var downstream: SocketObserver
        private var holding = true
        override suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection {
            downstream = observer
            return fixture.socket(endpoint, direct, object : SocketObserver by observer {
                override fun message(text: String) {
                    val frame = JSONObject(text)
                    val historyReply = fixture.sentOf("session.subscribe").any { it.optString("requestId") == frame.optString("requestId") }
                    if (holding && historyReply) held += text else observer.message(text)
                }
            })
        }
        fun release(error: Boolean = false) {
            holding = false
            val frames = held.toList(); held.clear()
            if (error) {
                val id = fixture.sentOf("session.subscribe").last().getString("requestId")
                downstream.message(json("type" to "response", "requestId" to id, "ok" to false, "message" to "History temporarily unavailable").toString())
            } else frames.forEach(downstream::message)
        }
        val pendingCount get() = held.size
    }

    private fun start(transport: DelayedHistory): WorkbenchRepository {
        transport.fixture.events = JSONArray().put(json("sessionId" to "session-1", "runId" to "saved-run", "sequence" to 1,
            "createdAt" to "2026-11-14T00:00:00Z", "payload" to json("type" to "message.user", "text" to "Saved offline history")))
        val repository = WorkbenchRepository(transport)
        runBlocking { repository.start() }
        compose.waitUntil(5_000) { repository.state.value.status == "online" }
        compose.setContent { WorkbenchApp(repository, requestedRoute = "chats") }
        compose.waitForIdle()
        return repository
    }

    @Test fun openingAnOfflineDeviceSessionNavigatesBeforeHistoryAndSurvivesListDisposal() {
        val transport = DelayedHistory(); val repository = start(transport)
        try {
            compose.onNodeWithContentDescription("打开会话：UI fixture").performClick()
            compose.waitUntil(5_000) { transport.pendingCount > 0 }
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("查找会话").assertCountEquals(0)
            compose.onNodeWithText("正在读取会话…").assertIsDisplayed()
            compose.onNodeWithText("设备离线 · 只读").assertIsDisplayed()
            assertEquals("session-1", repository.state.value.selectedId)
            assertTrue(repository.state.value.histories.getValue("session-1").loading)
            compose.runOnIdle { transport.release() }
            compose.waitUntil(5_000) { repository.state.value.histories["session-1"]?.loaded == true }
            compose.onNodeWithText("Saved offline history").assertIsDisplayed()
            compose.onAllNodesWithText("正在读取会话…").assertCountEquals(0)
            assertFalse(repository.state.value.histories.getValue("session-1").loading)
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun failedHistoryReadStaysInChatAndStopsTheLoadingState() {
        val transport = DelayedHistory(); val repository = start(transport)
        try {
            compose.onNodeWithContentDescription("打开会话：UI fixture").performClick()
            compose.waitUntil(5_000) { transport.pendingCount > 0 }
            compose.waitForIdle()
            compose.runOnIdle { transport.release(error = true) }
            compose.waitUntil(5_000) { repository.state.value.histories["session-1"]?.loadError != null }
            compose.onAllNodesWithContentDescription("查找会话").assertCountEquals(0)
            compose.onAllNodesWithText("正在读取会话…").assertCountEquals(0)
            compose.onNodeWithText("History temporarily unavailable").assertIsDisplayed()
            compose.onNodeWithText("重新读取会话").performClick()
            compose.waitUntil(5_000) { repository.state.value.histories["session-1"]?.loaded == true }
            compose.onNodeWithText("Saved offline history").assertIsDisplayed()
            compose.onAllNodesWithText("History temporarily unavailable").assertCountEquals(0)
        } finally { compose.runOnIdle { repository.dispose() } }
    }
}
