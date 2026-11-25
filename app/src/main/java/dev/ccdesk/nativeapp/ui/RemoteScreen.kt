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
import dev.ccdesk.nativeapp.core.Project
import dev.ccdesk.nativeapp.core.Session
import dev.ccdesk.nativeapp.core.WorkbenchRepository
import dev.ccdesk.nativeapp.core.contains
import dev.ccdesk.nativeapp.core.json
import dev.ccdesk.nativeapp.core.remoteComputers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** A computer keeps its sessions here even when its execution channel goes offline. */
@Composable
fun RemoteScreen(repository: WorkbenchRepository, onNavigate: (String) -> Unit, onClose: () -> Unit,
    onOpenDrawer: () -> Unit, active: Boolean = true) {
    val state by repository.state.collectAsState()
    val c = LocalDeskColors.current
    var query by rememberSaveable(state.endpoint) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var projectMenu by remember(state.endpoint) { mutableStateOf<Project?>(null) }
    var projectAction by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val creationRequests = remember(state.endpoint) { mutableMapOf<Pair<String, String>, String>() }
    val collapsed = remember(state.endpoint) { mutableStateMapOf<String, Boolean>() }
    val allComputers = remember(state.sessions, state.projects, state.devices, state.serverDeviceId, state.localDeviceId, state.status, state.pins) { remoteComputers(state) }
    val computers = if (query.isBlank()) allComputers else remember(allComputers, query) { remoteComputers(state, query) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "项目操作失败。" }
            finally { busy = false }
        }
    }
    BackHandler(enabled = active) {
        if (!busy) when {
            projectMenu != null -> projectMenu = null
            menu -> menu = false
            else -> onClose()
        }
    }
    Box(Modifier.fillMaxSize().then(AbsorbPointerInput).background(c.background).safeDrawingPadding()) {
        Column(Modifier.widthIn(max = 768.dp).fillMaxSize().align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.background(c.card, RoundedCornerShape(14.dp))) { DeskIconButton("menu", "打开侧栏", iconSize = 22.dp, onClick = onOpenDrawer) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DeskText("CCtunnel", size = 11, color = c.primary, weight = FontWeight.SemiBold)
                    DeskText("远程控制", Modifier.semantics { heading() }, size = 18, weight = FontWeight.SemiBold)
                }
                if (allComputers.isNotEmpty()) Box(Modifier.background(c.card, RoundedCornerShape(14.dp))) { DeskIconButton("ellipsis", "远程控制更多选项", iconSize = 23.dp) { menu = true } }
            }
            if (allComputers.isNotEmpty() || query.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeskIcon("search", size = 20.dp, tint = c.mutedForeground)
                BasicTextField(query, { query = it }, Modifier.weight(1f).semantics { contentDescription = "查找电脑或会话" },
                    textStyle = TextStyle(fontFamily = FontFamily.SansSerif, color = c.foreground, fontSize = 16.sp),
                    cursorBrush = SolidColor(c.primary), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner -> Box { if (query.isEmpty()) DeskText("查找电脑或会话", size = 16, color = c.mutedForeground); inner() } })
                if (query.isNotEmpty()) DeskIconButton("x", "清除远程控制搜索", iconSize = 17.dp) { query = "" }
            }
            if (error.isNotBlank() && projectMenu == null) DeskText(error, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = c.foreground, size = 14)
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
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "computer-summary") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DeskText("电脑与会话", size = 13, color = c.primary, weight = FontWeight.SemiBold)
                            DeskText("${computers.size} 台电脑", size = 13, color = c.mutedForeground)
                        }
                    }
                    computers.forEach { computer ->
                        item(key = computer.device.id) {
                            RemoteComputerCard(computer, expanded = collapsed[computer.device.id] != true || query.isNotBlank(),
                                selectedId = state.selectedId, pins = state.pins,
                                onToggle = { collapsed[computer.device.id] = collapsed[computer.device.id] != true },
                                onOpen = { repository.openSession(it.id); onClose() },
                                onCreate = { project -> perform {
                                    val key = project.device.id to project.root
                                    val requestId = creationRequests.getOrPut(key) { UUID.randomUUID().toString() }
                                    val id = repository.createProjectSession(project, requestId)
                                    creationRequests.remove(key)
                                    repository.openSession(id)
                                    onClose()
                                } },
                                onProject = { project -> projectMenu = project; projectAction = ""; projectName = project.name.orEmpty(); error = "" },
                                workbenchOnline = state.status == "online", busy = busy)
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
    val closeProjectMenu = { if (!busy) projectMenu = null }
    DeskSheetOverlay(projectMenu != null, closeProjectMenu, dismissible = !busy, scrimLabel = "关闭项目选项") {
        projectMenu?.let { project ->
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DeskText(project.label, size = 20, weight = FontWeight.SemiBold)
                DeskText(project.path, size = 13, color = c.mutedForeground)
                when (projectAction) {
                    "rename" -> {
                        DeskField(projectName, { projectName = it }, "项目名称（留空使用文件夹名称）", Modifier.fillMaxWidth(), enabled = !busy, maxLength = 60)
                        DeskAction("保存名称", !busy && state.status == "online", primary = true, loading = busy) { perform {
                            repository.command("project.rename", json("deviceId" to project.device.id, "path" to project.path, "name" to projectName.trim()))
                            projectMenu = null
                        } }
                    }
                    "remove" -> {
                        DeskText("移除后，这个项目的会话会保留为普通会话，文件夹也会保留。", size = 14, lineHeight = 20)
                        DeskAction("确认移除项目", !busy && state.status == "online", loading = busy) { perform {
                            repository.command("project.remove", json("deviceId" to project.device.id, "path" to project.path))
                            projectMenu = null
                        } }
                    }
                    else -> {
                        RemoteMenuRow("重命名项目", "pencil") { projectAction = "rename" }
                        RemoteMenuRow("移除项目", "trash-2") { projectAction = "remove" }
                    }
                }
                if (error.isNotBlank()) DeskText(error, size = 14)
            }
        }
    }
}

