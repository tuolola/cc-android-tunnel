// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Overlays render in the activity window above all app content instead of in separate
 * Dialog windows. The scrim therefore always covers the same fixed window bounds, and
 * window-level enter/exit animations can no longer move it together with a sheet.
 */
@Stable class OverlayHostState internal constructor() {
    internal val layers = mutableStateListOf<OverlayLayer>()
    val modalOpen: Boolean get() = layers.any { it.modal.value }
}
@Stable internal class OverlayLayer(val modal: State<Boolean>, val content: @Composable () -> Unit)
val LocalOverlayHost = staticCompositionLocalOf<OverlayHostState?> { null }

@Composable fun OverlayHost(content: @Composable () -> Unit) {
    val host = remember { OverlayHostState() }
    CompositionLocalProvider(LocalOverlayHost provides host) {
        Box(Modifier.fillMaxSize()) {
            // While a modal overlay is open, background content is hidden from accessibility.
            Box(Modifier.fillMaxSize().then(if (host.modalOpen) Modifier.clearAndSetSemantics { } else Modifier)) { content() }
            host.layers.forEach { layer -> key(layer) { OverlayLayerContent(layer) } }
        }
    }
}
@Composable private fun OverlayLayerContent(layer: OverlayLayer) { layer.content() }

/** A floating menu anchored to a screen corner, with a clear outside-tap target. */
@Composable internal fun DeskAnchoredOverlay(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    alignment: Alignment = Alignment.TopEnd,
    width: Dp = 216.dp,
    corner: Dp = 20.dp,
    margin: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val opacity = remember { Animatable(0f) }
    val scale = remember { Animatable(.94f) }
    var present by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            present = true
            launch { opacity.animateTo(1f, DeskMotion.menuFadeIn()) }
            scale.animateTo(1f, DeskMotion.menu())
        } else if (present) {
            val fade = launch { opacity.animateTo(0f, DeskMotion.menuFadeOut()) }
            scale.animateTo(.94f, DeskMotion.menu())
            fade.join()
            present = false
            scale.snapTo(.94f)
        }
    }
    if (!visible && !present) return
    val dismiss = rememberUpdatedState(onDismissRequest)
    val origin = when (alignment) {
        Alignment.BottomStart -> TransformOrigin(0f, 1f)
        Alignment.BottomEnd -> TransformOrigin(1f, 1f)
        Alignment.TopStart -> TransformOrigin(0f, 0f)
        else -> TransformOrigin(1f, 0f)
    }
    Portal(modal = visible || present) {
        BackHandler(enabled = visible, onBack = onDismissRequest)
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (visible) Modifier
                .pointerInput(Unit) { detectTapGestures { dismiss.value() } }
                .semantics { contentDescription = "关闭菜单"; role = Role.Button; onClick { dismiss.value(); true } }
            else BlockPointerInput.clearAndSetSemantics { }))
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(margin), contentAlignment = alignment) {
                val shape = RoundedCornerShape(corner)
                Column(Modifier.widthIn(max = width).fillMaxWidth().floatingAlpha { opacity.value }.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    transformOrigin = origin
                }.deskFloatingSurface(shape, 12.dp).border(1.dp, LocalDeskColors.current.border, shape).clip(shape)
                    .then(if (visible) AbsorbPointerInput else BlockPointerInput.clearAndSetSemantics { }),
                    content = content)
            }
        }
    }
}

/** Renders [content] in the nearest [OverlayHost]; without a host (isolated tests) it renders in place. */
@Composable internal fun Portal(modal: Boolean, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) { content(); return }
    val latest = rememberUpdatedState(content)
    val modalState = rememberUpdatedState(modal)
    val layer = remember { OverlayLayer(modalState) { latest.value() } }
    DisposableEffect(host, layer) { host.layers += layer; onDispose { host.layers -= layer } }
}

