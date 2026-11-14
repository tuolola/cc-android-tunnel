@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ccdesk.nativeapp.ui

import android.content.Intent
import android.net.Uri
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import dev.ccdesk.nativeapp.core.WorkbenchRepository
import dev.ccdesk.nativeapp.core.WorkbenchState
import dev.ccdesk.nativeapp.core.normalizeEndpoint
import dev.ccdesk.nativeapp.core.normalizeServiceAddress
import dev.ccdesk.nativeapp.core.normalizeFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor

private val statusLabels = mapOf("loading" to "正在读取连接信息", "setup" to "未设置地址", "login" to "需要登录", "connecting" to "正在连接", "online" to "已连接", "offline" to "连接已断开")
private val settingTabs = listOf("settings" to "概览", "connection" to "连接", "usage" to "账号与用量", "access" to "访问管理", "notifications" to "通知", "help" to "帮助")
// Reanimated withTiming's default is Easing.inOut(Easing.quad).
private val secondaryTiming = Easing { if (it < .5f) 2 * it * it else 1 - (-2 * it + 2) * (-2 * it + 2) / 2 }
private fun fields(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { (k, v) -> if (v != null) put(k, v) } }
private fun JSONObject?.text(key: String, fallback: String = ""): String = if (this == null || isNull(key)) fallback else optString(key, fallback)
private fun JSONObject?.objects(key: String): List<JSONObject> = this?.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
private fun JSONObject?.strings(key: String): List<String> = this?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
private fun dateTime(value: String?): String = runCatching { DateTimeFormatter.ofPattern("M/d HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault("—")
private fun date(value: String): String = runCatching { DateTimeFormatter.ofPattern("yyyy/M/d", Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault("—")
private fun number(value: Number): String = NumberFormat.getNumberInstance(Locale.CHINA).format(value)
private fun money(value: Double): String = "$" + NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 4 }.format(value)

@Stable
private class ScreenAction(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "请求失败，请重试。" }
            finally { busy = false }
        }
    }
}

@Composable private fun rememberAction(): ScreenAction {
    val scope = rememberCoroutineScope()
    return remember(scope) { ScreenAction(scope) }
}

/** Source parity: src/workbench/settings/ui.tsx. The panel uses foundation widgets, not Material defaults. */
@Composable
fun SecondaryScreen(route: String, repository: WorkbenchRepository, onNavigate: (String) -> Unit, onClose: () -> Unit, onOpenChat: () -> Unit = onClose) {
    val state by repository.state.collectAsState()
    val title = when (route) { "settings" -> "设置"; "connection" -> "连接工作台"; "usage" -> "账号与用量"; "access" -> "访问管理"; "notifications" -> "通知"; else -> "帮助" }
    SettingsPanel(route, title, onNavigate, onClose) {
        key(route, if (route == "access") state.endpoint + state.webSessionId else "") {
            when (route) {
                "settings" -> SettingsContent(state, repository, onNavigate)
                "connection" -> ConnectionContent(state, repository, onNavigate, onOpenChat)
                "usage" -> UsageContent(state, repository, onNavigate)
                "access" -> AccessContent(state, repository, onNavigate)
                "notifications" -> NotificationContent(state, repository, onOpenChat)
                else -> HelpContent(onNavigate)
            }
        }
    }
}

@Composable
internal fun SettingsPanel(route: String, title: String, onNavigate: (String) -> Unit, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalDeskColors.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f, tween(160, easing = secondaryTiming), label = "settings entrance")
    val slide = with(LocalDensity.current) { 25.dp.toPx() }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f))) {
        Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            val panelModifier = if (maxWidth >= 736.dp) Modifier.heightIn(max = 780.dp) else Modifier
            Column(panelModifier.fillMaxHeight().widthIn(max = 672.dp).fillMaxWidth().graphicsLayer { alpha = progress; translationY = slide * (1f - progress) }.clip(RoundedCornerShape(12.dp)).border(1.dp, c.border, RoundedCornerShape(12.dp)).background(c.card).pointerInput(Unit) { detectTapGestures { } }.padding(1.dp)) {
                Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeskText(title, Modifier.weight(1f).semantics { heading() }, size = 17, weight = FontWeight.SemiBold)
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(if (pressed) c.muted else Color.Transparent).clickable(interactionSource = interaction, indication = null, onClick = onClose).semantics { contentDescription = "关闭"; role = Role.Button }, contentAlignment = Alignment.Center) {
                        DeskIcon("x", size = 18.dp, tint = c.mutedForeground, strokeWidth = 1.7f)
                    }
                }
                val tabScroll = rememberScrollState()
                val tabPositions = remember { mutableStateMapOf<String, Int>() }
                // LayoutCoordinates use physical pixels; RN scrollTo uses dp and includes the 12 dp content inset.
                val tabRevealInset = with(LocalDensity.current) { (26.dp - 12.dp).roundToPx() }
                LaunchedEffect(route, tabPositions[route]) { tabPositions[route]?.let { tabScroll.scrollTo((it - tabRevealInset).coerceAtLeast(0)) } }
                Row(Modifier.fillMaxWidth().horizontalScroll(tabScroll).padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    settingTabs.forEach { (key, label) ->
                        val selected = route == key
                        val interaction = remember(key) { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        Box(Modifier.onGloballyPositioned { tabPositions[key] = it.positionInParent().x.roundToInt() }.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (pressed) c.muted else if (selected) c.accent else Color.Transparent).clickable(interactionSource = interaction, indication = null) { if (!selected) onNavigate(key) }.semantics { this.selected = selected; role = Role.Tab }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            DeskText(label, size = 14, color = if (selected) c.foreground else c.mutedForeground, weight = if (selected) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
                SDivider()
                key(route) { NativeScrollContent(Modifier.weight(1f).fillMaxWidth(), content = content) }
            }
        }
    }
}

/** The baseline ReactScrollView is Android ScrollView with OUTSIDE_OVERLAY scrollbars. */
private class SettingsScrollView(context: android.content.Context) : ScrollView(context) {
    fun revealScrollbar() { awakenScrollBars() }
}

@Composable internal fun NativeScrollContent(
    modifier: Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
    spacing: androidx.compose.ui.unit.Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val composition = rememberCompositionContext()
    val currentContent = rememberUpdatedState(content)
    AndroidView(modifier = modifier.clipToBounds(), factory = { context ->
        SettingsScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            isScrollbarFadingEnabled = true
            scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = false
            clipChildren = true
            clipToPadding = true
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            clipToOutline = true
            setPadding(0, 0, 0, 0)
            addView(ComposeView(context).apply {
                setParentCompositionContext(composition)
                setContent {
                    Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(spacing), content = currentContent.value)
                }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            post { revealScrollbar() }
        }
    })
}

