package dev.ccdesk.nativeapp.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pixel regressions for the *intermediate* opacity layers that can crop elevation to a
 * rectangle in Compose 1.7.6. A full-window capture is essential: capturing the surface
 * node itself would discard the very exterior shadow pixels under test.
 *
 * These are synthetic fixtures. Only the card fill is blue so its scaled outline can be
 * located independently of its gray shadow. Production shape, elevation, animation,
 * compositing, and (for menu/button cases) actual components remain in use.
 */
@RunWith(AndroidJUnit4::class)
class ShadowRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val marker = Color(0xff2060ff)
    private var density = 1f

    private data class Outline(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private data class Frame(
        val name: String,
        val opacity: Float,
        val outline: Outline,
        val exteriorHalo: Float,
        val cornerInside: Float,
        val cornerOutside: Float,
        val fillSpread: Float,
    )

    private fun fixture(content: @Composable BoxScope.() -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DeskTheme {
                val colors = LocalDeskColors.current.copy(card = marker, dark = false)
                val currentDensity = LocalDensity.current.density
                SideEffect { density = currentDensity }
                CompositionLocalProvider(LocalDeskColors provides colors, LocalReducedMotion provides false) {
                    Box(Modifier.fillMaxSize().background(Color.White).testTag("shadow-window"),
                        contentAlignment = Alignment.Center, content = content)
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun sharedFloatingSurfaceKeepsItsHaloThroughoutFadeAndScale() {
        val visible = mutableStateOf(false)
        fixture {
            val transition = remember { MutableTransitionState(false) }
            transition.targetState = visible.value
            DeskFloatingVisibility(transition, initialScale = .8f, targetScale = .94f) {
                Box(Modifier.size(160.dp, 96.dp).deskFloatingSurface(RoundedCornerShape(24.dp), 28.dp))
            }
        }
        exerciseVisibility("shared", visible, widthDp = 160f, radiusDp = 24f, uniformFill = true)
    }

    @Test fun anchoredMenuKeepsItsHaloThroughoutOpeningAndClosing() {
        val visible = mutableStateOf(false)
        fixture {
            OverlayHost {
                DeskAnchoredOverlay(visible.value, { visible.value = false },
                    alignment = Alignment.TopEnd, width = 216.dp, corner = 24.dp,
                    margin = PaddingValues(horizontal = 48.dp, vertical = 96.dp)) {
                    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterStart) { DeskText("在聊天中查找") }
                    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterStart) { DeskText("刷新会话") }
                }
            }
        }
        exerciseVisibility("anchored-menu", visible, widthDp = 216f, radiusDp = 24f)
    }

    @Test fun realJumpButtonKeepsItsHaloIncludingAnInterruptedExitAndRemainsClickable() {
        val visible = mutableStateOf(false)
        var clicks = 0
        fixture { JumpToLatestButton(visible.value) { clicks++ } }
        exerciseVisibility("jump-to-latest", visible, widthDp = 40f, radiusDp = 20f,
            reverseDuringExit = true) {
            compose.onNodeWithContentDescription("回到最新消息").performClick()
            compose.runOnIdle { assertEquals("Reopening must retain a working control", 1, clicks) }
        }
        compose.onNodeWithContentDescription("回到最新消息").assertDoesNotExist()
    }

    @Test fun surfaceIconButtonKeepsItsHaloWhileEnabledOpacityChanges() {
        val enabled = mutableStateOf(true)
        fixture { DeskSurfaceIconButton("menu", "Shadow fixture", enabled = enabled.value) {} }
        val full = requireNotNull(capture("enabled-full", 44f, 22f))
        assertReference(full)
        compose.runOnIdle { enabled.value = false }
        compose.mainClock.advanceTimeByFrame()
        val disabling = frames("disabling", 44f, 22f)
        assertPartialFrames(disabling, full, minimum = 2)
        compose.mainClock.advanceTimeBy(500)
        val disabled = requireNotNull(capture("disabled-full", 44f, 22f))
        assertEquals("The fixture must actually reach disabled opacity", .4f, disabled.opacity, .04f)
        assertContinuousHalo(disabled, full)
        compose.runOnIdle { enabled.value = true }
        compose.mainClock.advanceTimeByFrame()
        assertPartialFrames(frames("enabling", 44f, 22f), full, minimum = 2)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Shadow fixture").assertIsEnabled()
    }

    @Test fun pixelProbeDetectsTheLegacyAutoAlphaRectangularCrop() {
        // A required negative control, using the old production fade+scale path. It
        // proves that the exterior/corner probes discriminate the reported defect,
        // rather than passing because a static screenshot happens to look rounded.
        val visible = mutableStateOf(true)
        fixture {
            AnimatedVisibility(visible.value,
                enter = fadeIn(DeskMotion.fadeIn()) + scaleIn(DeskMotion.icon(), initialScale = .8f),
                exit = fadeOut(DeskMotion.fadeOut()) + scaleOut(DeskMotion.icon(), targetScale = .94f)) {
                Box(Modifier.size(160.dp, 96.dp).deskFloatingSurface(RoundedCornerShape(24.dp), 28.dp))
            }
        }
        val full = requireNotNull(capture("legacy-full", 160f, 24f))
        assertReference(full)
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeByFrame()
        val partial = frames("legacy-exit", 160f, 24f).filter { it.opacity in .25f.. .9f }
        assertTrue("The negative control must include a genuinely translucent frame", partial.isNotEmpty())
        val cropped = partial.minBy { it.exteriorHalo }
        assertTrue("Legacy Auto alpha must lose the exterior halo; probe is not sensitive: $cropped / $full",
            cropped.exteriorHalo < full.exteriorHalo * cropped.opacity * .12f)
        assertTrue("Legacy layer must still have shadow just inside its rectangular boundary: $cropped",
            cropped.cornerInside > .003f)
        assertTrue("Legacy rounded-corner shadow must abruptly stop at the rectangle: $cropped",
            cropped.cornerOutside < cropped.cornerInside * .25f)
    }

    private fun exerciseVisibility(
        name: String,
        visible: MutableState<Boolean>,
        widthDp: Float,
        radiusDp: Float,
        reverseDuringExit: Boolean = false,
        uniformFill: Boolean = false,
        onReopened: () -> Unit = {},
    ) {
        assertNull("A hidden fixture must not leave a surface", capture("$name-hidden", widthDp, radiusDp))
        compose.runOnIdle { visible.value = true }
        compose.mainClock.advanceTimeByFrame()
        val opening = frames("$name-enter", widthDp, radiusDp)
        compose.mainClock.advanceTimeBy(600)
        val full = requireNotNull(capture("$name-full", widthDp, radiusDp))
        assertReference(full)
        if (uniformFill) assertUniformFill(full)
        assertPartialFrames(opening, full, minimum = 2, uniformFill = uniformFill)
        assertTrue("Entering frames must exercise scaling as well as opacity", opening.any {
            it.opacity in .22f.. .98f && it.outline.width < full.outline.width - 1
        })

        if (reverseDuringExit) {
            compose.runOnIdle { visible.value = false }
            compose.mainClock.advanceTimeByFrame()
            assertPartialFrames(frames("$name-interrupted-exit", widthDp, radiusDp, count = 2), full, minimum = 1, uniformFill = uniformFill)
            compose.runOnIdle { visible.value = true }
            compose.mainClock.advanceTimeByFrame()
            assertPartialFrames(frames("$name-reenter", widthDp, radiusDp), full, minimum = 1, uniformFill = uniformFill)
            compose.mainClock.advanceTimeBy(600)
            assertContinuousHalo(requireNotNull(capture("$name-reopened", widthDp, radiusDp)), full)
            onReopened()
        }

        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeByFrame()
        assertPartialFrames(frames("$name-exit", widthDp, radiusDp), full, minimum = 2, uniformFill = uniformFill)
        compose.mainClock.advanceTimeBy(600)
        assertNull("Exit must remove the surface and its shadow", capture("$name-removed", widthDp, radiusDp))
    }

    private fun frames(prefix: String, widthDp: Float, radiusDp: Float, count: Int = 6): List<Frame> =
        (1..count).mapNotNull { index ->
            compose.mainClock.advanceTimeBy(32)
            capture("$prefix-${index * 32}ms", widthDp, radiusDp)
        }

    private fun assertReference(full: Frame) {
        assertTrue("Reference must be fully visible: $full", full.opacity > .98f)
        assertTrue("Hardware-rendered reference must contain a measurable exterior shadow: $full",
            full.exteriorHalo > .002f)
        assertTrue("Reference must contain rounded-corner shadow for the continuity probe: $full",
            full.cornerInside > .003f)
        assertContinuousHalo(full, full)
    }

    private fun assertPartialFrames(frames: List<Frame>, full: Frame, minimum: Int, uniformFill: Boolean = false) {
        val partial = frames.filter { it.opacity in .22f.. .98f }
        assertTrue("Must inspect intermediate opacity, not only endpoints: $frames", partial.size >= minimum)
        partial.forEach {
            assertContinuousHalo(it, full)
            if (uniformFill) assertUniformFill(it)
        }
    }

    private fun assertUniformFill(frame: Frame) {
        // Only called for the child-free solid-color panel. Group opacity must
        // produce a constant fill, even over the surface's own shadow. Applying
        // opacity per drawing command (ModulateAlpha) can preserve the exterior
        // halo while leaking shadow/outline geometry through the panel interior.
        assertTrue("Shadow or render-outline geometry leaked through the solid fill at ${frame.name}: $frame",
            frame.fillSpread <= 2f / 255f)
    }

    private fun assertContinuousHalo(frame: Frame, full: Frame) {
        // Normalizing to this component's own fully visible halo accommodates the
        // 3dp jump button and the 28dp menu without relaxing to an invisible shadow.
        val minimumHalo = full.exteriorHalo * frame.opacity * .15f
        assertTrue("Shadow was cropped to the rectangular layer at ${frame.name}: $frame; reference=$full",
            frame.exteriorHalo > minimumHalo)
        // Two nearby samples are both outside the rounded shape but straddle its
        // rectangular layout edge. A soft shadow may fall off; it cannot drop to
        // white there. Two 8-bit levels permit rasterization/quantization noise.
        assertTrue("Rounded-corner shadow has a hard rectangular cutoff at ${frame.name}: $frame",
            frame.cornerOutside + 2f / 255f >= frame.cornerInside * .30f)
    }

    private fun capture(name: String, widthDp: Float, radiusDp: Float): Frame? {
        val image = compose.onNodeWithTag("shadow-window", useUnmergedTree = true).captureToImage()
        val frame = analyze(name, image, widthDp, radiusDp)
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "shadow-review")
        assertTrue("Cannot create fixture screenshot directory", directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        File(directory, "$name.txt").writeText(frame?.toString() ?: "No blue surface")
        return frame
    }

    private fun analyze(name: String, image: ImageBitmap, widthDp: Float, radiusDp: Float): Frame? {
        val pixels = image.toPixelMap()
        var strongestBlue = 0f
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            strongestBlue = maxOf(strongestBlue, color.blue - color.red)
        }
        if (strongestBlue < .02f) return null
        var left = pixels.width; var top = pixels.height; var right = -1; var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.blue - color.red > strongestBlue * .5f) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        val outline = Outline(left, top, right, bottom)
        val scale = outline.width / (widthDp * density)
        val radius = minOf(radiusDp * density * scale, outline.width / 2f, outline.height / 2f)
        fun darkness(x: Float, y: Float): Float {
            val px = x.roundToInt(); val py = y.roundToInt()
            assertTrue("Fixture must leave room to measure exterior shadows", px in 0 until pixels.width && py in 0 until pixels.height)
            val color = pixels[px, py]
            assertTrue("A shadow probe crossed into the colored surface at ($px,$py): $outline", abs(color.blue - color.red) < .025f)
            return 1f - (color.red + color.green + color.blue) / 3f
        }
        val halo = mutableListOf<Float>()
        for (step in 0..10) {
            val fraction = .35f + .03f * step
            val x = left + outline.width * fraction
            val y = top + outline.height * fraction
            for (distance in listOf(1.5f, 2.5f, 3.5f)) {
                val offset = distance * density
                halo += darkness(left - offset, y)
                halo += darkness(right + offset, y)
                halo += darkness(x, bottom + offset)
            }
        }
        val inside = mutableListOf<Float>(); val outside = mutableListOf<Float>()
        // At 45–55% of a rounded corner's radius, a one-dp inset remains outside
        // the actual curved surface, including the small circular jump control.
        val edgeDistance = maxOf(1f, .75f * density * scale)
        for (fraction in listOf(.45f, .5f, .55f)) {
            for (y in listOf(top + radius * fraction, bottom - radius * fraction)) {
                inside += darkness(left + edgeDistance, y)
                outside += darkness(left - edgeDistance, y)
                inside += darkness(right - edgeDistance, y)
                outside += darkness(right + edgeDistance, y)
            }
        }
        // Sample throughout the rounded shape, including near its straight edges
        // and corners, but stay 3 scaled dp + one pixel inside its analytic outline
        // to exclude antialiasing. Text-bearing fixtures record this value only;
        // uniformity is asserted exclusively for the empty, solid-blue panel.
        val minimum = floatArrayOf(1f, 1f, 1f)
        val maximum = floatArrayOf(0f, 0f, 0f)
        val centerX = (left + right) / 2f; val centerY = (top + bottom) / 2f
        val inset = 3f * density * scale + 1f
        val stride = maxOf(1, (density * scale).roundToInt())
        var fillSamples = 0
        for (y in top..bottom step stride) for (x in left..right step stride) {
            val qx = abs(x - centerX) - (outline.width / 2f - radius)
            val qy = abs(y - centerY) - (outline.height / 2f - radius)
            val dx = maxOf(qx, 0f); val dy = maxOf(qy, 0f)
            val signedDistance = sqrt(dx * dx + dy * dy) + minOf(maxOf(qx, qy), 0f) - radius
            if (signedDistance <= -inset) {
                val color = pixels[x, y]
                val channels = floatArrayOf(color.red, color.green, color.blue)
                for (channel in channels.indices) {
                    minimum[channel] = minOf(minimum[channel], channels[channel])
                    maximum[channel] = maxOf(maximum[channel], channels[channel])
                }
                fillSamples++
            }
        }
        assertTrue("Fixture has no interior fill samples: $outline", fillSamples > 0)
        val fillSpread = (0..2).maxOf { maximum[it] - minimum[it] }
        return Frame(name, (strongestBlue / (marker.blue - marker.red)).coerceIn(0f, 1f), outline,
            halo.average().toFloat(), inside.average().toFloat(), outside.average().toFloat(), fillSpread)
    }
}
