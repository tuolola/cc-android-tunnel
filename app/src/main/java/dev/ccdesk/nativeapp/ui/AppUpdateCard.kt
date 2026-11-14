// Copyright 2026 CC Android Tunnel contributors. Apache-2.0.
package dev.ccdesk.nativeapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ccdesk.nativeapp.core.AppUpdateRepository
import dev.ccdesk.nativeapp.core.AppUpdateState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal val LocalAppUpdates = staticCompositionLocalOf<AppUpdateRepository?> { null }

/** Kept separate from the workbench connection: checking releases never uses service credentials. */
@Composable internal fun AppUpdateSettings() {
    val repository = LocalAppUpdates.current ?: return
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var linkError by remember { mutableStateOf(false) }
    AppUpdateContent(state,
        onCheck = { scope.launch { repository.check(force = true) } },
        onAutomaticChange = repository::setAutomatic,
        onPrereleasesChange = repository::setIncludePrereleases,
        onOpenLink = { url ->
            linkError = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
        })
    if (linkError) DeskText("无法打开浏览器，请检查设备是否有可处理网页链接的应用。",
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, size = 13)
}

@Composable internal fun AppUpdateContent(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onAutomaticChange: (Boolean) -> Unit,
    onPrereleasesChange: (Boolean) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val c = LocalDeskColors.current
    val latest = state.latest
    var expanded by rememberSaveable(latest?.tagName) { mutableStateOf(false) }
    val status = when {
        state.checking -> "正在检查更新…"
        state.error != null -> "检查更新失败"
        state.updateAvailable -> "发现新版本 ${latest?.version.orEmpty()}"
        state.lastCheckedAt == null -> "尚未检查更新"
        latest == null -> "当前渠道暂无可安装版本"
        else -> "当前版本无需更新"
    }
    Column(Modifier.fillMaxWidth().testTag("app-update-card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DeskText("App 更新", Modifier.semantics { heading() }, size = 14, weight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        DeskText("CCtunnel ${state.currentVersion}", size = 15, weight = FontWeight.Medium)
        DeskText(status, Modifier.testTag("app-update-status").semantics { liveRegion = LiveRegionMode.Polite }, size = 14)
        state.error?.let { DeskText(it, size = 13, color = c.mutedForeground) }
        state.lastCheckedAt?.let { checked ->
            val date = remember(checked) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(checked)) }
            DeskText("上次成功检查：$date", size = 12, color = c.mutedForeground)
        }
        DeskAction(if (state.error == null) "检查更新" else "重试检查", enabled = !state.checking,
            loading = state.checking, modifier = Modifier.fillMaxWidth().testTag("app-update-check"), onClick = onCheck)
        if (state.updateAvailable && latest != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.muted).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DeskText(latest.name.ifBlank { latest.tagName }, size = 15, weight = FontWeight.Medium)
                DeskText(if (latest.prerelease) "公开测试版" else "正式版", size = 12, color = c.mutedForeground)
                DeskAction(if (expanded) "收起更新说明" else "查看更新说明", modifier = Modifier.fillMaxWidth()) { expanded = !expanded }
                DeskExpandable(expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Readable, selectable native text; remote release notes cannot load images or execute code.
                        NativeSpanText(latest.body.ifBlank { "此版本未提供更新说明。" }, size = 13, lineHeight = 20)
                        latest.sha256?.let { hash ->
                            NativeSpanText("APK SHA-256\n$hash", size = 12, lineHeight = 18, monospace = true)
                        }
                    }
                }
                DeskAction("下载 APK", primary = true, modifier = Modifier.fillMaxWidth().testTag("app-update-download")) { onOpenLink(latest.apkUrl) }
                DeskAction("查看发布页面", modifier = Modifier.fillMaxWidth().testTag("app-update-release")) { onOpenLink(latest.releaseUrl) }
                DeskText("通过系统浏览器下载，由你确认安装。覆盖更新需要相同签名和更高的版本代码。",
                    size = 12, color = c.mutedForeground)
                if (state.error != null) DeskText("以上为上次成功检查发现的版本。", size = 12, color = c.mutedForeground)
            }
        }
        UpdateToggle("自动检查更新", "回到 App 时检查；成功后间隔 24 小时，失败后 1 小时再试。", state.automatic, "app-update-automatic", onAutomaticChange)
        UpdateToggle("接收测试版更新", "同时检查公开测试版；关闭后仅检查正式版。", state.includePrereleases, "app-update-prereleases", onPrereleasesChange)
    }
}

@Composable private fun UpdateToggle(label: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val c = LocalDeskColors.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.pressedState()
    val offset by animateFloatAsState(if (checked) 18f else 0f, DeskMotion.state(), label = "update switch thumb")
    val color by animateColorAsState(if (checked) c.foreground else c.muted, DeskMotion.state(), label = "update switch track")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .background(animatedHighlight(pressed, false, c.muted), RoundedCornerShape(8.dp))
        .toggleable(checked, interactionSource = interaction, indication = null, role = Role.Switch) { haptics.toggle(it); onChange(it) }
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DeskText(label, size = 14)
            DeskText(detail, size = 12, color = c.mutedForeground)
        }
        Box(Modifier.size(42.dp, 24.dp).clip(CircleShape).background(color).border(1.dp, c.border, CircleShape).padding(3.dp)) {
            Box(Modifier.offset(x = offset.dp).size(18.dp).background(if (checked) c.background else c.mutedForeground, CircleShape))
        }
    }
}

/** A small indicator keeps automatic checks visible without interrupting a conversation. */
@Composable internal fun AppUpdateBadge() {
    val repository = LocalAppUpdates.current ?: return
    val state by repository.state.collectAsStateWithLifecycle()
    if (state.updateAvailable) Box(Modifier.size(7.dp).background(LocalDeskColors.current.foreground, CircleShape)
        .semantics { contentDescription = "有新的 App 版本" })
}
