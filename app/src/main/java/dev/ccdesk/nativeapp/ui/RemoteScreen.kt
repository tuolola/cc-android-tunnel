// Copyright 2026 CC Android Tunnel contributors.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccdesk.nativeapp.core.RemoteComputer
import dev.ccdesk.nativeapp.core.RemoteComputerStatus
import dev.ccdesk.nativeapp.core.Session
import dev.ccdesk.nativeapp.core.WorkbenchRepository
import dev.ccdesk.nativeapp.core.remoteComputers

/** A computer keeps its sessions here even when its execution channel goes offline. */
@Composable
fun RemoteScreen(repository: WorkbenchRepository, onNavigate: (String) -> Unit, onClose: () -> Unit,
    onOpenDrawer: () -> Unit, active: Boolean = true) {
    val state by repository.state.collectAsState()
    val c = LocalDeskColors.current
    var query by rememberSaveable(state.endpoint) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val collapsed = remember(state.endpoint) { mutableStateMapOf<String, Boolean>() }
    val allComputers = remember(state.sessions, state.devices, state.serverDeviceId, state.localDeviceId, state.status, state.pins) { remoteComputers(state) }
    val computers = remember(state.sessions, state.devices, state.serverDeviceId, state.localDeviceId, state.status, state.pins, query) {
        remoteComputers(state, query)
    }
    BackHandler(enabled = active) { if (menu) menu = false else onClose() }
    Box(Modifier.fillMaxSize().then(AbsorbPointerInput).background(c.background).safeDrawingPadding()) {
        Column(Modifier.widthIn(max = 768.dp).fillMaxSize().align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.background(c.card, CircleShape)) { DeskIconButton("menu", "打开侧栏", iconSize = 22.dp, onClick = onOpenDrawer) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { DeskText("远程控制", Modifier.semantics { heading() }, size = 16, weight = FontWeight.SemiBold) }
                if (allComputers.isNotEmpty()) Box(Modifier.background(c.card, CircleShape)) { DeskIconButton("ellipsis", "远程控制更多选项", iconSize = 23.dp) { menu = true } }
            }
            if (allComputers.isNotEmpty() || query.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(48.dp)
                .clip(CircleShape).background(c.muted).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeskIcon("search", size = 20.dp, tint = c.mutedForeground)
                BasicTextField(query, { query = it }, Modifier.weight(1f).semantics { contentDescription = "查找电脑或会话" },
                    textStyle = TextStyle(fontFamily = FontFamily.SansSerif, color = c.foreground, fontSize = 16.sp),
                    cursorBrush = SolidColor(c.foreground), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner -> Box { if (query.isEmpty()) DeskText("查找电脑或会话", size = 16, color = c.mutedForeground); inner() } })
                if (query.isNotEmpty()) DeskIconButton("x", "清除远程控制搜索", iconSize = 17.dp) { query = "" }
            }
            if (state.status != "online" && computers.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(c.muted).deskPressable(c.accent, 16.dp) { onNavigate("connection") }
                    .semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DeskIcon("link-2", tint = c.mutedForeground)
                    DeskText(if (state.status in setOf("loading", "connecting")) "正在连接工作台…" else "连接已断开 · 查看连接", Modifier.weight(1f), size = 14, color = c.mutedForeground)
                    DeskIcon("chevron-right", size = 16.dp, tint = c.mutedForeground)
                }
            }
            if (computers.isEmpty()) {
                RemoteEmptyState(Modifier.weight(1f), query.isNotBlank(), state.status,
                    onClear = { query = "" }, onConnect = { onNavigate("connection") }, onHelp = { onNavigate("help") })
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    computers.forEach { computer ->
                        item(key = computer.device.id) {
                            RemoteComputerCard(computer, expanded = collapsed[computer.device.id] != true || query.isNotBlank(),
                                selectedId = state.selectedId, pins = state.pins,
                                onToggle = { collapsed[computer.device.id] = collapsed[computer.device.id] != true },
                                onOpen = { repository.openSession(it.id); onClose() })
                        }
                    }
                }
            }
        }
    }
    DeskSheetOverlay(menu, { menu = false }, scrimLabel = "关闭远程控制选项") {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DeskText("远程控制", size = 20, weight = FontWeight.SemiBold)
            RemoteMenuRow("连接设置", "link-2") { menu = false; onNavigate("connection") }
            RemoteMenuRow("使用帮助", "circle-help") { menu = false; onNavigate("help") }
        }
    }
}

