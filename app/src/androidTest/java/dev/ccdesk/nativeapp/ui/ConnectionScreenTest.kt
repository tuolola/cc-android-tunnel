package dev.ccdesk.nativeapp.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual connection form with a synthetic WSS protocol peer. */
@RunWith(AndroidJUnit4::class)
class ConnectionScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun nativeViews(): List<View> = buildList {
        fun visit(view: View) { add(view); if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i)) }
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .forEach { visit(it.window.decorView) }
    }

    private class ConnectionTransport(val fixture: FixtureTransport = FixtureTransport()) : WorkbenchTransport by fixture {
        var endpoint = ""
        var token = ""
        var connections = 0
        override suspend fun getEndpoint() = endpoint
        override suspend fun setEndpoint(endpoint: String) { this.endpoint = endpoint }
        override suspend fun credential(endpoint: String) = token
        override suspend fun saveCredential(endpoint: String, credential: String) { token = credential }
        override suspend fun socket(endpoint: String, observer: SocketObserver): SocketConnection {
            check(endpoint == "wss://fixture.test/ws")
            connections++
            return fixture.socket(endpoint, observer)
        }
    }

    @Test fun connectionFormOffersOnlyServiceCredentialsAndConnectsThroughWss() {
        val transport = ConnectionTransport()
        val repository = WorkbenchRepository(transport)
        var openedChat = 0
        try {
            runBlocking { repository.start() }
            compose.setContent { DeskTheme { SecondaryScreen("connection", repository, {}, {}, { openedChat++ }) } }
            compose.onNodeWithContentDescription("服务地址").assertExists()
            compose.onNodeWithContentDescription("服务证书指纹（可选）").assertExists()
            compose.onNodeWithContentDescription("服务凭据").assertExists()
            compose.runOnIdle {
                assertFalse(nativeViews().filterIsInstance<TextView>().any { it.text.toString() == "浏览器访问" })
            }
            compose.onNodeWithContentDescription("HTTPS 入口地址").assertDoesNotExist()
            compose.onNodeWithContentDescription("工作台密码").assertDoesNotExist()
            compose.onNodeWithContentDescription("身份验证器动态码").assertDoesNotExist()

            compose.onNodeWithContentDescription("服务地址").performTextInput("wss://fixture.test/ws")
            compose.waitForIdle()
            compose.onNodeWithContentDescription("服务凭据").performTextInput("fixture-service-token-0123456789")
            compose.onNodeWithContentDescription("服务凭据").performImeAction()
            compose.waitUntil(5_000) { repository.state.value.status == "online" && repository.state.value.serverDeviceId == "server" && openedChat == 1 }
            assertEquals("wss://fixture.test/ws", transport.endpoint)
            assertEquals("fixture-service-token-0123456789", transport.token)
            assertEquals(1, transport.connections)
            assertEquals("server", repository.state.value.serverDeviceId)
            assertEquals(1, transport.fixture.sentOf("auth").size)
            assertTrue(transport.fixture.sentOf("web.auth").isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun httpsAddressIsRejectedBeforeCredentialsAreSavedOrAConnectionIsOpened() {
        val transport = ConnectionTransport()
        val repository = WorkbenchRepository(transport)
        try {
            runBlocking { repository.start() }
            compose.setContent { DeskTheme { SecondaryScreen("connection", repository, {}, {}) } }
            compose.onNodeWithContentDescription("服务地址").performTextInput("https://fixture.test/workbench/")
            compose.onNodeWithContentDescription("服务凭据").performTextInput("fixture-service-token-0123456789")
            compose.onNodeWithContentDescription("服务凭据").performImeAction()
            compose.waitForIdle()
            val expectedError = "请输入完整的服务地址，例如 wss://example.com:8787/ws。"
            compose.runOnIdle {
                val error = nativeViews().filterIsInstance<TextView>().singleOrNull { it.text.toString() == expectedError }
                assertNotNull("The native connection form must display the WSS validation error", error)
                requireNotNull(error).requestRectangleOnScreen(Rect(0, 0, error.width, error.height), true)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val error = nativeViews().filterIsInstance<TextView>().single { it.text.toString() == expectedError }
                assertTrue("The WSS validation error must be visible", error.isShown && error.getGlobalVisibleRect(Rect()))
                assertTrue(error.isTextSelectable)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, error.accessibilityLiveRegion)
            }
            assertEquals("setup", repository.state.value.status)
            assertEquals("", transport.endpoint)
            assertEquals("", transport.token)
            assertEquals(0, transport.connections)
            assertTrue(transport.fixture.sent.isEmpty())
        } finally { compose.runOnIdle { repository.dispose() } }
    }
}
