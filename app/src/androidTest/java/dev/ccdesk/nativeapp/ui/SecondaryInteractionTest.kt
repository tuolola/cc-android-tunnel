package dev.ccdesk.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.autofill.AutofillTree
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ccdesk.nativeapp.core.Catalog
import dev.ccdesk.nativeapp.core.ModelInfo
import dev.ccdesk.nativeapp.core.WorkbenchRepository
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises production form input and modal actions without network or stored credentials. */
@RunWith(AndroidJUnit4::class)
class SecondaryInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun totpPasteFiltersNonAsciiDigitsAndSubmitsTheSixDigitValue() {
        val submissions = CopyOnWriteArrayList<String>()
        compose.setContent {
            DeskTheme {
                var code by remember { mutableStateOf("") }
                SField("Authenticator code", code, { code = it }, numeric = true, maxLength = 6,
                    imeAction = ImeAction.Go, onSubmit = { submissions += code })
            }
        }
        val field = compose.onNodeWithContentDescription("Authenticator code")
        field.performTextInput("1a2٣3４456 7")
        field.assertTextEquals("123456")
        field.performImeAction()
        compose.runOnIdle { assertEquals(listOf("123456"), submissions.toList()) }
    }

    @Test fun customModelUsesDoneToSubmitTrimmedIdentifier() {
        val submitted = CopyOnWriteArrayList<Pair<String?, String?>>()
        val catalog = Catalog(listOf(ModelInfo("model-a", "Model A", emptyList())), "2026-11-14T00:00:00Z")
        compose.setContent {
            DeskTheme {
                ModelSheet("model-a", null, catalog, null, "", false, {},
                    onSave = { model, effort -> submitted += model to effort }, onLoad = null, createsSession = false)
            }
        }
        compose.onNodeWithText("更多模型").performClick()
        compose.onNodeWithText("自定义模型标识").performClick()
        val field = compose.onNodeWithContentDescription("模型标识")
        field.performTextReplacement("  private-model-2026  ")
        field.performImeAction()
        compose.waitUntil { submitted.isNotEmpty() }
        assertEquals("private-model-2026", submitted.single().first)
        assertNull(submitted.single().second)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun nativePasswordAutofillHonorsDisabledStateAndRemovesItsNode() {
        val tree = AutofillTree()
        val visible = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        val password = mutableStateOf("")
        compose.setContent {
            CompositionLocalProvider(LocalAutofillTree provides tree) {
                DeskTheme {
                    if (visible.value) SField("Workspace password", password.value, { password.value = it }, secure = true, enabled = enabled.value)
                }
            }
        }
        compose.runOnIdle {
            val node = tree.children.values.single()
            assertEquals(listOf(AutofillType.Password), node.autofillTypes)
            node.onFill?.invoke("native-autofill-value")
            assertEquals("native-autofill-value", password.value)
            enabled.value = false
        }
        compose.runOnIdle {
            tree.children.values.single().onFill?.invoke("must-be-ignored")
            assertEquals("native-autofill-value", password.value)
            visible.value = false
        }
        compose.runOnIdle { assertTrue(tree.children.isEmpty()) }
    }

    @Test fun pendingModelSaveLocksSelectionAndDismissUntilCompletion() {
        val pending = CompletableDeferred<Unit>()
        val submitted = CopyOnWriteArrayList<String?>()
        val dismissals = CopyOnWriteArrayList<Unit>()
        val catalog = Catalog(listOf(ModelInfo("model-a", "Model A", emptyList()), ModelInfo("model-b", "Model B", emptyList())), "2026-11-14T00:00:00Z")
        compose.setContent {
            DeskTheme {
                ModelSheet("model-a", null, catalog, null, "", false, { dismissals += Unit },
                    onSave = { model, _ -> submitted += model; pending.await() }, onLoad = null, createsSession = false)
            }
        }
        compose.onNodeWithText("Model B").performClick()
        compose.waitUntil { submitted.isNotEmpty() }
        compose.onNodeWithText("Model A").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭模型面板").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("model-b"), submitted.toList()); assertTrue(dismissals.isEmpty()); pending.complete(Unit) }
        compose.waitUntil { dismissals.size == 1 }
    }

    @Test fun openingModelSheetDoesNotFetchAndFailedManualReadCanBeRetried() {
        val reads = CopyOnWriteArrayList<Unit>()
        val visible = mutableStateOf(true)
        val catalog = Catalog(listOf(ModelInfo("model-a", "Model A", emptyList())), "2000-01-01T00:00:00Z")
        compose.setContent {
            DeskTheme {
                if (visible.value) ModelSheet("model-a", null, catalog, null, "", false, { visible.value = false },
                    onSave = { _, _ -> }, onLoad = { reads += Unit; error("Catalog temporarily unavailable") }, createsSession = false)
            }
        }
        compose.waitForIdle()
        assertTrue(reads.isEmpty())
        compose.onNodeWithText("刷新服务端模型").performClick()
        compose.waitUntil { reads.size == 1 }
        compose.onNodeWithContentDescription("关闭模型面板").performClick()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, reads.size) }
        compose.onNodeWithText("刷新服务端模型").performClick()
        compose.waitUntil { reads.size == 2 }
    }

    @Test fun modelSheetNeverShowsFallbackAliasesAndDisplaysOnlyReceivedModels() {
        val catalog = mutableStateOf<Catalog?>(null)
        val loading = mutableStateOf(true)
        compose.setContent { DeskTheme {
            ModelSheet(null, null, catalog.value, null, "", false, {}, onSave = { _, _ -> },
                onLoad = {}, createsSession = false, catalogLoading = loading.value)
        } }
        for (alias in listOf("Opus", "Sonnet", "Haiku", "通用模型别名")) compose.onNodeWithText(alias).assertDoesNotExist()
        compose.onNodeWithText("正在读取模型…").assertIsNotEnabled()
        compose.runOnIdle {
            catalog.value = Catalog(listOf(ModelInfo("real-provider-model", "Real provider model", listOf("high"))), "2026-11-15T00:00:00Z")
            loading.value = false
        }
        compose.onNodeWithText("Real provider model").assertIsDisplayed()
        compose.onNodeWithText("刷新服务端模型").assertIsEnabled()
        for (alias in listOf("Opus", "Sonnet", "Haiku")) compose.onNodeWithText(alias).assertDoesNotExist()
    }

    @Test fun entryFadeUsesSharedDecelerationAndRemovesWithoutAnExitFade() {
        val visible = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(40.dp).background(Color.White).testTag("fade-background")) {
                SecondaryFadeContent(visible.value) {
                    Box(Modifier.size(40.dp).background(Color.Black).testTag("fade-entry"))
                }
            }
        }
        compose.runOnIdle { visible.value = true }
        // One frame applies the new composition; the next establishes animation time zero.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val sampleTime = 80L
        compose.mainClock.advanceTimeBy(sampleTime)
        val midpoint = compose.onNodeWithTag("fade-background").captureToImage().toPixelMap()
        val red = midpoint[midpoint.width / 2, midpoint.height / 2].red
        val expectedRed = 1f - DeskMotion.Decelerate.transform(sampleTime.toFloat() / DeskMotion.FadeIn)
        assertEquals("Entry opacity must follow the shared decelerating fade at ${sampleTime}ms", expectedRed, red, .03f)
        compose.mainClock.advanceTimeBy(DeskMotion.FadeIn - sampleTime + 32L)
        val finished = compose.onNodeWithTag("fade-background").captureToImage().toPixelMap()
        assertTrue(finished[finished.width / 2, finished.height / 2].red < .02f)
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("fade-entry").assertDoesNotExist()
    }

    @Test fun fullPageSettingsConsumesEdgeTouchesAndReturnsThroughItsHeader() {
        val dismissals = CopyOnWriteArrayList<Unit>()
        compose.setContent {
            DeskTheme {
                Box(Modifier.size(360.dp, 640.dp).testTag("settings-root")) {
                    SettingsPanel("settings", "Settings", {}, { dismissals += Unit }) { SNativeText("Panel contents") }
                }
            }
        }
        val root = compose.onNodeWithTag("settings-root")
        root.performTouchInput { click(center) }
        compose.runOnIdle { assertTrue(dismissals.isEmpty()) }
        root.performTouchInput { click(Offset(4f, centerY)) }
        compose.runOnIdle { assertTrue("The full-page surface must not expose a dismissible dim margin", dismissals.isEmpty()) }
        compose.onNodeWithContentDescription("返回").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, dismissals.size) }
    }

    @Test fun settingsSearchFindsConnectionByCredentialKeywordAndNavigatesToTheRealPage() {
        val destinations = CopyOnWriteArrayList<String>()
        compose.setContent {
            DeskTheme { SettingsPanel("settings", "设置", { destinations += it }, {}) { SNativeText("Settings overview") } }
        }
        val searchField = compose.onNodeWithContentDescription("搜索设置")
        searchField.performTextInput("凭据")
        searchField.assertTextEquals("凭据")
        try {
            // The native ScrollView owns a nested ComposeView, whose result rows can
            // attach after the outer text field has already reached Compose idleness.
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("打开设置：连接设置").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            roots.fetchSemanticsNodes().indices.forEach { index -> println("Settings search root $index:\n${roots[index].printToString(maxDepth = 20)}") }
            throw failure
        }
        compose.onNodeWithContentDescription("打开设置：连接设置").assertExists().performClick()
        compose.runOnIdle { assertEquals(listOf("connection"), destinations.toList()) }
    }

    @Test fun avatarSettingsRetainsConnectionWithoutTheRemovedChatAndProjectEntries() {
        val repository = WorkbenchRepository(FixtureTransport())
        val destinations = CopyOnWriteArrayList<String>()
        try {
            compose.setContent { DeskTheme { SecondaryScreen("settings", repository, { destinations += it }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("打开连接设置").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("打开聊天列表").assertDoesNotExist()
            compose.onNodeWithContentDescription("打开项目会话").assertDoesNotExist()
            compose.onNodeWithContentDescription("打开连接设置").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(listOf("connection"), destinations.toList()) }
        } finally { compose.runOnIdle { repository.dispose() } }
    }

    @Test fun modelCloseUses48DpTouchTargetWithoutChanging32DpLayout() {
        assertModelHeaderHitSlop(extent = 32, hitSlop = 5)
    }

    @Test fun modelBackUses48DpTouchTargetWithoutChanging40DpLayout() {
        assertModelHeaderHitSlop(extent = 40, hitSlop = 3)
    }

    @Test fun modelEntryTracksItsDynamicWidthWithAComposerSizedTouchTargetAndExpandedState() {
        val model = mutableStateOf("A")
        val expanded = mutableStateOf(false)
        val clicks = CopyOnWriteArrayList<Unit>()
        compose.setContent {
            DeskTheme {
                Box(Modifier.size(320.dp, 120.dp).testTag("model-entry-host"), contentAlignment = Alignment.Center) {
                    ModelPickerEntry(model.value, "Default", expanded.value, {
                        clicks += Unit
                        expanded.value = !expanded.value
                    })
                }
            }
        }
        val host = compose.onNodeWithTag("model-entry-host")
        var previousWidth = 0f
        var expected = 0
        for (label in listOf("A", "Long model fixture")) {
            compose.runOnIdle { model.value = label }
            val entry = compose.onNodeWithContentDescription("选择模型，$label，思考强度Default")
            entry.assertHeightIsEqualTo(32.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
            val bounds = entry.fetchSemanticsNode().boundsInRoot
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            assertTrue("The second fixture must actually resize the production entry", bounds.width > previousWidth)
            previousWidth = bounds.width
            val center = bounds.center - hostBounds.topLeft
            val scale = hostBounds.width / 320f
            for ((horizontal, direction) in listOf(true to 1f, true to -1f, false to 1f, false to -1f)) {
                for (inside in listOf(true, false)) {
                    // Horizontally the pill accepts 3dp beyond its edge; vertically it accepts a 44dp target.
                    val reach = if (horizontal) bounds.width / 2f + 3f * scale else 22f * scale
                    val distance = direction * (reach + (if (inside) -.5f else .5f) * scale)
                    host.performTouchInput {
                        click(center + if (horizontal) Offset(distance, 0f) else Offset(0f, distance))
                    }
                    if (inside) expected++
                    compose.runOnIdle { assertEquals("label=$label, horizontal=$horizontal, direction=$direction, inside=$inside", expected, clicks.size) }
                    entry.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (expected % 2 == 1) "已展开" else "已收起"))
                    assertEquals("Touch expansion must not change the visible layout", bounds.size, entry.fetchSemanticsNode().boundsInRoot.size)
                }
            }
        }
    }

    private fun assertModelHeaderHitSlop(extent: Int, hitSlop: Int) {
        val clicks = CopyOnWriteArrayList<Unit>()
        compose.setContent {
            DeskTheme {
                Box(Modifier.size(100.dp).testTag("header-hit-host"), contentAlignment = Alignment.Center) {
                    ModelHeaderButton("x", "Header action", extent, 16, true, hitSlop = hitSlop) { clicks += Unit }
                }
            }
        }
        compose.onNodeWithContentDescription("Header action")
            .assertWidthIsEqualTo(extent.dp).assertHeightIsEqualTo(extent.dp)
        val host = compose.onNodeWithTag("header-hit-host")
        var expected = 0
        // Both visual sizes expose the same 48dp target. Check each cardinal edge,
        // half a dp inside/outside, so the larger accessible target cannot grow unbounded.
        for ((horizontal, direction) in listOf(true to 1f, true to -1f, false to 1f, false to -1f)) {
            for (inside in listOf(true, false)) {
                val distance = 24f + if (inside) -.5f else .5f
                host.performTouchInput {
                    val delta = direction * distance * (width / 100f)
                    click(if (horizontal) Offset(centerX + delta, centerY) else Offset(centerX, centerY + delta))
                }
                if (inside) expected++
                compose.runOnIdle { assertEquals("extent=$extent, hitSlop=$hitSlop, horizontal=$horizontal, direction=$direction, inside=$inside", expected, clicks.size) }
                compose.onNodeWithContentDescription("Header action")
                    .assertWidthIsEqualTo(extent.dp).assertHeightIsEqualTo(extent.dp)
            }
        }
    }
}
