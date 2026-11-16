// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.viewinterop.AndroidView
import dev.ccdesk.nativeapp.R
import dev.ccdesk.nativeapp.core.WorkbenchRepository
import dev.ccdesk.nativeapp.core.WorkbenchState
import dev.ccdesk.nativeapp.core.normalizeServiceAddress
import dev.ccdesk.nativeapp.core.normalizeFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private val statusLabels = mapOf("loading" to "正在读取连接信息", "setup" to "等待连接", "connecting" to "正在连接", "online" to "已连接", "offline" to "连接已断开")
private val settingTabs = listOf("settings" to "概览", "connection" to "连接", "usage" to "账号与用量", "access" to "访问管理", "notifications" to "通知", "help" to "帮助")
private fun fields(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { (k, v) -> if (v != null) put(k, v) } }
private fun JSONObject?.text(key: String, fallback: String = ""): String = if (this == null || isNull(key)) fallback else optString(key, fallback)
private fun JSONObject?.objects(key: String): List<JSONObject> = this?.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
private fun JSONObject?.strings(key: String): List<String> = this?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
private fun dateTime(value: String?): String = runCatching { DateTimeFormatter.ofPattern("M/d HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault("—")
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

/** Full-page settings retain the native controls and existing workbench capabilities. */
@Composable
fun SecondaryScreen(route: String, repository: WorkbenchRepository, onNavigate: (String) -> Unit, onClose: () -> Unit, onOpenChat: () -> Unit = onClose, visible: Boolean = true, backEnabled: Boolean = true, onPresentChange: (Boolean) -> Unit = {}) {
    val state by repository.state.collectAsState()
    val document = licenseDocumentFor(route)
    val licenses = route == "licenses" || document != null
    var parents by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(visible) { if (!visible) parents = emptyList() }
    val navigate: (String) -> Unit = { target ->
        if (target != route) {
            parents = if (target in setOf("", "remote")) emptyList() else parents + route
            onNavigate(target)
        }
    }
    val close: () -> Unit = { parents = emptyList(); onClose() }
    val parent = parents.lastOrNull() ?: if (licenses) if (document == null) "help" else "licenses" else null
    val back: (() -> Unit)? = parent?.let { target -> {
        if (parents.isNotEmpty()) parents = parents.dropLast(1)
        onNavigate(target)
    } }
    SettingsPanel(route, titleFor(route), navigate, close, visible,
        selectedTab = if (licenses) "help" else route,
        onBack = back,
        backEnabled = backEnabled,
        onPresentChange = onPresentChange,
        backLabel = parent?.let { "返回${titleFor(it)}" } ?: "返回") { page ->
        key(page, if (page == "access") state.endpoint else "") {
            val pageDocument = licenseDocumentFor(page)
            if (pageDocument != null) LicenseDocumentContent(pageDocument)
            else when (page) {
                "settings" -> SettingsContent(state, repository, navigate)
                "connection" -> ConnectionContent(state, repository, navigate, onOpenChat)
                "usage" -> UsageContent(state, repository, navigate)
                "access" -> AccessContent(state, repository, navigate)
                "notifications" -> NotificationContent(state, repository, onOpenChat)
                "licenses" -> OpenSourceContent(navigate)
                else -> HelpContent(navigate)
            }
        }
    }
}

private fun licenseDocumentFor(route: String) = licenseDocuments.firstOrNull { route == "licenses/${it.id}" }
private fun titleFor(route: String) = licenseDocumentFor(route)?.title ?: when (route) { "settings" -> "设置"; "connection" -> "连接工作台"; "usage" -> "账号与用量"; "access" -> "访问管理"; "notifications" -> "通知"; "licenses" -> "开源许可与致谢"; else -> "帮助" }
private fun tabIndex(route: String) = when {
    route == "licenses" -> settingTabs.size
    licenseDocumentFor(route) != null -> settingTabs.size + 1
    else -> settingTabs.indexOfFirst { it.first == route }
}

/** Full-height pages share CCtunnel's quiet header and outlined section cards. */
@Composable
internal fun SettingsPanel(route: String, title: String, onNavigate: (String) -> Unit, onClose: () -> Unit, visible: Boolean = true,
    selectedTab: String = route, onBack: (() -> Unit)? = null, backLabel: String = "返回", backEnabled: Boolean = true, onPresentChange: (Boolean) -> Unit = {}, content: @Composable ColumnScope.(String) -> Unit) {
    val c = LocalDeskColors.current
    val motion = rememberModalMotion(visible)
    SideEffect { onPresentChange(visible || motion.present) }
    if (!visible && !motion.present) return
    val close by rememberUpdatedState(onClose)
    var search by remember { mutableStateOf("") }
    BackHandler(enabled = visible && backEnabled, onBack = onBack ?: onClose)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().testTag(OverlayScrimTag).graphicsLayer { alpha = motion.scrim.value }.background(Color.Black.copy(alpha = DeskMotion.PanelScrim))
            .then(if (visible) Modifier.pointerInput(Unit) { detectTapGestures { close() } }.semantics { contentDescription = "关闭设置"; role = Role.Button; onClick { close(); true } } else BlockPointerInput.clearAndSetSemantics { }))
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 736.dp
            val pageWidth = with(LocalDensity.current) { maxWidth.toPx() }
            val panelModifier = if (wide) Modifier.padding(24.dp).heightIn(max = 900.dp).clip(RoundedCornerShape(28.dp)).border(1.dp, c.border, RoundedCornerShape(28.dp)) else Modifier
            Box(panelModifier.fillMaxHeight().widthIn(max = 672.dp).fillMaxWidth()
                .graphicsLayer { val f = motion.panel.value; alpha = (1f - f).coerceIn(0f, 1f); translationX = pageWidth * .14f * f }.testTag(OverlayPanelTag)
                .background(c.background).then(if (visible) AbsorbPointerInput else BlockPointerInput.clearAndSetSemantics { }).safeDrawingPadding().imePadding()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        DeskIconButton("arrow-left", if (onBack != null) backLabel else "返回", iconSize = 24.dp, onClick = onBack ?: onClose)
                        AnimatedContent(title, Modifier.weight(1f), transitionSpec = { fadeIn(DeskMotion.fadeIn()) togetherWith fadeOut(DeskMotion.fadeOut()) }, label = "settings title") {
                            Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                DeskText("CCtunnel", size = 11, color = c.primary, weight = FontWeight.SemiBold)
                                DeskText(it, Modifier.semantics { heading() }, size = 18, weight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                        Box(Modifier.background(c.card, RoundedCornerShape(14.dp))) {
                            if (route == "settings") DeskIconButton("info", "关于与帮助", iconSize = 24.dp) { onNavigate("help") }
                            else DeskIconButton("x", "关闭", iconSize = 22.dp, onClick = onClose)
                        }
                    }
                    AnimatedContent(route, Modifier.weight(1f).fillMaxWidth(), transitionSpec = {
                        val forward = tabIndex(targetState) >= tabIndex(initialState)
                        (slideInHorizontally(DeskMotion.contentOffset) { if (forward) it / 5 else -it / 5 } + fadeIn(DeskMotion.fadeIn())) togetherWith
                            (slideOutHorizontally(DeskMotion.contentOffset) { if (forward) -it / 5 else it / 5 } + fadeOut(DeskMotion.fadeOut()))
                    }, label = "settings page") { page ->
                        NativeScrollContent(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (page == "settings") 88.dp else 24.dp), spacing = 22.dp) {
                            if (page == "settings" && search.isNotBlank()) SettingsSearchResults(search, onNavigate) else content(page)
                        }
                    }
                }
                if (route == "settings") Row(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeskIcon("search", size = 22.dp, tint = c.mutedForeground)
                    BasicTextField(search, { search = it }, Modifier.weight(1f).semantics { contentDescription = "搜索设置" }, singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.SansSerif, color = c.foreground, fontSize = 16.sp), cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), decorationBox = { inner -> Box { if (search.isEmpty()) DeskText("搜索设置", size = 16, color = c.mutedForeground); inner() } })
                    if (search.isNotEmpty()) DeskIconButton("x", "清除设置搜索", iconSize = 17.dp) { search = "" }
                }
            }
        }
    }
}

