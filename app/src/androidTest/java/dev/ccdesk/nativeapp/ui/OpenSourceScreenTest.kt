package dev.ccdesk.nativeapp.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ccdesk.nativeapp.R
import dev.ccdesk.nativeapp.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real help navigation and bundled documents work without any service or credentials. */
@RunWith(AndroidJUnit4::class)
class OpenSourceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun helpOpensEveryCompleteOfflineLicenseAndBackReturnsToItsParent() {
        val repository = WorkbenchRepository(NoNetworkTransport())
        val route = mutableStateOf("help")
        var closes = 0
        try {
            compose.setContent { DeskTheme { SecondaryScreen(route.value, repository, { route.value = it }, { closes++ }) } }
            compose.runOnIdle { views().filterIsInstance<ScrollView>().single().let { it.scrollTo(0, it.getChildAt(0).height) } }
            compose.onNodeWithText("开源许可与致谢").assertIsDisplayed().performClick()
            compose.onNodeWithText("开源许可与致谢").assertIsDisplayed()
            compose.onNodeWithContentDescription("打开上游项目：https://github.com/sun168567/cc-desk-tunnel").assertHasClickAction()
            compose.onNodeWithContentDescription("打开本项目仓库：https://github.com/tuolola/cc-android-tunnel").assertHasClickAction()
            compose.runOnIdle {
                assertTrue(views().filterIsInstance<TextView>().any { it.text.toString().contains("非上游官方发行") })
            }
            val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
            val documents = listOf(
                "LICENSE · Apache 2.0" to R.raw.app_license,
                "NOTICE · 来源与修改说明" to R.raw.app_notice,
                "第三方开源许可汇总" to R.raw.third_party_notices,
                "Lucide 图标许可" to R.raw.lucide_license,
                "Bootstrap Icons 许可" to R.raw.bootstrap_icons_license,
                "语法高亮规则许可" to R.raw.highlight_grammar_license,
                "文本行高实现许可" to R.raw.text_line_height_license,
            )
            for ((title, resource) in documents) {
                val expected = resources.openRawResource(resource).bufferedReader(Charsets.UTF_8).use { it.readText() }
                assertTrue("A bundled document must contain real text", expected.isNotBlank())
                compose.onNodeWithContentDescription("查看$title").performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.waitUntil(5_000) {
                    var completeAndSelectable = false
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        completeAndSelectable = views().filterIsInstance<TextView>().any {
                            it.text.toString() == expected && it.isTextSelectable
                        }
                    }
                    completeAndSelectable
                }
                compose.runOnIdle { activity().onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("开源许可与致谢").assertIsDisplayed()
                compose.runOnIdle { assertEquals("licenses", route.value); assertEquals(0, closes) }
            }
            compose.onNodeWithContentDescription("返回帮助").performClick()
            compose.runOnIdle { assertEquals("help", route.value); activity().onBackPressedDispatcher.onBackPressed(); assertEquals(1, closes) }
        } finally {
            compose.runOnIdle { repository.dispose() }
        }
    }

    private fun activity() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<ComponentActivity>().single()

    private fun views(): List<View> = buildList {
        fun visit(view: View) { add(view); if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i)) }
        visit(activity().window.decorView)
    }

    private class NoNetworkTransport : WorkbenchTransport {
        private fun unused(): Nothing = error("License pages must not access networking, credentials, or persistence")
        override suspend fun getEndpoint(): String = unused()
        override suspend fun setEndpoint(endpoint: String): Unit = unused()
        override suspend fun readPreferences(endpoint: String): Preferences = unused()
        override suspend fun writePreferences(endpoint: String, preferences: Preferences): Unit = unused()
        override suspend fun credential(endpoint: String): String = unused()
        override suspend fun saveCredential(endpoint: String, credential: String): Unit = unused()
        override suspend fun deviceId(): String = unused()
        override suspend fun fingerprint(endpoint: String): String = unused()
        override suspend fun setFingerprint(endpoint: String, fingerprint: String): Unit = unused()
        override suspend fun clearOrigin(endpoint: String): Unit = unused()
        override suspend fun clearBrowserSession(endpoint: String): Unit = unused()
        override suspend fun http(endpoint: String, route: String, body: JSONObject?): JSONObject = unused()
        override suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection = unused()
        override fun close() {}
    }
}
