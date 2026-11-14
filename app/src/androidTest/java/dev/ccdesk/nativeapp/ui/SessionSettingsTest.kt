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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Session settings and composer permission controls, exercised through the production app. */
@RunWith(AndroidJUnit4::class)
class SessionSettingsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var viewRoot: android.view.View

    private val modes = linkedMapOf(
        "auto" to "自动", "default" to "标准审批", "plan" to "计划模式",
        "acceptEdits" to "自动接受编辑", "bypassPermissions" to "跳过审批"
    )

    private fun start(transport: WorkbenchTransport, select: String? = "session-1", width: Dp? = null): WorkbenchRepository {
        val repository = WorkbenchRepository(transport)
        runBlocking { repository.start() }
        compose.waitUntil(5_000) { repository.state.value.status == "online" }
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
        permissionEntry(modes.getValue(mode)).assertIsDisplayed().assertIsEnabled()
    }

    /** Only the selected command response is held; state pushes still follow the real protocol. */
    private class HeldResponseTransport(private val fixture: FixtureTransport, private val command: String) : WorkbenchTransport by fixture {
        private val deferred = CopyOnWriteArrayList<String>()
        @Volatile private var holding = true
        private var downstream: SocketObserver? = null
        val pendingCount get() = deferred.size

        override suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection {
            downstream = observer
            return fixture.socket(endpoint, direct, object : SocketObserver {
                override fun opened() = observer.opened()
                override fun closed(code: Int, reason: String) = observer.closed(code, reason)
                override fun failed(cause: Throwable) = observer.failed(cause)
                override fun message(text: String) {
                    val frame = JSONObject(text)
                    val requestId = frame.optString("requestId")
                    val held = frame.optString("type") == "response" && fixture.sentOf(command).any { it.optString("requestId") == requestId }
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

    @Test fun settingsKeepSessionActionsButRemoveTheEntireProjectAndPermissionSections() {
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            compose.onAllNodesWithContentDescription("会话选项").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("查看项目，项目").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("新建会话").assertCountEquals(0)
            permissionEntry("标准审批").assertIsDisplayed()
            compose.onNodeWithContentDescription("会话设置").assertHasClickAction().performClick()
            compose.waitForIdle()
            for (removed in listOf("项目", "/workspace/fixture-project", "Fixture server", "查看全部会话", "权限模式", "保存会话配置", "保存权限模式")) {
                compose.onAllNodesWithText(removed).assertCountEquals(0)
            }
            for (label in modes.values) compose.onAllNodesWithText(label).assertCountEquals(0)
            for (action in listOf("查找对话内容", "刷新会话状态", "压缩上下文")) compose.onNodeWithText(action).assertExists()
            assertTrue(transport.sentOf("session.configure").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun everyPermissionModeRequiresExplicitSaveAndUpdatesTheComposerAfterClosing() {
        val transport = FixtureTransport()
        transport.sessions[0] = transport.session("session-1", "UI fixture", model = "model-kept").put("effort", "high")
        val repository = start(transport)
        try {
            for (mode in listOf("auto", "plan", "acceptEdits", "bypassPermissions", "default")) {
                val previousCount = transport.sentOf("session.configure").size
                permissionEntry().assertIsEnabled().performClick()
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
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            permissionEntry("标准审批").performClick()
            compose.onNodeWithText("计划模式").performClick()
            compose.onNodeWithText("保存权限模式").assertIsEnabled()
            closeSheet()
            permissionEntry("标准审批").assertIsDisplayed()
            assertTrue(transport.sentOf("session.configure").isEmpty())
            permissionEntry().performClick()
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled()
            closeSheet()
            assertTrue(transport.sentOf("session.configure").isEmpty())
            assertTrue(transport.sentOf("server.session.create").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun draftPermissionSaveCreatesOneSessionThenConfiguresThatSessionAndSendsThere() {
        val transport = FixtureTransport(); val repository = start(transport, select = null)
        try {
            permissionEntry("自动").performClick()
            compose.onNodeWithText("计划模式").performClick()
            assertTrue(transport.sentOf("server.session.create").isEmpty())
            assertTrue(transport.sentOf("session.configure").isEmpty())
            compose.onNodeWithText("保存权限模式").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { repository.state.value.selectedId == "created-1" && transport.sentOf("session.configure").isNotEmpty() }
            assertPermissionSaved(repository, "created-1", "plan")
            val configuration = transport.sentOf("session.configure").single()
            assertEquals("created-1", configuration.getString("sessionId"))
            assertEquals("plan", configuration.getString("permissionMode"))
            val createIndex = transport.sent.indexOfFirst { it.optString("type") == "server.session.create" }
            val configureIndex = transport.sent.indexOfFirst { it.optString("type") == "session.configure" }
            assertTrue("Creation must precede configuration", createIndex >= 0 && configureIndex > createIndex)
            compose.onNodeWithContentDescription("给 Claude 发消息…").performTextInput("Use the configured draft session")
            compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { transport.sentOf("message.send").isNotEmpty() }
            assertEquals("created-1", transport.sentOf("message.send").single().getString("sessionId"))
            assertEquals("Sending after permission save must reuse the created session", 1, transport.sentOf("server.session.create").size)
            assertEquals(1, transport.sentOf("session.configure").size)
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun cancellingDraftPermissionSelectionDoesNotCreateAnEmptySession() {
        val transport = FixtureTransport(); val repository = start(transport, select = null)
        try {
            permissionEntry("自动").performClick()
            compose.onNodeWithText("跳过审批").performClick()
            assertBypassWarningDisplayed()
            closeSheet()
            permissionEntry("自动").assertIsDisplayed()
            assertNull(repository.state.value.selectedId)
            assertTrue(transport.sentOf("server.session.create").isEmpty())
            assertTrue(transport.sentOf("session.configure").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun refreshStatusSendsOnceAndFindOpensSearch() {
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            compose.onNodeWithContentDescription("会话设置").performClick()
            compose.onNodeWithText("刷新会话状态").performClick()
            compose.waitUntil(5_000) { transport.sentOf("session.status").isNotEmpty() }
            assertTrue(transport.sentOf("session.status").single().getBoolean("quota"))
            compose.onNodeWithText("查找对话内容").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("查找对话内容").assertIsDisplayed()
            compose.onAllNodesWithTag(OverlayScrimTag).assertCountEquals(0)
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun readOnlySessionDisablesPermissionChangesAndRetainsReadOnlySettings() {
        val transport = FixtureTransport(json("id" to "offline-device", "name" to "Unavailable device"))
        val repository = start(transport)
        try {
            compose.onNodeWithContentDescription("只读会话").assertIsNotEnabled()
            permissionEntry("标准审批").performClick()
            for (label in modes.values) compose.onNodeWithText(label).assertIsNotEnabled()
            compose.onNodeWithText("计划模式").performTouchInput { click(center) }
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled().performTouchInput { click(center) }
            closeSheet()
            compose.onNodeWithContentDescription("会话设置").assertIsEnabled().performClick()
            compose.onNodeWithText("查找对话内容").assertIsEnabled()
            compose.onNodeWithText("刷新会话状态").assertIsNotEnabled()
            compose.onNodeWithText("压缩上下文").assertIsNotEnabled()
            compose.onAllNodesWithText("查看全部会话").assertCountEquals(0)
            assertTrue(transport.sent.none { it.optString("type") in setOf("session.status", "session.configure", "session.compact") })
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun activeRunLocksPermissionButStillAllowsARealStatusRefresh() {
        val transport = FixtureTransport()
        transport.sessions[0] = transport.session("session-1", "UI fixture", activeRun = json("id" to "run-1", "status" to "running", "surface" to "chat"))
        val repository = start(transport)
        try {
            permissionEntry("标准审批").performClick()
            for (label in modes.values) compose.onNodeWithText(label).assertIsNotEnabled()
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled()
            closeSheet()
            compose.onNodeWithContentDescription("会话设置").performClick()
            compose.onNodeWithText("刷新会话状态").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { transport.sentOf("session.status").isNotEmpty() }
            assertEquals("session-1", transport.sentOf("session.status").single().getString("sessionId"))
            assertTrue(transport.sentOf("session.status").single().getBoolean("quota"))
            compose.onNodeWithText("压缩上下文").assertIsNotEnabled()
            assertTrue(transport.sentOf("session.configure").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun pendingStatusResponseLocksPermissionChoicesUntilAcknowledged() {
        val transport = FixtureTransport(); val gate = HeldResponseTransport(transport, "session.status")
        val repository = start(gate)
        try {
            compose.onNodeWithContentDescription("会话设置").performClick()
            compose.onNodeWithText("刷新会话状态").performClick()
            compose.waitUntil(5_000) { gate.pendingCount == 1 }
            closeSheet()
            permissionEntry("标准审批").performClick()
            for (label in modes.values) compose.onNodeWithText(label).assertIsNotEnabled()
            compose.onNodeWithText("保存权限模式").assertIsNotEnabled()
            assertTrue(transport.sentOf("session.configure").isEmpty())
            compose.runOnIdle { gate.release() }
            compose.waitForIdle()
            compose.onNodeWithText("计划模式").assertIsEnabled()
            closeSheet()
        } finally { compose.runOnIdle { gate.release(); repository.dispose() } }
    }

    @Test fun pendingPermissionSaveLocksChoicesAndCannotSubmitTwice() {
        val transport = FixtureTransport(); val gate = HeldResponseTransport(transport, "session.configure")
        val repository = start(gate)
        try {
            permissionEntry().performClick()
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

    @Test fun longModelKeepsPermissionAtLeftAndModelAndSendUsableOnANarrowComposer() {
        val transport = FixtureTransport(); val repository = start(transport, width = 320.dp)
        try {
            val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
            val permission = permissionEntry("标准审批").fetchSemanticsNode().boundsInRoot
            val editor = compose.onNodeWithContentDescription("给 Claude 发消息…").fetchSemanticsNode().boundsInRoot
            val entry = compose.onNode(hasContentDescription("选择模型", substring = true)).fetchSemanticsNode().boundsInRoot
            assertTrue("Permission selector must precede model selector", permission.right <= entry.left + 1f)
            assertTrue("Model selector must be left of send", entry.right <= send.left + 1f)
            assertTrue("The toolbar must be below the editor", permission.top >= editor.bottom - 1f)
            assertEquals("Permission and send share one row", send.center.y, permission.center.y, 2f)
            val longModel = "claude-experimental-extremely-long-model-identifier-used-to-check-truncation-in-narrow-layouts"
            transport.update(transport.session("session-1", "UI fixture", model = longModel))
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(longModel, substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val afterSend = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
            val afterPermission = permissionEntry("标准审批").fetchSemanticsNode().boundsInRoot
            val longEntry = compose.onNode(hasContentDescription(longModel, substring = true)).fetchSemanticsNode().boundsInRoot
            assertEquals("Send must not move when the model label grows", send, afterSend)
            assertEquals("Permission must not move when the model label grows", permission, afterPermission)
            assertTrue("Long model must not overlap permission", longEntry.left >= afterPermission.right - 1f)
            assertTrue("Long model must not overlap send", longEntry.right <= afterSend.left + 1f)
            assertTrue("All three controls retain nonzero width", afterPermission.width > 0f && longEntry.width > 0f && afterSend.width > 0f)
            permissionEntry().performTouchInput { click(center) }
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

    @Test fun drawerNewSessionCreatesAndSelectsARealSession() {
        val transport = FixtureTransport(); val repository = start(transport)
        try {
            compose.onNodeWithContentDescription("打开导航").performClick()
            compose.onNodeWithText("新建会话").performClick()
            compose.waitUntil(5_000) { repository.state.value.selectedId == "created-1" }
            assertEquals(1, transport.sentOf("server.session.create").size)
            assertTrue(transport.sentOf("session.subscribe").any { it.getString("sessionId") == "created-1" })
            compose.waitForIdle()
            compose.onAllNodesWithText("新建会话").assertCountEquals(0)
        } finally { compose.runOnIdle { repository.dispose() } }
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
