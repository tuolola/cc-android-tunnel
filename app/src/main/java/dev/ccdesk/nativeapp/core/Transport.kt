// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

interface SocketConnection { fun send(text: String): Boolean; fun close() }
interface SocketObserver { fun opened(); fun message(text: String); fun closed(code: Int, reason: String); fun failed(cause: Throwable) }
interface WorkbenchTransport {
    suspend fun getEndpoint(): String
    suspend fun setEndpoint(endpoint: String)
    suspend fun readPreferences(endpoint: String): Preferences
    suspend fun writePreferences(endpoint: String, preferences: Preferences)
    suspend fun credential(endpoint: String): String
    suspend fun saveCredential(endpoint: String, credential: String)
    suspend fun deviceId(): String
    suspend fun fingerprint(endpoint: String): String
    suspend fun setFingerprint(endpoint: String, fingerprint: String)
    suspend fun clearOrigin(endpoint: String)
    suspend fun clearBrowserSession(endpoint: String)
    suspend fun http(endpoint: String, route: String, body: JSONObject? = null): JSONObject
    suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection
    fun close()
}

internal class AndroidWorkbenchTransport(context: Context) : WorkbenchTransport {
    private val prefs = context.getSharedPreferences("ccdesk-native-v1",Context.MODE_PRIVATE)
    private val secrets = PinnedSecrets(context)
    private val identityGate = Mutex()
    private val originGates = mutableMapOf<String,Mutex>()
    // Independent of the repository/UI scope: lifecycle cancellation must not cancel TLS shutdown.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shutdownComplete = CompletableDeferred<Unit>()
    private var closed = false
    private data class ClientKey(val origin: String, val pin: String, val direct: Boolean, val websocket: Boolean)
    private data class Client(val http: OkHttpClient, val cookies: PinnedCookies?)
    private val clients = mutableMapOf<ClientKey,Client>()
    private val sockets = mutableMapOf<WebSocket,String>()
    private val calls = mutableMapOf<Call,String>()
    private data class Retired(val sockets: List<WebSocket>, val calls: List<Call>, val clients: List<Client>)
    private fun url(endpoint: String) = parseUrl(endpoint,endpoint.startsWith("wss:",true))
    private suspend fun <T> withOrigin(endpoint: String, action: suspend (HttpUrl) -> T): T = withContext(Dispatchers.IO) {
        val target = url(endpoint)
        val gate = synchronized(this@AndroidWorkbenchTransport) { originGates.getOrPut(target.origin()) { Mutex() } }
        gate.withLock { action(target) }
    }
    private fun storedPin(origin: String): String {
        val value = prefs.getString("certificate:$origin","").orEmpty()
        check(normalizeFingerprint(value) == value) { "已保存的证书指纹无效。" }
        return value
    }
    @Synchronized private fun client(url: HttpUrl, direct: Boolean, websocket: Boolean = false): Client {
        check(!closed) { "Workbench transport is closed" }
        val key = ClientKey(url.origin(), storedPin(url.origin()), direct,websocket)
        return clients.getOrPut(key) {
            val jar = if (direct) null else PinnedCookies(secrets,key.origin,key.pin)
            val http=secureClient(key.origin,key.pin,jar ?: CookieJar.NO_COOKIES)
            // OkHttp suspends the read timeout while waiting for a frame header, but
            // restores it while reading the payload. A large history must not inherit
            // the short HTTP read timeout. The repository bounds history reads separately.
            // Pongs cannot overtake a large, unfragmented history frame. Allow two 30-second
            // intervals: real 459 KB snapshots can take ~37 seconds on a slow service link.
            // A silent peer still fails within about a minute; no business frame is used as a ping.
            Client(if(websocket)http.newBuilder().readTimeout(90,TimeUnit.SECONDS).pingInterval(30,TimeUnit.SECONDS).build()else http,jar)
        }
    }
    @Synchronized private fun retire(origin: String?): Retired {
        val retiredSockets = sockets.filterValues { origin == null || it == origin }.keys.toList()
        retiredSockets.forEach(sockets::remove)
        val retiredCalls = calls.filterValues { origin == null || it == origin }.keys.toList()
        retiredCalls.forEach(calls::remove)
        val keys = clients.keys.filter { origin == null || it.origin == origin }
        val retiredClients = keys.mapNotNull(clients::remove)
        // Retire synchronously before releasing the lock. An old response can no longer save a cookie.
        retiredClients.forEach { it.cookies?.retire() }
        return Retired(retiredSockets,retiredCalls,retiredClients)
    }
    private fun closeNetwork(resources: Retired) {
        // TLS close_notify can write to the network, including from ConnectionPool.evictAll().
        // This function is called only on Dispatchers.IO and never while holding the state lock.
        resources.sockets.forEach(WebSocket::cancel)
        resources.calls.forEach(Call::cancel)
        resources.clients.forEach { it.http.dispatcher.cancelAll(); it.http.connectionPool.evictAll() }
    }
    private fun clear(url: HttpUrl) {
        closeNetwork(retire(url.origin()))
        secrets.clearCookies(url.origin())
        secrets.write(secrets.credentialName(url.newBuilder().encodedPath("/ws").build()),"")
    }
    override suspend fun getEndpoint() = withContext(Dispatchers.IO) { prefs.getString("endpoint","").orEmpty() }
    override suspend fun setEndpoint(endpoint: String) = withContext(Dispatchers.IO) { check(prefs.edit().putString("endpoint",endpoint).commit()); Unit }
    override suspend fun readPreferences(endpoint: String) = withContext(Dispatchers.IO) {
        runCatching {
            val data = JSONObject(prefs.getString("preferences:$endpoint","{}").orEmpty())
            Preferences(data.optJSONArray("pins").strings(),data.optJSONArray("notices").objects().mapNotNull { runCatching { Notice.from(it) }.getOrNull() }.take(100))
        }.getOrDefault(Preferences())
    }
    override suspend fun writePreferences(endpoint: String, preferences: Preferences) = withContext(Dispatchers.IO) {
        val data = json("pins" to JSONArray(preferences.pins),"notices" to JSONArray(preferences.notices.map { it.toJson() }))
        check(prefs.edit().putString("preferences:$endpoint",data.toString()).commit()) { "本机偏好暂时无法保存。" }; Unit
    }
    override suspend fun credential(endpoint: String) = withOrigin(endpoint) { secrets.read(secrets.credentialName(it)) }
    override suspend fun saveCredential(endpoint: String, credential: String) = withOrigin(endpoint) { secrets.write(secrets.credentialName(it),credential) }
    override suspend fun deviceId() = withContext(Dispatchers.IO) { identityGate.withLock {
        prefs.getString("device",null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("device",it).commit()) }
    } }
    override suspend fun fingerprint(endpoint: String) = withOrigin(endpoint) { storedPin(it.origin()) }
    override suspend fun setFingerprint(endpoint: String, fingerprint: String) = withOrigin(endpoint) { target ->
        val pin = normalizeFingerprint(fingerprint)
        if (storedPin(target.origin()) != pin) {
            clear(target)
            check(prefs.edit().putString("certificate:${target.origin()}",pin).commit()) { "无法保存证书配置。" }
        }
    }
    override suspend fun clearOrigin(endpoint: String) = withOrigin(endpoint) { clear(it) }
    override suspend fun clearBrowserSession(endpoint: String) = withOrigin(normalizeEndpoint(endpoint)) { target ->
        secrets.write(secrets.cookieName(target.origin(),storedPin(target.origin()),target.encodedPath),"")
    }
    override suspend fun http(endpoint: String, route: String, body: JSONObject?): JSONObject = withOrigin(normalizeEndpoint(endpoint)) { base ->
        require((route == "session" && body == null) || (route in setOf("login","logout") && body != null)) { "Unsupported workbench request" }
        val target = base.resolve("api/$route")!!
        val request = Request.Builder().url(target).header("Origin",target.origin()).header("Accept","application/json")
        if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = synchronized(this@AndroidWorkbenchTransport) {
            client(target,false).http.newCall(request.build()).also { calls[it] = target.origin() }
        }
        try {
            call.execute().use { response ->
                val data = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                check(response.isSuccessful) { data?.optString("error")?.takeIf { it.isNotEmpty() } ?: "工作台返回 HTTP ${response.code}，请核对入口地址。" }
                data ?: error("此地址没有返回工作台数据，请核对入口路径。")
            }
        } catch (error: Exception) { throw networkError(error) }
        finally { synchronized(this@AndroidWorkbenchTransport) { calls.remove(call) } }
    }
    override suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection = withOrigin(endpoint) { target ->
        require(endpoint.startsWith("wss:",true)) { "Expected a secure WebSocket address" }
        require(if (direct) target.encodedPath == "/ws" else target.encodedPath.endsWith("/api/ws"))
        val request = Request.Builder().url(target)
        if (!direct) request.header("Origin",target.origin())
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = observer.opened()
            override fun onMessage(webSocket: WebSocket, text: String) = observer.message(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(if (code == 1005) 1000 else code,null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { synchronized(this@AndroidWorkbenchTransport) { sockets.remove(webSocket) }; observer.closed(code,reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { synchronized(this@AndroidWorkbenchTransport) { sockets.remove(webSocket) }; observer.failed(networkError(t)) }
        }
        val websocket = synchronized(this@AndroidWorkbenchTransport) {
            client(target,direct,true).http.newWebSocket(request.build(),listener).also { sockets[it] = target.origin() }
        }
        // Keep closing sockets tracked until onClosed/onFailure, so disposal can cancel an unresponsive peer.
        object : SocketConnection { override fun send(text: String) = websocket.send(text); override fun close() { websocket.close(1000,null) } }
    }
    override fun close() {
        val resources = synchronized(this) {
            if (closed) return
            closed = true
            retire(null)
        }
        cleanupScope.launch {
            try { closeNetwork(resources); shutdownComplete.complete(Unit) }
            catch (error: Throwable) { shutdownComplete.completeExceptionally(error); throw error }
            finally { cleanupScope.cancel() }
        }
    }
    internal suspend fun awaitClosed() { shutdownComplete.await() }
}
