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
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

const val PROTOCOL_VERSION = 25
private val allowed = setOf("server.session.create","session.subscribe","session.history","session.status","session.stats",
    "session.rename","session.delete","session.configure","session.compact","message.send","run.cancel","run.continue",
    "run.task.stop","run.task.background","approval.reply","usage.summary","usage.query","quota.watch","account.status",
    "compaction.get","web.settings.get","web.settings.update","web.sessions.revoke","web.totp.setup","web.totp.enable","web.totp.disable")
private fun validReady(frame: JSONObject, direct: Boolean): Boolean {
    val version = frame.opt("protocolVersion")
    return version is Number && version.toDouble() == PROTOCOL_VERSION.toDouble() &&
        (if (direct) frame.isNull("browser") || frame.opt("browser") == false else frame.optJSONObject("browser") != null) && frame.optJSONArray("sessions") != null
}

/** Protocol 25 client. All state and callbacks are confined to [dispatcher]; IO lives in transport. */
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
    private var lastActivity = 0L
    private data class Pending(val result: CompletableDeferred<JSONObject>, var frame: JSONObject? = null)
    private val pending = mutableMapOf<String,Pending>()
    private val catalogReads = mutableMapOf<String,Deferred<Unit>>()
    private var writes: Job? = null
    private var preferenceLoaded = false
    private var quotaWanted = false
    private var refreshingQuota = false
    private var sendInFlight = false
    private var draftRequestId: String? = null
    private fun patch(block: (WorkbenchState) -> WorkbenchState) { mutableState.value = block(current) }
    private fun updateHistory(id: String, block: (History) -> History) { val before = current.histories[id] ?: History(); val after = block(before); if (after !== before) patch { it.copy(histories = it.histories + (id to after)) } }
    private fun message(error: Throwable) = error.message ?: "连接失败，请检查网络和工作台地址。"
    private fun stop(): Long {
        generation++
        retry?.cancel(); retry = null; handshake?.cancel(); handshake = null
        val old = socket; socket = null; openSession = null; old?.close()
        pending.values.forEach { it.result.completeExceptionally(IllegalStateException("连接已中断，请恢复连接后核对操作结果。")) }
        pending.clear(); catalogReads.clear(); refreshingQuota = false
        patch { it.copy(histories = it.histories.mapValues { (_, h) -> h.copy(loading = false,fragments = emptyList()) }) }
        return generation
    }
    suspend fun start() = withContext(dispatcher) {
        active = true
        val ticket = generation
        try {
            val raw = transport.getEndpoint()
            val endpoint = if (raw.startsWith("wss:",true)) normalizeServiceAddress(raw) else normalizeEndpoint(raw)
            if (ticket != generation) return@withContext
            patch { it.copy(endpoint = endpoint,status = if (endpoint.isEmpty()) "setup" else "connecting") }
            if (endpoint.isNotEmpty()) reconnect()
        } catch (e: Exception) { if (e is CancellationException) throw e; if (ticket == generation) patch { it.copy(status = "setup",error = message(e)) } }
    }
    suspend fun setEndpoint(raw: String) = withContext(dispatcher) {
        val endpoint = normalizeEndpoint(raw)
        val forgotten = current.endpoint.takeIf { endpoint.isEmpty() && it.startsWith("wss:") }
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
        val endpoint = if (rawEndpoint.startsWith("wss:",true)) normalizeServiceAddress(rawEndpoint) else normalizeEndpoint(rawEndpoint)
        require(endpoint.isNotEmpty())
        val pin = normalizeFingerprint(rawPin)
        if (transport.fingerprint(endpoint) == pin) return@withContext
        if (current.endpoint.isNotEmpty() && parseUrl(endpoint,endpoint.startsWith("wss:")).origin() == parseUrl(current.endpoint,current.endpoint.startsWith("wss:")).origin()) {
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
    private suspend fun establish(data: JSONObject, ticket: Long) {
        if (ticket != generation) return
        require(data.opt("authenticated") is Boolean) { "这个地址没有返回工作台登录信息。" }
        if (!data.getBoolean("authenticated")) {
            preferenceLoaded = false
            mutableState.value = WorkbenchState(endpoint = current.endpoint,status = "login",totpEnabled = data.optBoolean("totpEnabled")); return
        }
        val endpoint = current.endpoint
        patch { it.copy(authenticated = true,totpEnabled = data.optBoolean("totpEnabled"),webSessionId = data.optJSONObject("session")?.stringOrNull("id"),status = "connecting") }
        loadPreferences(endpoint,ticket)
        if (ticket != generation) return
        val socketUrl = parseUrl(endpoint,false).resolve("api/ws")!!.toString().replaceFirst("https:","wss:")
        attach(socketUrl,false,json("type" to "web.auth","protocolVersion" to PROTOCOL_VERSION),ticket)
    }
    private suspend fun openService(endpoint: String, ticket: Long) {
        val token = transport.credential(endpoint)
        if (ticket != generation) return
        if (token.isEmpty()) { patch { it.copy(status = "setup",error = "请输入此服务地址对应的服务凭据。",authenticated = false) }; return }
        val deviceId = transport.deviceId()
        loadPreferences(endpoint,ticket)
        if (ticket != generation) return
        attach(endpoint,true,json("type" to "auth","protocolVersion" to PROTOCOL_VERSION,"token" to token,"deviceName" to "CC Desk Tunnel · Android","tunnel" to false),ticket,deviceId)
    }
    private suspend fun attach(url: String, direct: Boolean, greeting: JSONObject, ticket: Long, deviceId: String? = null) {
        val connected = CompletableDeferred<SocketConnection>()
        val listener = object : SocketObserver {
            override fun opened() { scope.launch { val connection = connected.await(); if (ticket == generation && !connection.send(greeting.toString())) disconnected("实时连接已关闭。") } }
            override fun message(text: String) { scope.launch {
                if (ticket != generation) return@launch
                try {
                    val frame = JSONObject(text)
                    if (deviceId != null && frame.optString("type") == "ready" && validReady(frame,direct)) connected.await().send(json("type" to "device","id" to deviceId).toString())
                    receive(frame)
                } catch (e: Exception) { if (e is CancellationException) throw e; disconnected("工作台返回了无法读取的数据。") }
            } }
            override fun closed(code: Int, reason: String) { scope.launch {
                if (ticket != generation) return@launch
                if (direct && code in 4001..4003) disconnected(if (code == 4001) "服务认证失败，请核对服务凭据。" else "服务拒绝连接，请核对客户端协议版本。",false)
                else if (code == 4001 && reason == "Invalid browser authentication") disconnected("登录握手失败，请核对工作台版本与登录状态。",false)
                else disconnected(if (code == 4001) "登录状态已改变。" else "实时连接已断开，正在重新连接。")
            } }
            override fun failed(cause: Throwable) { scope.launch { if (ticket == generation) { val error = networkError(cause); disconnected(message(error),error !is CertificateFailure) } } }
        }
        try {
            val connection = transport.socket(url,direct,listener)
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
        try { if (endpoint.startsWith("wss:")) openService(endpoint,ticket) else establish(transport.http(endpoint,"session"),ticket) }
        catch (e: Exception) { if (e is CancellationException) throw e; if (ticket != generation) return@withContext; val error = networkError(e); disconnected(message(error),error !is CertificateFailure); throw error }
    }
    fun foreground(active: Boolean) { scope.launch {
        this@WorkbenchRepository.active = active
        if (!active) { retry?.cancel(); retry = null }
        else if (retryOnForeground && current.endpoint.isNotEmpty() && current.status in setOf("offline","online")) runCatching { reconnect() }
    } }
    suspend fun login(password: String, code: String? = null) = withContext(dispatcher) {
        val endpoint = current.endpoint
        require(endpoint.isNotEmpty() && password.isNotEmpty()) { "请填写工作台地址和服务凭据。" }
        val ticket = stop(); patch { it.copy(status = "connecting",error = null) }
        try {
            val body = json("password" to password).apply { if (!code.isNullOrEmpty()) put("code",code) }
            val result = transport.http(endpoint,"login",body)
            check(result.optBoolean("authenticated")) { "登录失败，请检查凭据。" }
            establish(result,ticket)
        } catch (e: Exception) { if (e is CancellationException) throw e; if (ticket != generation) return@withContext; patch { it.copy(status = if (it.authenticated) "offline" else "login",error = message(e)) }; throw e }
    }
    suspend fun logout() = withContext(dispatcher) {
        val endpoint = current.endpoint
        if (endpoint.startsWith("wss:")) { setEndpoint(""); return@withContext }
        val ticket = stop(); patch { it.copy(status = "connecting") }
        try { transport.http(endpoint,"logout",JSONObject()) } catch (e: Exception) { if (ticket == generation) disconnected(message(e)); throw e }
        if (ticket != generation) return@withContext
        stop(); preferenceLoaded = false; quotaWanted = false
        val pins = current.pins
        mutableState.value = WorkbenchState(endpoint = endpoint,status = "login",totpEnabled = current.totpEnabled)
        transport.clearBrowserSession(endpoint)
        val previous = writes
        val cleared = persistenceScope.async { previous?.join(); transport.writePreferences(endpoint,Preferences(pins, emptyList())) }
        writes = cleared
        cleared.await()
    }
    suspend fun command(type: String, fields: JSONObject = JSONObject()): JSONObject = withContext(dispatcher) {
        val reading = fields.stringOrNull("sessionId")?.let { catalogReads[it] }
        if (reading != null && type in setOf("session.configure","message.send")) {
            val ticket = generation
            try { reading.await() } catch (e: Exception) { if (e is CancellationException) throw e }
            check(ticket == generation) { "连接已中断，请核对会话后重试。" }
        }
        sendCommand(type,fields,UUID.randomUUID().toString())
    }
    private suspend fun sendCommand(type: String, fields: JSONObject, id: String): JSONObject {
        require(type in allowed) { "工作台不支持此操作。" }
        if (type == "quota.watch") quotaWanted = fields.optBoolean("active")
        val connection = socket
        check(connection != null && current.status == "online") { "请等待工作台连接后重试。" }
        check(id !in pending) { "操作正在进行，请等待工作台响应。" }
        val waiter = Pending(CompletableDeferred())
        pending[id] = waiter
        try {
            val frame = JSONObject(fields.toString()).put("type",type).put("requestId",id)
            check(connection.send(frame.toString())) { "实时连接已关闭。" }
            return withTimeout(45_000) { waiter.result.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw IllegalStateException("等待工作台响应超时，请核对操作结果。",e) }
        finally { if (pending[id] === waiter) pending.remove(id) }
    }
    suspend fun loadModelCatalog(sessionId: String) = withContext(dispatcher) {
        val existing = catalogReads[sessionId]
        if (existing != null) { existing.await(); return@withContext }
        val session = current.sessions.find { it.id == sessionId } ?: error("这个会话已不存在。")
        val key = session.device?.id ?: session.id
        val previous = current.modelCatalogs[key]
        val read = scope.async(start = CoroutineStart.LAZY) {
            command("session.status",json("sessionId" to sessionId,"quota" to false))
            val catalog = current.modelCatalogs[key]
            check(catalog != null && catalog.models.isNotEmpty() && catalog !== previous) { "服务端本次未返回模型目录，可稍后重试。" }
        }
        catalogReads[sessionId] = read
        try { read.start(); read.await() } finally { if (catalogReads[sessionId] === read) catalogReads.remove(sessionId) }
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
        patch { it.copy(modelCatalogs = it.modelCatalogs + (key to Catalog(event.payload.getJSONArray("models").objects().map(ModelInfo::from),event.createdAt))) }
    }
    private fun notify(id: String, sessionId: String, title: String, body: String) {
        if (current.notices.any { it.id == id }) return
        patch { it.copy(notices = (listOf(Notice(id,sessionId,title,body,now())) + it.notices).take(100)) }; savePreferences()
    }
    private fun receive(frame: JSONObject) {
        val waiter = pending[frame.optString("requestId")]
        if (frame.optString("type") == "response") {
            if (waiter != null) { pending.remove(frame.optString("requestId")); if (frame.optBoolean("ok")) waiter.result.complete(waiter.frame ?: frame) else waiter.result.completeExceptionally(IllegalStateException(frame.optString("message","工作台拒绝了此操作。"))) }; return
        }
        if (waiter != null) waiter.frame = frame
        when (frame.optString("type")) {
            "ready" -> {
                val direct = current.endpoint.startsWith("wss:")
                if (!validReady(frame,direct)) { disconnected("工作台协议版本不匹配，请核对服务端版本。",false); return }
                handshake?.cancel(); handshake = null; retryCount = 0
                val sessions = frame.getJSONArray("sessions").objects().map(Session::from)
                val selected = current.selectedId?.takeIf { id -> sessions.any { it.id == id } }
                patch { it.copy(status = "online",authenticated = true,error = null,version = frame.stringOrNull("version"),defaultModel = frame.stringOrNull("model"),webSessionId = frame.optJSONObject("browser")?.stringOrNull("sessionId"),serverDeviceId = frame.optJSONObject("browser")?.stringOrNull("serverDeviceId"),sessions = sessions,selectedId = selected) }
                if (selected != null) scope.launch { runCatching { selectSession(selected) }.onFailure { e -> patch { it.copy(error = message(e)) } } }
                if (quotaWanted) scope.launch { runCatching { command("quota.watch",json("active" to true)) } }
            }
            "devices" -> { val devices = frame.optJSONArray("devices").objects().map(Device::from); patch { it.copy(devices = devices,serverDeviceId = devices.find { it.server }?.id ?: it.serverDeviceId) } }
            "session.updated" -> {
                val session = Session.from(frame.getJSONObject("session")); val old = upsert(session) ?: return; val run = session.activeRun
                if (run?.status == "awaiting_approval" && (old.activeRun?.status != run.status || old.activeRun?.waiting != run.waiting)) notify("${run.id}:${run.waiting}:${session.updatedAt}",session.id,session.title,if (run.waiting == "question") "Claude 有一个问题等你回答" else "有工具操作等待审批")
                else if (run == null && old.activeRun != null) notify("${old.activeRun.id}:ended",session.id,session.title,"本轮运行已结束")
            }
            "session.deleted" -> { val id = frame.getString("sessionId"); if (openSession == id) openSession = null; patch { it.copy(sessions = it.sessions.filter { s -> s.id != id },histories = it.histories - id,selectedId = it.selectedId.takeUnless { selected -> selected == id },pins = it.pins - id,notices = it.notices.filter { n -> n.sessionId != id }) }; savePreferences() }
            "session.snapshot" -> { val session = Session.from(frame.getJSONObject("session")); upsert(session); frame.optJSONArray("events").objects().map(DeskEvent::from).findLast { it.payload.optString("type") == "native.capabilities" }?.let { cacheCatalog(session,it) }; if (frame.optString("mode") != "prepend") openSession = session.id; updateHistory(session.id) { applySnapshot(it,frame) } }
            "session.fragment" -> { val id = frame.getString("sessionId"); if (id == openSession) updateHistory(id) { addFragment(it,id,frame.getString("runId"),frame.getJSONObject("payload")) } }
            "session.event" -> {
                val event = DeskEvent.from(frame.getJSONObject("event")); val p = event.payload
                current.sessions.find { it.id == event.sessionId }?.let { cacheCatalog(it,event) }
                if (p.optString("type") == "native.metrics" && p.optJSONObject("rateLimits")?.optBoolean("available") == true && quotaWanted && !refreshingQuota) { refreshingQuota = true; val ticket = generation; scope.launch { try { command("usage.summary") } catch (_: Exception) {} finally { if (ticket == generation) refreshingQuota = false } } }
                if (event.sessionId == openSession) updateHistory(event.sessionId) { addEvent(it,event) }
                if (p.optString("type") == "run.status" && p.optString("status") in setOf("cancelled","failed","completed")) notify("${event.runId}:ended",event.sessionId,current.sessions.find { it.id == event.sessionId }?.title ?: "工作台",when(p.optString("status")) { "completed" -> "本轮运行已完成"; "failed" -> "本轮运行失败"; else -> "本轮运行已停止" })
            }
            "connection.error" -> if (current.endpoint.startsWith("wss:") && frame.optString("code") in setOf("unauthorized","version_mismatch")) { patch { it.copy(authenticated = false) }; disconnected(frame.optString("message","代理服务认证失败，请核对服务凭据和版本。"),false) } else patch { it.copy(error = frame.stringOrNull("message")) }
            "account.state" -> patch { it.copy(account = frame) }
            "web.settings.state" -> { val values = frame.getJSONObject("values"); patch { it.copy(webSettings = values,totpEnabled = values.optBoolean("totpEnabled")) } }
            "usage.summary" -> patch { it.copy(usageSummary = frame) }
            "usage.page" -> patch { it.copy(usagePage = frame) }
        }
    }
    suspend fun selectSession(id: String) = withContext(dispatcher) {
        check(current.sessions.any { it.id == id }) { "这个会话已不存在。" }
        val alreadyOpen = current.selectedId == id && openSession == id
        patch { it.copy(selectedId = id) }
        if (current.status != "online" || alreadyOpen) return@withContext
        val after = current.histories[id]?.events?.lastOrNull()?.sequence ?: 0
        val ticket = generation
        updateHistory(id) { it.copy(loading = true,loadError = null,fragments = emptyList()) }
        try { command("session.subscribe",json("sessionId" to id,"afterSequence" to after)) }
        catch (e: Exception) { if (ticket == generation && id in current.histories) updateHistory(id) { it.copy(loadError = message(e)) }; throw e }
        finally { if (id in current.histories) updateHistory(id) { it.copy(loading = false) } }
    }
    fun newDraft() { scope.launch { patch { it.copy(selectedId = null) } } }
    suspend fun createSession(title: String = "新会话", requestId: String = UUID.randomUUID().toString(), onCreated: ((String) -> Unit)? = null): String = withContext(dispatcher) {
        val result = sendCommand("server.session.create",json("title" to title),requestId)
        val id = result.stringOrNull("sessionId") ?: error("服务端没有返回新会话。")
        onCreated?.invoke(id)
        selectSession(id); id
    }
    suspend fun loadEarlier(id: String) = withContext(dispatcher) {
        val history = current.histories[id] ?: return@withContext
        if (!history.hasEarlier || history.loading || history.events.isEmpty()) return@withContext
        updateHistory(id) { it.copy(loading = true) }
        try { command("session.history",json("sessionId" to id,"beforeSequence" to history.events.first().sequence)) }
        finally { if (id in current.histories) updateHistory(id) { it.copy(loading = false) } }
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
    fun activity() { scope.launch { if (socket != null && current.webSessionId != null && current.status == "online" && System.currentTimeMillis() - lastActivity >= 10_000) { lastActivity = System.currentTimeMillis(); socket?.send(json("type" to "web.activity").toString()) } } }
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