@Composable private fun SettingsSearchResults(query: String, onNavigate: (String) -> Unit) {
    val entries = listOf(
        Triple("remote", "远程控制 · 电脑与会话", "电脑 客户端 远程 项目"),
        Triple("connection", "连接设置", "服务 地址 凭据 证书 工作台"),
        Triple("usage", "账号与用量", "账号 配额 额度 模型"),
        Triple("access", "访问管理", "安全 登录 二次验证"),
        Triple("notifications", "通知中心", "通知 未读 提醒"),
        Triple("help", "使用帮助", "帮助 关于 更新"),
        Triple("licenses", "开源许可与致谢", "许可 开源 来源"),
    ).filter { (_, label, keywords) -> "$label $keywords".contains(query.trim(), ignoreCase = true) }
    if (entries.isEmpty()) SNativeText("未找到相关设置", Modifier.padding(16.dp), size = 16, color = LocalDeskColors.current.mutedForeground)
    else SSection { entries.forEachIndexed { index, (route, label, _) -> SRow(label, last = index == entries.lastIndex, accessibilityLabel = "打开设置：$label", onClick = { onNavigate(route) }) } }
}

/** Native scrolling content with fading OUTSIDE_OVERLAY scrollbars. */
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
    val c = LocalDeskColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(3.dp, 14.dp).background(c.primary, RoundedCornerShape(2.dp)))
            SNativeText(title, Modifier.semantics { heading() }, size = 13, lineHeight = 20, color = c.primary, weight = FontWeight.SemiBold, heading = true)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp)) { content() }
    }
}

