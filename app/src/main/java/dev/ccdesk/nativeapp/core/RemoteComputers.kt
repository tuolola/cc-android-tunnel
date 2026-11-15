// Copyright 2026 CC Android Tunnel contributors.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.core

/** Device presence is meaningful only while the workbench connection is online. */
enum class RemoteComputerStatus { ONLINE, PREPARING, OFFLINE, DISCONNECTED }

data class RemoteComputer(
    val device: Device,
    val sessions: List<Session>,
    val status: RemoteComputerStatus,
    val duplicateName: Boolean = false,
) {
    val name: String get() = device.name.ifBlank { "未命名电脑" }
}

internal fun WorkbenchState.serverDeviceIds(): Set<String> = buildSet {
    serverDeviceId?.let(::add)
    devices.filter { it.server }.forEach { add(it.id) }
    sessions.mapNotNull { it.device }.filter { it.server }.forEach { add(it.id) }
}

internal fun WorkbenchState.excludedComputerDeviceIds(): Set<String> = buildSet {
    addAll(serverDeviceIds())
    localDeviceId?.takeIf { it.isNotBlank() }?.let(::add)
}

internal fun Session.belongsToClient(excludedDeviceIds: Set<String>): Boolean =
    id.isNotBlank() && device?.let { it.id.isNotBlank() && !it.server && it.id !in excludedDeviceIds } == true

/** Only an existing, identified computer session can expose client-specific controls. */
fun WorkbenchState.isClientSession(session: Session): Boolean = session.belongsToClient(excludedComputerDeviceIds())

/**
 * Keep the identity stored with sessions when a computer leaves the presence snapshot.
 * Names and project paths are labels, never keys: two computers can share either one.
 * Server sessions and sessions without a device are intentionally left to the chat list.
 */
fun remoteComputers(state: WorkbenchState, query: String = ""): List<RemoteComputer> {
    val excludedDeviceIds = state.excludedComputerDeviceIds()
    val present = state.devices.filter { it.id.isNotBlank() && it.id !in excludedDeviceIds }.associateBy { it.id }
    val sessions = state.sessions.filter { it.belongsToClient(excludedDeviceIds) }
        .sortedWith(compareByDescending<Session> { it.id in state.pins }.thenByDescending { it.updatedAt }.thenBy { it.id })
        .groupBy { requireNotNull(it.device).id }
    val names = (sessions.mapValues { (_, rows) -> requireNotNull(rows.first().device) } + present)
    val duplicateNames = names.values.groupingBy { it.name.ifBlank { "未命名电脑" } }.eachCount().filterValues { it > 1 }.keys
    val search = query.trim()
    return names.values.mapNotNull { device ->
        val allSessions = sessions[device.id].orEmpty()
        val computerMatches = search.isEmpty() || device.name.contains(search, ignoreCase = true) || device.id.contains(search, ignoreCase = true)
        val matchingSessions = if (computerMatches) allSessions else allSessions.filter {
            it.title.contains(search, ignoreCase = true) || it.projectPath.contains(search, ignoreCase = true)
        }
        if (!computerMatches && matchingSessions.isEmpty()) return@mapNotNull null
        val status = when {
            state.status != "online" -> RemoteComputerStatus.DISCONNECTED
            present[device.id]?.ready == true -> RemoteComputerStatus.ONLINE
            device.id in present -> RemoteComputerStatus.PREPARING
            else -> RemoteComputerStatus.OFFLINE
        }
        RemoteComputer(device, matchingSessions, status, device.name.ifBlank { "未命名电脑" } in duplicateNames)
    }.sortedWith(compareBy<RemoteComputer> { it.status.ordinal }
        .thenByDescending { it.sessions.maxOfOrNull(Session::updatedAt).orEmpty() }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.device.id })
}