@Composable private fun RemoteEmptyState(modifier: Modifier, searching: Boolean, status: String, onClear: () -> Unit, onConnect: () -> Unit, onHelp: () -> Unit) {
    val c = LocalDeskColors.current
    val loading = !searching && status in setOf("loading", "connecting")
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) { DeskSpinner("正在读取电脑", 32.dp) }
            else Box(Modifier.size(72.dp).background(c.accent, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                DeskIcon(if (searching) "search" else "remote", size = 36.dp, tint = c.primary, strokeWidth = 1.8f)
            }
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
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (filled) c.primary else c.card)
        .then(if (filled) Modifier else Modifier.border(1.dp, c.border, RoundedCornerShape(14.dp))).deskPressable(if (filled) c.onPrimary.copy(alpha = .12f) else c.muted, 14.dp, onClick = onClick)
        .semantics { role = Role.Button }.padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        DeskText(label, size = 16, lineHeight = 24, weight = FontWeight.SemiBold, color = if (filled) c.onPrimary else c.foreground)
    }
}

@Composable private fun RemoteMenuRow(label: String, icon: String, onClick: () -> Unit) {
    val c = LocalDeskColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).deskPressable(c.muted, 18.dp, onClick = onClick)
        .semantics { role = Role.Button }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(32.dp).background(c.secondary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            DeskIcon(icon, size = 20.dp, tint = c.primary)
        }
        DeskText(label, Modifier.weight(1f), size = 16)
        DeskIcon("chevron-right", size = 16.dp, tint = c.mutedForeground)
    }
}