@Composable private fun SRow(label: String, detail: String? = null, icon: String? = null, danger: Boolean = false, last: Boolean = false, accessibilityLabel: String? = null, onClick: (() -> Unit)? = null) {
    val c = LocalDeskColors.current
    val red = if (c.dark) Color(0xFFFF6467) else Color(0xFFE7000B)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.pressedState()
    val highlight = animatedHighlight(pressed && onClick != null, false, c.muted)
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).background(highlight).then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick).semantics { role = Role.Button; if (accessibilityLabel != null) contentDescription = accessibilityLabel } else Modifier).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Box(Modifier.size(32.dp).background(if (danger) red.copy(alpha = .08f) else c.secondary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            DeskIcon(icon, size = 20.dp, tint = if (danger) red else c.primary, strokeWidth = 1.85f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SNativeText(label, size = 16, lineHeight = 23, color = if (danger) red else c.foreground)
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
        color = if (error) if (c.dark) Color(0xFFFF6467) else Color(0xFFE7000B) else c.mutedForeground)
}

@Composable private fun SAction(label: String, busy: Boolean = false, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    val c = LocalDeskColors.current
    val disabled = !enabled || busy
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.pressedState()
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (disabled) .5f else if (pressed) .8f else 1f, if (pressed) tween(DeskMotion.PressHighlightIn) else DeskMotion.state(), label = "action alpha")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(pressed, !disabled).graphicsLayer { this.alpha = alpha; compositingStrategy = CompositingStrategy.ModulateAlpha }.clip(RoundedCornerShape(14.dp)).background(if (secondary) c.card else c.primary)
        .then(if (secondary) Modifier.border(1.dp, c.border, RoundedCornerShape(14.dp)) else Modifier).clickable(enabled = !disabled, interactionSource = interaction, indication = null, onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(busy, enter = fadeIn(DeskMotion.fadeIn()) + androidx.compose.animation.expandHorizontally(DeskMotion.layoutSize), exit = fadeOut(DeskMotion.fadeOut()) + androidx.compose.animation.shrinkHorizontally(DeskMotion.layoutSize)) { Box(Modifier.padding(end = 8.dp)) { SBusy() } }
        NativeSpanText(label, size = 16, lineHeight = null, weight = 500, color = if (secondary) c.foreground else c.onPrimary, selectable = false)
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, if (focused) c.primary else c.border, RoundedCornerShape(14.dp)).background(c.background).onGloballyPositioned { autofillNode?.boundingBox = it.boundsInWindow() }.padding(1.dp).padding(horizontal = 14.dp, vertical = 12.dp).onFocusChanged {
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
    SSection("工作台") {
    val identityInteraction = remember { MutableInteractionSource() }
    val identityPressed by identityInteraction.pressedState()
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).background(animatedHighlight(identityPressed, false, c.muted)).clickable(interactionSource = identityInteraction, indication = null) { onNavigate(if (state.authenticated) "usage" else "connection") }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent), contentAlignment = Alignment.Center) { DeskIcon("circle-user", size = 24.dp, tint = c.primary, strokeWidth = 1.7f) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SNativeText(state.account.text("email").ifBlank { if (state.authenticated) "工作台账号" else "账户" }, size = 16, lineHeight = 23, weight = FontWeight.Normal, maxLines = 1)
            if (state.endpoint.isNotEmpty()) SNativeText(runCatching { URI(state.endpoint).authority }.getOrDefault(state.endpoint), size = 13, lineHeight = 18, color = c.mutedForeground, maxLines = 1)
        }
        DeskIcon("chevron-right", size = 16.dp, tint = c.mutedForeground)
    }
    SDivider()
    SRow("远程控制", "电脑客户端与会话", "remote", last = true, onClick = { onNavigate("remote") })
    }
    SSection("连接与管理") {
        SRow("连接设置", icon = "link-2", accessibilityLabel = "打开连接设置", onClick = { onNavigate("connection") })
        SRow("账号与用量", icon = "chart-column", onClick = { onNavigate("usage") })
        SRow("访问管理", icon = "shield-check", last = true, onClick = { onNavigate("access") })
    }
    SSection("通知与支持") {
        val unread = state.notices.count { !it.read }
        SRow("通知中心", if (unread > 0) "$unread 条未读" else null, "bell", onClick = { onNavigate("notifications") })
        SRow("使用帮助", icon = "circle-help", last = state.version.isNullOrBlank(), onClick = { onNavigate("help") })
        if (!state.version.isNullOrBlank()) SRow("工作台服务版本", state.version, "info", last = true)
    }
    AppUpdateSettings()
    if (state.authenticated) SSection {
        SRow("退出当前登录", icon = "log-out", danger = true, last = !confirm, onClick = { confirm = true })
        DeskExpandable(confirm) { Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SHint("退出后需要重新输入服务凭据。")
            SHint(action.error, true)
            SAction("退出登录", busy = action.busy) { action.run { repo.logout(); onNavigate("connection") } }
            SAction("取消", secondary = true) { confirm = false }
        } }
    }
}

