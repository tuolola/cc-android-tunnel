package dev.ccdesk.nativeapp.ui

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineComputerSessionOpensAndContinuesLoadingAfterRemoteLeavesComposition() {
        val fixture = FixtureTransport(json("id" to "offline-computer", "name" to "Offline computer"))
        fixture.events = JSONArray().put(json("sessionId" to "session-1", "runId" to "saved", "sequence" to 1,
            "createdAt" to "2026-11-15T00:00:00Z", "payload" to json("type" to "message.user", "text" to "Saved history")))
        val repository = WorkbenchRepository(fixture)
        val remote = mutableStateOf(true)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) { repository.state.value.status == "online" }
            compose.setContent { DeskTheme {
                if (remote.value) RemoteScreen(repository, {}, { remote.value = false }, {})
                else DeskText("Opened chat")
            } }
            compose.onNodeWithTag("remote-device:offline-computer").assertExists()
            compose.onNodeWithContentDescription("电脑：Offline computer").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "离线，已展开"))
            compose.onNodeWithContentDescription("打开会话：UI fixture").performClick()
            compose.onNodeWithText("Opened chat").assertIsDisplayed()
            compose.waitUntil(5_000) { repository.state.value.histories["session-1"]?.loaded == true }
            assertEquals("session-1", repository.state.value.selectedId)
            assertEquals("Saved history", repository.state.value.histories.getValue("session-1").events.single().payload.getString("text"))
            assertEquals(1, fixture.sentOf("session.subscribe").size)
            assertTrue(fixture.sentOf("server.session.create").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun serverSessionsDoNotBecomeComputersAndSetupOpensConnectionWithoutCreatingASession() {
        val fixture = FixtureTransport()
        val repository = WorkbenchRepository(fixture)
        var route = ""
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) { repository.state.value.serverDeviceId == "server" }
            compose.setContent { DeskTheme { RemoteScreen(repository, { route = it }, {}, {}) } }
            compose.onNodeWithTag("remote-device:server").assertDoesNotExist()
            compose.onNodeWithContentDescription("打开会话：UI fixture").assertDoesNotExist()
            compose.onNodeWithText("连接工作台").performClick()
            assertEquals("connection", route)
            assertTrue(fixture.sentOf("server.session.create").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun settingsAndConnectionReturnToRemoteWhileOpenChatLeavesRemote() {
        val fixture = FixtureTransport(json("id" to "offline-computer", "name" to "Offline computer"))
        val repository = WorkbenchRepository(fixture)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) { repository.state.value.status == "online" }
            compose.setContent { WorkbenchApp(repository, requestedRoute = "remote") }
            compose.onNodeWithText("远程控制").assertIsDisplayed()
            compose.onNodeWithContentDescription("打开侧栏").performClick()
            compose.onNodeWithContentDescription("工作台菜单").performClick()
            compose.onNodeWithContentDescription("搜索设置").assertIsDisplayed()
            compose.onNodeWithContentDescription("关于与帮助").performClick()
            compose.onNodeWithContentDescription("返回设置").performClick()
            compose.onNodeWithContentDescription("搜索设置").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("远程控制").assertIsDisplayed()

            compose.onNodeWithContentDescription("远程控制更多选项").performClick()
            compose.onNodeWithText("连接设置").performClick()
            compose.onNodeWithContentDescription("服务地址").assertExists()
            compose.onNodeWithContentDescription("关闭").performClick()
            compose.onNodeWithText("远程控制").assertIsDisplayed()
            compose.onNodeWithContentDescription("远程控制更多选项").performClick()
            compose.onNodeWithText("连接设置").performClick()
            // With the current authenticated address unchanged, submitting the form opens chat.
            compose.onNodeWithContentDescription("服务凭据").performImeAction()
            compose.onNodeWithContentDescription("服务地址").assertDoesNotExist()
            compose.onNodeWithText("远程控制").assertDoesNotExist()
            assertTrue(fixture.sentOf("server.session.create").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun localPhoneIsHiddenButSameNamedUnreadyComputerAndItsSessionRemainVisible() {
        val fixture = FixtureTransport()
        val sharedName = "CC Android Tunnel"
        fixture.sessions += fixture.session("local-phone-chat", "Local phone history")
            .put("device", json("id" to "fixture-device", "name" to sharedName))
        fixture.sessions += fixture.session("same-name-chat", "Same-name computer history")
            .put("device", json("id" to "same-name-computer", "name" to sharedName))
        val repository = WorkbenchRepository(fixture)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) { repository.state.value.status == "online" }
            fixture.frame(json("type" to "devices", "devices" to JSONArray()
                .put(json("id" to "server", "name" to "Fixture server", "server" to true, "ready" to true))
                .put(json("id" to "fixture-device", "name" to sharedName, "ready" to false))
                .put(json("id" to "same-name-computer", "name" to sharedName, "ready" to false))))
            compose.waitUntil(5_000) {
                repository.state.value.devices.any { it.id == "fixture-device" } &&
                    repository.state.value.devices.any { it.id == "same-name-computer" && it.ready == false }
            }
            val requestsBefore = fixture.sent.map { it.toString() }
            compose.setContent { DeskTheme { RemoteScreen(repository, {}, {}, {}) } }
            compose.onNodeWithTag("remote-device:fixture-device").assertDoesNotExist()
            compose.onNodeWithContentDescription("打开会话：Local phone history").assertDoesNotExist()
            compose.onNodeWithTag("remote-device:server").assertDoesNotExist()
            compose.onNodeWithTag("remote-device:same-name-computer").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("电脑：$sharedName").assertCountEquals(1)
            compose.onNodeWithContentDescription("电脑：$sharedName").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "准备中，已展开"))
            compose.onNodeWithContentDescription("打开会话：Same-name computer history").assertIsDisplayed()
            assertEquals("Filtering device identity must not send commands", requestsBefore, fixture.sent.map { it.toString() })
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun systemBackClosesTheRemoteDrawerWithoutLeavingRemote() {
        val fixture = FixtureTransport(json("id" to "offline-computer", "name" to "Offline computer"))
        val repository = WorkbenchRepository(fixture)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) { repository.state.value.status == "online" }
            compose.setContent { WorkbenchApp(repository, requestedRoute = "remote") }
            compose.onNodeWithText("远程控制").assertIsDisplayed()
            compose.onNodeWithContentDescription("打开侧栏").performClick()
            compose.onNodeWithContentDescription("关闭侧栏").assertExists()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            try {
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription("关闭侧栏").fetchSemanticsNodes().isEmpty() &&
                        runCatching { compose.onNodeWithText("远程控制").assertIsDisplayed() }.isSuccess
                }
            } catch (failure: Throwable) {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                roots.fetchSemanticsNodes().indices.forEach { index -> println("Remote Back root $index:\n${roots[index].printToString(maxDepth = 20)}") }
                throw failure
            }
            compose.onNodeWithContentDescription("关闭侧栏").assertDoesNotExist()
            compose.onNodeWithText("远程控制").assertIsDisplayed()
            compose.onNodeWithTag("remote-device:offline-computer").assertExists()
        } finally { compose.runOnIdle { repository.dispose() } }
    }
}