@Composable private fun SDivider() { Box(Modifier.fillMaxWidth().height(1.dp).background(LocalDeskColors.current.border)) }

/** Mirrors the MIT-licensed baseline CustomLineHeightSpan's paragraph bounds, without any RN runtime. */
private class SettingsLineHeight(private val height: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
        val leading = height - (fm.descent - fm.ascent)
        fm.ascent -= ceil(leading / 2.0).toInt()
        fm.descent += floor(leading / 2.0).toInt()
        if (start == 0) fm.top = fm.ascent
        if (end == text.length) fm.bottom = fm.descent
    }
}

@Composable internal fun SNativeText(text: String, modifier: Modifier = Modifier, size: Int = 14, lineHeight: Int? = null,
    color: Color = LocalDeskColors.current.foreground, weight: FontWeight = FontWeight.Normal, selectable: Boolean = false, maxLines: Int = Int.MAX_VALUE,
    heading: Boolean = false, live: Boolean = false) {
    val density = LocalDensity.current
    val heightPx = lineHeight?.let { with(density) { ceil(it.sp.toPx()).toInt() } }
    AndroidView(modifier = modifier, factory = { context ->
        TextView(context).apply {
            setPadding(0, 0, 0, 0)
            includeFontPadding = true
            gravity = Gravity.TOP or Gravity.START
            setLineSpacing(0f, 1f)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }, update = { view ->
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        view.setTextColor(color.toArgb())
        view.typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight.weight, false) else Typeface.create(Typeface.DEFAULT, if (weight.weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        view.maxLines = maxLines
        view.ellipsize = if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END
        view.setTextIsSelectable(selectable)
        view.accessibilityLiveRegion = if (live) android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE else android.view.View.ACCESSIBILITY_LIVE_REGION_NONE
        if (Build.VERSION.SDK_INT >= 28) view.isAccessibilityHeading = heading
        val signature = listOf(text, heightPx, size, weight.weight)
        if (view.tag != signature) {
            view.text = if (heightPx == null) text else SpannableString(text).apply { setSpan(SettingsLineHeight(heightPx), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
            view.tag = signature
        }
    })
}

@Composable private fun SSection(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) SNativeText(title, Modifier.semantics { heading() }, size = 14, lineHeight = 20, weight = FontWeight.SemiBold, heading = true)
        Column { SDivider(); content() }
    }
}

@Composable private fun SRow(label: String, detail: String? = null, icon: String? = null, danger: Boolean = false, last: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = LocalDeskColors.current
    val red = if (c.background == Color(0xFF191919)) Color(0xFFFF6467) else Color(0xFFE7000B)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (pressed) c.muted else Color.Transparent).then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick).semantics { role = Role.Button } else Modifier).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) DeskIcon(icon, size = 18.dp, tint = if (danger) red else c.mutedForeground, strokeWidth = 1.65f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SNativeText(label, size = 15, lineHeight = 21, color = if (danger) red else c.foreground)
            if (!detail.isNullOrEmpty()) {
                SNativeText(detail, size = 13, lineHeight = 19, color = c.mutedForeground, selectable = onClick == null)
            }
        }
        if (onClick != null) DeskIcon("chevron-right", size = 16.dp, tint = c.mutedForeground)
    }
    if (!last) SDivider()
}

@Composable private fun SHint(text: String?, error: Boolean = false) {
    if (text.isNullOrEmpty()) return
    val c = LocalDeskColors.current
    SNativeText(text, Modifier.then(if (error) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier), size = 13, lineHeight = 20, selectable = true, live = error,
        color = if (error) if (c.background == Color(0xFF191919)) Color(0xFFFF6467) else Color(0xFFE7000B) else c.mutedForeground)
}

@Composable private fun SAction(label: String, busy: Boolean = false, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    val c = LocalDeskColors.current
    val disabled = !enabled || busy
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).graphicsLayer { alpha = if (disabled) .5f else if (pressed) .75f else 1f; compositingStrategy = CompositingStrategy.ModulateAlpha }.clip(RoundedCornerShape(10.dp)).background(if (secondary) c.background else c.foreground)
        .then(if (secondary) Modifier.border(1.dp, c.border, RoundedCornerShape(10.dp)) else Modifier).clickable(enabled = !disabled, interactionSource = interaction, indication = null, onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (busy) SBusy()
        NativeSpanText(label, size = 14, lineHeight = null, weight = 500, color = if (secondary) c.foreground else c.background, selectable = false)
    }
}

@Composable internal fun SBusy() {
    AndroidView(modifier = Modifier.size(20.dp), factory = { context -> ProgressBar(context, null, android.R.attr.progressBarStyle).apply { isIndeterminate = true } })
}

@Composable internal fun nativeInputCursorColor(): Color {
    val context = LocalContext.current
    val dark = LocalDeskColors.current.dark
    val fallback = LocalTextSelectionColors.current.handleColor
    return remember(context, dark, fallback) {
        val attribute = TypedValue()
        if (!context.theme.resolveAttribute(androidx.appcompat.R.attr.colorControlActivated, attribute, true)) fallback
        else if (attribute.resourceId == 0) Color(attribute.data)
        else runCatching {
            val tint = androidx.appcompat.content.res.AppCompatResources.getColorStateList(context, attribute.resourceId)
            tint?.let { Color(it.getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_activated), it.defaultColor)) } ?: fallback
        }.getOrDefault(fallback)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun SField(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", secure: Boolean = false, enabled: Boolean = true,
    numeric: Boolean = false, maxLength: Int = Int.MAX_VALUE, imeAction: ImeAction = ImeAction.Default, keyboardType: KeyboardType = KeyboardType.Text, onSubmit: (() -> Unit)? = null) {
    val c = LocalDeskColors.current
    val cursorColor = nativeInputCursorColor()
    var focused by remember { mutableStateOf(false) }
    val updateValue by rememberUpdatedState<(String) -> Unit> { next ->
        if (enabled) onChange((if (numeric) next.filter { it in '0'..'9' } else next).take(maxLength))
    }
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val autofillNode = remember(secure, numeric) {
        when {
            secure -> AutofillNode(listOf(AutofillType.Password), onFill = { updateValue(it) })
            numeric -> AutofillNode(listOf(AutofillType.SmsOtpCode), onFill = { updateValue(it) })
            else -> null
        }
    }
    DisposableEffect(autofillNode, autofillTree) {
        if (autofillNode != null) autofillTree += autofillNode
        onDispose {
            if (autofillNode != null) {
                autofill?.cancelAutofillForNode(autofillNode)
                autofillTree.children.remove(autofillNode.id)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SNativeText(label, size = 14, weight = FontWeight.Medium)
        BasicTextField(value, updateValue, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, if (focused) c.mutedForeground else c.border, RoundedCornerShape(10.dp)).background(c.background).onGloballyPositioned { autofillNode?.boundingBox = it.boundsInWindow() }.padding(1.dp).padding(horizontal = 14.dp, vertical = 10.dp).onFocusChanged {
                focused = it.isFocused
                autofillNode?.let { node -> if (it.isFocused && enabled) autofill?.requestAutofillForNode(node) else autofill?.cancelAutofillForNode(node) }
            }.semantics { contentDescription = label },
            textStyle = TextStyle(color = c.foreground, fontSize = 16.sp, platformStyle = PlatformTextStyle(includeFontPadding = true)), cursorBrush = SolidColor(cursorColor), singleLine = true,
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (secure) KeyboardType.Password else if (numeric) KeyboardType.Number else keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onGo = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (value.isEmpty()) SNativeText(placeholder, size = 16, color = c.mutedForeground); inner() } })
    }
}

