package dev.ccdesk.nativeapp.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Device screenshots use an in-memory peer: no credentials or real user conversations. */
class VisualReferenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun capturePagesAndExerciseTheirNavigation() {
        val peer = FixtureTransport()
        peer.sessions += peer.session("desktop-chat", "原生界面优化").put("device", json("id" to "desktop-1", "name" to "工作电脑"))
        peer.sessions += peer.session("offline-chat", "移动端布局检查").put("device", json("id" to "desktop-2", "name" to "笔记本"))
        peer.events = JSONArray()
            .put(event(1, "message.user", "帮我整理一下今天的工作计划。"))
            .put(event(2, "text.delta", "可以从这三件事开始：\n\n1. 检查当前项目的界面布局。\n2. 完成侧栏与输入框的交互。\n3. 在真机上验证滚动和动画。\n\n每完成一项，就记录验证结果，方便接着推进。"))
            .put(json("sessionId" to "session-1", "runId" to "review-run", "sequence" to 3, "createdAt" to "2026-11-15T08:00:00Z", "payload" to json("type" to "run.status", "status" to "completed")))
        val repo = WorkbenchRepository(peer)
        val route = mutableStateOf<String?>(null)
        try {
            clearPreviousShots()
            runBlocking { repo.start() }
            compose.waitUntil(5_000) { repo.state.value.status == "online" }
            peer.frame(json("type" to "devices", "devices" to JSONArray()
                .put(json("id" to "server", "name" to "工作台", "server" to true, "ready" to true))
                .put(json("id" to "desktop-1", "name" to "工作电脑", "ready" to true))))
            runBlocking { repo.selectSession("session-1") }
            compose.setContent { ReviewEnvironment { WorkbenchApp(repo, route.value) { route.value = null } } }
            compose.onNodeWithContentDescription("给 Claude 发消息…").assertIsDisplayed()
            shot("01-chat")
            compose.onNodeWithContentDescription("会话菜单").performClick()
            compose.onNodeWithContentDescription("在聊天中查找").assertIsDisplayed()
            compose.onNodeWithContentDescription("刷新会话").assertIsDisplayed()
            compose.onNodeWithText("置顶").assertDoesNotExist()
            compose.onNodeWithText("会话设置").assertDoesNotExist()
            shot("02-chat-menu")
            compose.onNodeWithContentDescription("关闭菜单").performClick()
            compose.onNodeWithContentDescription("聊天工具").assertDoesNotExist()
            compose.onNodeWithContentDescription("选择权限模式，标准审批").assertDoesNotExist()
            shot("03-server-composer")
            compose.onNodeWithContentDescription("打开导航").performClick()
            shot("04-sidebar")
            compose.onNodeWithText("远程控制").performClick()
            compose.onNodeWithText("远程控制").assertIsDisplayed()
            shot("05-remote")
            peer.events = JSONArray()
                .put(event(1, "message.user", "检查电脑项目的当前布局。", "desktop-chat"))
                .put(event(2, "text.delta", "已经打开电脑项目，可以继续检查界面和处理审批。", "desktop-chat"))
                .put(json("sessionId" to "desktop-chat", "runId" to "review-run", "sequence" to 3, "createdAt" to "2026-11-15T08:00:00Z", "payload" to json("type" to "run.status", "status" to "completed")))
            compose.onNodeWithContentDescription("打开会话：原生界面优化").performClick()
            compose.waitUntil(5_000) { repo.state.value.selectedId == "desktop-chat" }
            compose.onNodeWithContentDescription("给 Claude 发消息…").assertIsDisplayed()
            compose.onNodeWithContentDescription("聊天工具").assertDoesNotExist()
            compose.onNodeWithContentDescription("选择权限模式，标准审批").assertIsDisplayed()
            shot("06-client-composer")
            compose.onNodeWithContentDescription("选择权限模式，标准审批").performClick()
            compose.onNodeWithText("保存权限模式").assertIsDisplayed()
            shot("07-client-permissions")
            compose.onNodeWithContentDescription("关闭权限面板").performClick()
            for (page in listOf("settings", "connection", "usage", "access", "notifications", "help")) {
                compose.runOnIdle { route.value = page }
                shot("page-$page")
            }
            compose.runOnIdle { repo.newDraft(); route.value = "" }
            compose.onNodeWithContentDescription("选择权限模式，标准审批").assertDoesNotExist()
            compose.onNodeWithContentDescription("聊天工具").assertDoesNotExist()
            compose.onNodeWithText("继续电脑上的工作").assertDoesNotExist()
            compose.onNodeWithText("撰写或编辑").assertDoesNotExist()
            shot("08-new-chat")
            assertFalse(peer.sent.any { it.optString("type") in setOf("message.send", "session.configure", "server.session.create") })
        } finally { compose.runOnIdle { repo.dispose() } }
    }

    @Test fun longContentEmptySearchAndOfflineHistoryRemainUsableWithoutWrites() {
        val title = "离线电脑上的超长会话标题：检查聊天布局、模型菜单和项目操作是否仍然清晰可用"
        val peer = FixtureTransport(json("id" to "offline-computer", "name" to "外出时保留历史的工作电脑"))
        peer.sessions.clear()
        peer.sessions += peer.session("session-1", title)
        // Match the fixture's offline device explicitly; the title must stay available
        // to accessibility even if its visible label ellipsizes on a narrow display.
        peer.sessions[0].put("device", json("id" to "offline-computer", "name" to "外出时保留历史的工作电脑"))
        peer.events = JSONArray()
            .put(event(1, "message.user", "请检查这一段较长的消息在窄屏与大字体下的换行。".repeat(9)))
            .put(event(2, "text.delta", "这是保存在离线电脑会话中的历史内容。\n\n" + (1..18).joinToString("\n\n") { "$it. 保留完整正文与段落间距，滚动时仍可打开菜单和回到最新消息。" }))
            .put(json("sessionId" to "session-1", "runId" to "review-run", "sequence" to 3,
                "createdAt" to "2026-11-15T08:00:00Z", "payload" to json("type" to "run.status", "status" to "completed")))
        val repo = WorkbenchRepository(peer)
        try {
            runBlocking { repo.start() }
            compose.waitUntil(5_000) { repo.state.value.status == "online" }
            compose.setContent { ReviewEnvironment { WorkbenchApp(repo, requestedRoute = "remote") } }
            compose.onNodeWithTag("remote-device:offline-computer").assertIsDisplayed()
            compose.onNodeWithContentDescription("打开会话：$title").assertIsDisplayed()
            shot("edge-offline-long-title")
            compose.onNodeWithContentDescription("打开会话：$title").performClick()
            compose.waitUntil(5_000) { repo.state.value.histories["session-1"]?.loaded == true }
            compose.onNodeWithContentDescription("只读会话").assertIsNotEnabled()
            compose.onNodeWithContentDescription("会话菜单").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("刷新会话").assertIsNotEnabled()
            shot("edge-offline-menu")
            compose.onNodeWithContentDescription("关闭菜单").performClick()
            // A real drag enters reader mode; an instantaneous semantics jump does
            // not provide isScrollInProgress and can be corrected by follow-at-end.
            compose.onNode(hasScrollToIndexAction()).performTouchInput {
                swipeDown(startY = height * .2f, endY = height * .8f, durationMillis = 700)
            }
            compose.waitForIdle()
            shot("edge-long-transcript")
            compose.onNodeWithContentDescription("回到最新消息").assertIsDisplayed().performClick()
            shot("edge-transcript-latest")
            compose.onNodeWithContentDescription("打开导航").performClick()
            compose.onNodeWithContentDescription("搜索会话").performClick()
            val requestsBeforeSearch = peer.sent.map { it.toString() }
            compose.onNodeWithContentDescription("搜索会话内容").performTextInput("no-match-fixture")
            compose.onNodeWithText("没有匹配的会话").assertIsDisplayed()
            shot("edge-empty-search")
            assertEquals("Sidebar search must stay local", requestsBeforeSearch, peer.sent.map { it.toString() })
            assertFalse(peer.sent.any { it.optString("type") in setOf("message.send", "session.configure", "server.session.create", "session.delete") })
        } finally { compose.runOnIdle { repo.dispose() } }
    }

    /**
     * Run with -e reviewTheme dark -e reviewFontScale 1.4. Configuration is scoped
     * to this composition, including native TextViews; device settings stay intact.
     */
    @Composable private fun ReviewEnvironment(content: @Composable () -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val baseConfiguration = LocalConfiguration.current
        val baseContext = LocalContext.current
        val baseDensity = LocalDensity.current
        val theme = args.getString("reviewTheme")
        val scale = args.getString("reviewFontScale")?.toFloatOrNull()?.coerceIn(1f, 2f) ?: baseConfiguration.fontScale
        val configuration = remember(baseConfiguration, theme, scale) {
            Configuration(baseConfiguration).apply {
                fontScale = scale
                if (theme == "dark" || theme == "light") {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (theme == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
            }
        }
        val context = remember(baseContext, configuration) {
            ContextThemeWrapper(baseContext, dev.ccdesk.nativeapp.R.style.AppTheme).apply { applyOverrideConfiguration(configuration) }
        }
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context,
            LocalDensity provides Density(baseDensity.density, scale), content = content)
    }

    private fun outputFolder(): File {
        val args = InstrumentationRegistry.getArguments()
        val variant = args.getString("reviewVariant")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,40}")) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(requireNotNull(context.getExternalFilesDir(null)), if (variant == null) "ui-review" else "ui-review-$variant")
    }

    private fun event(sequence: Int, type: String, text: String, sessionId: String = "session-1") = json(
        "sessionId" to sessionId, "runId" to "review-run", "sequence" to sequence,
        "createdAt" to "2026-11-15T08:00:00Z", "payload" to json("type" to type, "messageId" to "review-$sequence", "text" to text))

    /** This directory belongs to this screenshot test; remove its obsolete named captures. */
    private fun clearPreviousShots() {
        val folder = outputFolder()
        val captureName = Regex("(?:[0-9]{2}-[a-z-]+|page-[a-z-]+)\\.png")
        folder.listFiles()?.filter { it.isFile && captureName.matches(it.name) }?.forEach {
            check(it.delete()) { "Could not replace visual reference ${it.name}" }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        // Semantics can be ready before SurfaceFlinger has presented the first frame.
        android.os.SystemClock.sleep(400)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = outputFolder().apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
