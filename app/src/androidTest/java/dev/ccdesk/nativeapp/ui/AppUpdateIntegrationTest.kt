package dev.ccdesk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Real settings navigation and native scrolling, backed only by in-memory English fixtures. */
@RunWith(AndroidJUnit4::class)
class AppUpdateIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val notes = (1..18).joinToString("\n") { "Fixture release note $it: preserve the current draft." }
    private val release = AppRelease(
        version = "9.2.0", tagName = "v9.2.0", name = "Integration fixture release 9.2.0",
        releaseUrl = "https://github.com/tuolola/cc-android-tunnel/releases/tag/v9.2.0",
        apkUrl = "https://github.com/tuolola/cc-android-tunnel/releases/download/v9.2.0/CCtunnel.apk",
        body = notes, prerelease = false, sha256 = "0123456789abcdef".repeat(4),
    )

    @Test fun offlineSettingsCanCheckScrollAndKeepPreferencesAcrossNavigation() {
        val transport = NoNetworkTransport()
        val workbench = offlineWorkbench(transport)
        val source = FixtureSource(listOf(release))
        val store = MemoryStore()
        val updates = AppUpdateRepository("1.0.0", source, store, dispatcher = Dispatchers.Main.immediate)
        val route = mutableStateOf("settings")
        val visible = mutableStateOf(true)
        val screenshot = screenshotFile()
        // A failed run must not leave a previous run's screenshot as apparent evidence.
        if (screenshot.exists()) check(screenshot.delete())
        try {
            compose.setContent {
                CompositionLocalProvider(LocalAppUpdates provides updates) {
                    DeskTheme {
                        Box(Modifier.fillMaxSize().background(LocalDeskColors.current.background).testTag("update-integration-root")) {
                            if (!visible.value) DeskAction("Reopen settings") { visible.value = true }
                            SecondaryScreen(route.value, workbench, { route.value = it }, { visible.value = false }, visible = visible.value)
                        }
                    }
                }
            }
            compose.runOnIdle {
                assertEquals("offline", workbench.state.value.status)
                assertFalse(workbench.state.value.authenticated)
            }
            val checkButton = scrollToNative(compose.onNodeWithTag("app-update-check"))
            tapThroughWindow(checkButton.assertIsEnabled())
            compose.waitUntil { updates.state.value.updateAvailable && !updates.state.value.checking }
            compose.onNodeWithTag("app-update-status").assertTextEquals("发现新版本 9.2.0")
            compose.runOnIdle { assertEquals(1, source.calls.get()) }

            val automatic = scrollToNative(compose.onNodeWithTag("app-update-automatic"))
            tapThroughWindow(automatic.assertIsOn())
            automatic.assertIsOff()
            val prereleases = scrollToNative(compose.onNodeWithTag("app-update-prereleases"))
            tapThroughWindow(prereleases.assertIsOff())
            prereleases.assertIsOn()
            compose.waitUntil { source.calls.get() == 2 && !updates.state.value.checking }
            compose.runOnIdle {
                assertTrue("The production native container must actually scroll", nativeScroll().scrollY > 0)
                assertFalse(updates.state.value.automatic)
                assertTrue(updates.state.value.includePrereleases)
                val saved = JSONObject(requireNotNull(store.value))
                assertFalse(saved.getBoolean("automatic"))
                assertTrue(saved.getBoolean("includePrereleases"))
            }

            var collapsedHeight = 0
            scrollToNative(compose.onNodeWithText("查看更新说明"))
            compose.runOnIdle { collapsedHeight = nativeScroll().getChildAt(0).height }
            tapThroughWindow(compose.onNodeWithText("查看更新说明"))
            compose.onNodeWithText("收起更新说明").assertHasClickAction()
            compose.runOnIdle {
                assertTrue("Expanding native notes must remeasure the outer ScrollView", nativeScroll().getChildAt(0).height > collapsedHeight)
                assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == notes && it.isTextSelectable })
            }
            // There is no espresso-intents dependency. Do not launch a browser or download;
            // isolated AppUpdateScreenTest verifies the exact URL callback values separately.
            scrollToNative(compose.onNodeWithTag("app-update-download")).assertIsEnabled().assertHasClickAction()
            scrollToNative(compose.onNodeWithTag("app-update-release")).assertIsEnabled().assertHasClickAction()

            tab("帮助").performScrollTo().performClick()
            compose.runOnIdle { assertEquals("help", route.value) }
            compose.onNodeWithTag("app-update-card").assertDoesNotExist()
            tab("概览").performScrollTo().performClick()
            compose.runOnIdle { assertEquals("settings", route.value) }
            scrollToNative(compose.onNodeWithTag("app-update-automatic")).assertIsOff()
            scrollToNative(compose.onNodeWithTag("app-update-prereleases")).assertIsOn()
            compose.onNodeWithTag("app-update-status").assertTextEquals("发现新版本 9.2.0")

            compose.onNodeWithContentDescription("关闭").performClick()
            compose.onNodeWithTag("app-update-card").assertDoesNotExist()
            compose.onNodeWithText("Reopen settings").performClick()
            scrollToNative(compose.onNodeWithTag("app-update-automatic")).assertIsOff()
            scrollToNative(compose.onNodeWithTag("app-update-prereleases")).assertIsOn()
            compose.onNodeWithTag("app-update-status").assertTextEquals("发现新版本 9.2.0")
            compose.runOnIdle {
                assertEquals("Navigation must not start another update check", 2, source.calls.get())
                assertEquals("Only the deliberate offline workbench probe is allowed", 1, transport.probes)
                assertEquals(0, transport.forbiddenCalls)
            }
            // Fixture-only evidence, generated after all functional assertions above have passed.
            scrollToNative(compose.onNodeWithText("Integration fixture release 9.2.0"))
            val bitmap = compose.onNodeWithTag("update-integration-root").captureToImage().asAndroidBitmap()
            screenshot.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            compose.runOnIdle { workbench.dispose(); updates.dispose() }
        }
    }

    @Test fun offlineSettingsDistinguishAnEmptyReleaseChannelFromTheLatestVersion() {
        val transport = NoNetworkTransport()
        val workbench = offlineWorkbench(transport)
        val source = FixtureSource(emptyList())
        val updates = AppUpdateRepository("1.0.0", source, MemoryStore(), dispatcher = Dispatchers.Main.immediate)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalAppUpdates provides updates) {
                    DeskTheme { SecondaryScreen("settings", workbench, {}, {}) }
                }
            }
            tapThroughWindow(scrollToNative(compose.onNodeWithTag("app-update-check")))
            compose.waitUntil { updates.state.value.status == AppUpdateStatus.NO_RELEASE }
            scrollToNative(compose.onNodeWithTag("app-update-status")).assertTextEquals("当前渠道暂无可安装版本")
            compose.onAllNodesWithText("当前版本无需更新").assertCountEquals(0)
            compose.onNodeWithTag("app-update-download").assertDoesNotExist()
            compose.onNodeWithTag("app-update-release").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("offline", workbench.state.value.status)
                assertEquals(1, source.calls.get())
                assertNotNull(updates.state.value.lastCheckedAt)
                assertNull(updates.state.value.error)
                assertEquals(0, transport.forbiddenCalls)
            }
        } finally {
            compose.runOnIdle { workbench.dispose(); updates.dispose() }
        }
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    /**
     * Do not treat boundsInRoot as ScrollView content coordinates: the nested ComposeView
     * shares a parent composition and can expose a different root. Scroll the real View
     * and compare window coordinates with its actual screen viewport after every layout.
     */
    private fun scrollToNative(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        compose.runOnIdle { nativeScroll().scrollTo(0, 0) }
        repeat(60) {
            awaitNativeLayout()
            val target = screenTarget(node)
            if (target.fullyVisible) {
                try { return node.assertIsDisplayed() }
                catch (failure: AssertionError) { diagnoseTarget(node, target); throw failure }
            }
            var advanced = false
            compose.runOnIdle {
                val scroll = nativeScroll()
                val before = scroll.scrollY
                scroll.scrollBy(0, (scroll.height / 3).coerceAtLeast(1))
                advanced = scroll.scrollY > before
            }
            if (!advanced) {
                diagnoseTarget(node, target)
                error("Cannot reveal node in native ScrollView: $target")
            }
        }
        error("Native scrolling exceeded the fixture's expected content length: ${screenTarget(node)}")
    }

    /** Inject at the window, allowing ScrollView, AndroidView and Compose to dispatch the actual touch. */
    private fun tapThroughWindow(node: SemanticsNodeInteraction) {
        awaitNativeLayout()
        val target = screenTarget(node)
        check(target.fullyVisible) { "Refusing to touch a clipped or stale target: $target" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downAt = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, target.bounds.centerX(), target.bounds.centerY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(60)
        send(MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    private fun awaitNativeLayout() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        compose.waitUntil(5_000) {
            var settled = false
            instrumentation.runOnMainSync {
                val scroll = nativeScroll()
                settled = !scroll.isLayoutRequested && !scroll.getChildAt(0).isLayoutRequested
            }
            settled
        }
    }

    private data class ScreenTarget(val bounds: RectF, val viewport: Rect, val scrollY: Int) {
        val fullyVisible: Boolean get() = bounds.width() > 0f && bounds.height() > 0f &&
            bounds.left >= viewport.left && bounds.top >= viewport.top &&
            bounds.right <= viewport.right && bounds.bottom <= viewport.bottom
    }

    private fun screenTarget(node: SemanticsNodeInteraction): ScreenTarget {
        val semantics = node.fetchSemanticsNode()
        var result: ScreenTarget? = null
        compose.runOnIdle {
            // LayoutCoordinates and AndroidComposeView's cached transform matrices are
            // UI-thread state. Read them with the native View positions in one sample.
            val bounds = semantics.boundsInWindow
            val scroll = nativeScroll()
            val decor = scroll.rootView
            val inWindow = IntArray(2)
            val onScreen = IntArray(2)
            decor.getLocationInWindow(inWindow)
            decor.getLocationOnScreen(onScreen)
            val offsetX = onScreen[0] - inWindow[0]
            val offsetY = onScreen[1] - inWindow[1]
            val viewport = Rect()
            check(scroll.getLocalVisibleRect(viewport)) { "The native settings viewport is not visible" }
            val scrollOnScreen = IntArray(2)
            scroll.getLocationOnScreen(scrollOnScreen)
            // View local-visible coordinates include this View's own scroll offset.
            // Subtract it before translating to screen pixels; otherwise the viewport
            // incorrectly moves down with the content and accepts offscreen targets.
            viewport.offset(scrollOnScreen[0] - scroll.scrollX, scrollOnScreen[1] - scroll.scrollY)
            result = ScreenTarget(RectF(bounds.left + offsetX, bounds.top + offsetY, bounds.right + offsetX, bounds.bottom + offsetY), viewport, scroll.scrollY)
        }
        return requireNotNull(result)
    }

    private fun diagnoseTarget(node: SemanticsNodeInteraction, target: ScreenTarget) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val semantics = node.fetchSemanticsNode()
        val details = StringBuilder("target=$target\n${node.printToString()}\n")
        compose.runOnIdle {
            details.append("root=${semantics.boundsInRoot}\nwindow=${semantics.boundsInWindow}\nsize=${semantics.size}\n")
            views().filter { it is ScrollView || it.javaClass.name.contains("Compose") }.forEach { view ->
                val screen = IntArray(2); val window = IntArray(2); val global = Rect(); val local = Rect()
                view.getLocationOnScreen(screen); view.getLocationInWindow(window)
                view.getGlobalVisibleRect(global); view.getLocalVisibleRect(local)
                details.append("${view.javaClass.name}: ${view.width}x${view.height}, scroll=${view.scrollX},${view.scrollY}, screen=${screen.toList()}, window=${window.toList()}, global=$global, local=$local, shown=${view.isShown}, layout=${view.isLayoutRequested}\n")
            }
        }
        File(output, "update-integration-diagnostic.txt").writeText(details.toString())
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "update-integration-diagnostic.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        println(details)
    }

    private fun nativeScroll(): ScrollView = views().filterIsInstance<ScrollView>().single()

    private fun views(): List<View> = buildList {
        fun visit(view: View) { add(view); if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i)) }
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<ComponentActivity>().forEach { visit(it.window.decorView) }
    }

    private fun screenshotFile() = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "update-settings-integration.png")

    private fun offlineWorkbench(transport: NoNetworkTransport): WorkbenchRepository {
        val repository = WorkbenchRepository(transport)
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                repository.foreground(false) // Prevent automatic retries of the deliberately rejected fixture probe.
                runCatching { repository.setEndpoint("https://offline.invalid") }
                assertEquals("offline", repository.state.value.status)
            }
        }
        return repository
    }

    private class FixtureSource(private val releases: List<AppRelease>) : AppUpdateSource {
        val calls = AtomicInteger()
        override suspend fun releases(): List<AppRelease> { calls.incrementAndGet(); return releases }
    }

    private class MemoryStore : AppUpdateStore {
        @Volatile var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    /** No disk, credentials, cookies, sockets or actual HTTP are accessible to these settings fixtures. */
    private class NoNetworkTransport : WorkbenchTransport {
        var probes = 0
        var forbiddenCalls = 0
        private fun forbidden(): Nothing { forbiddenCalls++; error("Integration fixture must not access credentials, persistence or networking") }
        override suspend fun getEndpoint(): String = forbidden()
        override suspend fun setEndpoint(endpoint: String) { check(endpoint == "https://offline.invalid/") }
        override suspend fun readPreferences(endpoint: String): Preferences = forbidden()
        override suspend fun writePreferences(endpoint: String, preferences: Preferences): Unit = forbidden()
        override suspend fun credential(endpoint: String): String = forbidden()
        override suspend fun saveCredential(endpoint: String, credential: String): Unit = forbidden()
        override suspend fun deviceId(): String = forbidden()
        override suspend fun fingerprint(endpoint: String): String = forbidden()
        override suspend fun setFingerprint(endpoint: String, fingerprint: String): Unit = forbidden()
        override suspend fun clearOrigin(endpoint: String): Unit = forbidden()
        override suspend fun clearBrowserSession(endpoint: String): Unit = forbidden()
        override suspend fun http(endpoint: String, route: String, body: JSONObject?): JSONObject {
            check(endpoint == "https://offline.invalid/" && route == "session" && body == null)
            probes++
            throw IOException("Offline workbench fixture")
        }
        override suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection = forbidden()
        override fun close() {}
    }
}
