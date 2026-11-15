// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.ui

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's single motion language. Every duration, easing curve, spring, displacement,
 * scrim opacity and gesture threshold used by interactive UI lives here so it can be tuned
 * in one place. Springs are used for anything that can be interrupted or receives gesture
 * velocity; short tweens are used for opacity and color, which have no momentum.
 */
object DeskMotion {
    // Easing
    val Standard: Easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, .2f, 1f)
    val Accelerate: Easing = CubicBezierEasing(.4f, 0f, 1f, 1f)

    // Durations (ms)
    const val PressHighlightIn = 70
    const val PressHighlightOut = 180
    /** A tap shorter than this still shows its pressed state for this long. */
    const val MinimumPressVisibleMs = 75L
    const val StateChange = 180
    const val FadeIn = 180
    const val FadeOut = 130
    const val ScrimIn = 260
    /** Longer than the panel's closing spring and slow at first, so a leaving panel is never shown over an undimmed window. */
    const val ScrimOut = 300
    const val ContentSwap = 220
    const val MenuFadeIn = 160
    const val MenuFadeOut = 150

    // Springs: damping ratio / stiffness
    const val PressDamping = .9f; const val PressStiffness = 1600f
    const val ReleaseDamping = .82f; const val ReleaseStiffness = 650f
    const val IconDamping = .88f; const val IconStiffness = 700f
    const val SheetDamping = .94f; const val SheetStiffness = 430f
    const val SheetCloseDamping = 1f; const val SheetCloseStiffness = 600f
    const val PanelDamping = .9f; const val PanelStiffness = 520f
    // The recorded drawer covers 60% of its travel at 54ms and 97% by 140ms.
    // A critically damped 1400 spring fits the visible edge without overshoot.
    const val DrawerDamping = 1f; const val DrawerStiffness = 1400f
    const val MenuDamping = 1f; const val MenuStiffness = 1300f
    const val PageDamping = 1f; const val PageStiffness = 460f
    const val LayoutDamping = 1f; const val LayoutStiffness = 560f

    // Scale and displacement
    const val ButtonPressScale = .96f
    const val ChipPressScale = .96f
    const val IconPressScale = .94f
    const val CardPressScale = .985f
    const val PanelEnterScale = .965f
    val PanelLift: Dp = 24.dp
    const val PageShiftFraction = .3f
    val SheetCorner: Dp = 28.dp
    /** Sheet background extends this far below its bottom edge to cover spring overshoot. */
    val SheetOverscrollFill: Dp = 120.dp

    // Scrim opacity (black)
    const val SheetScrim = .38f
    const val MenuScrim = .45f
    const val PanelScrim = .5f
    const val DrawerScrim = .5f

    // Gestures
    val SheetDismissDistance: Dp = 120.dp
    const val SheetDismissFraction = .3f
    val SheetDismissVelocity: Dp = 900.dp // per second
    const val RubberBandCoefficient = .55f
    val DrawerFlingVelocity: Dp = 500.dp // per second
    // Device samples: a 6.7dp slow drag cancels; a 40dp slow drag closes.
    val DrawerDistance: Dp = 24.dp

    fun <T> press(): SpringSpec<T> = spring(PressDamping, PressStiffness)
    fun <T> release(): SpringSpec<T> = spring(ReleaseDamping, ReleaseStiffness)
    fun <T> icon(): SpringSpec<T> = spring(IconDamping, IconStiffness)
    fun <T> sheetOpen(): SpringSpec<T> = spring(SheetDamping, SheetStiffness)
    fun <T> sheetClose(): SpringSpec<T> = spring(SheetCloseDamping, SheetCloseStiffness)
    fun <T> panel(): SpringSpec<T> = spring(PanelDamping, PanelStiffness)
    fun <T> drawer(): SpringSpec<T> = spring(DrawerDamping, DrawerStiffness)
    fun <T> menu(): SpringSpec<T> = spring(MenuDamping, MenuStiffness)
    fun <T> menuFadeIn(): TweenSpec<T> = tween(MenuFadeIn, easing = Decelerate)
    fun <T> menuFadeOut(): TweenSpec<T> = tween(MenuFadeOut, easing = Accelerate)
    fun <T> state(): TweenSpec<T> = tween(StateChange, easing = Standard)
    fun <T> fadeIn(): TweenSpec<T> = tween(FadeIn, easing = Decelerate)
    fun <T> fadeOut(): TweenSpec<T> = tween(FadeOut, easing = Accelerate)
    fun <T> scrimIn(): TweenSpec<T> = tween(ScrimIn, easing = Decelerate)
    fun <T> scrimOut(): TweenSpec<T> = tween(ScrimOut, easing = Accelerate)
    fun highlight(pressed: Boolean): TweenSpec<Color> = tween(if (pressed) PressHighlightIn else PressHighlightOut, easing = Standard)
    val layoutSize: SpringSpec<IntSize> = spring(LayoutDamping, LayoutStiffness, IntSize.VisibilityThreshold)
    val pageOffset: SpringSpec<IntOffset> = spring(PageDamping, PageStiffness, IntOffset.VisibilityThreshold)
    val contentOffset: SpringSpec<IntOffset> = spring(PanelDamping, PanelStiffness, IntOffset.VisibilityThreshold)

    /** Rubber-band resistance for dragging past a resting edge: approaches [dimension] asymptotically. */
    fun rubberBand(overscroll: Float, dimension: Float, coefficient: Float = RubberBandCoefficient): Float {
        if (overscroll <= 0f || dimension <= 0f) return 0f
        return (1f - 1f / (overscroll * coefficient / dimension + 1f)) * dimension
    }

    /**
     * Release policy shared by every draggable sheet. A clear downward fling closes, a clear
     * upward fling keeps it open, otherwise the distance travelled decides.
     */
    fun shouldDismissSheet(offset: Float, height: Float, velocity: Float, distanceThreshold: Float, velocityThreshold: Float): Boolean {
        if (offset <= 0f) return false
        if (velocity >= velocityThreshold) return true
        if (velocity <= -velocityThreshold) return false
        val distance = if (height > 0f) minOf(distanceThreshold, height * SheetDismissFraction) else distanceThreshold
        return offset >= distance
    }

    /** Scrim opacity (0..1) while a panel of [height] is dragged [offset] pixels down. */
    fun scrimForOffset(offset: Float, height: Float): Float = if (height <= 0f) 1f else (1f - offset / height).coerceIn(0f, 1f)

    /** Drawer release target: fling velocity wins, otherwise distance, otherwise unchanged. */
    fun drawerTarget(open: Boolean, dx: Float, velocity: Float, distanceThreshold: Float, velocityThreshold: Float): Boolean = when {
        kotlin.math.abs(velocity) > velocityThreshold -> velocity > 0
        kotlin.math.abs(dx) > distanceThreshold -> dx > 0
        else -> open
    }
}