@Composable private fun RemoteComputerCard(computer: RemoteComputer, expanded: Boolean, selectedId: String?, pins: List<String>,
    onToggle: () -> Unit, onOpen: (Session) -> Unit, onCreate: (Project) -> Unit, onProject: (Project) -> Unit,
    workbenchOnline: Boolean, busy: Boolean) {
    val c = LocalDeskColors.current
    val statusLabel = when (computer.status) {
        RemoteComputerStatus.ONLINE -> "在线"
        RemoteComputerStatus.PREPARING -> "准备中"
        RemoteComputerStatus.OFFLINE -> "离线"
        RemoteComputerStatus.DISCONNECTED -> "连接中断"
    }
    val statusColor = when (computer.status) {
        RemoteComputerStatus.ONLINE -> c.primary
        RemoteComputerStatus.PREPARING -> Color(0xffc28b26)
        else -> c.mutedForeground
    }
    val offlineHistory = computer.status == RemoteComputerStatus.OFFLINE || computer.status == RemoteComputerStatus.DISCONNECTED
    Column(Modifier.fillMaxWidth().testTag("remote-device:${computer.device.id}").clip(RoundedCornerShape(20.dp))
        .background(c.card).border(1.dp, c.border, RoundedCornerShape(20.dp))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).deskPressable(c.muted, 20.dp, onClick = onToggle)
            .semantics { role = Role.Button; contentDescription = "电脑：${computer.name}"; stateDescription = "$statusLabel，${if (expanded) "已展开" else "已收起"}" }
            .padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).background(if (offlineHistory) c.secondary else c.accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                DeskIcon("monitor", size = 24.dp, tint = if (offlineHistory) c.mutedForeground else c.primary, strokeWidth = 1.8f)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DeskText(computer.name, size = 17, weight = FontWeight.Medium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                    DeskText("$statusLabel · ${computer.sessions.size} 个会话" + if (computer.duplicateName) " · ${computer.device.id.takeLast(6)}" else "", size = 12, color = c.mutedForeground, maxLines = 2)
                }
            }
            DeskChevron(expanded, size = 18.dp)
        }
        DeskExpandable(expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(1.dp).background(c.border))
                if (offlineHistory) DeskText(if (computer.status == RemoteComputerStatus.DISCONNECTED) "已保留历史 · 连接工作台后查看状态" else "离线历史 · 电脑上线后可继续执行", Modifier.padding(horizontal = 8.dp, vertical = 10.dp), size = 12, color = c.mutedForeground)
                if (computer.sessions.isEmpty() && computer.projects.isEmpty()) DeskText("这台电脑还没有会话", Modifier.padding(horizontal = 12.dp, vertical = 14.dp), size = 14, color = c.mutedForeground)
                computer.projects.forEach { project ->
                    key(project.root) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                DeskText(project.label, size = 15, weight = FontWeight.SemiBold, maxLines = 1)
                                DeskText(project.path, size = 12, color = c.mutedForeground, maxLines = 1)
                            }
                            if (computer.status == RemoteComputerStatus.ONLINE) DeskIconButton("plus", "在${project.label}新建会话", !busy) { onCreate(project) }
                            if (workbenchOnline) DeskIconButton("ellipsis", "${project.label}项目选项", !busy) { onProject(project) }
                        }
                        computer.sessions.filter(project::contains).forEach { session ->
                            key(session.id) { RemoteSessionRow(session, selectedId, pins, onOpen) }
                        }
                    }
                }
                computer.sessions.filter { session -> computer.projects.none { it.contains(session) } }.forEach { session ->
                    key(session.id) { RemoteSessionRow(session, selectedId, pins, onOpen) }
                }
            }
        }
    }
}

@Composable private fun RemoteSessionRow(session: Session, selectedId: String?, pins: List<String>, onOpen: (Session) -> Unit) {
    val c = LocalDeskColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).deskPressable(c.accent, 12.dp, selected = selectedId == session.id) { onOpen(session) }
        .semantics { role = Role.Button; contentDescription = "打开会话：${session.title}"; selected = selectedId == session.id }
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(3.dp, 28.dp).background(if (selectedId == session.id) c.primary else c.border, RoundedCornerShape(2.dp)))
        if (session.id in pins) DeskIcon("pin", size = 17.dp, tint = c.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            DeskText(session.title, size = 16, maxLines = 2)
            val folder = session.projectPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
            if (folder.isNotBlank()) DeskText(folder, size = 13, color = c.mutedForeground, maxLines = 1)
        }
        DeskIcon("chevron-right", size = 15.dp, tint = c.mutedForeground)
    }
}