private fun originOf(raw: String): String = runCatching { URI(normalizeServiceAddress(raw)).let { "wss://${it.host.lowercase()}:${if (it.port == -1) 443 else it.port}" } }.getOrDefault("")
private fun normalizedPin(raw: String): String {
    return normalizeFingerprint(raw)
}

@Composable private fun AccessContent(state: WorkbenchState, repo: WorkbenchRepository, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val c = LocalDeskColors.current
    var edit by remember { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf<JSONObject?>(null) }
    var path by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var enrollment by remember { mutableStateOf<JSONObject?>(null) }
    var loadError by remember { mutableStateOf("") }
    val action = rememberAction()
    val settings = state.webSettings
    val start: (String?, JSONObject?) -> Unit = { kind, nextDevice ->
        action.error = ""; code = ""; enrollment = null; path = settings.text("path"); device = nextDevice; edit = kind
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
                    SHint("更改路径会退出所有浏览器登录。请将新地址提供给浏览器用户。")
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
                }
                if (enrollment != null) SField("六位动态码", code, { code = it }, numeric = true, maxLength = 6)
                SHint(action.error, true)
                val canSubmit = if (enrollment != null) code.length == 6 else edit != "path" || path.trim() != settings.text("path")
                SAction(actionLabel, busy = action.busy, enabled = canSubmit) {
                    action.run {
                        if (enrollment != null) {
                            require(code.matches(Regex("\\d{6}"))) { "请输入身份验证器中的六位动态码。" }
                            repo.command("web.totp.enable", fields("code" to code))
                        } else {
                            when (edit) {
                                "setup" -> {
                                    val result = repo.command("web.totp.setup")
                                    require(result.text("secret").isNotEmpty() && result.text("uri").isNotEmpty()) { "工作台未返回验证器密钥，请重试。" }
                                    code = ""; enrollment = result
                                    return@run
                                }
                                "disable" -> repo.command("web.totp.disable")
                                "revoke" -> repo.command("web.sessions.revoke", fields("sessionId" to device.text("id")))
                                "path" -> {
                                    val value = path.trim()
                                    require(value.length <= 64 && value.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)?")) && value !in listOf("ws", "health", "client", "assets", "api")) { "使用小写字母、数字和中间连字符，避开服务端保留路径。" }
                                    repo.command("web.settings.update", fields("values" to fields("path" to value)))
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
            SHint("管理服务端的浏览器访问；手机使用服务凭据直连。")
            SSection("浏览器登录保护") {
                SRow("身份验证器", if (settings.optBoolean("totpEnabled")) "已启用 · 点按关闭二次验证" else "未启用 · 点按设置二次验证", "fingerprint", onClick = { start(if (settings.optBoolean("totpEnabled")) "disable" else "setup", null) })
                SRow("空闲退出时间", "${settings.optInt("idleMinutes")} 分钟", "key-round", last = true)
            }
            SSection("访问地址") {
                SRow("入口路径", settings.text("path").let { if (it.isEmpty()) "/" else "/$it/" }, "globe", onClick = { start("path", null) })
                SRow("HTTPS 地址", settings.text("url").ifBlank { "浏览器入口尚未公布" }, last = true)
            }
            val devices = settings.objects("sessions")
            SSection("浏览器登录设备（${devices.size}）") {
                if (devices.isEmpty()) SRow("没有活动设备", last = true)
                devices.forEachIndexed { index, item ->
                    SRow(item.text("name"), "最后活动 ${dateTime(item.text("lastActiveAt"))} · 登录到期 ${dateTime(item.text("expiresAt"))}", "monitor-smartphone", last = index == devices.lastIndex, onClick = { start("revoke", item) })
                }
            }
            SSection { SRow("刷新设备列表", icon = "refresh-cw", last = true, onClick = { action.run { repo.command("web.settings.get") } }) }
        }
        SHint(action.error, true)
    }
}

@Composable private fun SOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalDeskColors.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.pressedState()
    val background by animateColorAsState(if (selected || pressed) c.accent else c.muted, DeskMotion.highlight(pressed), label = "option background")
    val text by animateColorAsState(if (selected) c.primary else c.foreground, DeskMotion.state(), label = "option text")
    Box(Modifier.heightIn(min = 48.dp).pressScale(pressed, true, DeskMotion.ChipPressScale).clip(RoundedCornerShape(12.dp)).background(background).border(1.dp, if (selected) c.primary.copy(alpha = .4f) else Color.Transparent, RoundedCornerShape(12.dp)).clickable(interactionSource = interaction, indication = null) { if (!selected) haptics.selection(); onClick() }.semantics { this.selected = selected; role = Role.Button }.padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        DeskText(label, size = 14, color = text, weight = if (selected) FontWeight.Medium else FontWeight.Normal)
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
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.secondary).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SNativeText("估算费用", size = 12, lineHeight = 18, color = c.mutedForeground)
                    SNativeText(money(totals.optDouble("costUsd", 0.0)), size = 26, lineHeight = 34, weight = FontWeight.SemiBold, color = c.primary)
                    SNativeText("${number(totals.optLong("requests"))} 次请求", size = 14, lineHeight = 20, weight = FontWeight.Medium)
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
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.accent).padding(16.dp)) { DeskIcon("bell", size = 24.dp, tint = c.primary) }
            SNativeText("暂无通知", size = 17, weight = FontWeight.Medium)
            SHint("会话需要审批、回答问题或结束运行时，可在这里查看。")
        } else state.notices.forEachIndexed { index, n ->
            key(n.id) {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.pressedState()
                SecondaryFadeContent(true) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).animateContentSize(DeskMotion.layoutSize).background(animatedHighlight(pressed, false, c.muted)).clickable(enabled = !action.busy, interactionSource = interaction, indication = null) {
                        action.run { if (n.sessionId.isNotEmpty()) repo.selectSession(n.sessionId); repo.readNotice(n.id); if (n.sessionId.isNotEmpty()) onClose() }
                    }.semantics { contentDescription = "${if (n.read) "已读" else "未读"}，${n.title}，${n.body}"; role = Role.Button }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val dot by animateColorAsState(if (n.read) c.muted else c.primary, DeskMotion.state(), label = "unread dot")
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
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
    "连接失败或凭据失效怎么办？" to "核对 WSS 服务地址、端口和 /ws 路径，确认手机可以访问服务。服务凭据变更后，在连接设置中填写新凭据并重新连接。",
    "自签证书的指纹在哪里填写？" to "在「连接工作台」填入服务地址，在「服务证书指纹（可选）」粘贴管理员通过可信渠道提供的 SHA-256 叶证书指纹。常规受信任证书可留空。若入口使用反向代理，填写代理对外提供的证书指纹。证书续期或更换后，从管理员处取得新指纹并重新连接。点击「忘记此地址」会清除该主机和端口的指纹。",
    "如何打开或新建会话？" to "电脑会话从侧栏远程控制中按电脑选择；普通会话从侧栏的聊天记录中打开。点击顶部的新建会话按钮进入空白聊天，发送第一条消息后创建服务端会话。",
    "怎样处理工具审批？" to "会话出现审批请求时，先阅读工具名和请求内容，再选择允许或拒绝。通知中心可以带你回到对应的会话。",
    "如何引用项目文件？" to "在聊天中引用在线项目目录中的文件，让模型读取项目上下文。当前工作台协议支持项目文件引用，手机本地文件上传尚无对应接口。",
    "如何管理浏览器访问？" to "进入「设置 → 访问管理」查看服务端的浏览器登录记录、撤销登录或配置浏览器二次验证。这些设置不用于手机登录；手机只使用 WSS 服务地址和服务凭据。域名、证书和访问开关由桌面端管理。",
    "在哪里查看模型用量？" to "打开「设置 → 账号与用量」查看登录身份、配额窗口及工作台记录的请求。成本采用服务端报告的 API 价格估算。"
)