/** True when the user removed animations (system animator scale 0). Compose animations then finish immediately. */
val LocalReducedMotion = compositionLocalOf { false }

@Composable internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var scale by remember(resolver) { mutableFloatStateOf(runCatching { read() }.getOrDefault(1f)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = runCatching { read() }.getOrDefault(1f) }
        }
        runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale == 0f
}

/**
 * Semantic haptics. [View.performHapticFeedback] honors the system touch-feedback setting
 * and needs no vibrator permission; constants degrade gracefully on older releases.
 */
@Stable class DeskHaptics internal constructor(private val view: View) {
    fun tap() = perform(HapticFeedbackConstants.CONTEXT_CLICK)
    fun selection() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun toggle(on: Boolean) = perform(if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF) else HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
    fun threshold() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)
    fun gestureEnd() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK)
    private fun perform(constant: Int) { view.performHapticFeedback(constant) }
}

@Composable fun rememberHaptics(): DeskHaptics { val view = LocalView.current; return remember(view) { DeskHaptics(view) } }

/**
 * Pressed state that stays visible for at least [DeskMotion.MinimumPressVisibleMs], so quick
 * taps (whose press is reported late inside scrolling containers) still show feedback.
 * A cancelled press clears immediately.
 */
@Composable fun InteractionSource.pressedState(): State<Boolean> {
    val pressed = remember(this) { mutableStateOf(false) }
    LaunchedEffect(this) {
        val presses = mutableListOf<PressInteraction.Press>()
        var pressedAt = 0L
        var release: Job? = null
        interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> { presses += interaction; release?.cancel(); pressedAt = SystemClock.uptimeMillis(); pressed.value = true }
                is PressInteraction.Release -> {
                    presses -= interaction.press
                    if (presses.isEmpty()) {
                        val remaining = DeskMotion.MinimumPressVisibleMs - (SystemClock.uptimeMillis() - pressedAt)
                        release?.cancel()
                        release = launch { if (remaining > 0) delay(remaining); pressed.value = false }
                    }
                }
                is PressInteraction.Cancel -> { presses -= interaction.press; if (presses.isEmpty()) { release?.cancel(); pressed.value = false } }
            }
        }
    }
    return pressed
}

