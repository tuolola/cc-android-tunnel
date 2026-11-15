package dev.ccdesk.nativeapp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ccdesk.nativeapp.core.WorkbenchRepository
import dev.ccdesk.nativeapp.core.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SidebarInteractionTest {
    @get:Rule val compose = createComposeRule()

    private fun title(index: Int) = "Server chat ${index.toString().padStart(2, '0')}"
    private fun clickableLabel(label: String) = hasClickAction() and
        (hasText(label) or hasAnyDescendant(hasText(label)))

    @Test fun sidebarRevealsThirtyMoreLocalChatsWithoutLeavingOrCallingTheServer() {
        val transport = FixtureTransport()
        val timestamp = Instant.parse("2026-11-15T10:00:00Z")
        transport.sessions.clear()
        for (index in 1..65) {
            transport.sessions += transport.session("server-chat-$index", title(index))
                .put("updatedAt", timestamp.minusSeconds(index.toLong()).toString())
        }
        transport.sessions += transport.session("desktop-chat", "Remote-only computer chat")
            .put("device", json("id" to "desktop", "name" to "Computer fixture"))
            .put("updatedAt", timestamp.plusSeconds(1).toString())
        val repository = WorkbenchRepository(transport)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) {
                repository.state.value.status == "online" && repository.state.value.serverDeviceId == "server"
            }
            compose.setContent { WorkbenchApp(repository) }
            compose.waitForIdle()
            val requestsBefore = transport.sent.map { it.toString() }
            compose.onNodeWithContentDescription("打开导航").performClick()
            val sidebar = compose.onNodeWithTag("sidebar-conversations")
            sidebar.assertIsDisplayed()
            val inSidebar = hasAnyAncestor(hasTestTag("sidebar-conversations"))
            compose.onNode(clickableLabel("远程控制") and inSidebar).assertIsDisplayed().assertHasClickAction()
            compose.onNode(clickableLabel("通知") and inSidebar).assertIsDisplayed().assertHasClickAction()
            compose.onAllNodes(clickableLabel("聊天") and inSidebar).assertCountEquals(0)
            for (removed in listOf("项目", "查看更多", "设置", "图像", "资料库", "自定义", "账号与用量", "访问管理", "帮助")) {
                compose.onAllNodes(hasText(removed) and inSidebar).assertCountEquals(0)
            }
            // The newer desktop chat would sort first if it leaked into this server-chat list.
            compose.onNodeWithText("Remote-only computer chat").assertDoesNotExist()

            sidebar.performScrollToNode(hasText(title(30)))
            compose.onNodeWithText(title(30)).assertIsDisplayed()
            assertThrows("Only the initial thirty chats should be present", AssertionError::class.java) {
                sidebar.performScrollToNode(hasText(title(31)))
            }
            sidebar.performScrollToNode(hasText("加载更多会话"))
            compose.onNode(clickableLabel("加载更多会话") and inSidebar).assertIsDisplayed().performClick()
            sidebar.assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭侧栏").assertExists()
            sidebar.performScrollToNode(hasText(title(60)))
            compose.onNodeWithText(title(60)).assertIsDisplayed()
            assertThrows("One expansion must stop at sixty chats", AssertionError::class.java) {
                sidebar.performScrollToNode(hasText(title(61)))
            }

            sidebar.performScrollToNode(hasText("加载更多会话"))
            compose.onNode(clickableLabel("加载更多会话") and inSidebar).performClick()
            sidebar.performScrollToNode(hasText(title(65)))
            compose.onNodeWithText(title(65)).assertIsDisplayed()
            compose.onNodeWithText("加载更多会话").assertDoesNotExist()
            assertEquals("Incremental sidebar display is local, with no list/history requests", requestsBefore, transport.sent.map { it.toString() })

            // The scrim spans the whole screen; its centre is covered by conversation rows.
            // Tap the exposed strip at the right edge so dismissal cannot open a session.
            compose.onNodeWithContentDescription("关闭侧栏").performTouchInput {
                click(Offset(width - 10f, height / 2f))
            }
            compose.onNodeWithContentDescription("打开导航").performClick()
            sidebar.performScrollToNode(hasText(title(65)))
            compose.onNodeWithText(title(65)).assertIsDisplayed()
            compose.onNodeWithText("加载更多会话").assertDoesNotExist()
            assertEquals("Reopening keeps the expanded list without fetching again", requestsBefore, transport.sent.map { it.toString() })

            compose.onNodeWithContentDescription("工作台菜单").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("搜索设置").assertIsDisplayed()
            compose.onNodeWithTag("sidebar-conversations").assertDoesNotExist()
            assertTrue(transport.sent.none {
                it.optString("type") in setOf("server.session.create", "session.configure", "session.history", "session.list", "server.session.list")
            })
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun sidebarSearchFindsOlderServerChatsLocallyAndOpensOneSubscription() {
        val transport = FixtureTransport()
        val timestamp = Instant.parse("2026-11-15T10:00:00Z")
        transport.sessions.clear()
        for (index in 1..65) {
            transport.sessions += transport.session("server-chat-$index", title(index))
                .put("updatedAt", timestamp.minusSeconds(index.toLong()).toString())
        }
        // This newer title also matches the query, but belongs exclusively to Remote.
        val remoteTitle = "Remote-only computer chat 65"
        transport.sessions += transport.session("desktop-chat", remoteTitle)
            .put("device", json("id" to "desktop", "name" to "Computer fixture"))
            .put("updatedAt", timestamp.plusSeconds(1).toString())
        val repository = WorkbenchRepository(transport)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) {
                repository.state.value.status == "online" && repository.state.value.serverDeviceId == "server"
            }
            compose.setContent { WorkbenchApp(repository) }
            compose.waitForIdle()
            val requestsBefore = transport.sent.map { it.toString() }
            compose.onNodeWithContentDescription("打开导航").performClick()
            val sidebar = compose.onNodeWithTag("sidebar-conversations")
            val inSidebar = hasAnyAncestor(hasTestTag("sidebar-conversations"))
            assertThrows("The older match must begin outside the initial thirty rows", AssertionError::class.java) {
                sidebar.performScrollToNode(hasText(title(65)))
            }

            compose.onNodeWithContentDescription("搜索会话").performClick()
            val search = compose.onNodeWithContentDescription("搜索会话内容")
            search.assertIsDisplayed().performTextInput("65")
            sidebar.performScrollToNode(hasText(title(65)))
            compose.onNode(clickableLabel(title(65)) and inSidebar).assertIsDisplayed()
            compose.onNodeWithText(remoteTitle).assertDoesNotExist()
            compose.onNodeWithText(title(1)).assertDoesNotExist()
            compose.onNodeWithText("加载更多会话").assertDoesNotExist()
            assertEquals("Searching includes all cached server chats without fetching", requestsBefore, transport.sent.map { it.toString() })

            search.performTextClearance()
            sidebar.performScrollToNode(hasText(title(1)))
            compose.onNodeWithText(title(1)).assertIsDisplayed()
            assertThrows("Clearing restores the initial locally paged list", AssertionError::class.java) {
                sidebar.performScrollToNode(hasText(title(65)))
            }
            search.performTextInput("65")
            sidebar.performScrollToNode(hasText(title(65)))
            compose.onNodeWithText(title(65)).assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭会话搜索").performClick()
            search.assertDoesNotExist()
            compose.onNodeWithContentDescription("搜索会话").assertIsDisplayed()
            sidebar.performScrollToNode(hasText(title(1)))
            compose.onNodeWithText(title(1)).assertIsDisplayed()
            assertThrows("Closing search also restores the unfiltered page", AssertionError::class.java) {
                sidebar.performScrollToNode(hasText(title(65)))
            }
            assertEquals("Clearing and closing search must remain entirely local", requestsBefore, transport.sent.map { it.toString() })

            compose.onNodeWithContentDescription("搜索会话").performClick()
            compose.onNodeWithContentDescription("搜索会话内容").performTextInput("65")
            sidebar.performScrollToNode(hasText(title(65)))
            compose.onNode(clickableLabel(title(65)) and inSidebar).performClick()
            compose.waitUntil(5_000) {
                repository.state.value.selectedId == "server-chat-65" &&
                    repository.state.value.histories["server-chat-65"]?.loading == false
            }
            compose.onNodeWithContentDescription("给 Claude 发消息…").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭侧栏").assertDoesNotExist()
            val newRequests = transport.sent.drop(requestsBefore.size)
            val subscriptions = newRequests.filter { it.optString("type") == "session.subscribe" }
            assertEquals("Opening the search result subscribes exactly once", 1, subscriptions.size)
            assertEquals("server-chat-65", subscriptions.single().optString("sessionId"))
            assertTrue("Only opening the match may contact the peer", newRequests.all { it.optString("type") == "session.subscribe" })
        } finally { compose.runOnIdle { repository.dispose() } }
    }
}