@Composable private fun ConnectionRequired(onNavigate: (String) -> Unit) {
    SSection { SRow("连接工作台后查看", "打开连接设置，检查地址和登录状态。", "link-2", last = true, onClick = { onNavigate("connection") }) }
}

@Composable private fun SettingsContent(state: WorkbenchState, repo: WorkbenchRepository, onNavigate: (String) -> Unit) {
    val c = LocalDeskColors.current
    var confirm by remember { mutableStateOf(false) }
    val action = rememberAction()
    LaunchedEffect(state.status) { if (state.status == "online") runCatching { repo.command("account.status") } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    val identityInteraction = remember { MutableInteractionSource() }
    val identityPressed by identityInteraction.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (identityPressed) c.muted else Color.Transparent).clickable(interactionSource = identityInteraction, indication = null) { onNavigate(if (state.authenticated) "usage" else "connection") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.foreground), contentAlignment = Alignment.Center) { DeskIcon("circle-user", size = 20.dp, tint = c.background, strokeWidth = 1.5f) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SNativeText(state.account.text("email").ifBlank { if (state.authenticated) "工作台账号" else "尚未登录工作台" }, size = 14, lineHeight = 20, weight = FontWeight.Medium, maxLines = 1)
            if (state.endpoint.isNotEmpty()) SNativeText(runCatching { URI(state.endpoint).authority }.getOrDefault(state.endpoint), size = 13, lineHeight = 18, color = c.mutedForeground, maxLines = 1)
        }
        DeskIcon("chevron-right", size = 16.dp, tint = c.mutedForeground)
    }
    SSection("工作台") {
        SRow("连接设置", statusLabels[state.status] + if (state.endpoint.isNotEmpty()) " · ${state.endpoint}" else "", "link-2", onClick = { onNavigate("connection") })
        SRow("账号与用量", icon = "chart-column", onClick = { onNavigate("usage") })
        SRow("访问管理", icon = "shield-check", last = true, onClick = { onNavigate("access") })
    }
    }
    SSection("通知与支持") {
        val unread = state.notices.count { !it.read }
        SRow("通知中心", if (unread > 0) "$unread 条未读" else null, "bell", onClick = { onNavigate("notifications") })
        SRow("使用帮助", icon = "circle-help", last = state.version.isNullOrBlank(), onClick = { onNavigate("help") })
        if (!state.version.isNullOrBlank()) SRow("工作台服务版本", state.version, "info", last = true)
    }
    if (state.authenticated) SSection {
        SRow("退出当前登录", icon = "log-out", danger = true, last = !confirm, onClick = { confirm = true })
        if (confirm) Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SHint(if (state.endpoint.startsWith("wss:")) "退出后需要重新输入服务凭据。" else "退出后需要重新输入工作台密码。")
            SHint(action.error, true)
            SAction("退出登录", busy = action.busy) { action.run { repo.logout(); onNavigate("connection") } }
            SAction("取消", secondary = true) { confirm = false }
        }
    }
}

private fun normalizeAddress(raw: String, direct: Boolean): String {
    return if (direct) normalizeServiceAddress(raw) else normalizeEndpoint(raw)
}

private fun originOf(raw: String, direct: Boolean): String = runCatching { URI(normalizeAddress(raw, direct)).let { "https://${it.host.lowercase()}:${if (it.port == -1) 443 else it.port}" } }.getOrDefault("")
private fun normalizedPin(raw: String): String {
    return normalizeFingerprint(raw)
}