/** Spring scale feedback for press and release; interrupted presses reverse from where they are. */
fun Modifier.pressScale(pressed: Boolean, enabled: Boolean = true, scale: Float = DeskMotion.ButtonPressScale): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val target = if (pressed && enabled && !reduced) scale else 1f
    val value by animateFloatAsState(target, if (target < 1f) DeskMotion.press() else DeskMotion.release(), label = "press scale")
    graphicsLayer { scaleX = value; scaleY = value }
}

/**
 * Reserve real pixels around a floating surface's alpha buffer for the largest (28dp)
 * shadow, then compensate the layout so anchors and touch targets stay unchanged.
 * Group alpha is intentional: modulating individual draws lets the shadow show through
 * its own translucent surface and produces bands/polygons inside otherwise flat fills.
 */
internal fun Modifier.floatingAlpha(opacity: () -> Float): Modifier = composed {
    val space = 56.dp
    val inset = with(LocalDensity.current) { space.roundToPx() }
    layout { measurable, constraints ->
        val child = measurable.measure(constraints.offset(horizontal = inset * 2, vertical = inset * 2))
        layout((child.width - inset * 2).coerceAtLeast(0), (child.height - inset * 2).coerceAtLeast(0)) {
            child.place(-inset, -inset)
        }
    }.graphicsLayer { alpha = opacity().coerceIn(0f, 1f) }.padding(space)
}

/** Scale and fade shadowed content, retaining it until both exit animations finish. */
@Composable internal fun DeskFloatingVisibility(
    visibleState: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
    initialScale: Float = .7f,
    targetScale: Float = initialScale,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    content: @Composable () -> Unit,
) {
    // AnimatedVisibility's built-in fade uses an Auto layer bounded by the content.
    // Register opacity on its transition instead, so shadow pixels outside those
    // bounds survive every intermediate frame and interrupted/reversed animation.
    AnimatedVisibility(visibleState, modifier,
        enter = scaleIn(DeskMotion.icon(), initialScale, transformOrigin),
        exit = scaleOut(DeskMotion.icon(), targetScale, transformOrigin)) {
        val opacity by transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) DeskMotion.fadeIn() else DeskMotion.fadeOut() },
            label = "floating opacity",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        Box(Modifier.floatingAlpha { opacity }) { content() }
    }
}

/** Highlight color that appears quickly on press and eases out on release. Rest is the transparent variant of [pressed]. */
@Composable fun animatedHighlight(pressed: Boolean, selected: Boolean, pressedColor: Color, selectedColor: Color = pressedColor, rest: Color = pressedColor.copy(alpha = 0f)): Color {
    val target = if (pressed) pressedColor else if (selected) selectedColor else rest
    return animateColorAsState(target, DeskMotion.highlight(pressed), label = "highlight").value
}

/** Enabled/disabled opacity that changes smoothly instead of popping. */
@Composable fun animatedEnabledAlpha(enabled: Boolean, disabledAlpha: Float = .45f): Float =
    animateFloatAsState(if (enabled) 1f else disabledAlpha, DeskMotion.state(), label = "enabled alpha").value

/** Expansion arrow that rotates continuously between states instead of swapping glyphs. */
@Composable fun DeskChevron(expanded: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = LocalDeskColors.current.mutedForeground, collapsed: String = "chevron-down", expandedRotation: Float = 180f, strokeWidth: Float = 2f) {
    val rotation by animateFloatAsState(if (expanded) expandedRotation else 0f, DeskMotion.icon(), label = "chevron")
    DeskIcon(collapsed, modifier.graphicsLayer { rotationZ = rotation }, size, tint, strokeWidth)
}

/** Height-continuous reveal for expandable sections: spring height with a short fade. */
@Composable fun DeskExpandable(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier,
        enter = expandVertically(DeskMotion.layoutSize, expandFrom = Alignment.Top) + fadeIn(DeskMotion.fadeIn()),
        exit = shrinkVertically(DeskMotion.layoutSize, shrinkTowards = Alignment.Top) + fadeOut(DeskMotion.fadeOut())) { content() }
}

/** Keeps the last non-null value so exiting content can finish animating with its final data. */
@Composable fun <T : Any> rememberLastNonNull(value: T?): T? {
    val held = remember { mutableStateOf(value) }
    SideEffect { if (value != null) held.value = value }
    return value ?: held.value
}