/** Independent opacity (scrim) and position (panel) progress for one modal surface. */
@Stable internal class ModalMotion(visible: Boolean) {
    /** Scrim opacity factor, 0 = clear, 1 = full scrim. Animated with tweens only. */
    val scrim = Animatable(0f)
    /** Panel travel, 0 = resting open, 1 = fully hidden. Spring-driven, may receive gesture velocity. */
    val panel = Animatable(1f)
    var present by mutableStateOf(visible)
    /** Panel drag offset in pixels while a finger is down; null when not dragging. */
    var dragOffset by mutableStateOf<Float?>(null)
    /** Velocity in panel-travel fractions per second handed from a release to the next spring. */
    var releaseVelocity = 0f
    /** Bumped after a drag release so the settle animation re-runs with the owner's latest visibility. */
    var settle by mutableIntStateOf(0)
    /** Last measured panel height in pixels; 0 before the first layout. */
    var measuredHeight by mutableFloatStateOf(0f)
    /** Panel travel; before the first measurement the window height keeps the panel off screen. */
    fun panelHeight(fallback: Float): Float = measuredHeight.takeIf { it > 0f } ?: fallback
}

/**
 * [scrimHandoff]: when the surface closes because another modal surface is opening, its scrim
 * yields immediately so the two scrims never stack; the panel still animates out.
 */
@Composable internal fun rememberModalMotion(visible: Boolean, scrimHandoff: Boolean = false): ModalMotion {
    val motion = remember { ModalMotion(visible) }
    LaunchedEffect(motion, visible, motion.settle) {
        // An owner can close while a finger is still on the handle (for example via
        // system Back). Hand the visible drag position to the spring before clearing
        // the drag override, otherwise the panel freezes and disappears at exit end.
        if (!visible) motion.dragOffset?.let { offset ->
            val height = motion.measuredHeight.coerceAtLeast(1f)
            motion.panel.snapTo(offset / height)
            motion.scrim.snapTo(DeskMotion.scrimForOffset(offset, height))
            motion.dragOffset = null
        }
        val velocity = motion.releaseVelocity.also { motion.releaseVelocity = 0f }
        if (visible) {
            motion.present = true
            launch { motion.scrim.animateTo(1f, DeskMotion.scrimIn()) }
            motion.panel.animateTo(0f, DeskMotion.sheetOpen(), if (velocity != 0f) velocity else motion.panel.velocity)
        } else if (motion.present) {
            val scrim = launch { if (scrimHandoff) motion.scrim.snapTo(0f) else motion.scrim.animateTo(0f, DeskMotion.scrimOut()) }
            motion.panel.animateTo(1f, DeskMotion.sheetClose(), if (velocity != 0f) velocity else motion.panel.velocity)
            scrim.join()
            motion.present = false
        }
    }
    return motion
}

/**
 * Consumes every pointer event; used on a closing panel so its controls cannot fire again.
 * Distinct keys are required for each input mode: Compose can reuse the modifier node
 * when a live tap detector is replaced, and an unchanged key keeps its old coroutine.
 */
internal val BlockPointerInput = Modifier.pointerInput("desk-block-pointer") {
    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
}
/**
 * Blocks clicks in a departing drawer while allowing its ancestor to finish a reversing
 * drag. Do not consume a normal UP: the parent distinguishes Android ACTION_CANCEL by
 * its consumed synthetic release. Consuming DOWN already prevents child click handlers.
 */
internal val BlockTapInput = Modifier.pointerInput("desk-block-tap") {
    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { if (it.pressed) it.consume() } }
}
/**
 * Makes a panel a hit target so touches inside it never reach the scrim or content below, and
 * consumes presses its children left unconsumed so no ancestor starts a click. Moves are left
 * alone: consuming them would cancel a child's tap in progress.
 */
internal val AbsorbPointerInput = Modifier.pointerInput("desk-absorb-panel") {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { if (it.pressed && !it.previousPressed && !it.isConsumed) it.consume() } }
}

const val OverlayScrimTag = "overlay-scrim"
const val OverlayPanelTag = "overlay-panel"

