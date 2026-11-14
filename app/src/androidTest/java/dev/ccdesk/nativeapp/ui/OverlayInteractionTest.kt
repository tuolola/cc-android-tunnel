package dev.ccdesk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Frame-level and input-routing checks for the shared sheet overlay used by every bottom panel. */
@RunWith(AndroidJUnit4::class)
class OverlayInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val blue = Color(0xff2060ff)

    private data class Frame(val corners: List<Float>, val panelTop: Int?)

    /** Darkness (0 = white) at the four window edges, plus the first panel row in the center column. */
    private fun sample(image: ImageBitmap): Frame {
        val pixels = image.toPixelMap()
        val w = pixels.width; val h = pixels.height
        val points = listOf(3 to 3, w - 4 to 3, 3 to h / 3, w - 4 to h / 3)
        val corners = points.map { (x, y) -> 1f - pixels[x, y].red }
        val top = (0 until h).firstOrNull { y -> val p = pixels[w / 2, y]; p.blue > .9f && p.red < .3f }
        return Frame(corners, top)
    }

    private fun setOverlay(visible: androidx.compose.runtime.MutableState<Boolean>, dismissals: MutableList<Unit>, dismissible: Boolean = true) {
        compose.setContent {
            DeskTheme {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("window")) {
                    DeskSheetOverlay(visible.value, { dismissals += Unit; visible.value = false }, dismissible = dismissible) { sheet ->
                        Box(Modifier.fillMaxWidth().height(56.dp).then(sheet.dragHandle).testTag("handle").background(blue))
                        Box(Modifier.fillMaxWidth().height(180.dp).testTag("panel-body").background(blue))
                    }
                }
            }
        }
    }

    @Test fun scrimStaysWindowFixedAndOnlyFadesWhileThePanelSlides() {
        val visible = mutableStateOf(false); val dismissals = mutableListOf<Unit>()
        compose.mainClock.autoAdvance = false
        setOverlay(visible, dismissals)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { visible.value = true }
        val opening = (0 until 45).map { compose.mainClock.advanceTimeByFrame(); sample(compose.onRoot().captureToImage()) }
        compose.runOnIdle { visible.value = false }
        val closing = (0 until 45).map { compose.mainClock.advanceTimeByFrame(); sample(compose.onRoot().captureToImage()) }
        val height = compose.onRoot().fetchSemanticsNode().size.height
        for ((phase, frames) in listOf("open" to opening, "close" to closing)) {
            frames.forEachIndexed { index, frame ->
                val spread = frame.corners.max() - frame.corners.min()
                assertTrue("$phase frame $index: all four window edges must share one scrim opacity (${frame.corners})", spread < .02f)
                if (frame.panelTop != null) assertTrue("$phase frame $index: a visible panel must never expose an unscrimmed window edge", frame.corners.min() > .01f)
            }
            val darkness = frames.map { it.corners.average().toFloat() }
            darkness.zipWithNext().forEachIndexed { i, (a, b) ->
                if (phase == "open") assertTrue("scrim must only fade in while opening (frame $i: $a -> $b)", b >= a - .004f)
                else assertTrue("scrim must only fade out while closing (frame $i: $a -> $b)", b <= a + .004f)
            }
        }
        val partial = opening.mapNotNull { it.panelTop }.filter { it in (height / 2 + 1) until height - 1 }
        assertTrue("Opening must show intermediate panel positions, not only start and end", partial.size >= 3)
        assertEquals("Fully open scrim is 38% black", .38f, opening.last().corners.average().toFloat(), .02f)
        assertTrue("Closing ends with no scrim", closing.last().corners.max() < .01f)
        assertNull("Closing ends with the panel off screen", closing.last().panelTop)
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithTag(OverlayScrimTag).assertCountEquals(0)
        compose.onAllNodesWithTag(OverlayPanelTag).assertCountEquals(0)
    }

    @Test fun panelTapsStayInsideScrimTapDismissesOnceAndClosingLayerPassesInputThrough() {
        val visible = mutableStateOf(true); val dismissals = mutableListOf<Unit>(); val background = mutableIntStateOf(0); val inside = mutableIntStateOf(0)
        compose.setContent {
            DeskTheme {
                Box(Modifier.fillMaxSize().clickable { background.intValue++ }.testTag("window")) {
                    DeskSheetOverlay(visible.value, { dismissals += Unit; visible.value = false }) { _ ->
                        Box(Modifier.fillMaxWidth().height(120.dp).testTag("panel-body"))
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("panel-button").clickable { inside.intValue++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("panel-body", useUnmergedTree = true).performTouchInput { click(center) }
        compose.onNodeWithTag("panel-button", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(0, dismissals.size); assertEquals(1, inside.intValue); assertEquals("Panel taps must not reach the background", 0, background.intValue) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("window").performTouchInput { click(Offset(centerX, 40f)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, dismissals.size)
        assertEquals("The first scrim tap is consumed by the overlay", 0, background.intValue)
        // Mid-exit: the fading scrim no longer intercepts, and a second tap cannot dismiss twice.
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("window").performTouchInput { click(Offset(centerX, 40f)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, dismissals.size)
        assertEquals("A closing scrim must let input through", 1, background.intValue)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onAllNodesWithTag(OverlayScrimTag).assertCountEquals(0)
    }

    @Test fun systemBackClosesOnlyTheOpenOverlayAndRepeatedBackReachesTheScreenBelow() {
        val visible = mutableStateOf(true); val dismissals = mutableListOf<Unit>(); val outer = mutableIntStateOf(0)
        compose.setContent {
            DeskTheme {
                BackHandler { outer.intValue++ }
                DeskSheetOverlay(visible.value, { dismissals += Unit; visible.value = false }) { _ -> Box(Modifier.fillMaxWidth().height(160.dp)) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Back closes the sheet once", 1, dismissals.size)
        assertEquals("A second back during the exit animation belongs to the screen below", 1, outer.intValue)
        compose.mainClock.autoAdvance = true
    }

    @Test fun dragFollowsTheFingerReturnsWhenShortAndClosesPastThreshold() {
        val visible = mutableStateOf(true); val dismissals = mutableListOf<Unit>()
        setOverlay(visible, dismissals)
        compose.waitForIdle()
        val rest = compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("handle").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 6f * density), delayMillis = 32) }
        }
        val dragged = compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top - rest
        assertTrue("Panel must follow the finger (moved ${dragged / density}dp)", dragged / density in 40f..61f)
        compose.onNodeWithTag("handle").performTouchInput { repeat(4) { moveBy(Offset(0f, 0f), delayMillis = 40) }; up() }
        compose.waitForIdle()
        assertEquals("Short slow drag does not dismiss", 0, dismissals.size)
        assertEquals("Panel springs back to rest", rest, compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top, 1.5f)
        compose.onNodeWithTag("handle").performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, 9f * density), delayMillis = 32) }
            repeat(4) { moveBy(Offset(0f, 0f), delayMillis = 40) }
            up()
        }
        compose.waitForIdle()
        assertEquals("Dragging past the threshold dismisses once", 1, dismissals.size)
        compose.onAllNodesWithTag(OverlayPanelTag).assertCountEquals(0)
    }

    @Test fun declinedDismissalSpringsBackAndKeepsTheScrim() {
        val visible = mutableStateOf(true); val dismissals = mutableListOf<Unit>()
        setOverlay(visible, dismissals, dismissible = false)
        compose.waitForIdle()
        val rest = compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("handle").performTouchInput { down(center); repeat(20) { moveBy(Offset(0f, 9f * density), delayMillis = 16) }; up() }
        compose.waitForIdle()
        assertEquals(0, dismissals.size)
        assertEquals(rest, compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top, 1.5f)
        compose.onNodeWithTag("window").performTouchInput { click(Offset(centerX, 40f)) }
        compose.runOnIdle { assertEquals("A locked sheet ignores scrim taps", 0, dismissals.size); assertTrue(visible.value) }
    }

    @Test fun grabbingThePanelMidOpeningContinuesFromItsCurrentPosition() {
        val visible = mutableStateOf(false); val dismissals = mutableListOf<Unit>()
        compose.mainClock.autoAdvance = false
        setOverlay(visible, dismissals)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { visible.value = true }
        repeat(6) { compose.mainClock.advanceTimeByFrame() }
        val midway = compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top
        val density = compose.activity.resources.displayMetrics.density
        // Zero-delay events: the test clock must not advance the opening spring before the grab.
        compose.onNodeWithTag("handle").performTouchInput { down(center); moveBy(Offset(0f, 12f * density), delayMillis = 0) }
        compose.mainClock.advanceTimeByFrame()
        val grabbed = compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Grabbing must not jump to rest or to hidden (midway=$midway grabbed=$grabbed)", abs(grabbed - midway) < 2f * density)
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        val held = compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top
        assertEquals("The interrupted opening animation must stop under a still finger", grabbed, held, 1f)
        // Reverse mid-gesture: drag back up past the resting position into the rubber band,
        // hold still and lift. The panel must settle open without dismissing.
        val panel = compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot
        val rest = compose.onRoot().fetchSemanticsNode().size.height - panel.height
        compose.onNodeWithTag("handle").performTouchInput {
            moveBy(Offset(0f, -(held - rest) - 24f * density), delayMillis = 120)
            repeat(4) { moveBy(Offset.Zero, delayMillis = 40) }
            up()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Reversing and releasing slowly keeps the sheet open", 0, dismissals.size)
        assertEquals("Panel settles exactly at rest after the rubber band", rest, compose.onNodeWithTag(OverlayPanelTag).fetchSemanticsNode().boundsInRoot.top, 1.5f)
    }
}
