package dev.ccdesk.nativeapp.ui

import android.os.Handler
import android.os.Looper
import dev.ccdesk.nativeapp.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory protocol 25 peer for UI tests. It never opens a socket, reads credentials or
 * contacts a service; it answers subscribe and create requests like the workbench does.
 */
internal class FixtureTransport(private val device: JSONObject = json("id" to "server", "name" to "Fixture server")) : WorkbenchTransport {
    val sent = CopyOnWriteArrayList<JSONObject>()
    private val main = Handler(Looper.getMainLooper())
    private var observer: SocketObserver? = null
    private var created = 0
    val sessions = mutableListOf(session("session-1", "UI fixture"))
    /** Events returned by the next session snapshot. */
    var events = JSONArray()

    fun session(id: String, title: String, model: String? = null, activeRun: JSONObject? = null): JSONObject =
        json("id" to id, "title" to title, "projectPath" to "/workspace/fixture-project", "permissionMode" to "default", "device" to device,
            "activeRun" to (activeRun ?: JSONObject.NULL), "model" to (model ?: JSONObject.NULL), "updatedAt" to "2026-11-14T00:00:00Z")
    fun frame(value: JSONObject) { main.post { observer?.message(value.toString()) } }
    fun update(session: JSONObject) { sessions.replaceAll { if (it.getString("id") == session.getString("id")) session else it }; frame(json("type" to "session.updated", "session" to session)) }
    /** Streams one live chunk exactly as the workbench does for an open session. */
    fun fragment(sessionId: String, runId: String, payload: JSONObject) { frame(json("type" to "session.fragment", "sessionId" to sessionId, "runId" to runId, "payload" to payload)) }
    fun sentOf(type: String) = sent.filter { it.optString("type") == type }

    override suspend fun getEndpoint() = "wss://fixture.test/ws"
    override suspend fun setEndpoint(endpoint: String) {}
    override suspend fun readPreferences(endpoint: String) = Preferences()
    override suspend fun writePreferences(endpoint: String, preferences: Preferences) {}
    override suspend fun credential(endpoint: String) = "fixture-service-token-0123456789"
    override suspend fun saveCredential(endpoint: String, credential: String) {}
    override suspend fun deviceId() = "fixture-device"
    override suspend fun fingerprint(endpoint: String) = ""
    override suspend fun setFingerprint(endpoint: String, fingerprint: String) {}
    override suspend fun clearOrigin(endpoint: String) {}

    override suspend fun socket(endpoint: String, observer: SocketObserver): SocketConnection {
        this.observer = observer
        main.post { observer.opened() }
        return object : SocketConnection {
            override fun send(text: String): Boolean {
                val request = JSONObject(text); sent += request
                if (request.optString("type") == "auth") {
                    check(request.optString("token") == "fixture-service-token-0123456789")
                    check(request.optInt("protocolVersion") == 25 && !request.optBoolean("tunnel", true))
                    frame(json("type" to "ready", "protocolVersion" to 25, "sessions" to JSONArray(sessions)))
                    frame(json("type" to "devices", "devices" to JSONArray().put(json("id" to "server", "name" to "Fixture server", "server" to true, "ready" to true))))
                }
                val id = request.optString("requestId")
                if (id.isEmpty()) return true
                when (request.optString("type")) {
                    "session.subscribe" -> sessions.find { it.getString("id") == request.getString("sessionId") }?.let {
                        frame(json("type" to "session.snapshot", "requestId" to id, "session" to it, "mode" to "replace", "events" to events, "hasEarlier" to false))
                    }
                    "server.session.create" -> {
                        val session = session("created-${++created}", request.optString("title", "新会话"))
                        sessions += session
                        frame(json("type" to "session.updated", "requestId" to id, "sessionId" to session.getString("id"), "session" to session))
                    }
                    "session.configure" -> sessions.find { it.getString("id") == request.getString("sessionId") }?.let { previous ->
                        val configured = JSONObject(previous.toString())
                        for (field in listOf("permissionMode", "model", "effort")) {
                            if (request.has(field)) configured.put(field, request.get(field))
                        }
                        update(configured)
                    }
                }
                frame(json("type" to "response", "requestId" to id, "ok" to true))
                return true
            }
            override fun close() {}
        }
    }
    override fun close() {}
}