@Composable private fun HelpContent(onNavigate: (String) -> Unit) {
    val c = LocalDeskColors.current
    var open by remember { mutableStateOf<Int?>(0) }
    SSection("常见问题") {
        helpTopics.forEachIndexed { index, (question, answer) ->
            val interaction = remember(index) { MutableInteractionSource() }
            val pressed by interaction.pressedState()
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(animatedHighlight(pressed, false, c.muted)).clickable(interactionSource = interaction, indication = null) { open = if (open == index) null else index }.semantics { stateDescription = if (open == index) "展开" else "收起"; role = Role.Button }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SNativeText(question, Modifier.weight(1f), size = 15, lineHeight = 21)
                    DeskChevron(open == index)
                }
                DeskExpandable(open == index) {
                    SNativeText(answer, Modifier.padding(bottom = 16.dp), size = 14, lineHeight = 23, color = c.mutedForeground, selectable = true)
                }
                if (index != helpTopics.lastIndex) SDivider()
            }
        }
    }
    SAction("打开连接设置", secondary = true) { onNavigate("connection") }
    SAction("开源许可与致谢", secondary = true) { onNavigate("licenses") }
}

private data class LicenseDocument(val id: String, val title: String, val resource: Int)
private val licenseDocuments = listOf(
    LicenseDocument("license", "LICENSE · Apache 2.0", R.raw.app_license),
    LicenseDocument("notice", "NOTICE · 来源与修改说明", R.raw.app_notice),
    LicenseDocument("third-party", "第三方开源许可汇总", R.raw.third_party_notices),
    LicenseDocument("lucide", "Lucide 图标许可", R.raw.lucide_license),
    LicenseDocument("bootstrap", "Bootstrap Icons 许可", R.raw.bootstrap_icons_license),
    LicenseDocument("highlight", "语法高亮规则许可", R.raw.highlight_grammar_license),
    LicenseDocument("text-layout", "文本行高实现许可", R.raw.text_line_height_license),
)