@Composable private fun AccessContent(state: WorkbenchState, repo: WorkbenchRepository, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val c = LocalDeskColors.current
    var edit by remember { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf<JSONObject?>(null) }
    var path by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var enrollment by remember { mutableStateOf<JSONObject?>(null) }
    var loadError by remember { mutableStateOf("") }
    val action = rememberAction()
    val settings = state.webSettings
    val direct = state.endpoint.startsWith("wss:")
    val start: (String?, JSONObject?) -> Unit = { kind, nextDevice ->
        action.error = ""; password = ""; code = ""; enrollment = null; path = settings.text("path"); device = nextDevice; edit = kind
    }
    LaunchedEffect(state.status) {
        if (state.status == "online") {
            loadError = ""
            try { repo.command("web.settings.get") } catch (e: CancellationException) { throw e } catch (e: Exception) { loadError = e.message.orEmpty() }
        }
    }
    if (!state.authenticated || state.status != "online") { ConnectionRequired(onNavigate); return }
    val actionLabel = when (edit) { "path" -> "更改入口路径"; "disable" -> "关闭二次验证"; "revoke" -> "撤销设备登录"; else -> if (enrollment != null) "启用二次验证" else "添加身份验证器" }
    if (edit != null) {
        SSection(actionLabel) {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (edit == "path") {
                    SField("入口路径", path, { path = it }, "留空使用根路径", maxLength = 64)
                    SHint("更改路径会退出所有设备。请将新地址提供给其他设备。")
                }
                if (edit == "disable") SHint("关闭后，其他设备会退出登录。")
                if (edit == "revoke") SHint("「${device.text("name")}」将需要重新登录。")
                if (enrollment != null) {
                    SHint("在身份验证器中添加基于时间的账号，输入下方密钥，然后填写生成的动态码。")
                    SelectionContainer { androidx.compose.material3.Text(enrollment.text("secret"), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.muted).padding(12.dp), color = c.foreground, fontSize = 16.sp, fontFamily = FontFamily.Monospace) }
                    SHint("密钥有效至 ${dateTime(enrollment.text("expiresAt"))}。")
                    if (enrollment.text("uri").startsWith("otpauth://totp/")) SAction("在验证器中打开", secondary = true) {
                        action.run {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(enrollment.text("uri")))) }
                            catch (e: Exception) { throw IllegalStateException("未找到可打开的身份验证器，请手动输入密钥。", e) }
                        }
                    }
                } else if (!direct) SField("当前工作台密码", password, { password = it }, secure = true)
                if (enrollment != null || (!direct && settings?.optBoolean("totpEnabled") == true)) SField("六位动态码", code, { code = it }, numeric = true, maxLength = 6)
                SHint(action.error, true)
                val canSubmit = if (enrollment != null) code.length == 6 else (direct || (password.isNotEmpty() && (settings?.optBoolean("totpEnabled") != true || code.length == 6))) && (edit != "path" || path.trim() != settings.text("path"))
                SAction(actionLabel, busy = action.busy, enabled = canSubmit) {
                    action.run {
                        if (enrollment != null) {
                            require(code.matches(Regex("\\d{6}"))) { "请输入身份验证器中的六位动态码。" }
                            repo.command("web.totp.enable", fields("code" to code))
                        } else {
                            val proof = if (direct) null else fields("password" to password, "code" to code.takeIf { settings?.optBoolean("totpEnabled") == true })
                            when (edit) {
                                "setup" -> {
                                    val result = repo.command("web.totp.setup", fields("proof" to proof))
                                    require(result.text("secret").isNotEmpty() && result.text("uri").isNotEmpty()) { "工作台未返回验证器密钥，请重试。" }
                                    password = ""; code = ""; enrollment = result
                                    return@run
                                }
                                "disable" -> repo.command("web.totp.disable", fields("proof" to proof))
                                "revoke" -> repo.command("web.sessions.revoke", fields("sessionId" to device.text("id"), "proof" to proof))
                                "path" -> {
                                    val value = path.trim()
                                    require(value.length <= 64 && value.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)?")) && value !in listOf("ws", "health", "client", "assets", "api")) { "使用小写字母、数字和中间连字符，避开服务端保留路径。" }
                                    repo.command("web.settings.update", fields("values" to fields("path" to value), "proof" to proof))
                                    if (!direct) {
                                        val old = URI(state.endpoint)
                                        repo.setEndpoint("https://${old.rawAuthority}/" + if (value.isEmpty()) "" else "$value/")
                                        onNavigate("connection")
                                    }
                                }
                            }
                        }
                        start(null, null)
                    }
                }
                SAction("取消", secondary = true, enabled = !action.busy) { start(null, null) }
            }
        }
    } else {
        if (loadError.isNotEmpty()) SSection {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SHint(loadError, true)
                SAction("重试", secondary = true, busy = action.busy) { action.run { repo.command("web.settings.get"); loadError = "" } }
            }
        }
        if (settings == null && loadError.isEmpty()) SHint("正在读取访问设置…")
        if (settings != null) {
            SSection("登录保护") {
                SRow("身份验证器", if (settings.optBoolean("totpEnabled")) "已启用 · 点按关闭二次验证" else "未启用 · 点按设置二次验证", "fingerprint", onClick = { start(if (settings.optBoolean("totpEnabled")) "disable" else "setup", null) })
                SRow("空闲退出时间", "${settings.optInt("idleMinutes")} 分钟", "key-round", last = true)
            }
            SSection("访问地址") {
                SRow("入口路径", settings.text("path").let { if (it.isEmpty()) "/" else "/$it/" }, "globe", onClick = { start("path", null) })
                SRow("HTTPS 地址", settings.text("url").ifBlank { if (direct) "浏览器入口尚未公布" else state.endpoint }, last = true)
            }
            val devices = settings.objects("sessions")
            SSection("登录设备（${devices.size}）") {
                if (devices.isEmpty()) SRow("没有活动设备", last = true)
                devices.forEachIndexed { index, item ->
                    val own = item.text("id") == state.webSessionId
                    SRow(item.text("name") + if (own) " · 当前设备" else "", "最后活动 ${dateTime(item.text("lastActiveAt"))} · 登录到期 ${dateTime(item.text("expiresAt"))}", "monitor-smartphone", last = index == devices.lastIndex, onClick = if (own) null else ({ start("revoke", item) }))
                }
            }
            SSection { SRow("刷新设备列表", icon = "refresh-cw", last = true, onClick = { action.run { repo.command("web.settings.get") } }) }
        }
        SHint(action.error, true)
    }
}

