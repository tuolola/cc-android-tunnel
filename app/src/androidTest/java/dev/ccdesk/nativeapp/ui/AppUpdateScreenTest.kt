package dev.ccdesk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ccdesk.nativeapp.core.AppRelease
import dev.ccdesk.nativeapp.core.AppUpdateState
import dev.ccdesk.nativeapp.core.AppUpdateStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pure UI fixtures: no repository, network request, browser or installer is created. */
@RunWith(AndroidJUnit4::class)
class AppUpdateScreenTest {
    @get:Rule val compose = createComposeRule()
    private val notes = "Fixture release notes.\nKeep the current draft.\nImprove update feedback."
    private val release = AppRelease(
        version = "9.2.0",
        tagName = "v9.2.0",
        name = "Fixture release 9.2.0",
        releaseUrl = "https://github.com/tuolola/cc-android-tunnel/releases/tag/v9.2.0",
        apkUrl = "https://github.com/tuolola/cc-android-tunnel/releases/download/v9.2.0/CCtunnel.apk",
        body = notes,
        prerelease = false,
        sha256 = "0123456789abcdef".repeat(4),
    )

    private fun show(
        state: State<AppUpdateState>,
        onCheck: () -> Unit = {},
        onAutomaticChange: (Boolean) -> Unit = {},
        onPrereleasesChange: (Boolean) -> Unit = {},
        onOpenLink: (String) -> Unit = { error("Unexpected external-link callback") },
    ) {
        compose.setContent {
            DeskTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AppUpdateContent(state.value, onCheck, onAutomaticChange, onPrereleasesChange, onOpenLink)
                }
            }
        }
    }

    @Test fun checkingDisablesTheCheckActionAndIgnoresRealTaps() {
        val state = mutableStateOf(AppUpdateState(currentVersion = "1.0.1-native", checking = true, status = AppUpdateStatus.CHECKING))
        var checks = 0
        show(state, onCheck = { checks++ })
        compose.onNodeWithTag("app-update-status").assertTextEquals("正在检查更新…")
        compose.onNodeWithTag("app-update-check").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals("Checking must not dispatch another request", 0, checks) }
        compose.onAllNodesWithText("当前版本无需更新").assertCountEquals(0)
    }

    @Test fun availableReleaseExpandsSelectableNotesAndDispatchesExactLinks() {
        val state = mutableStateOf(AppUpdateState(
            currentVersion = "1.0.1-native", lastCheckedAt = 1_800_000_000_000L,
            latest = release, updateAvailable = true, status = AppUpdateStatus.UPDATE_AVAILABLE,
        ))
        val opened = mutableListOf<String>()
        show(state, onOpenLink = { opened += it })
        compose.onNodeWithTag("app-update-status").assertTextEquals("发现新版本 9.2.0")
        compose.onNodeWithText("CCtunnel 1.0.1-native").assertIsDisplayed()
        compose.onNodeWithText("Fixture release 9.2.0").assertIsDisplayed()
        compose.runOnIdle { assertFalse("Release notes start collapsed", nativeTextVisible(notes)) }
        compose.onNodeWithText("查看更新说明").performScrollTo().performClick()
        compose.onNodeWithText("收起更新说明").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Expanded native notes must actually be visible", nativeTextVisible(notes))
            assertTrue("Release notes must remain selectable", nativeTextViews().any { it.text.toString() == notes && it.isTextSelectable })
        }
        saveFixtureScreenshot("update-available.png")
        compose.onNodeWithTag("app-update-download").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("app-update-release").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(release.apkUrl, release.releaseUrl), opened) }
        compose.onNodeWithText("收起更新说明").performScrollTo().performClick()
        compose.runOnIdle { assertFalse("Collapsing must hide the native notes", nativeTextVisible(notes)) }
    }

    @Test fun failedCheckOffersRetryWithoutReportingThatTheCurrentVersionIsLatest() {
        // A previous successful same-version response must not hide a subsequent error.
        val state = mutableStateOf(AppUpdateState(
            currentVersion = release.version, lastCheckedAt = 1_800_000_000_000L,
            latest = release, error = "Fixture service unavailable", status = AppUpdateStatus.ERROR,
        ))
        var checks = 0
        show(state, onCheck = {
            checks++
            state.value = state.value.copy(checking = true, error = null, status = AppUpdateStatus.CHECKING)
        })
        compose.onNodeWithTag("app-update-status").assertTextEquals("检查更新失败")
        compose.onNodeWithText("Fixture service unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("当前版本无需更新").assertCountEquals(0)
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()
        compose.onNodeWithText("重试检查").assertIsEnabled()
        saveFixtureScreenshot("update-error.png")
        compose.onNodeWithTag("app-update-check").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks) }
        compose.onNodeWithTag("app-update-status").assertTextEquals("正在检查更新…")
        compose.onNodeWithTag("app-update-check").assertIsNotEnabled()
        compose.onAllNodesWithText("当前版本无需更新").assertCountEquals(0)
    }

    @Test fun updateSwitchesSendTheirNewValuesAndReflectControlledStateIndependently() {
        val state = mutableStateOf(AppUpdateState(currentVersion = "1.0.1-native", automatic = true, includePrereleases = false))
        val automatic = mutableListOf<Boolean>()
        val prereleases = mutableListOf<Boolean>()
        show(state,
            onAutomaticChange = { automatic += it; state.value = state.value.copy(automatic = it) },
            onPrereleasesChange = { prereleases += it; state.value = state.value.copy(includePrereleases = it) },
        )
        val automaticSwitch = compose.onNodeWithTag("app-update-automatic")
        val prereleaseSwitch = compose.onNodeWithTag("app-update-prereleases")
        automaticSwitch.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn()
        prereleaseSwitch.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
        automaticSwitch.performScrollTo().performClick().assertIsOff()
        prereleaseSwitch.assertIsOff().performScrollTo().performClick().assertIsOn()
        automaticSwitch.assertIsOff()
        prereleaseSwitch.performClick().assertIsOff()
        automaticSwitch.performScrollTo().performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf(false, true), automatic)
            assertEquals(listOf(true, false), prereleases)
            assertTrue(state.value.automatic)
            assertFalse(state.value.includePrereleases)
        }
    }

    /** These images document synthetic UI states, never a live release-check result. */
    private fun saveFixtureScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), name)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun nativeTextViews(): List<TextView> = buildList {
        fun visit(view: View) {
            if (view is TextView) add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { visit(it.window.decorView) }
    }

    private fun nativeTextVisible(text: String): Boolean = nativeTextViews().any {
        it.text.toString() == text && it.isShown && it.width > 0 && it.height > 0 &&
            it.getGlobalVisibleRect(Rect())
    }
}
