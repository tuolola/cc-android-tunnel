// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

const val PROTOCOL_VERSION = 29
private val historyCommands = setOf("session.subscribe", "session.history")
private val allowed = setOf("server.session.create","session.subscribe","session.history","session.status","session.stats",
    "session.rename","session.delete","session.configure","session.compact","message.send","run.cancel","run.continue",
    "run.task.stop","run.task.background","approval.reply","usage.summary","usage.query","quota.watch","account.status",
    "compaction.get","web.settings.get","web.settings.update","web.sessions.revoke","web.totp.setup","web.totp.enable","web.totp.disable",
    "session.create","session.move","project.rename","project.remove")
private fun validReady(frame: JSONObject): Boolean {
    val version = frame.opt("protocolVersion")
    return version is Number && version.toDouble() == PROTOCOL_VERSION.toDouble() &&
        (frame.isNull("browser") || frame.opt("browser") == false) && frame.optJSONArray("sessions") != null
}

/** All state and callbacks are confined to [dispatcher]; IO lives in transport. */
class WorkbenchRepository(private val transport: WorkbenchTransport, private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    constructor(context: Context) : this(AndroidWorkbenchTransport(context.applicationContext))
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // Like the baseline Promise write queue, persisted user choices survive UI disposal.
    private val persistenceScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val persistenceDrained = CompletableDeferred<Unit>()
    private var disposed = false
    private val mutableState = MutableStateFlow(WorkbenchState())
    val state: StateFlow<WorkbenchState> = mutableState.asStateFlow()
    private val current get() = mutableState.value
    private var socket: SocketConnection? = null
    private var generation = 0L
    private var retry: Job? = null
    private var handshake: Job? = null
    private var retryCount = 0
    private var retryOnForeground = true
    private var openSession: String? = null
    private var active = true
    private data class Pending(
        val result: CompletableDeferred<JSONObject>, val type: String, val sessionId: String?,
        var frame: JSONObject? = null, var timeout: Job? = null, var transfer: HistoryTransfer? = null,
        val following: MutableList<JSONObject> = mutableListOf(), var followingChars: Long = 0,
    )
    private val pending = mutableMapOf<String,Pending>()
    private val historyRequestIds = linkedSetOf<String>()
    private val needsReload = mutableSetOf<String>()
    private val historyOwners = mutableMapOf<String,String>()
    private var historyId: String? = null
    private data class Subscription(val sessionId: String, val requestId: String, val task: Deferred<Unit>)
    private var subscription: Subscription? = null
    private data class CatalogRead(val sessionId: String, val task: Deferred<Unit>)
    private val catalogReads = mutableMapOf<String,CatalogRead>()
    private val catalogAttempts = mutableSetOf<String>()
    private var catalogPresenceKnown = false
    private var writes: Job? = null
    private var preferenceLoaded = false
    private var quotaWanted = false
    private var sendInFlight = false
    private var draftRequestId: String? = null
    private fun patch(block: (WorkbenchState) -> WorkbenchState) { mutableState.value = block(current) }
    private fun updateHistory(id: String, block: (History) -> History) { val before = current.histories[id] ?: History(); val after = block(before); if (after !== before) patch { it.copy(histories = it.histories + (id to after)) } }
    private fun message(error: Throwable) = error.message ?: "连接失败，请检查网络和工作台地址。"
    private fun stop(): Long {
        generation++
        retry?.cancel(); retry = null; handshake?.cancel(); handshake = null
        val old = socket; socket = null; openSession = null; old?.close()
        // Completing a waiter can immediately resume sendCommand's finally block.
        // Detach all old requests before invoking those continuations.
        val interrupted = pending.values.toList()
        pending.clear()
        interrupted.forEach {
            if (it.type == "session.subscribe" && it.frame == null) it.sessionId?.let(needsReload::add)
            it.timeout?.cancel(); it.result.completeExceptionally(IllegalStateException("连接已中断，请恢复连接后核对操作结果。"))
        }
        subscription = null; catalogReads.clear(); catalogAttempts.clear(); catalogPresenceKnown = false
        historyOwners.clear()
        patch { it.copy(modelCatalogLoading = emptySet(), histories = it.histories.mapValues { (_, h) -> h.copy(loading = false,fragments = emptyList()) }) }
        return generation
    }
    suspend fun start() = withContext(dispatcher) {
        active = true
        val ticket = generation
        try {
            val raw = transport.getEndpoint()
            val endpoint = if (raw.isBlank()) "" else normalizeServiceAddress(raw)
            if (ticket != generation) return@withContext
            patch { it.copy(endpoint = endpoint,status = if (endpoint.isEmpty()) "setup" else "connecting") }
            if (endpoint.isNotEmpty()) reconnect()
        } catch (e: Exception) { if (e is CancellationException) throw e; if (ticket == generation) patch { it.copy(status = "setup",error = message(e)) } }
    }
    suspend fun setEndpoint(raw: String) = withContext(dispatcher) {
        val endpoint = if (raw.isBlank()) "" else normalizeServiceAddress(raw)
        val forgotten = current.endpoint.takeIf { endpoint.isEmpty() && it.isNotEmpty() }
        chooseEndpoint(endpoint, if (forgotten == null) null else suspend { transport.saveCredential(forgotten,"") })
    }
    suspend fun connectService(raw: String, credential: String) = withContext(dispatcher) {
        val endpoint = normalizeServiceAddress(raw)
        val token = credential.trim()
        require(token.isEmpty() || token.length in 24..512) { "请填写完整的服务凭据（24 至 512 个字符）。" }
        chooseEndpoint(endpoint,if (token.isEmpty()) null else suspend { transport.saveCredential(endpoint,token) })
    }
    private suspend fun chooseEndpoint(endpoint: String, prepare: (suspend () -> Unit)? = null) {
        if (endpoint == current.endpoint && endpoint.isNotEmpty() && prepare == null) { reconnect(); return }
        val ticket = stop(); retryCount = 0; quotaWanted = false; preferenceLoaded = false; draftRequestId = null
        needsReload.clear()
        historyId = null
        historyRequestIds.clear()
        mutableState.value = WorkbenchState(endpoint = endpoint,status = if (endpoint.isEmpty()) "setup" else "connecting")
        val previous = writes
        val selected = persistenceScope.async { previous?.join(); prepare?.invoke(); transport.setEndpoint(endpoint) }
        writes = selected
        try { selected.await() }
        catch (e: Exception) { if (ticket == generation) patch { it.copy(status = "offline",error = message(e)) }; throw e }
        if (endpoint.isNotEmpty() && ticket == generation) reconnect()
    }
    suspend fun fingerprint(endpoint: String): String = transport.fingerprint(endpoint)
    suspend fun setFingerprint(rawEndpoint: String, rawPin: String) = withContext(dispatcher) {
        val endpoint = normalizeServiceAddress(rawEndpoint)
        require(endpoint.isNotEmpty())
        val pin = normalizeFingerprint(rawPin)
        if (transport.fingerprint(endpoint) == pin) return@withContext
        if (current.endpoint.isNotEmpty() && parseUrl(endpoint,true).origin() == parseUrl(current.endpoint,true).origin()) {
            stop(); retryOnForeground = false; patch { it.copy(status = "offline",authenticated = false,error = "工作台证书设置已修改，请重新连接。") }
        }
        transport.setFingerprint(endpoint,pin)
    }
    suspend fun forgetEndpoint() = withContext(dispatcher) {
        val endpoint = current.endpoint
        val ticket = stop(); retryOnForeground = false
        patch { it.copy(status = if (endpoint.isEmpty()) "setup" else "offline",authenticated = false,error = null) }
        try {
            if (endpoint.isNotEmpty()) { transport.clearOrigin(endpoint); transport.setFingerprint(endpoint,"") }
            chooseEndpoint("")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (ticket == generation) patch { it.copy(status = "offline",authenticated = false,error = message(error)) }
            throw error
        }
    }
    private suspend fun loadPreferences(endpoint: String, ticket: Long) {
        if (preferenceLoaded) return
        val prefs = transport.readPreferences(endpoint)
        if (ticket != generation) return
        preferenceLoaded = true; patch { it.copy(pins = prefs.pins,notices = prefs.notices) }
    }
    private suspend fun openService(endpoint: String, ticket: Long) {
        val token = transport.credential(endpoint)
        if (ticket != generation) return
        if (token.isEmpty()) { patch { it.copy(status = "setup",error = "请输入此服务地址对应的服务凭据。",authenticated = false) }; return }
        val deviceId = transport.deviceId()
        if (ticket != generation) return
        // Publish the exact identity used by the device frame before presence can arrive.
        // Reconnect retains it with cached history; switching services resets the state.
        patch { it.copy(localDeviceId = deviceId) }
        loadPreferences(endpoint,ticket)
        if (ticket != generation) return
        attach(endpoint,json("type" to "auth","protocolVersion" to PROTOCOL_VERSION,"token" to token,"deviceName" to "CC Android Tunnel","tunnel" to false),ticket,deviceId)
    }
    private suspend fun attach(url: String, greeting: JSONObject, ticket: Long, deviceId: String) {
        val connected = CompletableDeferred<SocketConnection>()
        val listener = object : SocketObserver {
            override fun opened() { scope.launch { val connection = connected.await(); if (ticket == generation && !connection.send(greeting.toString())) disconnected("实时连接已关闭。") } }
            override fun message(text: String) { scope.launch {
                if (ticket != generation) return@launch
                try {
                    val frame = JSONObject(text)
                    if (frame.optString("type") == "ready" && validReady(frame)) connected.await().send(json("type" to "device","id" to deviceId).toString())
                    receive(frame)
                } catch (e: Exception) { if (e is CancellationException) throw e; disconnected("工作台返回了无法读取的数据。") }
            } }
            override fun closed(code: Int, reason: String) { scope.launch {
                if (ticket != generation) return@launch
                if (code in 4001..4003) disconnected(if (code == 4001) "服务认证失败，请核对服务凭据。" else "服务拒绝连接，请核对客户端协议版本。",false)
                else disconnected("实时连接已断开，正在重新连接。")
            } }
            override fun failed(cause: Throwable) { scope.launch { if (ticket == generation) { val error = networkError(cause); disconnected(message(error),error !is CertificateFailure) } } }
        }
        try {
            val connection = transport.socket(url,listener)
            connected.complete(connection)
            if (ticket != generation) { connection.close(); return }
            socket = connection
            handshake = scope.launch { delay(15_000); if (ticket == generation && current.status != "online") disconnected("连接工作台超时，请检查网络。") }
        } catch (e: Exception) { connected.cancel(); throw e }
    }
    private fun disconnected(error: String, retryAllowed: Boolean = true) {
        stop(); retryOnForeground = retryAllowed; patch { it.copy(status = "offline",error = error) }
        if (!retryAllowed || !active || current.endpoint.isEmpty()) return
        val delayMs = minOf(30_000L,750L * (1L shl minOf(retryCount++,6)))
        retry = scope.launch { delay(delayMs); retry = null; runCatching { reconnect() } }
    }
    suspend fun reconnect() = withContext(dispatcher) {
        val endpoint = current.endpoint
        if (endpoint.isEmpty()) return@withContext
        retryOnForeground = true
        val ticket = stop(); patch { it.copy(status = "connecting",error = null) }
        try { openService(endpoint,ticket) }
        catch (e: Exception) { if (e is CancellationException) throw e; if (ticket != generation) return@withContext; val error = networkError(e); disconnected(message(error),error !is CertificateFailure); throw error }
    }
    fun foreground(active: Boolean) { scope.launch {
        this@WorkbenchRepository.active = active
        if (!active) { retry?.cancel(); retry = null }
        // Keep the live subscription and unfinished stream when returning to the app.
        // The transport heartbeat reports dead sockets through disconnected().
        else if (retryOnForeground && current.endpoint.isNotEmpty() && current.status == "offline") runCatching { reconnect() }
    } }
    suspend fun logout() = setEndpoint("")
    suspend fun command(type: String, fields: JSONObject = JSONObject()): JSONObject = withContext(dispatcher) {
        val reading = fields.stringOrNull("sessionId")?.let { id -> catalogReads.values.find { it.sessionId == id }?.task }
        if (reading != null && type in setOf("session.configure","message.send")) {
            val ticket = generation
            try { reading.await() } catch (e: Exception) { if (e is CancellationException) throw e }
            check(ticket == generation) { "连接已中断，请核对会话后重试。" }
        }
        sendCommand(type,fields,UUID.randomUUID().toString())
    }
    private suspend fun sendCommand(type: String, fields: JSONObject, id: String): JSONObject {
        require(type in allowed) { "工作台不支持此操作。" }
        val frame = JSONObject(fields.toString()).put("type",type).put("requestId",id).toString()
        if (type == "approval.reply" && fields.has("plan")) {
            require(frame.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "计划内容过长，请缩短后重试。" }
        }
        if (type == "quota.watch") quotaWanted = fields.optBoolean("active")
        val connection = socket
        check(connection != null && current.status == "online") { "请等待工作台连接后重试。" }
        check(id !in pending) { "操作正在进行，请等待工作台响应。" }
        val waiter = Pending(CompletableDeferred(),type,fields.stringOrNull("sessionId"))
        pending[id] = waiter
        if (type in historyCommands) {
            historyRequestIds += id
            if (historyRequestIds.size > 256) historyRequestIds.remove(historyRequestIds.first())
        }
        armTimeout(id,waiter)
        try {
            check(connection.send(frame)) { "实时连接已关闭。" }
            return waiter.result.await()
        } finally {
            waiter.timeout?.cancel()
            if (pending[id] === waiter) {
                pending.remove(id)
                if (type in historyCommands) {
                    if (type == "session.subscribe" && waiter.frame == null) waiter.sessionId?.let(needsReload::add)
                    connection.send(json("type" to "session.history.cancel","requestId" to id).toString())
                }
            }
        }
    }
    /** Only matching, valid parts extend the history idle deadline; unrelated traffic cannot. */
    private fun armTimeout(id: String, waiter: Pending) {
        waiter.timeout?.cancel()
        waiter.timeout = scope.launch {
            delay(if (waiter.type in historyCommands) 90_000L else 45_000L)
            if (pending[id] === waiter) {
                val error = IllegalStateException("等待工作台响应超时，请核对操作结果。")
                if (waiter.type in historyCommands) failHistory(id,waiter,error)
                else { pending.remove(id); waiter.result.completeExceptionally(error) }
            }
        }
    }
    private fun cancelHistoryReads(keepSession: String? = null) {
        pending.entries.filter { it.value.type in historyCommands && it.value.sessionId != keepSession }.forEach { (id, waiter) ->
            pending.remove(id); waiter.timeout?.cancel()
            if (waiter.type == "session.subscribe" && waiter.frame == null) waiter.sessionId?.let(needsReload::add)
            socket?.send(json("type" to "session.history.cancel","requestId" to id).toString())
            waiter.result.cancel(CancellationException("已切换会话。"))
            waiter.sessionId?.let { sessionId ->
                if (historyOwners[sessionId] == id) {
                    historyOwners.remove(sessionId)
                    if (sessionId in current.histories) updateHistory(sessionId) { it.copy(loading = false) }
                }
            }
        }
    }
    private fun failHistory(id: String, waiter: Pending, error: Exception) {
        if (pending[id] !== waiter) return
        pending.remove(id); waiter.timeout?.cancel()
        if (waiter.type == "session.subscribe" && waiter.frame == null) waiter.sessionId?.let(needsReload::add)
        socket?.send(json("type" to "session.history.cancel","requestId" to id).toString())
        waiter.result.completeExceptionally(IllegalStateException(error.message ?: "无法读取历史，请重新读取。", error))
    }
    private fun historyPart(frame: JSONObject) {
        val id = frame.optString("requestId")
        val waiter = pending[id] ?: return
        if (waiter.type !in historyCommands) return
        try {
            check(waiter.frame == null && waiter.sessionId == frame.opt("sessionId")) { "历史分块与请求不匹配，请重新读取。" }
            val transfer = waiter.transfer ?: HistoryTransfer(id,requireNotNull(waiter.sessionId),waiter.type == "session.history").also { waiter.transfer = it }
            val page = transfer.append(frame)
            armTimeout(id,waiter)
            if (page != null) {
                waiter.transfer = null
                page.forEach { receive(it, replay = true) }
                val following = waiter.following.toList(); waiter.following.clear(); waiter.followingChars = 0
                following.forEach { receive(it, replay = true) }
            }
            // Commit before the last ACK: transports may deliver its response synchronously.
            // Controls are not commands, and the final ACK releases the original response.
            check(socket?.send(json("type" to "session.history.next","requestId" to id).toString()) == true) { "实时连接已关闭。" }
        } catch (error: Exception) {
            failHistory(id,waiter,if (error is org.json.JSONException) IllegalStateException("历史内容格式无效，请重新读取。",error) else error)
        }
    }
    suspend fun loadModelCatalog(sessionId: String) = withContext(dispatcher) {
        val session = current.sessions.find { it.id == sessionId } ?: error("这个会话已不存在。")
        val key = session.device?.id ?: session.id
        val existing = catalogReads[key]
        if (existing != null) { existing.task.await(); return@withContext }
        val ticket = generation
        val previous = current.modelCatalogs[key]
        catalogAttempts += key
        patch { it.copy(modelCatalogLoading = it.modelCatalogLoading + key, modelCatalogErrors = it.modelCatalogErrors - key) }
        val read = scope.async(start = CoroutineStart.LAZY) {
            try {
                check(ticket == generation) { "连接已中断，请恢复连接后重试。" }
                command("session.status",json("sessionId" to sessionId,"quota" to false))
                check(ticket == generation) { "连接已中断，请恢复连接后重试。" }
                val catalog = current.modelCatalogs[key]
                check(catalog != null && catalog.models.isNotEmpty() && catalog !== previous) { "服务端本次未返回模型目录，可稍后重试。" }
            } catch (e: Exception) {
                if (e !is CancellationException && ticket == generation) patch { it.copy(modelCatalogErrors = it.modelCatalogErrors + (key to message(e))) }
                throw e
            } finally {
                if (ticket == generation) patch { it.copy(modelCatalogLoading = it.modelCatalogLoading - key) }
            }
        }
        catalogReads[key] = CatalogRead(sessionId, read)
        try { read.start(); read.await() } finally { if (catalogReads[key]?.task === read) catalogReads.remove(key) }
    }
    /** Refresh once per device on each connection, without selecting or creating a chat. */
    private fun preloadModelCatalogs() {
        if (current.status != "online") return
        val ticket = generation
        val servers = current.serverDeviceIds()
        val present = current.devices.associateBy { it.id }
        current.sessions.filter { session ->
            session.activeRun == null && session.device?.id != current.localDeviceId &&
                (session.device == null || session.device.server || session.device.id in servers ||
                    (catalogPresenceKnown && present[session.device.id]?.ready == true))
        }.distinctBy { it.device?.id ?: it.id }.forEach { session ->
            val key = session.device?.id ?: session.id
            if (!catalogAttempts.add(key)) return@forEach
            scope.launch {
                if (ticket != generation) return@launch
                try { loadModelCatalog(session.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Exposed in modelCatalogErrors; manual retry remains available. */ }
            }
        }
    }
    suspend fun configureModel(sessionId: String, model: String?, effort: String?) {
        val session = current.sessions.find { it.id == sessionId }
        check(!session?.permissionMode.isNullOrEmpty()) { "尚未读取会话权限，请重新打开会话后重试。" }
        command("session.configure",json("sessionId" to sessionId,"permissionMode" to session!!.permissionMode,"model" to model,"effort" to effort))
    }
    private fun upsert(session: Session): Session? { val old = current.sessions.find { it.id == session.id }; patch { it.copy(sessions = listOf(session) + it.sessions.filter { s -> s.id != session.id }) }; return old }
    private fun cacheCatalog(session: Session, event: DeskEvent) {
        if (event.payload.optString("type") != "native.capabilities" || event.payload.optJSONArray("models") == null) return
        val key = session.device?.id ?: session.id
        if ((current.modelCatalogs[key]?.at ?: "") > event.createdAt) return
        patch { it.copy(modelCatalogs = it.modelCatalogs + (key to Catalog(event.payload.getJSONArray("models").objects().map(ModelInfo::from),event.createdAt)), modelCatalogErrors = it.modelCatalogErrors - key) }
    }
    private fun notify(id: String, sessionId: String, title: String, body: String) {
        if (current.notices.any { it.id == id }) return
        patch { it.copy(notices = (listOf(Notice(id,sessionId,title,body,now())) + it.notices).take(100)) }; savePreferences()
    }
    private fun receive(frame: JSONObject, replay: Boolean = false) {
        val waiter = pending[frame.optString("requestId")]
        if (frame.optString("type") == "session.history.part") { historyPart(frame); return }
        if (!replay) {
            val sessionId = when (frame.optString("type")) {
                "session.updated" -> frame.optJSONObject("session")?.optString("id")
                "session.event" -> frame.optJSONObject("event")?.optString("sessionId")
                "session.fragment" -> frame.optString("sessionId")
                else -> null
            }
            pending.entries.firstOrNull { it.value.type == "session.subscribe" && it.value.transfer != null && it.value.frame == null && it.value.sessionId == sessionId }?.let { (id, reading) ->
                // Catalog reads run independently of chat history. Do not hide their result
                // until after session.status has already acknowledged completion.
                if (frame.optString("type") == "session.event") {
                    val event = DeskEvent.from(frame.getJSONObject("event"))
                    current.sessions.find { it.id == event.sessionId }?.let { cacheCatalog(it,event) }
                }
                if (waiter != null && waiter.type !in historyCommands) waiter.frame = frame
                reading.followingChars += frame.toString().length
                if (reading.followingChars > 16 * 1024 * 1024) failHistory(id,reading,IllegalStateException("历史加载期间实时内容过多，请重新读取。"))
                else reading.following += frame
                return
            }
        }
        if (frame.optString("type") == "response") {
            if (waiter != null) {
                pending.remove(frame.optString("requestId")); waiter.timeout?.cancel()
                if (waiter.type == "session.subscribe" && waiter.frame == null) waiter.sessionId?.let(needsReload::add)
                if (!frame.optBoolean("ok")) waiter.result.completeExceptionally(IllegalStateException(frame.optString("message","工作台拒绝了此操作。")))
                else if (waiter.type in historyCommands && waiter.frame == null) waiter.result.completeExceptionally(IllegalStateException("服务端未返回完整会话历史，请重新读取。"))
                else waiter.result.complete(waiter.frame ?: frame)
            }; return
        }
        if (frame.optString("type") == "session.snapshot" && (waiter == null || waiter.type !in historyCommands || waiter.frame != null || (!replay && waiter.transfer != null) || waiter.sessionId != frame.optJSONObject("session")?.optString("id"))) return
        if (waiter != null && (waiter.type !in historyCommands || frame.optString("type") == "session.snapshot")) waiter.frame = frame
        when (frame.optString("type")) {
            "ready" -> {
                if (!validReady(frame)) { disconnected("工作台协议版本不匹配，请核对服务端版本。",false); return }
                handshake?.cancel(); handshake = null; retryCount = 0
                val sessions = frame.getJSONArray("sessions").objects().map(Session::from)
                val sessionIds = sessions.mapTo(mutableSetOf()) { it.id }
                val catalogKeys = sessions.mapTo(mutableSetOf()) { it.device?.id ?: it.id }
                val nextHistoryId = frame.stringOrNull("historyId")
                val keepHistory = nextHistoryId != null && historyId == nextHistoryId
                historyId = nextHistoryId
                if (keepHistory) needsReload.retainAll(sessionIds) else needsReload.clear()
                val selected = current.selectedId?.takeIf { it in sessionIds }
                patch { it.copy(status = "online", authenticated = true, error = null,
                    version = frame.stringOrNull("version"), defaultModel = frame.stringOrNull("model"),
                    sessions = sessions, selectedId = selected, projects = frame.optJSONArray("projects").objects().map(Project::from),
                    quota = frame.optJSONObject("quota"),
                    histories = if (keepHistory) it.histories.filterKeys(sessionIds::contains) else emptyMap(),
                    modelCatalogs = if (keepHistory) it.modelCatalogs.filterKeys(catalogKeys::contains) else emptyMap(),
                    modelCatalogErrors = emptyMap()) }
                preloadModelCatalogs()
                if (selected != null) {
                    val ticket = generation
                    scope.launch {
                        try { selectSession(selected) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (ticket == generation && current.selectedId == selected && current.histories[selected]?.loadError == null) patch { it.copy(error = message(e)) } }
                    }
                }
                if (quotaWanted) scope.launch { runCatching { command("quota.watch",json("active" to true)) } }
            }
            "devices" -> { val devices = frame.optJSONArray("devices").objects().map(Device::from); catalogPresenceKnown = true; patch { it.copy(devices = devices,serverDeviceId = devices.find { it.server }?.id ?: it.serverDeviceId) }; preloadModelCatalogs() }
            "projects" -> {
                val projects = frame.getJSONArray("projects").objects().map(Project::from)
                patch { it.copy(projects = projects) }
            }
            "quota" -> patch { it.copy(quota = frame) }
            "session.updated" -> {
                val session = Session.from(frame.getJSONObject("session")); val old = upsert(session); preloadModelCatalogs(); if (old == null) return; val run = session.activeRun
                if (run?.status == "awaiting_approval" && (old.activeRun?.status != run.status || old.activeRun?.waiting != run.waiting)) notify("${run.id}:${run.waiting}:${session.updatedAt}",session.id,session.title,when (run.waiting) {
                    "question" -> "Claude 有一个问题等你回答"
                    "plan" -> "Claude 有一份计划等待确认"
                    else -> "有工具操作等待审批"
                })
                else if (run == null && old.activeRun != null) notify("${old.activeRun.id}:ended",session.id,session.title,"本轮运行已结束")
            }
            "session.deleted" -> {
                val id = frame.getString("sessionId")
                pending.entries.filter { it.value.type in historyCommands && it.value.sessionId == id }.toList().forEach { (requestId, reading) -> failHistory(requestId,reading,IllegalStateException("这个会话已不存在。")) }
                needsReload.remove(id); historyOwners.remove(id)
                if (openSession == id) openSession = null
                patch { it.copy(sessions = it.sessions.filter { s -> s.id != id },histories = it.histories - id,selectedId = it.selectedId.takeUnless { selected -> selected == id },pins = it.pins - id,notices = it.notices.filter { n -> n.sessionId != id }) }; savePreferences()
            }
            "session.snapshot" -> {
                val session = Session.from(frame.getJSONObject("session"))
                // Older pages must not roll current run/permission metadata back to their capture time.
                if (frame.optString("mode") != "prepend" || current.sessions.none { it.id == session.id }) upsert(session)
                if (frame.optBoolean("reset")) patch { it.copy(modelCatalogs = it.modelCatalogs - (session.device?.id ?: session.id)) }
                frame.optJSONArray("events").objects().map(DeskEvent::from).findLast { it.payload.optString("type") == "native.capabilities" }?.let { cacheCatalog(session,it) }
                if (frame.optString("mode") != "prepend") {
                    needsReload.remove(session.id)
                    if (current.selectedId == session.id) openSession = session.id
                }
                updateHistory(session.id) { applySnapshot(it,frame).copy(loading = it.loading) }
                if (!replay && waiter?.type == "session.subscribe") {
                    val following = waiter.following.toList(); waiter.following.clear(); waiter.followingChars = 0
                    following.forEach { receive(it,replay = true) }
                }
            }
            "session.fragment" -> { val id = frame.getString("sessionId"); if (id == openSession) updateHistory(id) { addFragment(it,id,frame.getString("runId"),frame.getJSONObject("payload")) } }
            "session.event" -> {
                val event = DeskEvent.from(frame.getJSONObject("event")); val p = event.payload
                current.sessions.find { it.id == event.sessionId }?.let { cacheCatalog(it,event) }
                if (event.sessionId == openSession) updateHistory(event.sessionId) { addEvent(it,event) }
                if (p.optString("type") == "run.status" && p.optString("status") in setOf("cancelled","failed","completed")) notify("${event.runId}:ended",event.sessionId,current.sessions.find { it.id == event.sessionId }?.title ?: "工作台",when(p.optString("status")) { "completed" -> "本轮运行已完成"; "failed" -> "本轮运行失败"; else -> "本轮运行已停止" })
            }
            "connection.error" -> if (frame.optString("code") in setOf("unauthorized","version_mismatch")) { patch { it.copy(authenticated = false) }; disconnected(frame.optString("message","代理服务认证失败，请核对服务凭据和版本。"),false) }
                else if (waiter?.type in historyCommands) failHistory(frame.optString("requestId"),waiter!!,IllegalStateException(frame.optString("message","无法读取历史。")))
                else if (frame.optString("requestId") in historyRequestIds) Unit
                else patch { it.copy(error = frame.stringOrNull("message")) }
            "account.state" -> patch { it.copy(account = frame) }
            "web.settings.state" -> { val values = frame.getJSONObject("values"); patch { it.copy(webSettings = values) } }
            "usage.summary" -> patch { it.copy(usageSummary = frame) }
            "usage.page" -> patch { it.copy(usagePage = frame) }
        }
    }
    /** Navigation must not wait for a network reply or cancel the read when its list disappears. */
    fun openSession(id: String) { scope.launch {
        val ticket = generation
        try { selectSession(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (ticket == generation && current.selectedId == id && current.histories[id]?.loadError == null) patch { it.copy(error = message(e)) } }
    } }
    suspend fun selectSession(id: String) = withContext(dispatcher) {
        check(current.sessions.any { it.id == id }) { "这个会话已不存在。" }
        val alreadyOpen = current.selectedId == id && openSession == id && current.histories[id]?.loadError == null
        cancelHistoryReads(keepSession = id)
        patch { it.copy(selectedId = id) }
        if (current.status != "online") return@withContext
        subscription?.takeIf { it.sessionId == id }?.let { it.task.await(); return@withContext }
        if (alreadyOpen) return@withContext
        subscription?.let { previous ->
            previous.task.cancel()
            updateHistory(previous.sessionId) { it.copy(loading = false) }
        }
        val cursor = current.histories[id]?.events?.lastOrNull()?.takeIf { id !in needsReload && it.createdAt.isNotBlank() }
        openSession = null
        val ticket = generation
        val requestId = UUID.randomUUID().toString()
        historyOwners[id] = requestId
        updateHistory(id) { it.copy(loading = true,loadError = null,fragments = emptyList()) }
        val read = scope.async(start = CoroutineStart.LAZY) {
            try {
                val fields = json("sessionId" to id, "afterSequence" to (cursor?.sequence ?: 0))
                if (cursor != null) fields.put("afterCreatedAt", cursor.createdAt)
                val result = sendCommand("session.subscribe",fields,requestId)
                check(result.optString("type") == "session.snapshot") { "服务端未返回会话历史，请重新读取。" }
            } catch (e: Exception) {
                if (e !is CancellationException && ticket == generation && subscription?.requestId == requestId && id in current.histories) updateHistory(id) { it.copy(loadError = message(e)) }
                throw e
            } finally {
                if (ticket == generation && subscription?.requestId == requestId) {
                    subscription = null
                    if (historyOwners[id] == requestId) {
                        historyOwners.remove(id)
                        if (id in current.histories) updateHistory(id) { it.copy(loading = false) }
                    }
                }
            }
        }
        subscription = Subscription(id,requestId,read)
        read.start()
        read.await()
    }
    fun newDraft() { scope.launch { cancelHistoryReads(); subscription?.task?.cancel(); subscription = null; patch { it.copy(selectedId = null) } } }
    suspend fun createSession(title: String = "新会话", requestId: String = UUID.randomUUID().toString(), onCreated: ((String) -> Unit)? = null): String = withContext(dispatcher) {
        val result = sendCommand("server.session.create",json("title" to title),requestId)
        val id = result.stringOrNull("sessionId") ?: error("服务端没有返回新会话。")
        onCreated?.invoke(id)
        selectSession(id); id
    }
    suspend fun createProjectSession(project: Project, requestId: String): String = withContext(dispatcher) {
        checkProjectOnline(project)
        val result = sendCommand("session.create", json("title" to "新会话", "deviceId" to project.device.id, "projectPath" to project.path), requestId)
        result.getString("sessionId")
    }

    suspend fun moveSessionToProject(sessionId: String, project: Project) = withContext(dispatcher) {
        val session = current.sessions.find { it.id == sessionId } ?: error("这个会话已不存在。")
        check(session.activeRun == null) { "请先结束当前运行。" }
        checkProjectOnline(project)
        command("session.move", json("sessionId" to sessionId, "deviceId" to project.device.id, "projectPath" to project.path))
    }

    private fun checkProjectOnline(project: Project) {
        check(current.projects.any { it.device.id == project.device.id && it.root == project.root }) { "这个项目已不存在。" }
        check(current.status == "online" && current.devices.any { it.id == project.device.id && it.ready == true }) { "项目所在电脑未就绪。" }
    }
    suspend fun loadEarlier(id: String) = withContext(dispatcher) {
        val history = current.histories[id] ?: return@withContext
        if (!history.hasEarlier || history.loading || history.events.isEmpty()) return@withContext
        val ticket = generation
        val requestId = UUID.randomUUID().toString()
        historyOwners[id] = requestId
        updateHistory(id) { it.copy(loading = true) }
        try { sendCommand("session.history",json("sessionId" to id,"beforeSequence" to history.events.first().sequence),requestId) }
        finally {
            if (ticket == generation && historyOwners[id] == requestId) {
                historyOwners.remove(id)
                if (id in current.histories) updateHistory(id) { it.copy(loading = false) }
            }
        }
    }
    suspend fun sendMessage(text: String, model: String? = null, effort: String? = null, applyChoice: Boolean = false) = withContext(dispatcher) {
        require(text.trim().isNotEmpty() && text.length <= 16000) { "请输入 1 至 16000 个字符。" }
        check(!sendInFlight) { "消息正在发送，请等待工作台响应。" }
        sendInFlight = true
        try {
            val selected = current.selectedId
            val id = selected ?: createSession(requestId = draftRequestId ?: UUID.randomUUID().toString().also { draftRequestId = it }).also { draftRequestId = null }
            if (selected == null && (applyChoice || model != null || effort != null)) configureModel(id,model,effort)
            command("message.send",json("sessionId" to id,"text" to text.trim()))
        } finally { sendInFlight = false }
    }
    private fun savePreferences() {
        val endpoint = current.endpoint; val prefs = Preferences(current.pins,current.notices); val previous = writes
        writes = persistenceScope.launch { previous?.join(); try { transport.writePreferences(endpoint,prefs) } catch (_: Exception) { if (!disposed && current.endpoint == endpoint) patch { it.copy(error = "本机偏好暂时无法保存。") } } }
    }
    fun togglePin(id: String) { scope.launch { if (current.sessions.any { it.id == id }) { patch { it.copy(pins = if (id in it.pins) it.pins - id else it.pins + id) }; savePreferences() } } }
    fun readNotice(id: String? = null) { scope.launch { patch { it.copy(notices = it.notices.map { n -> if (id == null || n.id == id) n.copy(read = true) else n }) }; savePreferences() } }
    fun setQuotaWatching(active: Boolean) { scope.launch { quotaWanted = active; if (current.status == "online") runCatching { command("quota.watch",json("active" to active)) } } }
    fun dispose() {
        if (disposed) return
        disposed = true; active = false; stop(); transport.close(); scope.cancel()
        val pendingWrites = writes
        persistenceScope.launch {
            pendingWrites?.join()
            persistenceDrained.complete(Unit)
            persistenceScope.cancel()
        }
    }
    internal suspend fun awaitPersistenceDrain() { persistenceDrained.await() }
}
