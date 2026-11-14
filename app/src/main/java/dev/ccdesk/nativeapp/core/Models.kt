// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun json(vararg values: Pair<String, Any?>): JSONObject = JSONObject().apply {
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }
internal fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

data class Device(val id: String, val name: String = "", val platform: String? = null, val ready: Boolean? = null, val server: Boolean = false) {
    companion object { fun from(o: JSONObject) = Device(o.getString("id"), o.optString("name"), o.stringOrNull("platform"), if (o.has("ready")) o.optBoolean("ready") else null, o.optBoolean("server")) }
}
data class ActiveRun(val id: String, val status: String, val connectionId: String = "", val waiting: String? = null, val surface: String? = null, val activity: String? = null) {
    companion object { fun from(o: JSONObject) = ActiveRun(o.getString("id"),o.optString("status"),o.optString("connectionId"),o.stringOrNull("waiting"),o.stringOrNull("surface"),o.stringOrNull("activity")) }
}
data class Session(val id: String, val title: String = "新会话", val projectPath: String = "", val device: Device? = null,
    val createdAt: String = "", val updatedAt: String = "", val permissionMode: String = "", val model: String? = null,
    val effort: String? = null, val activeRun: ActiveRun? = null) {
    companion object { fun from(o: JSONObject) = Session(o.getString("id"),o.optString("title","新会话"),o.optString("projectPath"),o.optJSONObject("device")?.let(Device::from),o.optString("createdAt"),o.optString("updatedAt"),o.optString("permissionMode"),o.stringOrNull("model"),o.stringOrNull("effort"),o.optJSONObject("activeRun")?.let(ActiveRun::from)) }
}
data class ModelInfo(val value: String, val displayName: String, val supportedEffortLevels: List<String> = emptyList(), val description: String? = null, val resolvedModel: String? = null, val source: String? = null) {
    companion object { fun from(o: JSONObject) = ModelInfo(o.getString("value"),o.optString("displayName"),o.optJSONArray("supportedEffortLevels").strings(),o.stringOrNull("description"),o.stringOrNull("resolvedModel"),o.stringOrNull("source")) }
}
data class Catalog(val models: List<ModelInfo>, val at: String)
data class DeskEvent(val sessionId: String, val runId: String, val sequence: Long, val createdAt: String, val payload: JSONObject) {
    companion object { fun from(o: JSONObject) = DeskEvent(o.getString("sessionId"),o.getString("runId"),o.getLong("sequence"),o.optString("createdAt"),o.getJSONObject("payload")) }
}
data class History(val events: List<DeskEvent> = emptyList(), val fragments: List<DeskEvent> = emptyList(), val hasEarlier: Boolean = false, val loading: Boolean = false, val loaded: Boolean = false, val loadError: String? = null)
data class Notice(val id: String, val sessionId: String, val title: String, val body: String, val at: String, val read: Boolean = false) {
    fun toJson() = json("id" to id,"sessionId" to sessionId,"title" to title,"body" to body,"at" to at,"read" to read)
    companion object { fun from(o: JSONObject) = Notice(o.getString("id"),o.getString("sessionId"),o.getString("title"),o.getString("body"),o.getString("at"),o.optBoolean("read")) }
}
data class WorkbenchState(val status: String = "loading", val endpoint: String = "", val authenticated: Boolean = false,
    val error: String? = null, val version: String? = null, val webSessionId: String? = null, val serverDeviceId: String? = null,
    val defaultModel: String? = null, val totpEnabled: Boolean = false, val sessions: List<Session> = emptyList(),
    val devices: List<Device> = emptyList(), val selectedId: String? = null, val histories: Map<String, History> = emptyMap(),
    val modelCatalogs: Map<String,Catalog> = emptyMap(), val pins: List<String> = emptyList(), val notices: List<Notice> = emptyList(),
    val account: JSONObject? = null, val webSettings: JSONObject? = null, val usageSummary: JSONObject? = null, val usagePage: JSONObject? = null)