@Composable private fun RemoteEmptyState(modifier: Modifier, searching: Boolean, status: String, onClear: () -> Unit, onConnect: () -> Unit, onHelp: () -> Unit) {
    val c = LocalDeskColors.current
    val loading = !searching && status in setOf("loading", "connecting")
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) { DeskSpinner("正在读取电脑", 32.dp) }
            else DeskIcon(if (searching) "search" else "remote", size = 60.dp, strokeWidth = 2.4f)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Text(if (searching) "未找到电脑或会话" else if (loading) "正在连接" else "设置远程功能", color = c.foreground,
                fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Text(if (searching) "试试其他名称或项目。" else "出门在外，也能继续\n电脑上的项目和会话。", color = c.mutedForeground,
                fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 25.sp, textAlign = TextAlign.Center)
            if (!searching) {
                Spacer(Modifier.height(40.dp))
                RemoteExplanation("monitor-smartphone", "查看不同电脑客户端的会话，继续对话、开发软件并处理项目中的工作。")
                Spacer(Modifier.height(24.dp))
                RemoteExplanation("link-2", if (status == "online") "工作台已连接。在电脑客户端连接同一工作台后，这台电脑和它的会话就会出现在这里。" else "将手机和电脑客户端连接到同一工作台，即可在这里查看各台电脑的会话。")
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RemotePill(if (searching) "清除搜索" else "连接工作台", filled = true, onClick = if (searching) onClear else onConnect)
            if (!searching) RemotePill("查看连接帮助", filled = false, onClick = onHelp)
        }
    }
}

@Composable private fun RemoteExplanation(icon: String, text: String) {
    val c = LocalDeskColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        DeskIcon(icon, Modifier.padding(top = 3.dp), size = 24.dp, tint = c.mutedForeground, strokeWidth = 1.8f)
        DeskText(text, Modifier.weight(1f), size = 16, lineHeight = 24, color = c.mutedForeground)
    }
}

@Composable private fun RemotePill(label: String, filled: Boolean, onClick: () -> Unit) {
    val c = LocalDeskColors.current
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape).background(if (filled) c.foreground else Color.Transparent)
        .then(if (filled) Modifier else Modifier.border(1.dp, c.border, CircleShape)).deskPressable(if (filled) c.background.copy(alpha = .12f) else c.muted, 28.dp, onClick = onClick)
        .semantics { role = Role.Button }.padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        DeskText(label, size = 16, lineHeight = 24, weight = FontWeight.SemiBold, color = if (filled) c.background else c.foreground)
    }
}

@Composable private fun RemoteMenuRow(label: String, icon: String, onClick: () -> Unit) {
    val c = LocalDeskColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).deskPressable(c.muted, 18.dp, onClick = onClick)
        .semantics { role = Role.Button }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DeskIcon(icon, size = 23.dp)
        DeskText(label, Modifier.weight(1f), size = 16)
        DeskIcon("chevron-right", size = 16.dp, tint = c.mutedForeground)
    }
}

@Composable private fun RemoteComputerCard(computer: RemoteComputer, expanded: Boolean, selectedId: String?, pins: List<String>, onToggle: () -> Unit, onOpen: (Session) -> Unit) {
    val c = LocalDeskColors.current
    val statusLabel = when (computer.status) {
        RemoteComputerStatus.ONLINE -> "在线"
        RemoteComputerStatus.PREPARING -> "准备中"
        RemoteComputerStatus.OFFLINE -> "离线"
        RemoteComputerStatus.DISCONNECTED -> "连接中断"
    }
    val statusColor = when (computer.status) {
        RemoteComputerStatus.ONLINE -> Color(0xff20a66a)
        RemoteComputerStatus.PREPARING -> Color(0xffc28b26)
        else -> c.mutedForeground
    }
    Column(Modifier.fillMaxWidth().testTag("remote-device:${computer.device.id}").clip(RoundedCornerShape(24.dp))
        .background(c.card).border(1.dp, c.border.copy(alpha = .65f), RoundedCornerShape(24.dp))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).deskPressable(c.muted, 24.dp, onClick = onToggle)
            .semantics { role = Role.Button; contentDescription = "电脑：${computer.name}"; stateDescription = "$statusLabel，${if (expanded) "已展开" else "已收起"}" }
            .padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DeskIcon("monitor-smartphone", size = 26.dp, strokeWidth = 1.7f)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DeskText(computer.name, size = 17, weight = FontWeight.Medium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                    DeskText("$statusLabel · ${computer.sessions.size} 个会话" + if (computer.duplicateName) " · ${computer.device.id.takeLast(6)}" else "", size = 12, color = c.mutedForeground, maxLines = 1)
                }
            }
            DeskChevron(expanded, size = 18.dp)
        }
        DeskExpandable(expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                if (computer.sessions.isEmpty()) DeskText("这台电脑还没有会话", Modifier.padding(horizontal = 12.dp, vertical = 14.dp), size = 14, color = c.mutedForeground)
                computer.sessions.forEach { session ->
                    key(session.id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).deskPressable(c.muted, 16.dp, selected = selectedId == session.id) { onOpen(session) }
                            .semantics { role = Role.Button; contentDescription = "打开会话：${session.title}"; selected = selectedId == session.id }
                            .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (session.id in pins) DeskIcon("pin", size = 17.dp, tint = c.mutedForeground)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                DeskText(session.title, size = 16, maxLines = 2)
                                val project = session.projectPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                                if (project.isNotBlank()) DeskText(project, size = 13, color = c.mutedForeground, maxLines = 1)
                            }
                            DeskIcon("chevron-right", size = 15.dp, tint = c.mutedForeground)
                        }
                    }
                }
            }
        }
    }
}