@Stable class DeskSheetScope internal constructor(
    /** Apply to the grabber/header region to make the sheet draggable from there. */
    val dragHandle: Modifier,
    val close: () -> Unit,
)

/**
 * A bottom sheet (or a centered card on wide screens when [centeredWhenWide]).
 *
 * Layers, from back to front, all filling the window:
 * 1. scrim: fixed window bounds, animates opacity only, never moved, scaled or clipped;
 * 2. panel container: applies status bar and keyboard insets for the panel only;
 * 3. panel: translates (or fades/scales when centered) via a graphics layer and follows drags.
 */
@Composable internal fun DeskSheetOverlay(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    onBack: (() -> Unit)? = null,
    scrimAlpha: Float = DeskMotion.SheetScrim,
    maxWidth: Dp = 720.dp,
    maxHeightFraction: Float = .88f,
    heightFraction: Float? = null,
    centeredWhenWide: Boolean = false,
    bordered: Boolean = false,
    scrimLabel: String = "关闭面板",
    scrimHandoff: Boolean = false,
    content: @Composable ColumnScope.(DeskSheetScope) -> Unit,
) {
    val motion = rememberModalMotion(visible, scrimHandoff)
    if (!visible && !motion.present) return
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) { if (!visible) keyboard?.hide() }
    val dismiss = rememberUpdatedState(onDismissRequest)
    val canDismiss = rememberUpdatedState(dismissible)
    Portal(modal = visible || motion.present) {
        BackHandler(enabled = visible) { (onBack ?: onDismissRequest)() }
        val c = LocalDeskColors.current
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val haptics = rememberHaptics()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = centeredWhenWide && maxWidth >= 640.dp
            val windowHeight = constraints.maxHeight.toFloat()
            // 1. Keep the fading surface modal until its final frame. A rapid second
            // tap must not activate a control revealed underneath a closing panel.
            Box(Modifier.fillMaxSize().testTag(OverlayScrimTag)
                .graphicsLayer { alpha = motion.dragOffset?.let { DeskMotion.scrimForOffset(it, motion.panelHeight(windowHeight)) } ?: motion.scrim.value }
                .background(Color.Black.copy(alpha = scrimAlpha))
                .then(if (visible) Modifier.pointerInput(Unit) { detectTapGestures { if (canDismiss.value) dismiss.value() } }
                    .semantics { contentDescription = scrimLabel; role = Role.Button; onClick { if (canDismiss.value) dismiss.value(); true } } else BlockPointerInput.clearAndSetSemantics { }))
            // 2. Panel container: insets apply to the panel only, never to the scrim.
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding(), contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter) {
                val corner = DeskMotion.SheetCorner
                val shape = if (wide) RoundedCornerShape(corner) else RoundedCornerShape(topStart = corner, topEnd = corner)
                val lift = with(density) { DeskMotion.PanelLift.toPx() }
                val fill = with(density) { DeskMotion.SheetOverscrollFill.toPx() }
                val drag = Modifier.pointerInput(wide, visible) {
                    if (wide || !visible) return@pointerInput
                    val tracker = VelocityTracker()
                    val distance = DeskMotion.SheetDismissDistance.toPx()
                    val velocityThreshold = DeskMotion.SheetDismissVelocity.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tracker.resetTracking(); tracker.addPosition(down.uptimeMillis, down.position)
                        var accepted = false; var start = 0f; var offset = 0f; var armed = false; var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) { cancelled = true; break }
                            // Android reports ACTION_CANCEL as a consumed synthetic release.
                            if (!change.pressed && change.isConsumed) { cancelled = true; break }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val dy = change.position.y - down.position.y
                            val dx = change.position.x - down.position.x
                            if (!accepted && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) {
                                accepted = true
                                val height = motion.panelHeight(windowHeight)
                                // Interrupt any running animation and continue from where it is now.
                                start = motion.panel.value * height - dy
                                motion.dragOffset = start + dy
                                scope.launch { motion.panel.stop(); motion.scrim.stop() }
                            }
                            if (accepted) {
                                change.consume()
                                val height = motion.panelHeight(windowHeight)
                                val raw = start + dy
                                offset = if (raw < 0f) -DeskMotion.rubberBand(-raw, height) else raw
                                motion.dragOffset = offset
                                val over = DeskMotion.shouldDismissSheet(offset, height, 0f, distance, velocityThreshold)
                                if (over != armed) {
                                    armed = over
                                    if (over && canDismiss.value) {
                                        haptics.threshold()
                                    }
                                }
                            }
                            if (!change.pressed) break
                        }
                        if (!accepted) {
                            return@awaitEachGesture
                        }
                        val height = motion.panelHeight(windowHeight)
                        val velocity = if (cancelled) 0f else tracker.calculateVelocity().y
                        val close = !cancelled && canDismiss.value && DeskMotion.shouldDismissSheet(offset, height, velocity, distance, velocityThreshold)
                        scope.launch {
                            motion.panel.snapTo(offset / height)
                            motion.scrim.snapTo(DeskMotion.scrimForOffset(offset, height))
                            motion.dragOffset = null
                            motion.releaseVelocity = velocity / height
                            // Closing continues with the finger's velocity; if the owner declines
                            // (for example while saving) the same settle pass springs back open.
                            if (close) dismiss.value()
                            motion.settle++
                        }
                    }
                }
                Column(Modifier.widthIn(max = maxWidth).fillMaxWidth()
                    .then(heightFraction?.let { Modifier.height(this@BoxWithConstraints.maxHeight * it) } ?: Modifier.heightIn(max = this@BoxWithConstraints.maxHeight * maxHeightFraction))
                    .then(if (wide) Modifier.padding(horizontal = 16.dp) else Modifier)
                    .onSizeChanged { motion.measuredHeight = it.height.toFloat() }
                    .graphicsLayer {
                        if (wide) {
                            val f = (motion.dragOffset?.let { it / motion.panelHeight(windowHeight) } ?: motion.panel.value).coerceIn(0f, 1f)
                            alpha = 1f - f
                            val s = 1f - (1f - DeskMotion.PanelEnterScale) * f
                            scaleX = s; scaleY = s; translationY = lift * f
                        } else translationY = motion.dragOffset ?: (motion.panel.value * motion.panelHeight(windowHeight))
                    }
                    .testTag(OverlayPanelTag) // inside the layer, so semantics bounds follow the moving panel
                    .then(if (!wide) Modifier.drawBehind { drawRect(c.card, Offset(0f, size.height - 1f), Size(size.width, fill)) } else Modifier)
                    .then(if (bordered) Modifier.border(1.dp, c.border, shape) else Modifier)
                    .clip(shape).background(c.card)
                    .then(if (visible) AbsorbPointerInput else BlockPointerInput.clearAndSetSemantics { })
                    .then(if (!wide) Modifier.navigationBarsPadding() else Modifier.padding(bottom = 8.dp))
                ) { content(DeskSheetScope(drag) { if (canDismiss.value) dismiss.value() }) }
            }
        }
    }
}


/** Grabber and title row shared by sheets; the whole row is the drag handle. */
@Composable internal fun SheetHeader(title: String, scope: DeskSheetScope, closeLabel: String = "关闭", closeEnabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    val c = LocalDeskColors.current
    Column(Modifier.fillMaxWidth().then(scope.dragHandle)) {
        SheetGrabber()
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (leading == null) 24.dp else 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            leading?.invoke()
            SNativeText(title, Modifier.weight(1f).semantics { heading() }, size = 17, lineHeight = 24, weight = FontWeight.SemiBold, heading = true)
            DeskIconButton("x", closeLabel, enabled = closeEnabled, iconSize = 18.dp, tint = c.mutedForeground, onClick = scope.close)
        }
    }
}

@Composable internal fun SheetGrabber() {
    val c = LocalDeskColors.current
    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(32.dp).height(4.dp).background(c.mutedForeground.copy(alpha = .28f), RoundedCornerShape(2.dp)))
    }
}