@Composable private fun SOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalDeskColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.alpha(if (pressed) .75f else 1f).clip(CircleShape).background(if (selected) c.foreground else c.muted).clickable(interactionSource = interaction, indication = null, onClick = onClick).semantics { this.selected = selected; role = Role.Button }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        DeskText(label, size = 14, color = if (selected) c.background else c.foreground, weight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable private fun UsageContent(state: WorkbenchState, repo: WorkbenchRepository, onNavigate: (String) -> Unit) {
    val c = LocalDeskColors.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableIntStateOf(7) }
    var model by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var page by remember(state.endpoint) { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var accountError by remember { mutableStateOf("") }
    var summaryError by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var generation by remember { mutableIntStateOf(0) }
    fun filters(offset: Int) = fields("from" to from, "to" to to, "limit" to 25, "offset" to offset, "offsetMinutes" to (ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60), "model" to model.takeIf { it.isNotEmpty() })
    LaunchedEffect(state.endpoint, state.status, revision) {
        if (state.status != "online") return@LaunchedEffect
        accountError = ""; summaryError = ""
        launch { try { repo.command("account.status") } catch (e: CancellationException) { throw e } catch (e: Exception) { accountError = e.message.orEmpty() } }
        launch { try { repo.command("usage.summary") } catch (e: CancellationException) { throw e } catch (e: Exception) { summaryError = e.message.orEmpty() } }
    }
    DisposableEffect(repo, state.endpoint, state.status) {
        if (state.status == "online") repo.setQuotaWatching(true)
        onDispose { repo.setQuotaWatching(false) }
    }
    LaunchedEffect(state.endpoint, state.status, days, model, revision) {
        val ticket = ++generation
        page = null; error = ""
        if (state.status != "online") { busy = false; return@LaunchedEffect }
        val now = Instant.now()
        from = now.minusSeconds(days * 86400L).toString(); to = now.toString()
        busy = true
        try { val result = repo.command("usage.query", filters(0)); if (ticket == generation) page = result }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (ticket == generation) error = e.message ?: "读取用量失败。" }
        finally { if (ticket == generation) busy = false }
    }
    if (state.status != "online") { ConnectionRequired(onNavigate); return }
    SSection("模型账号") {
        val account = state.account
        val hasNotice = account.text("notice").isNotEmpty() || accountError.isNotEmpty()
        SRow(account.text("email").ifBlank { if (account?.optBoolean("loggedIn") == true) "已登录" else if (account != null) "工作台未登录模型账号" else "正在读取账号…" }, listOf(account.text("organization"), account.text("subscriptionType"), account.text("authMethod")).filter { it.isNotEmpty() }.joinToString(" · "), "circle-user", last = account.text("loginExpiresAt").isEmpty() && !hasNotice)
        if (account.text("loginExpiresAt").isNotEmpty()) SRow("登录有效至", dateTime(account.text("loginExpiresAt")), last = !hasNotice)
        if (account.text("notice").isNotEmpty() || accountError.isNotEmpty()) Column(Modifier.padding(vertical = 12.dp)) { SHint(account.text("notice")); SHint(accountError, true) }
    }
    SSection("配额") {
        val names = mapOf("five_hour" to "5 小时配额", "seven_day" to "7 天配额", "seven_day_sonnet" to "Sonnet 周配额", "seven_day_opus" to "Opus 周配额")
        val windows = state.usageSummary.objects("windows")
        windows.forEachIndexed { index, window ->
            SRow(names[window.text("name")] ?: window.text("name"), "已用 " + (if (window.isNull("utilization")) "—" else number(window.optDouble("utilization")) + "%") + if (window.text("resetsAt").isNotEmpty()) " · ${dateTime(window.text("resetsAt"))} 重置" else "", "gauge", last = index == windows.lastIndex)
        }
        if (windows.isEmpty()) SRow(summaryError.ifBlank { if (state.usageSummary != null) "暂无配额信息" else "正在读取配额…" }, last = true)
    }
    SSection("用量明细") {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "24 小时", 7 to "7 天", 30 to "30 天").forEach { (d, label) -> SOption(label, days == d) { days = d; model = "" } }
            }
            if (model.isNotEmpty() || page.strings("models").isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SOption("全部模型", model.isEmpty()) { model = "" }
                page.strings("models").ifEmpty { listOf(model).filter(String::isNotEmpty) }.forEach { name -> SOption(name, model == name) { model = name } }
            }
            SHint(error, true)
            if (busy && page == null) SHint("正在读取请求记录…")
            page?.optJSONObject("totals")?.let { totals ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.muted).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SNativeText("${number(totals.optLong("requests"))} 次请求 · ${money(totals.optDouble("costUsd", 0.0))}", size = 14, lineHeight = 20, weight = FontWeight.Medium)
                    SHint("输入 ${number(totals.optLong("inputTokens"))} · 输出 ${number(totals.optLong("outputTokens"))} Token")
                    SHint("缓存读取 ${number(totals.optLong("cacheReadTokens"))} · 写入 ${number(totals.optLong("cacheCreationTokens"))} Token")
                }
            }
        }
        if (page != null) SDivider()
        page.objects("rows").forEachIndexed { index, entry ->
            SRow(entry.text("sessionTitle").ifBlank { entry.text("model") }, "${dateTime(entry.text("at"))} · ${entry.text("model")}\n输入 ${number(entry.optLong("inputTokens"))} · 输出 ${number(entry.optLong("outputTokens"))} · ${money(entry.optDouble("costUsd", 0.0))}", last = index == page.objects("rows").lastIndex && page?.optBoolean("hasMore") != true)
        }
        if (page != null && page.objects("rows").isEmpty()) SRow("这个时间范围内没有请求记录", last = true)
        if (page?.optBoolean("hasMore") == true) Column(Modifier.padding(vertical = 12.dp)) {
            SAction("加载更多", secondary = true, busy = busy) {
                if (!busy) {
                    busy = true
                    error = ""
                    val ticket = generation
                    val previous = page.objects("rows")
                    scope.launch {
                        try {
                            val result = repo.command("usage.query", filters(previous.size))
                            if (ticket == generation) page = JSONObject(result.toString()).put("rows", JSONArray(previous + result.objects("rows")))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (ticket == generation) error = e.message ?: "读取用量失败。" }
                        finally { if (ticket == generation) busy = false }
                    }
                }
            }
        }
    }
    SAction("刷新用量", secondary = true, busy = busy) { revision++ }
}

@Composable private fun NotificationContent(state: WorkbenchState, repo: WorkbenchRepository, onClose: () -> Unit) {
    val c = LocalDeskColors.current
    val action = rememberAction()
    if (state.notices.any { !it.read }) SAction("全部标为已读", secondary = true) { repo.readNotice() }
    SHint(action.error, true)
    SSection {
        if (state.notices.isEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.clip(CircleShape).background(c.muted).padding(16.dp)) { DeskIcon("bell", size = 24.dp, tint = c.mutedForeground) }
            SNativeText("暂无通知", size = 17, weight = FontWeight.Medium)
            SHint("会话需要审批、回答问题或结束运行时，可在这里查看。")
        } else state.notices.forEachIndexed { index, n ->
            key(n.id) {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                SecondaryFadeContent(true) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).animateContentSize(tween(180, easing = secondaryTiming)).background(if (pressed) c.muted else Color.Transparent).clickable(enabled = !action.busy, interactionSource = interaction, indication = null) {
                        action.run { if (n.sessionId.isNotEmpty()) repo.selectSession(n.sessionId); repo.readNotice(n.id); if (n.sessionId.isNotEmpty()) onClose() }
                    }.semantics { contentDescription = "${if (n.read) "已读" else "未读"}，${n.title}，${n.body}"; role = Role.Button }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (n.read) c.muted else c.foreground))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SNativeText(n.title, size = 14, lineHeight = 20, weight = FontWeight.Medium)
                            if (n.body.isNotEmpty()) SNativeText(n.body, size = 13, lineHeight = 18, color = c.mutedForeground)
                            SNativeText(dateTime(n.at), size = 12, lineHeight = 16, color = c.mutedForeground)
                        }
                        if (n.sessionId.isNotEmpty()) DeskIcon("chevron-right", size = 16.dp, tint = c.mutedForeground)
                    }
                }
                if (index != state.notices.lastIndex) SDivider()
            }
        }
    }
}

