package dev.ccdesk.nativeapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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

    private fun event(sequence: Int, payload: org.json.JSONObject, sessionId: String = "session-1") =
        json("sessionId" to sessionId, "runId" to "earlier-run", "sequence" to sequence, "createdAt" to "2026-11-14T00:00:00Z", "payload" to payload)

    @Test fun openingAChatAfterCompositionPreservesTheReadersPosition() {
        assertReaderPositionAfterOpeningChat(switchFromExistingChat = false)
    }

    @Test fun switchingChatsPreservesTheReadersPosition() {
        assertReaderPositionAfterOpeningChat(switchFromExistingChat = true)
    }

    private fun assertReaderPositionAfterOpeningChat(switchFromExistingChat: Boolean) {
        val transport = FixtureTransport()
        val target = if (switchFromExistingChat) "session-2" else "session-1"
        val targetSession = transport.session(target, "Scroll regression", activeRun = json("id" to "live-run", "status" to "running", "surface" to "chat"))
        if (switchFromExistingChat) transport.sessions += targetSession else transport.sessions[0] = targetSession
        val repository = WorkbenchRepository(transport)
        val bottomInset = mutableIntStateOf(0)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) { repository.state.value.status == "online" }
            if (switchFromExistingChat) {
                transport.events = JSONArray().put(event(1, json("type" to "message.user", "messageId" to "first", "text" to "First session")))
                runBlocking { repository.selectSession("session-1") }
            }
            compose.setContent {
                Box(Modifier.fillMaxSize().padding(bottom = bottomInset.intValue.dp)) { WorkbenchApp(repository) }
            }
            // Let the original chat's scroll observers start before replacing the session.
            compose.waitForIdle()
            transport.events = JSONArray().apply {
                for (i in 1..40) {
                    put(event(i * 2 - 1, json("type" to "message.user", "messageId" to "question-$i", "text" to "Earlier question $i"), target))
                    put(event(i * 2, json("type" to "text.delta", "messageId" to "answer-$i", "text" to "Earlier answer $i has a short, separately measured transcript row."), target))
                }
            }
            runBlocking { repository.selectSession(target) }
            compose.waitForIdle()
            fun chunk(i: Int) = transport.fragment(target, "live-run", json("type" to "text.delta", "messageId" to "live", "text" to "New streamed paragraph $i.\n\n"))
            chunk(1)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("回到最新消息").assertDoesNotExist()
            val transcript = compose.onNode(hasScrollToIndexAction())
            fun position() = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val end = position()
            // Multiple short rows re-enter the lazy list and report their first measurement.
            // Those layout callbacks must not look like a reason to return to the latest reply.
            transcript.performTouchInput { swipeDown(startY = height * .2f, endY = height * .8f, durationMillis = 700) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("回到最新消息").assertIsDisplayed()
            val anchored = position()
            assertTrue("The gesture must reveal earlier rows", anchored < end)
            for (i in 2..8) {
                chunk(i)
                compose.waitForIdle()
                assertEquals("Streaming after opening $target must retain the reading position", anchored, position(), .0001f)
            }
            compose.runOnIdle { bottomInset.intValue = 80 }
            compose.waitForIdle()
            assertEquals("Resizing after opening $target must retain the reading position", anchored, position(), .0001f)
            compose.onNodeWithContentDescription("回到最新消息").assertIsDisplayed().performClick()
            compose.waitForIdle()
            chunk(9)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("回到最新消息").assertDoesNotExist()
            assertEquals("Scrolling must not reload the conversation", 1, transport.sentOf("session.subscribe").count { it.optString("sessionId") == target })
            assertTrue("Scrolling must not request older pages", transport.sentOf("session.history").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

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