data class Preferences(val pins: List<String> = emptyList(), val notices: List<Notice> = emptyList())

private fun fragmentKey(p: JSONObject) = listOf(p.optString("type"), p.stringOrNull("messageId") ?: p.stringOrNull("toolId") ?: p.stringOrNull("taskId") ?: "", p.optString("stream")).joinToString("/")
fun addFragment(history: History, sessionId: String, runId: String, payload: JSONObject): History {
    val fragments = history.fragments.toMutableList()
    val index = fragments.indexOfFirst { it.runId == runId && fragmentKey(it.payload) == fragmentKey(payload) }
    val previous = fragments.getOrNull(index)
    val combined = JSONObject(payload.toString())
    if (previous != null && payload.opt("text") is String) combined.put("text", previous.payload.optString("text") + payload.getString("text"))
    // Settling one fragment can leave a gap in this list. Allocate below the smallest
    // surviving sequence instead of from list size, so concurrent tools retain unique keys.
    val item = DeskEvent(sessionId,runId,previous?.sequence ?: ((fragments.minOfOrNull { it.sequence } ?: 0L) - 1L),previous?.createdAt ?: now(),combined)
    if (index < 0) fragments.add(item) else fragments[index] = item
    return history.copy(fragments = fragments)
}
fun addEvent(history: History, event: DeskEvent): History {
    if (history.events.any { it.sequence == event.sequence }) return history
    val events = if ((history.events.lastOrNull()?.sequence ?: Long.MIN_VALUE) > event.sequence) (history.events + event).sortedBy { it.sequence } else history.events + event
    val settled = event.payload.optString("type") == "run.status" && event.payload.optString("status") in setOf("cancelled","failed","completed")
    return history.copy(events = events, fragments = history.fragments.filter { it.runId != event.runId || (!settled && fragmentKey(it.payload) != fragmentKey(event.payload)) })
}
fun applySnapshot(history: History, frame: JSONObject): History {
    val mode = frame.optString("mode")
    val events = (if (mode == "replace") emptyList() else history.events) + frame.optJSONArray("events").objects().map(DeskEvent::from)
    return History(events.associateBy { it.sequence }.values.sortedBy { it.sequence }, if (mode == "prepend") history.fragments else emptyList(), if (mode == "append") history.hasEarlier else frame.optBoolean("hasEarlier"), loaded = true)
}
fun modelForChoice(models: List<ModelInfo>, current: String?, serverDefault: String? = null, native: String? = null): ModelInfo? {
    fun find(id: String?): ModelInfo? = if (id.isNullOrEmpty()) null else models.find { it.value == id } ?: models.find { it.value != "default" && it.resolvedModel == id } ?: models.find { it.resolvedModel == id }
    if (!current.isNullOrEmpty()) return find(current)
    val advertised = find("default")
    if (!serverDefault.isNullOrEmpty()) return find(serverDefault)
    return find(advertised?.resolvedModel) ?: find(native) ?: advertised
}
fun modelName(row: ModelInfo?): String {
    row ?: return ""
    val id = row.resolvedModel ?: row.value
    val label = row.displayName.ifEmpty { row.value }
    val match = Regex("^claude-([a-z]+)-(\\d+)(?:-(\\d{1,2})(?=-|\\[|$))?(?=-|\\[|$)",RegexOption.IGNORE_CASE).find(id) ?: return if (label.equals("default",true)) id else label
    val family = match.groupValues[1]
    val version = match.groupValues[2] + match.groupValues[3].let { if (it.isEmpty()) "" else ".$it" }
    val full = family.replaceFirstChar { it.uppercase() } + " " + version
    return when { row.value == "default" || label == id -> full; !label.contains(family,true) -> "$label · $full"; label.any(Char::isDigit) -> label; else -> "$label $version" }
}