private val helpTopics = listOf(
    "如何使用服务地址直连？" to "打开「连接工作台」，填写管理员提供的 WSS 服务地址（例如 wss://host:port/ws）和服务凭据，使用自签证书时再粘贴服务证书指纹。点「连接」即可进入工作台。地址和指纹都没有改变时，凭据可留空。更新或移除服务证书指纹时，请重新输入凭据。",
    "如何使用浏览器访问入口？" to "在连接设置中选择「浏览器访问」。在桌面端打开「设置 → 浏览器访问」，启用访问并复制完整 HTTPS 地址。填入入口地址，再输入工作台密码。如果启用了二次验证，还要输入身份验证器显示的六位动态码。",
    "连接失败或登录过期怎么办？" to "核对端口和入口路径，确认手机可以访问该 HTTPS 地址。入口路径变更后重新复制地址，登录超时则在连接设置中再次登录。",
    "自签证书的指纹在哪里填写？" to "在「连接工作台」填入服务地址，在「服务证书指纹（可选）」粘贴管理员通过可信渠道提供的 SHA-256 叶证书指纹。常规受信任证书可留空。若入口使用反向代理，填写代理对外提供的证书指纹。证书续期或更换后，从管理员处取得新指纹并重新连接。相同主机和端口的 WSS 与 HTTPS 入口共用该指纹，点击「忘记此地址」会清除它。",
    "如何打开或新建会话？" to "打开会话列表，点选已有会话即可继续对话。点击右上角的加号新建服务端会话。需要改名时，打开对应会话的更多操作。",
    "怎样处理工具审批？" to "会话出现审批请求时，先阅读工具名和请求内容，再选择允许或拒绝。通知中心可以带你回到对应的会话。",
    "如何引用项目文件？" to "在聊天中引用在线项目目录中的文件，让模型读取项目上下文。当前工作台协议支持项目文件引用，手机本地文件上传尚无对应接口。",
    "如何管理登录设备？" to "进入「设置 → 访问管理」查看已登录设备和最后活动时间。可撤销其他设备，或为工作台启用身份验证器动态码。域名、证书和访问开关由桌面端管理。",
    "在哪里查看模型用量？" to "打开「设置 → 账号与用量」查看登录身份、配额窗口及工作台记录的请求。成本采用服务端报告的 API 价格估算。"
)

@Composable private fun HelpContent(onNavigate: (String) -> Unit) {
    val c = LocalDeskColors.current
    var open by remember { mutableStateOf<Int?>(0) }
    SSection("常见问题") {
        helpTopics.forEachIndexed { index, (question, answer) ->
            val interaction = remember(index) { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Column(Modifier.animateContentSize(tween(180, easing = secondaryTiming))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (pressed) c.muted else Color.Transparent).clickable(interactionSource = interaction, indication = null) { open = if (open == index) null else index }.semantics { stateDescription = if (open == index) "展开" else "收起"; role = Role.Button }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SNativeText(question, Modifier.weight(1f), size = 15, lineHeight = 21)
                    DeskIcon(if (open == index) "chevron-up" else "chevron-down", size = 16.dp, tint = c.mutedForeground)
                }
                SecondaryFadeContent(open == index) {
                    SNativeText(answer, Modifier.padding(bottom = 16.dp), size = 14, lineHeight = 23, color = c.mutedForeground, selectable = true)
                }
                if (index != helpTopics.lastIndex) SDivider()
            }
        }
    }
    SAction("打开连接设置", secondary = true) { onNavigate("connection") }
}

/** Baseline entries fade in; removal has no exit fade. Layout changes animate separately. */
@Composable internal fun SecondaryFadeContent(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    AnimatedVisibility(visibility, modifier, enter = fadeIn(tween(160, easing = secondaryTiming)), exit = ExitTransition.None) { content() }
}