@Composable private fun OpenSourceContent(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    var linkError by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SNativeText("CC Android Tunnel", size = 17, lineHeight = 24, weight = FontWeight.SemiBold, heading = true)
        SNativeText("基于 sun168567/cc-desk-tunnel（Apache License 2.0）开发的独立 Android 客户端。本项目非上游官方发行，与上游项目无隶属或背书关系。", size = 14, lineHeight = 22, selectable = true)
    }
    SSection("项目来源") {
        listOf("上游项目" to "https://github.com/sun168567/cc-desk-tunnel", "本项目仓库" to "https://github.com/tuolola/cc-android-tunnel").forEachIndexed { index, (label, url) ->
            SRow(label, url, "globe", last = index == 1, accessibilityLabel = "打开$label：$url", onClick = {
                linkError = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
            })
        }
    }
    if (linkError) SHint("无法打开浏览器，请检查是否已安装可处理网页链接的应用。", error = true)
    SHint("以下全文随应用打包，无需联网即可阅读、选择和复制。感谢上游作者及各开源项目的贡献。")
    SSection("离线许可全文") {
        licenseDocuments.forEachIndexed { index, document ->
            SRow(document.title, icon = "book-open", last = index == licenseDocuments.lastIndex,
                accessibilityLabel = "查看${document.title}", onClick = { onNavigate("licenses/${document.id}") })
        }
    }
}

