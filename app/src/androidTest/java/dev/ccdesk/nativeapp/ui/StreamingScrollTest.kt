package dev.ccdesk.nativeapp.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Streaming must update rows in place and must never pull a reader who scrolled away back to the end. */
@RunWith(AndroidJUnit4::class)
class StreamingScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun event(sequence: Int, payload: org.json.JSONObject) =
        json("sessionId" to "session-1", "runId" to "earlier-run", "sequence" to sequence, "createdAt" to "2026-11-14T00:00:00Z", "payload" to payload)

    @Test fun readerWhoScrolledAwayStaysPutWhileTheReplyStreams() {
        val transport = FixtureTransport()
        val earlier = (1..80).joinToString("\n") { "Earlier line $it: native text that wraps across the transcript width." }
        transport.events = JSONArray().put(event(1, json("type" to "text.delta", "messageId" to "old", "text" to earlier))).put(event(2, json("type" to "run.status", "status" to "completed")))
        transport.sessions[0] = transport.session("session-1", "UI fixture", activeRun = json("id" to "live-run", "status" to "running", "surface" to "chat"))
        val repository = WorkbenchRepository(transport)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) { repository.state.value.status == "online" }
            runBlocking { repository.selectSession("session-1") }
            compose.setContent { WorkbenchApp(repository) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("已完成").fetchSemanticsNodes().isNotEmpty() }
            fun chunk(i: Int) = transport.fragment("session-1", "live-run", json("type" to "text.delta", "messageId" to "live", "text" to "Streamed sentence $i adds a full wrapped line of reply text to the open message.\n\n"))
            for (i in 1..6) { chunk(i); compose.waitForIdle() }
            compose.onNodeWithContentDescription("回到最新消息").assertDoesNotExist()
            val transcript = compose.onNode(hasScrollToIndexAction())
            transcript.performTouchInput { swipeDown(startY = centerY - 200f, endY = centerY + 400f, durationMillis = 700) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("回到最新消息").assertIsDisplayed()
            val anchored = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            for (i in 7..30) {
                chunk(i); compose.waitForIdle()
                val now = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertEquals("Chunk $i must not move a reader who scrolled away", anchored, now, .5f)
            }
            compose.onNodeWithContentDescription("回到最新消息").assertIsDisplayed().performClick()
            compose.waitForIdle()
            chunk(31); compose.waitForIdle()
            compose.onNodeWithContentDescription("回到最新消息").assertDoesNotExist()
        } finally { compose.runOnIdle { repository.dispose() } }
    }
}