/** Full screen session library (src/app/chats.tsx), kept separate from the settings overlay. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(repository: WorkbenchRepository, onNavigate: (String) -> Unit, onClose: () -> Unit, onOpenDrawer: () -> Unit) {
    val state by repository.state.collectAsState()
    val c = LocalDeskColors.current
    val action = rememberAction()
    var query by remember { mutableStateOf("") }
    var pinnedOnly by remember { mutableStateOf(false) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("menu") }
    var title by remember { mutableStateOf("") }
    val online = state.status == "online"
    val loading = state.status == "loading" || state.status == "connecting"
    val current = state.sessions.find { it.id == activeId }
    val groups = remember(state.sessions, state.pins, state.serverDeviceId, query, pinnedOnly) {
        val search = query.trim().lowercase()
        state.sessions.sortedWith(compareByDescending<dev.ccdesk.nativeapp.core.Session> { it.id in state.pins }.thenByDescending { it.updatedAt }).filter { session ->
            val server = session.device != null && session.device.id == state.serverDeviceId
            val device = if (server) "服务端" else session.device?.name ?: "未标记设备"
            val path = if (server) "" else session.projectPath
            val project = path.split(Regex("[/\\\\]+")).filter(String::isNotEmpty).lastOrNull() ?: "普通会话"
            (!pinnedOnly || session.id in state.pins) && (search.isEmpty() || listOf(session.title, device, path, project).joinToString(" ").lowercase().contains(search))
        }.groupBy { session -> (session.device?.id ?: "") to if (session.device != null && session.device.id == state.serverDeviceId) "" else session.projectPath }
    }
    val create: () -> Unit = { if (!online) onNavigate("connection") else action.run { repository.createSession(); onClose() } }
    val closeMenu: () -> Unit = { if (!action.busy) { activeId = null; action.error = "" } }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(c.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 768.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SIconButton("menu", "打开侧栏", onClick = onOpenDrawer)
                androidx.compose.material3.Text("会话", Modifier.weight(1f).semantics { heading() }, fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 32.sp, color = c.foreground)
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (pinnedOnly) c.accent else Color.Transparent)) {
                    SIconButton("list-filter", if (pinnedOnly) "显示全部会话" else "仅显示置顶", selected = pinnedOnly) { pinnedOnly = !pinnedOnly }
                }
                SIconButton("plus", "新建会话", enabled = !action.busy, onClick = create)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(c.muted).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DeskIcon("search", size = 16.dp, tint = c.mutedForeground)
                BasicTextField(query, { query = it }, Modifier.weight(1f).semantics { contentDescription = "查找会话" }, textStyle = TextStyle(color = c.foreground, fontSize = 15.sp), cursorBrush = SolidColor(c.foreground), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), decorationBox = { inner -> Box { if (query.isEmpty()) DeskText("查找会话、设备或项目", size = 15, color = c.mutedForeground); inner() } })
                if (query.isNotEmpty()) Box(Modifier.size(32.dp).clickable { query = "" }.semantics { contentDescription = "清除搜索"; role = Role.Button }, contentAlignment = Alignment.Center) { DeskIcon("x", size = 16.dp, tint = c.mutedForeground) }
            }
            if (action.error.isNotEmpty() && current == null) Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { SHint(action.error, true) }
            if (!online && state.sessions.isNotEmpty()) Box(Modifier.fillMaxWidth().clickable { onNavigate("connection") }.padding(horizontal = 24.dp, vertical = 8.dp)) { DeskText(if (loading) "正在连接工作台…" else "连接已断开 · 查看连接", size = 14, color = c.mutedForeground) }
            if (groups.isEmpty()) {
                val noResults = query.isNotBlank() || pinnedOnly
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    if (loading) SBusy() else DeskIcon(if (noResults) "search" else "message-square", size = 32.dp, tint = c.mutedForeground)
                    SNativeText(if (noResults) "未找到会话" else if (loading) "正在连接工作台" else if (online) "暂无会话" else "尚未连接工作台", size = 16, lineHeight = 24, weight = FontWeight.Medium)
                    if (!loading) Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp)).background(c.card).clickable { if (noResults) { query = ""; pinnedOnly = false } else create() }.padding(horizontal = 16.dp, vertical = 12.dp)) { DeskText(if (noResults) "查看全部" else if (online) "新建会话" else "连接工作台", size = 14, weight = FontWeight.Medium) }
                }
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                groups.forEach { (group, sessions) ->
                    item(key = "group:${group.first}:${group.second}") {
                        val device = if (group.first == state.serverDeviceId) "服务端" else sessions.first().device?.name ?: "未标记设备"
                        val project = group.second.split(Regex("[/\\\\]+")).filter(String::isNotEmpty).lastOrNull() ?: "普通会话"
                        SNativeText("$device · $project", Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp), size = 13, color = c.mutedForeground, weight = FontWeight.Medium, maxLines = 2)
                    }
                    items(sessions, key = { it.id }) { session ->
                        val connected = state.devices.find { it.id == session.device?.id }
                        val deviceStatus = if (!online) "连接中断" else if (connected?.ready == true) "在线" else if (connected != null) "连接中" else "离线"
                        val menu: () -> Unit = { action.error = ""; title = session.title; activeId = session.id; mode = "menu" }
                        val interaction = remember(session.id) { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(8.dp)).background(if (pressed) c.accent else Color.Transparent).combinedClickable(interactionSource = interaction, indication = null, onClick = { action.run { repository.selectSession(session.id); onClose() } }, onLongClick = menu).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (session.id in state.pins) DeskIcon("pin", size = 16.dp, tint = c.mutedForeground)
                                    Column(Modifier.weight(1f)) {
                                        SNativeText(session.title, size = 15, lineHeight = 21, maxLines = 1)
                                        SNativeText("$deviceStatus · ${date(session.updatedAt)}", size = 13, lineHeight = 19, color = c.mutedForeground)
                                    }
                                }
                                SIconButton("ellipsis", session.title + "的操作", onClick = menu)
                            }
                            SDivider()
                        }
                    }
                }
            }
        }
    }
    if (current != null) Dialog(onDismissRequest = closeMenu, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).imePadding()) {
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = closeMenu))
            val wide = maxWidth >= 640.dp
            Column(Modifier.align(if (wide) Alignment.Center else Alignment.BottomCenter).widthIn(max = if (wide) 448.dp else maxWidth).fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (wide) 16.dp else 0.dp, bottomEnd = if (wide) 16.dp else 0.dp)).border(1.dp, c.border, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(c.card).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.border))
                SNativeText(if (mode == "rename") "重命名会话" else if (mode == "delete") "删除「${current.title}」？" else current.title, Modifier.padding(bottom = 12.dp), size = 18, lineHeight = 28, weight = FontWeight.SemiBold, maxLines = 2)
                if (mode == "menu") {
                    SMenuAction(if (current.id in state.pins) "取消置顶" else "置顶会话") { repository.togglePin(current.id); closeMenu() }
                    SMenuAction("重命名", online) { mode = "rename" }
                    SMenuAction("删除会话", online) { mode = "delete" }
                } else {
                    if (mode == "rename") {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        BasicTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.muted).padding(horizontal = 16.dp, vertical = 12.dp).focusRequester(focus).semantics { contentDescription = "会话名称" }, singleLine = true, textStyle = TextStyle(color = c.foreground, fontSize = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = true)), cursorBrush = SolidColor(c.foreground))
                    }
                    else DeskText("此会话的服务端记录将被删除。", Modifier.padding(bottom = 12.dp), color = c.mutedForeground)
                    SMenuAction(if (action.busy) "正在保存…" else if (mode == "rename") "保存名称" else "确认删除", !action.busy && online && (mode != "rename" || title.isNotBlank())) {
                        action.run {
                            if (mode == "rename") repository.command("session.rename", fields("sessionId" to current.id, "title" to title.trim()))
                            else repository.command("session.delete", fields("sessionId" to current.id))
                            activeId = null
                        }
                    }
                }
                if (action.error.isNotEmpty()) Box(Modifier.padding(vertical = 8.dp)) { SHint(action.error, true) }
                SMenuAction("取消", !action.busy, closeMenu)
            }
        }
    }
}

@Composable private fun SIconButton(name: String, label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val c = LocalDeskColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(if (pressed) c.muted else Color.Transparent).alpha(if (enabled) 1f else .4f).clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick).semantics { contentDescription = label; this.selected = selected; role = Role.Button }, contentAlignment = Alignment.Center) {
        DeskIcon(name, size = if (name == "list-filter") 18.dp else 20.dp, strokeWidth = 1.65f)
    }
}

@Composable private fun SMenuAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (pressed) LocalDeskColors.current.muted else Color.Transparent).alpha(if (enabled) 1f else .4f).clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 12.dp, vertical = 14.dp)) { SNativeText(label, size = 16, lineHeight = 24) }
}

@Composable private fun ConnectionContent(state: WorkbenchState, repo: WorkbenchRepository, onNavigate: (String) -> Unit, onClose: () -> Unit) {
    var direct by remember { mutableStateOf(state.endpoint.isBlank() || state.endpoint.startsWith("wss:")) }
    var service by remember { mutableStateOf(state.endpoint.takeIf { it.startsWith("wss:") }.orEmpty()) }
    var browser by remember { mutableStateOf(state.endpoint.takeIf { it.startsWith("https:") }.orEmpty()) }
    val address = if (direct) service else browser
    val savedDirect = state.endpoint.startsWith("wss:")
    var credential by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var changingAddress by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    var enterWhenReady by remember { mutableStateOf(false) }
    val action = rememberAction()
    val origin = originOf(address, direct)
    var pin by remember { mutableStateOf("") }
    var savedPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    var pinOrigin by remember { mutableStateOf("") }
    var pinPending by remember { mutableStateOf(false) }
    var pinRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(origin, pinRevision) {
        pin = ""; savedPin = ""; pinError = ""; pinOrigin = ""
        if (origin.isBlank()) { pinPending = false; return@LaunchedEffect }
        pinPending = true
        try { val loaded = repo.fingerprint(address); savedPin = loaded; pin = loaded; pinOrigin = origin }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { pinError = e.message ?: "读取已保存的证书指纹失败。" }
        finally { pinPending = false }
    }
    LaunchedEffect(state.status, enterWhenReady) { if (enterWhenReady && state.status == "online") { enterWhenReady = false; onClose() } }
    val connecting = action.busy || state.status in listOf("loading", "connecting")
    val showLogin = !direct && !savedDirect && state.status == "login" && !changingAddress
    val connect: () -> Unit = {
        action.run {
            val value = normalizeAddress(address, direct)
            val token = credential.trim()
            require(!direct || token.isNotEmpty() || value == state.endpoint) { "请输入此服务地址的服务凭据。" }
            require(!direct || token.isEmpty() || token.length in 24..512) { "服务凭据应为 24 至 512 个字符，请从管理员处复制完整凭据。" }
            require(origin.isNotEmpty() && pinOrigin == origin && !pinPending && pinError.isEmpty()) { pinError.ifEmpty { "正在读取此地址的证书指纹。" } }
            val next = normalizedPin(pin)
            val changed = next != savedPin
            if (changed) {
                if (savedDirect && originOf(state.endpoint, true) == origin) {
                    require(!direct || token.isNotEmpty()) { "服务证书指纹已修改，请重新输入服务凭据。" }
                    repo.setEndpoint("")
                }
                repo.setFingerprint(value, next)
                savedPin = next; pin = next
            }
            if (value == state.endpoint && state.status == "online" && !changed && token.isEmpty()) { onClose() }
            else {
                if (value != state.endpoint) { password = ""; code = "" }
                changingAddress = false; enterWhenReady = true
                try { if (direct) { repo.connectService(value, token); credential = "" } else repo.setEndpoint(value) }
                catch (e: Exception) { enterWhenReady = false; throw e }
            }
        }
    }
    val login: () -> Unit = { action.run { enterWhenReady = true; try { repo.login(password, if (state.totpEnabled) code else null); password = ""; code = "" } catch (e: Exception) { enterWhenReady = false; throw e } } }
    SSection {
        SRow(statusLabels[state.status] ?: state.status, state.endpoint.ifBlank { if (direct) "填写管理员提供的服务连接信息。" else "在桌面端的「设置 → 浏览器访问」中复制连接地址。" }, "link-2", last = action.busy)
        if (!action.busy) SRow(if (direct) "浏览器访问" else "使用服务地址直连", if (direct) "HTTPS 入口、密码及动态码" else "WSS 地址、证书指纹及服务凭据", last = true, onClick = {
            enterWhenReady = false; credential = ""; password = ""; code = ""; forget = false; action.error = ""; changingAddress = false; direct = !direct
        })
    }
    SSection(if (direct) "连接服务端" else if (showLogin) "登录工作台" else "工作台地址") {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showLogin) {
                SField("工作台密码", password, { password = it }, secure = true, enabled = !connecting, imeAction = if (state.totpEnabled) ImeAction.Next else ImeAction.Go, onSubmit = if (state.totpEnabled) null else login)
                if (state.totpEnabled) SField("身份验证器动态码", code, { code = it }, "6 位数字", numeric = true, maxLength = 6, imeAction = ImeAction.Go, onSubmit = login)
                SHint(action.error.ifBlank { state.error.orEmpty() }, true)
                SAction("登录", busy = connecting, enabled = password.isNotEmpty() && (!state.totpEnabled || code.length == 6), onClick = login)
                SAction("修改连接地址", secondary = true) { changingAddress = true; password = ""; code = ""; action.error = "" }
            } else {
                SField(if (direct) "服务地址" else "HTTPS 入口地址", address, { if (it != address) credential = ""; if (direct) service = it else browser = it; action.error = "" }, if (direct) "wss://host:port/ws" else "https://example.com/", enabled = !action.busy, imeAction = if (direct) ImeAction.Next else ImeAction.Go, keyboardType = KeyboardType.Uri, onSubmit = if (direct) null else connect)
                SField("服务证书指纹（可选）", if (pinOrigin == origin) pin else "", { pin = it; action.error = "" }, if (origin.isNotEmpty() && (pinPending || pinOrigin != origin)) "正在读取此地址的指纹…" else if (origin.isEmpty()) "先填写完整地址" else "SHA-256，留空使用系统证书验证", enabled = origin.isNotEmpty() && pinOrigin == origin && !pinPending && pinError.isEmpty() && !action.busy, imeAction = if (direct) ImeAction.Next else ImeAction.Go, onSubmit = if (direct) null else connect)
                SHint("使用自签证书时，粘贴管理员提供的 64 位 SHA-256 叶证书指纹，可含冒号。")
                if (pinError.isNotEmpty()) { SHint(pinError, true); SAction("重新读取指纹", secondary = true) { pinRevision++ } }
                if (direct) SField("服务凭据", credential, { credential = it }, if (state.endpoint == address && savedDirect && pin == savedPin) "留空使用此地址已保存的凭据" else "输入管理员提供的完整服务凭据", secure = true, enabled = !connecting, imeAction = ImeAction.Go, onSubmit = connect)
                SHint(action.error.ifBlank { state.error.orEmpty() }, true)
                SAction(if (state.status == "online" && address.trim() == state.endpoint && credential.isEmpty() && pin == savedPin) "打开聊天" else if (direct) "连接" else "连接工作台", busy = connecting, enabled = address.isNotBlank() && (origin.isEmpty() || pinOrigin == origin) && !pinPending && pinError.isEmpty(), onClick = connect)
            }
        }
    }
    SSection {
        SRow("连接遇到问题？", icon = "circle-help", last = state.endpoint.isEmpty() || savedDirect != direct, onClick = { onNavigate("help") })
        if (state.endpoint.isNotEmpty() && savedDirect == direct) SRow("忘记此地址", danger = true, last = true, onClick = { forget = true })
    }
    if (forget) SSection("忘记此地址？") {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DeskText("下次连接时需要重新输入工作台地址。")
            SHint((if (savedDirect) "下次直连时需要重新输入服务凭据。" else "") + "此主机和端口已保存的证书指纹也会清除。")
            SAction("忘记地址", busy = action.busy) { action.run { enterWhenReady = false; repo.forgetEndpoint(); forget = false; changingAddress = false; service = ""; browser = ""; credential = ""; pin = ""; savedPin = "" } }
            SAction("取消", secondary = true) { forget = false }
        }
    }
}
