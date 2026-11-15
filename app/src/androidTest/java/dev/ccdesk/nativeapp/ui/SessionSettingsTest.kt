package dev.ccdesk.nativeapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** The compact chat menu and desktop-only permission controls, exercised through the production app. */
@RunWith(AndroidJUnit4::class)
class SessionSettingsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var viewRoot: android.view.View

    private val modes = linkedMapOf(
        "auto" to "自动", "default" to "标准审批", "plan" to "计划模式",
        "acceptEdits" to "自动接受编辑", "bypassPermissions" to "跳过审批"
    )

    private fun desktopFixture() = FixtureTransport(json("id" to "desktop-online", "name" to "Desktop fixture"))
    private fun FixtureTransport.refreshRequests() = sentOf("session.status").filter { it.optBoolean("quota") }

    private fun start(transport: WorkbenchTransport, select: String? = "session-1", width: Dp? = null, readyDesktop: FixtureTransport? = null): WorkbenchRepository {
        val repository = WorkbenchRepository(transport)
        runBlocking { repository.start() }
        compose.waitUntil(5_000) { repository.state.value.status == "online" }
        if (readyDesktop != null) {
            readyDesktop.frame(json("type" to "devices", "devices" to JSONArray()
                .put(json("id" to "server", "name" to "Fixture server", "server" to true, "ready" to true))
                .put(json("id" to "desktop-online", "name" to "Desktop fixture", "ready" to true))))
            compose.waitUntil(5_000) { repository.state.value.devices.any { it.id == "desktop-online" && it.ready == true } }
        }
        if (select != null) runBlocking { repository.selectSession(select) }
        else {
            repository.newDraft()
            compose.waitUntil(5_000) { repository.state.value.selectedId == null }
        }
        compose.setContent {
            val view = LocalView.current
            SideEffect { viewRoot = view.rootView }
            if (width == null) WorkbenchApp(repository)
            else Box(Modifier.width(width).fillMaxHeight()) { WorkbenchApp(repository) }
        }
        compose.waitForIdle()
        return repository
    }

    private fun permissionEntry(label: String? = null) = compose.onNode(
        hasContentDescription("选择权限模式，${label.orEmpty()}", substring = true)
    )

    private fun openPermissionSheet(label: String? = null) {
        permissionEntry(label).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun assertPermissionInComposer(label: String) {
        permissionEntry(label).assertIsDisplayed().assertIsEnabled()
    }

    private fun openSessionMenu() {
        if (compose.onAllNodesWithContentDescription("刷新会话").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("会话菜单").assertIsEnabled().performClick()
        }
        compose.waitForIdle()
    }

    private fun closeMenu() {
        compose.onNodeWithContentDescription("关闭菜单").performTouchInput {
            click(Offset(8.dp.toPx(), height / 2f))
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("关闭菜单").assertCountEquals(0)
    }

    private fun closeSheet() {
        // The scrim covers the full window, but its centre is behind the sheet.
        // Inject a real tap in the exposed strip above the panel instead.
        compose.onNodeWithTag(OverlayScrimTag).performTouchInput {
            click(Offset(center.x, 8.dp.toPx()))
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(OverlayScrimTag).assertCountEquals(0)
    }

    private fun assertBypassWarningDisplayed() {
        // SNativeText is an Android TextView, without a Compose Text semantic.
        // Query the native view and require real on-screen visibility after its
        // expansion; looking only in the Compose semantics tree cannot find it.
        fun fullyVisible(view: android.view.View): Boolean {
            if (view is android.widget.TextView && view.text.contains("工具执行前的审批")) {
                val bounds = android.graphics.Rect()
                return view.isShown && view.alpha > 0f && view.getGlobalVisibleRect(bounds) &&
                    bounds.width() >= view.width && bounds.height() >= view.height && view.height > 0
            }
            return view is android.view.ViewGroup && (0 until view.childCount).any { fullyVisible(view.getChildAt(it)) }
        }
        fun warningVisible(): Boolean {
            var visible = false
            compose.runOnIdle { visible = fullyVisible(viewRoot) }
            return visible
        }
        compose.waitUntil(5_000) { warningVisible() }
        assertTrue("The complete native approval warning must be visible", warningVisible())
    }

    private fun assertPermissionSaved(repository: WorkbenchRepository, sessionId: String, mode: String) {
        compose.waitUntil(5_000) {
            repository.state.value.sessions.any { it.id == sessionId && it.permissionMode == mode }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(OverlayScrimTag).assertCountEquals(0)
        assertPermissionInComposer(modes.getValue(mode))
    }

    /** Only the selected command response is held; state pushes still follow the real protocol. */
    private class HeldResponseTransport(private val fixture: FixtureTransport, private val command: String) : WorkbenchTransport by fixture {
        private val deferred = CopyOnWriteArrayList<String>()
        @Volatile private var holding = true
        private var downstream: SocketObserver? = null
        val pendingCount get() = deferred.size

        override suspend fun socket(endpoint: String, observer: SocketObserver): SocketConnection {
            downstream = observer
            return fixture.socket(endpoint,object : SocketObserver {
                override fun opened() = observer.opened()
                override fun closed(code: Int, reason: String) = observer.closed(code, reason)
                override fun failed(cause: Throwable) = observer.failed(cause)
                override fun message(text: String) {
                    val frame = JSONObject(text)
                    val requestId = frame.optString("requestId")
                    val held = frame.optString("type") == "response" && fixture.sentOf(command).any { it.optString("requestId") == requestId && (command != "session.status" || it.optBoolean("quota")) }
                    if (holding && held) deferred += text else observer.message(text)
                }
            })
        }

        /** Called on the UI thread, matching FixtureTransport.frame delivery. */
        fun release() {
            holding = false
            val messages = deferred.toList()
            deferred.clear()
            messages.forEach { downstream?.message(it) }
        }
    }

    @Test fun serverChatMenuContainsOnlyFindAndRefreshAndHasNoPermissionControl() {
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            compose.onAllNodesWithContentDescription("会话选项").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("查看项目，项目").assertCountEquals(0)
            compose.onNodeWithContentDescription("新建会话").assertIsDisplayed().assertIsEnabled()
            permissionEntry().assertDoesNotExist()
            compose.onNodeWithContentDescription("聊天工具").assertDoesNotExist()
            compose.onNodeWithText("继续电脑上的工作").assertDoesNotExist()
            compose.onNodeWithText("撰写或编辑").assertDoesNotExist()
            openSessionMenu()
            compose.waitForIdle()
            for (removed in listOf("项目", "/workspace/fixture-project", "Fixture server", "查看全部会话", "权限模式", "保存会话配置", "保存权限模式", "会话设置", "置顶", "远程控制", "设置", "压缩上下文")) {
                compose.onAllNodesWithText(removed).assertCountEquals(0)
            }
            for (label in modes.values) compose.onAllNodesWithText(label).assertCountEquals(0)
            for (action in listOf("在聊天中查找", "刷新会话")) compose.onNodeWithContentDescription(action).assertIsDisplayed().assertIsEnabled()
            assertTrue(transport.sentOf("session.configure").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun everyDesktopPermissionModeRequiresExplicitSaveAndUpdatesTheComposerAfterClosing() {
        val transport = desktopFixture()
        transport.sessions[0] = transport.session("session-1", "UI fixture", model = "model-kept").put("effort", "high")
        val repository = start(transport, readyDesktop = transport)
        try {
            for (mode in listOf("auto", "plan", "acceptEdits", "bypassPermissions", "default")) {
                val previousCount = transport.sentOf("session.configure").size
                openPermissionSheet()
                for (label in modes.values) compose.onNodeWithText(label).assertExists().assertIsEnabled()
                compose.onNodeWithText("保存权限模式").assertIsNotEnabled()
                compose.onNodeWithText(modes.getValue(mode)).performClick()
                compose.waitForIdle()
                assertEquals("Selecting a mode must not write configuration", previousCount, transport.sentOf("session.configure").size)
                if (mode == "bypassPermissions") {
                    assertBypassWarningDisplayed()
                }
                compose.onNodeWithText("保存权限模式").assertIsEnabled().performClick()
                compose.waitUntil(5_000) { transport.sentOf("session.configure").size == previousCount + 1 }
                val frame = transport.sentOf("session.configure").last()
                assertEquals("session-1", frame.getString("sessionId"))
                assertEquals(mode, frame.getString("permissionMode"))
                assertFalse("Permission changes must not overwrite the model", frame.has("model"))
                assertFalse("Permission changes must not overwrite effort", frame.has("effort"))
                assertPermissionSaved(repository, "session-1", mode)
                assertEquals("model-kept", repository.state.value.sessions.single().model)
                assertEquals("high", repository.state.value.sessions.single().effort)
            }
            assertEquals(5, transport.sentOf("session.configure").size)
            assertTrue(transport.sentOf("server.session.create").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun dismissingAnUnsavedSelectionRestoresTheCurrentModeWithoutAWrite() {
        val transport = desktopFixture(); val repository = start(transport, readyDesktop = transport)
        try {
            openPermissionSheet("标准审批")
            compose.onNodeWithText("计划模式").performClick()
            compose.onNodeWithText("保存权限模式").assertIsEnabled()
            closeSheet()
            assertPermissionInComposer("标准审批")
            assertTrue(transport.sentOf("session.configure").isEmpty())
            openPermissionSheet()
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled()
            closeSheet()
            assertTrue(transport.sentOf("session.configure").isEmpty())
            assertTrue(transport.sentOf("server.session.create").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun serverSessionSendsWithoutExposingOrWritingPermissionConfiguration() {
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            permissionEntry().assertDoesNotExist()
            compose.onNodeWithContentDescription("聊天工具").assertDoesNotExist()
            openSessionMenu()
            permissionEntry().assertDoesNotExist()
            compose.onNodeWithText("权限模式").assertDoesNotExist()
            closeMenu()
            assertTrue(transport.sentOf("session.configure").isEmpty())
            compose.onNodeWithContentDescription("给 Claude 发消息…").performTextInput("Send in the server chat")
            compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { transport.sentOf("message.send").isNotEmpty() }
            assertEquals("session-1", transport.sentOf("message.send").single().getString("sessionId"))
            assertEquals("Send in the server chat", transport.sentOf("message.send").single().getString("text"))
            assertTrue(transport.sentOf("server.session.create").isEmpty())
            assertTrue(transport.sentOf("session.configure").isEmpty())
            permissionEntry().assertDoesNotExist()
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun draftHasNoPermissionEntryAndFirstSendCreatesWithoutConfiguring() {
        val transport = FixtureTransport(); val repository = start(transport, select = null)
        try {
            permissionEntry().assertDoesNotExist()
            compose.onNodeWithContentDescription("聊天工具").assertDoesNotExist()
            compose.onNodeWithText("继续电脑上的工作").assertDoesNotExist()
            compose.onNodeWithText("撰写或编辑").assertDoesNotExist()
            openSessionMenu()
            permissionEntry().assertDoesNotExist()
            compose.onNodeWithContentDescription("刷新会话").assertIsNotEnabled().performTouchInput { click(center) }
            closeMenu()
            assertNull(repository.state.value.selectedId)
            assertTrue(transport.sentOf("server.session.create").isEmpty())
            assertTrue(transport.sentOf("session.configure").isEmpty())
            assertTrue(transport.refreshRequests().isEmpty())
            compose.onNodeWithContentDescription("给 Claude 发消息…").performTextInput("First message without a permission picker")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(5_000) { transport.sentOf("message.send").size == 1 }
            assertEquals(1, transport.sentOf("server.session.create").size)
            assertEquals("created-1", transport.sentOf("message.send").single().getString("sessionId"))
            assertTrue(transport.sentOf("session.configure").isEmpty())
            permissionEntry().assertDoesNotExist()
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun refreshStatusSendsOnceAndFindOpensSearch() {
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            openSessionMenu()
            compose.onNodeWithContentDescription("刷新会话").performClick()
            compose.waitUntil(5_000) { transport.refreshRequests().isNotEmpty() }
            assertEquals("session-1", transport.refreshRequests().single().getString("sessionId"))
            assertTrue(transport.refreshRequests().single().getBoolean("quota"))
            openSessionMenu()
            compose.onNodeWithContentDescription("在聊天中查找").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("查找对话内容").assertIsDisplayed()
            compose.onAllNodesWithTag(OverlayScrimTag).assertCountEquals(0)
            compose.onAllNodesWithContentDescription("关闭菜单").assertCountEquals(0)
            assertTrue(transport.sentOf("session.configure").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun offlineDesktopDisablesPermissionChangesAndRefreshButKeepsSearch() {
        val transport = FixtureTransport(json("id" to "offline-device", "name" to "Unavailable device"))
        val repository = start(transport)
        try {
            compose.onNodeWithContentDescription("只读会话").assertIsNotEnabled()
            openPermissionSheet("标准审批")
            for (label in modes.values) compose.onNodeWithText(label).assertIsNotEnabled()
            compose.onNodeWithText("计划模式").performTouchInput { click(center) }
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled().performTouchInput { click(center) }
            closeSheet()
            openSessionMenu()
            compose.onNodeWithContentDescription("在聊天中查找").assertIsEnabled()
            compose.onNodeWithContentDescription("刷新会话").assertIsNotEnabled().performTouchInput { click(center) }
            compose.onNodeWithText("压缩上下文").assertDoesNotExist()
            compose.onAllNodesWithText("查看全部会话").assertCountEquals(0)
            assertTrue(transport.sent.none { it.optString("type") in setOf("session.status", "session.configure", "session.compact") })
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun activeRunLocksPermissionButStillAllowsARealStatusRefresh() {
        val transport = desktopFixture()
        transport.sessions[0] = transport.session("session-1", "UI fixture", activeRun = json("id" to "run-1", "status" to "running", "surface" to "chat"))
        val repository = start(transport, readyDesktop = transport)
        try {
            openPermissionSheet("标准审批")
            for (label in modes.values) compose.onNodeWithText(label).assertIsNotEnabled()
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled()
            closeSheet()
            openSessionMenu()
            compose.onNodeWithContentDescription("刷新会话").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { transport.refreshRequests().isNotEmpty() }
            assertEquals("session-1", transport.refreshRequests().single().getString("sessionId"))
            assertTrue(transport.refreshRequests().single().getBoolean("quota"))
            compose.onNodeWithText("压缩上下文").assertDoesNotExist()
            assertTrue(transport.sentOf("session.configure").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun pendingStatusResponseLocksRefreshAndPermissionChoicesUntilAcknowledged() {
        val transport = desktopFixture(); val gate = HeldResponseTransport(transport, "session.status")
        val repository = start(gate, readyDesktop = transport)
        try {
            openSessionMenu()
            compose.onNodeWithContentDescription("刷新会话").performClick()
            compose.waitUntil(5_000) { gate.pendingCount == 1 }
            openSessionMenu()
            compose.onNodeWithContentDescription("刷新会话").assertIsNotEnabled().performTouchInput { click(center) }
            compose.waitForIdle()
            assertEquals("A pending refresh cannot be submitted twice", 1, transport.refreshRequests().size)
            closeMenu()
            openPermissionSheet("标准审批")
            for (label in modes.values) compose.onNodeWithText(label).assertIsNotEnabled()
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled()
            assertTrue(transport.sentOf("session.configure").isEmpty())
            compose.runOnIdle { gate.release() }
            compose.waitForIdle()
            compose.onNodeWithText("计划模式").assertIsEnabled()
            closeSheet()
            openSessionMenu()
            compose.onNodeWithContentDescription("刷新会话").assertIsEnabled()
            assertEquals(1, transport.refreshRequests().size)
        } finally { compose.runOnIdle { gate.release(); repository.dispose() } }
    }

    @Test fun pendingPermissionSaveLocksChoicesAndCannotSubmitTwice() {
        val transport = desktopFixture(); val gate = HeldResponseTransport(transport, "session.configure")
        val repository = start(gate, readyDesktop = transport)
        try {
            openPermissionSheet()
            compose.onNodeWithText("计划模式").performClick()
            compose.onNodeWithText("保存权限模式").performClick()
            compose.waitUntil(5_000) { gate.pendingCount == 1 }
            for (label in modes.values) compose.onNodeWithText(label).assertIsNotEnabled()
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled().performTouchInput { click(center) }
            compose.waitForIdle()
            assertEquals(1, transport.sentOf("session.configure").size)
            assertEquals("plan", transport.sentOf("session.configure").single().getString("permissionMode"))
            compose.runOnIdle { gate.release() }
            assertPermissionSaved(repository, "session-1", "plan")
        } finally { compose.runOnIdle { gate.release(); repository.dispose() } }
    }

    @Test fun longModelKeepsHeaderAndComposerControlsUsableOnANarrowScreen() {
        val transport = desktopFixture(); val repository = start(transport, width = 320.dp, readyDesktop = transport)
        try {
            val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
            val permission = permissionEntry("标准审批").fetchSemanticsNode().boundsInRoot
            val editor = compose.onNodeWithContentDescription("给 Claude 发消息…").fetchSemanticsNode().boundsInRoot
            val entry = compose.onNode(hasContentDescription("选择模型", substring = true)).fetchSemanticsNode().boundsInRoot
            val navigation = compose.onNodeWithContentDescription("打开导航").fetchSemanticsNode().boundsInRoot
            val newChat = compose.onNodeWithContentDescription("新建会话").fetchSemanticsNode().boundsInRoot
            assertTrue("Desktop permission must remain left of the editor", permission.right <= editor.left + 1f)
            assertTrue("Editor must remain left of send", editor.right <= send.left + 1f)
            assertTrue("Model selector must remain in the header", entry.bottom < editor.top)
            assertTrue("Model selector must not overlap navigation", navigation.right <= entry.left + 1f)
            assertTrue("Model selector must not overlap new chat", entry.right <= newChat.left + 1f)
            compose.onNodeWithContentDescription("聊天工具").assertDoesNotExist()
            val longModel = "claude-experimental-extremely-long-model-identifier-used-to-check-truncation-in-narrow-layouts"
            transport.update(transport.session("session-1", "UI fixture", model = longModel))
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(longModel, substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val afterSend = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
            val afterPermission = permissionEntry("标准审批").fetchSemanticsNode().boundsInRoot
            val longEntry = compose.onNode(hasContentDescription(longModel, substring = true)).fetchSemanticsNode().boundsInRoot
            assertEquals("Send must not move when the model label grows", send, afterSend)
            assertEquals("Permission must not move when the model label grows", permission, afterPermission)
            assertTrue("Long model must not overlap navigation", longEntry.left >= navigation.right - 1f)
            assertTrue("Long model must not overlap new chat", longEntry.right <= newChat.left + 1f)
            assertTrue("All controls retain nonzero width", afterPermission.width > 0f && longEntry.width > 0f && afterSend.width > 0f)
            openPermissionSheet("标准审批")
            compose.onNodeWithText("计划模式").performClick()
            compose.onNodeWithText("保存权限模式").performClick()
            assertPermissionSaved(repository, "session-1", "plan")
            compose.onNode(hasContentDescription(longModel, substring = true)).performTouchInput { click(center) }
            compose.onNodeWithText("使用服务端默认模型").assertExists()
            compose.onNodeWithContentDescription("关闭模型面板").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("给 Claude 发消息…").performTextInput("Narrow composer still sends")
            compose.onNodeWithContentDescription("发送").assertIsEnabled().performTouchInput { click(center) }
            compose.waitUntil(5_000) { transport.sentOf("message.send").isNotEmpty() }
            assertEquals("Narrow composer still sends", transport.sentOf("message.send").single().getString("text"))
            assertEquals("session-1", transport.sentOf("message.send").single().getString("sessionId"))
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun drawerNewChatDefersCreationUntilTheFirstMessage() {
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            compose.onNodeWithContentDescription("打开导航").performClick()
            compose.onNodeWithContentDescription("新建会话").performClick()
            compose.waitUntil(5_000) { repository.state.value.selectedId == null }
            compose.waitForIdle()
            assertTrue("Opening a draft must not create an empty server session", transport.sentOf("server.session.create").isEmpty())
            compose.onAllNodesWithContentDescription("关闭侧栏").assertCountEquals(0)
            compose.onNodeWithContentDescription("给 Claude 发消息…").performTextInput("First message from the new chat")
            compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { repository.state.value.selectedId == "created-1" }
            compose.waitUntil(5_000) { transport.sentOf("message.send").isNotEmpty() }
            assertEquals(1, transport.sentOf("server.session.create").size)
            assertTrue(transport.sentOf("session.subscribe").any { it.getString("sessionId") == "created-1" })
            assertEquals("created-1", transport.sentOf("message.send").single().getString("sessionId"))
            assertEquals("First message from the new chat", transport.sentOf("message.send").single().getString("text"))
            val createIndex = transport.sent.indexOfFirst { it.optString("type") == "server.session.create" }
            val sendIndex = transport.sent.indexOfFirst { it.optString("type") == "message.send" }
            assertTrue("The selected session must exist before its first message", createIndex >= 0 && sendIndex > createIndex)
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun consecutiveNewChatsCreateDifferentSessionsForTheirFirstMessages() {
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            for (index in 1..2) {
                compose.onNodeWithContentDescription("新建会话").assertIsEnabled().performClick()
                compose.waitUntil(5_000) { repository.state.value.selectedId == null }
                compose.waitForIdle()
                assertEquals("Opening another draft must not create an empty session", index - 1, transport.sentOf("server.session.create").size)
                val editor = compose.onNodeWithContentDescription("给 Claude 发消息…")
                editor.assertTextEquals("")
                editor.performTextInput("First message for chat $index")
                compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
                compose.waitUntil(5_000) {
                    repository.state.value.selectedId == "created-$index" && transport.sentOf("message.send").size == index
                }
                compose.waitForIdle()
                assertEquals(index, transport.sentOf("server.session.create").size)
                val message = transport.sentOf("message.send")[index - 1]
                assertEquals("created-$index", message.getString("sessionId"))
                assertEquals("First message for chat $index", message.getString("text"))
                assertTrue(transport.sentOf("session.subscribe").any { it.getString("sessionId") == "created-$index" })
                compose.onNodeWithContentDescription("新建会话").assertIsEnabled()
            }
            assertEquals(listOf("created-1", "created-2"), transport.sentOf("message.send").map { it.getString("sessionId") })
            assertEquals("Each new draft needs its own create request", 2, transport.sentOf("server.session.create").map { it.getString("requestId") }.toSet().size)
            assertTrue(repository.state.value.sessions.map { it.id }.containsAll(listOf("created-1", "created-2")))
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun pendingFirstCreationDisablesBothNewChatEntriesAndSendsToItsOriginalSession() {
        val transport = FixtureTransport(); val gate = HeldResponseTransport(transport, "server.session.create")
        val repository = start(gate, select = null)
        try {
            compose.onNodeWithContentDescription("给 Claude 发消息…").performTextInput("Keep this first message with its pending session")
            compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { gate.pendingCount == 1 }
            compose.waitForIdle()
            assertNull("Selection waits for the create acknowledgement", repository.state.value.selectedId)
            assertTrue(transport.sentOf("message.send").isEmpty())
            val headerNewChat = compose.onNodeWithContentDescription("新建会话")
            headerNewChat.assertIsNotEnabled().performTouchInput { click(center) }
            headerNewChat.assertIsNotEnabled().performTouchInput { click(center) }

            compose.onNodeWithContentDescription("打开导航").performClick()
            compose.onNodeWithContentDescription("新建会话").assertIsNotEnabled().performTouchInput { click(center) }
            compose.waitForIdle()
            assertEquals("Disabled entries must not start another creation", 1, transport.sentOf("server.session.create").size)
            assertNull(repository.state.value.selectedId)
            assertTrue(transport.sentOf("message.send").isEmpty())
            compose.onNodeWithContentDescription("关闭侧栏").performTouchInput { click(Offset(width - 10f, height / 2f)) }
            compose.waitForIdle()

            compose.runOnIdle { gate.release() }
            compose.waitUntil(5_000) {
                repository.state.value.selectedId == "created-1" && transport.sentOf("message.send").size == 1
            }
            compose.waitForIdle()
            assertEquals(1, transport.sentOf("server.session.create").size)
            val message = transport.sentOf("message.send").single()
            assertEquals("created-1", message.getString("sessionId"))
            assertEquals("Keep this first message with its pending session", message.getString("text"))
            assertTrue(transport.sentOf("session.subscribe").any { it.getString("sessionId") == "created-1" })
            compose.onNodeWithContentDescription("新建会话").assertIsEnabled()
        } finally { compose.runOnIdle { gate.release(); repository.dispose() } }
    }

    @Test fun repeatedStopTapsCancelTheRunOnce() {
        val transport = FixtureTransport()
        transport.sessions[0] = transport.session("session-1", "UI fixture", activeRun = json("id" to "run-9", "status" to "running", "surface" to "chat"))
        val repository = start(transport)
        try {
            val stop = compose.onNodeWithContentDescription("停止运行")
            stop.performClick(); stop.performClick(); stop.performClick()
            compose.waitUntil(5_000) { transport.sentOf("run.cancel").isNotEmpty() }
            compose.waitForIdle()
            assertEquals(1, transport.sentOf("run.cancel").size)
            stop.assertIsNotEnabled()
        } finally { compose.runOnIdle { repository.dispose() } }
    }
}