@Composable private fun LicenseDocumentContent(document: LicenseDocument) {
    val resources = LocalContext.current.resources
    val text by produceState<String?>(initialValue = null, resources, document.resource) {
        value = withContext(Dispatchers.IO) {
            resources.openRawResource(document.resource).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
    if (text == null) SHint("正在读取许可文本…")
    else SNativeText(text.orEmpty(), size = 13, lineHeight = 20, selectable = true)
}

/** Entries use the shared fade; removal has no exit fade to avoid delaying updates. */
@Composable internal fun SecondaryFadeContent(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    AnimatedVisibility(visibility, modifier, enter = fadeIn(DeskMotion.fadeIn()), exit = ExitTransition.None) { content() }
}


@Composable private fun ConnectionContent(state: WorkbenchState, repo: WorkbenchRepository, onNavigate: (String) -> Unit, onClose: () -> Unit) {
    var address by remember(state.endpoint) { mutableStateOf(state.endpoint) }
    var credential by remember { mutableStateOf("") }
    var forget by remember { mutableStateOf(false) }
    var enterWhenReady by remember { mutableStateOf(false) }
    val action = rememberAction()
    val origin = originOf(address)
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
    val connect: () -> Unit = {
        action.run {
            val value = normalizeServiceAddress(address)
            val token = credential.trim()
            require(token.isNotEmpty() || value == state.endpoint) { "请输入此服务地址的服务凭据。" }
            require(token.isEmpty() || token.length in 24..512) { "服务凭据应为 24 至 512 个字符，请从管理员处复制完整凭据。" }
            require(origin.isNotEmpty() && pinOrigin == origin && !pinPending && pinError.isEmpty()) { pinError.ifEmpty { "正在读取此地址的证书指纹。" } }
            val next = normalizedPin(pin)
            val changed = next != savedPin
            if (changed) {
                if (originOf(state.endpoint) == origin) {
                    require(token.isNotEmpty()) { "服务证书指纹已修改，请重新输入服务凭据。" }
                    repo.setEndpoint("")
                }
                repo.setFingerprint(value, next)
                savedPin = next; pin = next
            }
            if (value == state.endpoint && state.status == "online" && !changed && token.isEmpty()) { onClose() }
            else {
                enterWhenReady = true
                try { repo.connectService(value, token); credential = "" }
                catch (e: Exception) { enterWhenReady = false; throw e }
            }
        }
    }
    SSection {
        SRow(statusLabels[state.status] ?: state.status, state.endpoint.ifBlank { "填写管理员提供的 WSS 服务地址和服务凭据。" }, "link-2", last = true)
    }
    SSection("连接服务端") {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SField("服务地址", address, { if (it != address) credential = ""; address = it; action.error = "" }, "wss://host:port/ws", enabled = !action.busy, imeAction = ImeAction.Next, keyboardType = KeyboardType.Uri)
            SField("服务证书指纹（可选）", if (pinOrigin == origin) pin else "", { pin = it; action.error = "" }, if (origin.isNotEmpty() && (pinPending || pinOrigin != origin)) "正在读取此地址的指纹…" else if (origin.isEmpty()) "先填写完整地址" else "SHA-256，留空使用系统证书验证", enabled = origin.isNotEmpty() && pinOrigin == origin && !pinPending && pinError.isEmpty() && !action.busy, imeAction = ImeAction.Next)
            SHint("使用自签证书时，粘贴管理员提供的 64 位 SHA-256 叶证书指纹，可含冒号。")
            if (pinError.isNotEmpty()) { SHint(pinError, true); SAction("重新读取指纹", secondary = true) { pinRevision++ } }
            SField("服务凭据", credential, { credential = it }, if (state.endpoint == address && state.endpoint.isNotEmpty() && pin == savedPin) "留空使用此地址已保存的凭据" else "输入管理员提供的完整服务凭据", secure = true, enabled = !connecting, imeAction = ImeAction.Go, onSubmit = connect)
            SHint(action.error.ifBlank { state.error.orEmpty() }, true)
            SAction(if (state.status == "online" && address.trim() == state.endpoint && credential.isEmpty() && pin == savedPin) "打开聊天" else "连接", busy = connecting, enabled = address.isNotBlank() && (origin.isEmpty() || pinOrigin == origin) && !pinPending && pinError.isEmpty(), onClick = connect)
        }
    }
    SSection {
        SRow("连接遇到问题？", icon = "circle-help", last = state.endpoint.isEmpty(), onClick = { onNavigate("help") })
        if (state.endpoint.isNotEmpty()) SRow("忘记此地址", danger = true, last = true, onClick = { forget = true })
    }
    DeskExpandable(forget) { SSection("忘记此地址？") {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DeskText("下次连接时需要重新输入服务地址和服务凭据。")
            SHint("此主机和端口已保存的证书指纹也会清除。")
            SAction("忘记地址", busy = action.busy) { action.run { enterWhenReady = false; repo.forgetEndpoint(); forget = false; address = ""; credential = ""; pin = ""; savedPin = "" } }
            SAction("取消", secondary = true) { forget = false }
        }
    } }
}
