package dev.ccdesk.nativeapp.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProtocolTest {
    private class FakeSocket(val url: String, val direct: Boolean, val observer: SocketObserver) : SocketConnection {
        val sent = mutableListOf<JSONObject>(); var closed = false
        override fun send(text: String): Boolean { sent += JSONObject(text); return !closed }
        override fun close() { closed = true }
        fun frame(frame: JSONObject) {
            // Real snapshots always echo the history request ID.
            if (frame.optString("type") == "session.snapshot" && !frame.has("requestId")) {
                sent.lastOrNull { it.optString("type") in setOf("session.subscribe","session.history") }?.let { frame.put("requestId",it.getString("requestId")) }
            }
            observer.message(frame.toString())
        }
        fun response(id: String = sent.last().getString("requestId")) = frame(json("type" to "response","requestId" to id,"ok" to true))
    }
    private class FakeTransport : WorkbenchTransport {
        val sockets = mutableListOf<FakeSocket>(); val credentials = mutableMapOf<String,String>(); val preferences = mutableMapOf<String,Preferences>()
        val preferenceWrites = mutableListOf<Preferences>()
        var endpoint = ""; var pin = ""; var httpHandler: suspend (String,String,JSONObject?) -> JSONObject = { _,_,_ -> json("authenticated" to true,"session" to json("id" to "browser")) }
        var endpointBarrier: (suspend (String) -> Unit)? = null
        var preferenceBarrier: (suspend (String,Preferences) -> Unit)? = null
        var clearOriginHandler: (suspend () -> Unit)? = null
        override suspend fun getEndpoint() = endpoint
        override suspend fun setEndpoint(endpoint: String) { endpointBarrier?.invoke(endpoint); this.endpoint = endpoint }
        override suspend fun readPreferences(endpoint: String) = preferences[endpoint] ?: Preferences()
        override suspend fun writePreferences(endpoint: String, preferences: Preferences) { preferenceBarrier?.invoke(endpoint,preferences); this.preferences[endpoint] = preferences; preferenceWrites += preferences }
        override suspend fun credential(endpoint: String) = credentials[endpoint].orEmpty()
        override suspend fun saveCredential(endpoint: String, credential: String) { if (credential.isEmpty()) credentials.remove(endpoint) else credentials[endpoint] = credential }
        override suspend fun deviceId() = "android-device"
        override suspend fun fingerprint(endpoint: String) = pin
        override suspend fun setFingerprint(endpoint: String, fingerprint: String) { pin = fingerprint; clearOrigin(endpoint) }
        override suspend fun clearOrigin(endpoint: String) { clearOriginHandler?.invoke(); credentials.clear() }
        override suspend fun clearBrowserSession(endpoint: String) {}
        override suspend fun http(endpoint: String, route: String, body: JSONObject?) = httpHandler(endpoint,route,body)
        override suspend fun socket(endpoint: String, direct: Boolean, observer: SocketObserver): SocketConnection = FakeSocket(endpoint,direct,observer).also { sockets += it }
        override fun close() {}
    }
    private fun session(id: String = "s") = json("id" to id,"title" to "chat","permissionMode" to "plan","device" to json("id" to "server"),"activeRun" to JSONObject.NULL)
    private fun event(sequence: Long, text: String = "part", id: String = "s") = json("sessionId" to id,"runId" to "r","sequence" to sequence,"createdAt" to "2026-01-01T00:00:00Z","payload" to json("type" to "text.delta","messageId" to "m","text" to text))
    private fun ready(socket: FakeSocket) {
        socket.observer.opened()
        socket.frame(json("type" to "ready","protocolVersion" to 25,"sessions" to JSONArray().put(session()),"version" to "test").apply { if (!socket.direct) put("browser",json("sessionId" to "browser","serverDeviceId" to "server")) })
    }
    @Test fun foregroundPreservesHealthySocketPendingReadAndLiveFragments() = runTest {
        val transport = FakeTransport()
        val repository = WorkbenchRepository(transport, StandardTestDispatcher(testScheduler))
        try {
            repository.setEndpoint("https://example.test")
            val socket = transport.sockets.single()
            ready(socket); runCurrent()
            val reading = async { repository.selectSession("s") }; runCurrent()
            repository.foreground(false); runCurrent()
            repository.foreground(true); runCurrent()
            assertEquals(1, transport.sockets.size)
            assertFalse(socket.closed)
            assertFalse(reading.isCompleted)
            socket.frame(json("type" to "session.snapshot", "session" to session(), "mode" to "replace", "events" to JSONArray().put(event(1, "Saved"))))
            socket.response(); runCurrent(); reading.await()
            socket.frame(json("type" to "session.fragment", "sessionId" to "s", "runId" to "r", "payload" to json("type" to "text.delta", "messageId" to "live", "text" to "Unfinished")))
            runCurrent()
            val history = repository.state.value.histories.getValue("s")
            repository.foreground(false); runCurrent()
            repository.foreground(true); runCurrent()
            assertEquals("online", repository.state.value.status)
            assertEquals(1, transport.sockets.size)
            assertSame(history, repository.state.value.histories.getValue("s"))
            assertEquals(1, socket.sent.count { it.optString("type") == "session.subscribe" })
        } finally { repository.dispose() }
    }

    @Test fun foregroundRecoversAnOfflineSocketOnceAndLeavesTheHandshakeAlone() = runTest {
        val transport = FakeTransport()
        val repository = WorkbenchRepository(transport, StandardTestDispatcher(testScheduler))
        try {
            repository.setEndpoint("https://example.test")
            repository.foreground(false); repository.foreground(true); runCurrent()
            assertEquals(1, transport.sockets.size)
            val socket = transport.sockets.single(); ready(socket); runCurrent()
            repository.foreground(false); runCurrent()
            socket.observer.failed(java.io.IOException("Network unavailable")); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, transport.sockets.size)
            repository.foreground(true); runCurrent()
            repository.foreground(true); runCurrent()
            assertEquals(2, transport.sockets.size)
            ready(transport.sockets.last()); runCurrent()
            assertEquals("online", repository.state.value.status)
        } finally { repository.dispose() }
    }

    @Test fun repeatedSessionSelectionSharesOneReadAndMissingSnapshotCanRetry() = runTest {
        val transport = FakeTransport()
        val repository = WorkbenchRepository(transport, StandardTestDispatcher(testScheduler))
        try {
            repository.setEndpoint("https://example.test")
            val socket = transport.sockets.single(); ready(socket); runCurrent()
            repository.openSession("s"); runCurrent()
            repository.openSession("s"); runCurrent()
            assertEquals(1, socket.sent.count { it.optString("type") == "session.subscribe" })
            socket.response(); runCurrent()
            var history = repository.state.value.histories.getValue("s")
            assertFalse(history.loading)
            assertFalse(history.loaded)
            assertNotNull(history.loadError)
            repository.openSession("s"); runCurrent()
            socket.frame(json("type" to "session.snapshot", "session" to session(), "mode" to "replace", "events" to JSONArray().put(event(1, "Loaded on retry"))))
            socket.response(); runCurrent()
            history = repository.state.value.histories.getValue("s")
            assertTrue(history.loaded)
            assertFalse(history.loading)
            assertNull(history.loadError)
            assertEquals("Loaded on retry", history.events.single().payload.getString("text"))
        } finally { repository.dispose() }
    }

    @Test fun changingSessionsRejectsLateSnapshotsAndKeepsTheLatestStream() = runTest {
        val transport = FakeTransport()
        val repository = WorkbenchRepository(transport, StandardTestDispatcher(testScheduler))
        try {
            repository.setEndpoint("https://example.test")
            val socket = transport.sockets.single(); ready(socket)
            socket.frame(json("type" to "session.updated", "session" to session("second"))); runCurrent()
            repository.openSession("s"); runCurrent()
            val oldId = socket.sent.last().getString("requestId")
            repository.openSession("second"); runCurrent()
            assertFalse(repository.state.value.histories.getValue("s").loading)
            assertTrue(repository.state.value.histories.getValue("second").loading)
            val currentId = socket.sent.last().getString("requestId")
            socket.frame(json("type" to "session.snapshot", "requestId" to currentId, "session" to session("second"), "mode" to "replace", "events" to JSONArray().put(event(1, "Second history", "second"))))
            socket.response(currentId); runCurrent()
            socket.frame(json("type" to "session.snapshot", "requestId" to oldId, "session" to session(), "mode" to "replace", "events" to JSONArray().put(event(99, "Late stale history"))))
            socket.response(oldId)
            socket.frame(json("type" to "session.fragment", "sessionId" to "second", "runId" to "r", "payload" to json("type" to "text.delta", "messageId" to "live", "text" to "Still receiving")))
            runCurrent()
            assertEquals("second", repository.state.value.selectedId)
            assertTrue(repository.state.value.histories.getValue("s").events.isEmpty())
            assertEquals("Still receiving", repository.state.value.histories.getValue("second").fragments.single().payload.getString("text"))
        } finally { repository.dispose() }
    }

    @Test fun unansweredSessionReadStopsLoadingAndCanBeRetried() = runTest {
        val transport = FakeTransport()
        val repository = WorkbenchRepository(transport, StandardTestDispatcher(testScheduler))
        try {
            repository.setEndpoint("https://example.test")
            val socket = transport.sockets.single(); ready(socket); runCurrent()
            repository.openSession("s"); runCurrent()
            advanceTimeBy(90_000); runCurrent()
            assertFalse(repository.state.value.histories.getValue("s").loading)
            assertNotNull(repository.state.value.histories.getValue("s").loadError)
            repository.openSession("s"); runCurrent()
            assertEquals(2, socket.sent.count { it.optString("type") == "session.subscribe" })
            socket.frame(json("type" to "session.snapshot", "session" to session(), "mode" to "replace", "events" to JSONArray()))
            socket.response(); runCurrent()
            assertTrue(repository.state.value.histories.getValue("s").loaded)
            assertNull(repository.state.value.histories.getValue("s").loadError)
        } finally { repository.dispose() }
    }
    @Test fun addressRulesPreservePrefixAndRejectCredentialsAndDowngrades() {
        assertEquals("https://example.test:8443/workbench/",normalizeEndpoint(" https://example.test:8443/workbench "))
        assertEquals("wss://example.test:8787/ws",normalizeServiceAddress("wss://example.test:8787/ws"))
        listOf("http://example.test","https://user@example.test","https://example.test/#key","https://example.test/?x=1","https://example.test\\evil").forEach { assertThrows(IllegalArgumentException::class.java) { normalizeEndpoint(it) } }
        listOf("ws://example.test/ws","wss://example.test/","wss://a:b@example.test/ws","wss://example.test/api/ws").forEach { assertThrows(IllegalArgumentException::class.java) { normalizeServiceAddress(it) } }
    }
    @Test fun fragmentsSettleDeduplicatePreservePagesAndClearOnRunCompletion() {
        var h = applySnapshot(History(),json("mode" to "replace","events" to JSONArray().put(event(3,"first")),"hasEarlier" to true))
        h = addFragment(h,"s","r",event(0,"hel").getJSONObject("payload"))
        h = addFragment(h,"s","r",event(0,"lo").getJSONObject("payload"))
        assertEquals("hello",h.fragments.single().payload.getString("text"))
        h = addEvent(h,DeskEvent.from(event(4,"hello")))
        assertTrue(h.fragments.isEmpty()); assertSame(h,addEvent(h,DeskEvent.from(event(4,"hello"))))
        h = applySnapshot(h,json("mode" to "append","events" to JSONArray().put(event(5,"later")),"hasEarlier" to false))
        assertTrue(h.hasEarlier)
        h = applySnapshot(h,json("mode" to "prepend","events" to JSONArray().put(event(1,"old")).put(event(3,"first")),"hasEarlier" to false))
        assertEquals(listOf(1L,3L,4L,5L),h.events.map { it.sequence })
        h = addFragment(h,"s","r",event(0).getJSONObject("payload"))
        h = addEvent(h,DeskEvent("s","r",6,"",json("type" to "run.status","status" to "completed")))
        assertTrue(h.fragments.isEmpty())
    }
    @Test fun modelAliasesResolveAdvertisedConcreteVersionAndEffortWithoutGuessing() {
        val fable = ModelInfo("fable","Fable",listOf("high"),resolvedModel = "claude-fable-5-2")
        val models = listOf(ModelInfo("default","Default",resolvedModel = fable.resolvedModel),fable)
        listOf("fable", "claude-fable-5-2", null).forEach { choice ->
            assertSame(fable,modelForChoice(models,choice)); assertEquals("Fable 5.2",modelName(modelForChoice(models,choice)))
        }
        assertNull(modelForChoice(models,null,"private-model")); assertNull(modelForChoice(emptyList(),"fable"))
        assertEquals(listOf("high"),modelForChoice(models,null)?.supportedEffortLevels)
    }
    @Test fun settlingOneConcurrentFragmentCannotReuseSurvivingStableSequence() {
        var h = addFragment(History(),"s","r",json("type" to "tool.output","toolId" to "first","text" to "one"))
        h = addFragment(h,"s","r",json("type" to "tool.output","toolId" to "second","text" to "two"))
        val secondSequence = h.fragments.last().sequence
        h = addEvent(h,DeskEvent("s","r",1,"",json("type" to "tool.output","toolId" to "first","text" to "one")))
        h = addFragment(h,"s","r",json("type" to "tool.output","toolId" to "third","text" to "three"))
        assertEquals(secondSequence,h.fragments.first().sequence)
        assertEquals(h.fragments.size,h.fragments.map { it.sequence }.distinct().size)
    }
    @Test fun serviceHandshakeOmitsBrowserProbeAndLogoutForgetsToken() = runTest {
        val t = FakeTransport().apply { httpHandler = { _,_,_ -> error("Service mode may not use browser APIs") } }
        val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws","local-service-test-credential")
            val s = t.sockets.single(); ready(s); runCurrent()
            assertEquals("auth",s.sent[0].getString("type")); assertEquals(25,s.sent[0].getInt("protocolVersion")); assertFalse(s.sent[0].getBoolean("tunnel"))
            assertEquals("android-device",s.sent[1].getString("id")); assertTrue(s.direct)
            repo.activity(); runCurrent(); assertEquals(2,s.sent.size)
            assertEquals("online",repo.state.value.status)
            s.frame(json("type" to "connection.error","code" to "unauthorized","message" to "拒绝")); runCurrent()
            repo.foreground(false); repo.foreground(true); runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(1,t.sockets.size); assertFalse(repo.state.value.authenticated)
            repo.logout(); assertTrue(t.credentials.isEmpty()); assertEquals("setup",repo.state.value.status)
        } finally { repo.dispose() }
    }
    @Test fun reconnectUsesDurableCursorNeverReplaysMutationAndIgnoresGlobalEvents() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://example.test/path"); val first = t.sockets.single(); ready(first); runCurrent()
            assertEquals("wss://example.test/path/api/ws",first.url)
            val select = async { repo.selectSession("s") }; runCurrent()
            first.frame(json("type" to "session.event","event" to event(90,"unsubscribed"))); runCurrent()
            assertTrue(repo.state.value.histories.getValue("s").events.isEmpty())
            first.frame(json("type" to "session.snapshot","session" to session(),"mode" to "replace","events" to JSONArray().put(event(7,"settled"))))
            first.response(); runCurrent(); select.await()
            val send = async { runCatching { repo.command("message.send",json("sessionId" to "s","text" to "once")) } }; runCurrent()
            first.observer.closed(1006,""); runCurrent(); assertTrue(send.await().isFailure)
            advanceTimeBy(750); runCurrent(); assertEquals(2,t.sockets.size)
            val second = t.sockets.last(); ready(second); runCurrent()
            val subscribe = second.sent.last(); assertEquals("session.subscribe",subscribe.getString("type")); assertEquals(7,subscribe.getInt("afterSequence"))
            assertFalse(second.sent.any { it.optString("type") == "message.send" })
            second.frame(json("type" to "session.snapshot","session" to session(),"mode" to "append","events" to JSONArray().put(event(8,"later"))))
            second.response(); runCurrent()
            assertEquals(listOf(7L,8L),repo.state.value.histories.getValue("s").events.map { it.sequence })
            assertTrue(runCatching { repo.command("terminal.open") }.isFailure)
        } finally { repo.dispose() }
    }
    @Test fun protocolMismatchAndCertificateFailureNeverAutoRetry() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://example.test"); val s = t.sockets.single()
            s.frame(json("type" to "ready","protocolVersion" to 24,"sessions" to JSONArray(),"browser" to JSONObject())); runCurrent()
            repo.foreground(true); runCurrent(); advanceTimeBy(60_000); runCurrent(); assertEquals(1,t.sockets.size)
            repo.reconnect(); t.sockets.last().observer.failed(CertificateFailure("pin mismatch")); runCurrent()
            repo.foreground(true); runCurrent(); advanceTimeBy(60_000); runCurrent(); assertEquals(2,t.sockets.size)
        } finally { repo.dispose() }
    }
    @Test fun catalogRequestsDeduplicateGateWritesAndPreserveLatestPermissions() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://example.test"); val s = t.sockets.single(); ready(s); runCurrent()
            val first = async { repo.loadModelCatalog("s") }; val second = async { repo.loadModelCatalog("s") }; runCurrent()
            assertEquals(1,s.sent.count { it.optString("type") == "session.status" })
            val requestId = s.sent.last().getString("requestId")
            val configure = async { repo.configureModel("s","fable","high") }; runCurrent()
            assertFalse(s.sent.any { it.optString("type") == "session.configure" })
            val capabilities = event(17).put("payload",json("type" to "native.capabilities","models" to JSONArray().put(json("value" to "fable","displayName" to "Fable","supportedEffortLevels" to JSONArray().put("high")))))
            s.frame(json("type" to "session.event","event" to capabilities)); s.response(requestId); runCurrent(); first.await(); second.await()
            assertTrue(repo.state.value.histories.isEmpty()); assertNull(repo.state.value.selectedId)
            assertEquals("plan",s.sent.last().getString("permissionMode")); assertEquals("session.configure",s.sent.last().getString("type"))
            s.response(); runCurrent(); configure.await()
        } finally { repo.dispose() }
    }
    @Test fun staleEndpointProbeCannotRestorePreviousLoginAndCommandsReturnPayload() = runTest {
        val t = FakeTransport(); val slow = CompletableDeferred<JSONObject>()
        t.httpHandler = { endpoint,_,_ -> if (endpoint.contains("slow")) slow.await() else json("authenticated" to false) }
        val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            val switching = async { repo.setEndpoint("https://slow.test") }; runCurrent()
            repo.setEndpoint("https://new.test"); slow.complete(json("authenticated" to true)); runCurrent(); switching.await()
            assertEquals("https://new.test/",repo.state.value.endpoint); assertEquals("login",repo.state.value.status); assertTrue(t.sockets.isEmpty())
            t.httpHandler = { _,_,_ -> json("authenticated" to true) }; repo.reconnect(); val s = t.sockets.single(); ready(s); runCurrent()
            val request = async { repo.command("web.settings.get") }; runCurrent(); val id = s.sent.last().getString("requestId")
            s.frame(json("type" to "web.settings.state","requestId" to id,"values" to json("totpEnabled" to true))); s.response(id); runCurrent()
            assertEquals("web.settings.state",request.await().getString("type")); assertTrue(repo.state.value.totpEnabled)
        } finally { repo.dispose() }
    }
    @Test fun endpointWritesAndNoticeClearingCannotBeReordered() = runTest {
        val t = FakeTransport(); val barrier = CompletableDeferred<Unit>(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        t.endpointBarrier = { if (it.contains("first")) barrier.await() }
        try {
            val first = async { repo.setEndpoint("https://first.test") }; runCurrent()
            val second = async { repo.setEndpoint("https://second.test") }; runCurrent()
            assertEquals("",t.endpoint)
            barrier.complete(Unit); runCurrent(); first.await(); second.await()
            assertEquals("https://second.test/",t.endpoint); assertEquals(1,t.sockets.size)
            val s = t.sockets.single(); ready(s); runCurrent()
            val prefsBarrier = CompletableDeferred<Unit>()
            t.preferenceBarrier = { _,prefs -> if (prefs.notices.isNotEmpty()) prefsBarrier.await() }
            s.frame(json("type" to "session.event","event" to event(1).put("payload",json("type" to "run.status","status" to "completed")))); runCurrent()
            val logout = async { repo.logout() }; runCurrent(); assertFalse(logout.isCompleted)
            prefsBarrier.complete(Unit); runCurrent(); logout.await()
            assertTrue(t.preferences.getValue("https://second.test/").notices.isEmpty())
        } finally { repo.dispose() }
    }
    @Test fun staleSocketFramesCannotMutateNewConnectionAndHandshakeTimeoutBacksOff() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://example.test"); val first = t.sockets.single()
            advanceTimeBy(15_000); runCurrent(); assertEquals("offline",repo.state.value.status)
            advanceTimeBy(749); runCurrent(); assertEquals(1,t.sockets.size)
            advanceTimeBy(1); runCurrent(); assertEquals(2,t.sockets.size)
            val second = t.sockets.last(); ready(second); runCurrent()
            first.frame(json("type" to "session.deleted","sessionId" to "s")); first.observer.failed(IllegalStateException("stale")); runCurrent()
            assertEquals("online",repo.state.value.status); assertEquals("s",repo.state.value.sessions.single().id)
            val mutation = async { runCatching { repo.command("session.rename",json("sessionId" to "s","title" to "new")) } }; runCurrent()
            advanceTimeBy(45_000); runCurrent(); assertTrue(mutation.await().exceptionOrNull()?.message.orEmpty().contains("超时"))
            assertEquals(1,second.sent.count { it.optString("type") == "session.rename" })
        } finally { repo.dispose() }
    }
    @Test fun subscriptionFailureResetsLoadingAndQuotaRefreshIsDeduplicated() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://example.test"); val s = t.sockets.single(); ready(s); runCurrent()
            val load = async { runCatching { repo.selectSession("s") } }; runCurrent(); val request = s.sent.last().getString("requestId")
            s.frame(json("type" to "response","requestId" to request,"ok" to false,"message" to "暂时无法读取")); runCurrent(); assertTrue(load.await().isFailure)
            val history = repo.state.value.histories.getValue("s"); assertFalse(history.loading); assertEquals("暂时无法读取",history.loadError)
            repo.setQuotaWatching(true); runCurrent(); s.response(); runCurrent()
            val metrics = json("type" to "session.event","event" to event(99).put("payload",json("type" to "native.metrics","rateLimits" to json("available" to true))))
            s.frame(metrics); s.frame(metrics); runCurrent()
            assertEquals(1,s.sent.count { it.optString("type") == "usage.summary" })
            assertTrue(repo.state.value.histories.getValue("s").events.isEmpty())
            s.response(); runCurrent(); repo.setQuotaWatching(false); runCurrent(); s.response(); runCurrent()
            s.frame(metrics); runCurrent(); assertEquals(1,s.sent.count { it.optString("type") == "usage.summary" })
        } finally { repo.dispose() }
    }
    @Test fun cancellationAndContinuationUseAcknowledgedCommandsWithoutMutationReplay() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://example.test"); val socket = t.sockets.single(); ready(socket); runCurrent()
            val subscribe = async { repo.selectSession("s") }; runCurrent()
            socket.frame(json("type" to "session.snapshot","session" to session(),"mode" to "replace","events" to JSONArray().put(event(1,"initial"))))
            socket.response(); runCurrent(); subscribe.await()
            val cancel = async { repo.command("run.cancel",json("sessionId" to "s","runId" to "r")) }; runCurrent()
            val cancelledRequest = socket.sent.last()
            assertEquals("run.cancel",cancelledRequest.getString("type")); assertEquals("r",cancelledRequest.getString("runId"))
            socket.frame(json("type" to "session.event","event" to event(2).put("payload",json("type" to "run.status","status" to "cancelled"))))
            runCurrent(); assertFalse(cancel.isCompleted)
            socket.response(cancelledRequest.getString("requestId")); runCurrent(); cancel.await()
            val resume = async { runCatching { repo.command("run.continue",json("sessionId" to "s")) } }; runCurrent()
            val request = socket.sent.last()
            assertEquals("run.continue",request.getString("type")); assertEquals("s",request.getString("sessionId")); assertFalse(request.has("runId"))
            socket.observer.closed(1006,"Connection lost before acknowledgement"); runCurrent()
            assertTrue(resume.await().isFailure)
            advanceTimeBy(750); runCurrent(); val next = t.sockets.last(); ready(next); runCurrent()
            assertFalse(next.sent.any { it.optString("type") in setOf("run.cancel","run.continue") })
            assertEquals(2,next.sent.last().getInt("afterSequence"))
        } finally { repo.dispose() }
    }
    @Test fun earlierHistoryUsesOldestDurableCursorSuppressesOverlapAndCanRetryFailure() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://example.test"); val socket = t.sockets.single(); ready(socket); runCurrent()
            val subscribe = async { repo.selectSession("s") }; runCurrent()
            socket.frame(json("type" to "session.snapshot","session" to session(),"mode" to "replace","hasEarlier" to true,"events" to JSONArray().put(event(50)).put(event(60))))
            socket.response(); runCurrent(); subscribe.await()
            socket.frame(json("type" to "session.fragment","sessionId" to "s","runId" to "r","payload" to json("type" to "text.delta","messageId" to "live","text" to "Streaming"))); runCurrent()
            val first = async { runCatching { repo.loadEarlier("s") } }; runCurrent()
            assertEquals("session.history",socket.sent.last().getString("type")); assertEquals(50,socket.sent.last().getInt("beforeSequence"))
            repo.loadEarlier("s"); assertEquals(1,socket.sent.count { it.optString("type") == "session.history" })
            socket.frame(json("type" to "response","requestId" to socket.sent.last().getString("requestId"),"ok" to false,"message" to "Retry this page")); runCurrent()
            assertTrue(first.await().isFailure); assertFalse(repo.state.value.histories.getValue("s").loading)
            val retry = async { repo.loadEarlier("s") }; runCurrent(); assertEquals(50,socket.sent.last().getInt("beforeSequence"))
            socket.frame(json("type" to "session.snapshot","session" to session(),"mode" to "prepend","hasEarlier" to false,"events" to JSONArray().put(event(40)).put(event(50))))
            socket.response(); runCurrent(); retry.await()
            val history = repo.state.value.histories.getValue("s")
            assertEquals(listOf(40L,50L,60L),history.events.map { it.sequence }); assertEquals("Streaming",history.fragments.single().payload.getString("text"))
            assertFalse(history.hasEarlier); repo.loadEarlier("s")
            assertEquals(2,socket.sent.count { it.optString("type") == "session.history" })
        } finally { repo.dispose() }
    }
    @Test fun endpointDisconnectClearsTransientStateAndCannotRestorePreviousSessionData() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://first.test"); val first = t.sockets.single(); ready(first); runCurrent()
            val subscribe = async { repo.selectSession("s") }; runCurrent()
            first.frame(json("type" to "session.snapshot","session" to session(),"mode" to "replace","events" to JSONArray().put(event(10,"Saved history"))))
            first.response(); runCurrent(); subscribe.await()
            first.frame(json("type" to "session.fragment","sessionId" to "s","runId" to "r","payload" to json("type" to "text.delta","messageId" to "partial","text" to "Temporary"))); runCurrent()
            val pending = async { runCatching { repo.command("session.stats",json("sessionId" to "s")) } }; runCurrent()
            first.observer.closed(1006,"Lost connection"); runCurrent()
            assertTrue(pending.await().isFailure)
            val offline = repo.state.value.histories.getValue("s")
            assertEquals(listOf(10L),offline.events.map { it.sequence }); assertTrue(offline.fragments.isEmpty()); assertFalse(offline.loading)
            repo.setEndpoint("https://second.test"); val second = t.sockets.last(); ready(second); runCurrent()
            first.frame(json("type" to "session.snapshot","session" to session(),"mode" to "replace","events" to JSONArray().put(event(99,"Old endpoint data"))))
            first.frame(json("type" to "account.state","account" to "Old endpoint account")); runCurrent()
            assertEquals("https://second.test/",repo.state.value.endpoint); assertTrue(repo.state.value.histories.isEmpty()); assertNull(repo.state.value.account)
            assertNull(repo.state.value.selectedId); assertEquals("online",repo.state.value.status)
        } finally { repo.dispose() }
    }
    @Test fun disposalDrainsQueuedPreferenceChangesInOrder() = runTest {
        val t = FakeTransport(); val barrier = CompletableDeferred<Unit>(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.setEndpoint("https://example.test"); ready(t.sockets.single()); runCurrent()
            t.preferenceBarrier = { _,_ -> barrier.await() }
            repo.togglePin("s"); runCurrent()
            repo.togglePin("s"); runCurrent()
            assertTrue(t.preferenceWrites.isEmpty())
            repo.dispose()
            barrier.complete(Unit)
            repo.awaitPersistenceDrain()
            assertEquals(listOf(listOf("s"), emptyList<String>()),t.preferenceWrites.map { it.pins })
            assertTrue(t.preferences.getValue("https://example.test/").pins.isEmpty())
        } finally { barrier.complete(Unit); repo.dispose() }
    }
    @Test fun failedCredentialRemovalCannotLeaveForgottenConnectionMarkedOnline() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            val endpoint = "wss://example.test/ws"
            repo.connectService(endpoint,"instrumentation-only-service-token")
            ready(t.sockets.single()); runCurrent()
            t.clearOriginHandler = { error("Credential storage unavailable") }
            assertTrue(runCatching { repo.forgetEndpoint() }.isFailure)
            assertEquals("offline",repo.state.value.status); assertFalse(repo.state.value.authenticated)
            assertEquals(endpoint,repo.state.value.endpoint)
            assertEquals("Credential storage unavailable",repo.state.value.error)
            repo.foreground(true); runCurrent(); advanceTimeBy(60_000); runCurrent(); assertEquals(1,t.sockets.size)
            t.clearOriginHandler = null
            repo.forgetEndpoint()
            assertEquals("setup",repo.state.value.status); assertTrue(t.credentials.isEmpty()); assertEquals("",t.endpoint)
        } finally { repo.dispose() }
    }
    @Test fun malformedReadyMarkersAndCoercedProtocolVersionsAreRejectedBeforeDeviceIdentity() = runTest {
        val invalid = listOf(
            json("protocolVersion" to "25"), json("protocolVersion" to 25.5),
            json("protocolVersion" to 25,"browser" to true),
            json("protocolVersion" to 25,"browser" to "browser"),
            json("protocolVersion" to 25,"browser" to JSONObject())
        )
        for (fields in invalid) {
            val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
            try {
                repo.connectService("wss://example.test/ws","instrumentation-only-service-token")
                val socket = t.sockets.single(); socket.observer.opened()
                socket.frame(fields.put("type","ready").put("sessions",JSONArray()))
                runCurrent()
                assertEquals("offline",repo.state.value.status)
                assertFalse(socket.sent.any { it.optString("type") == "device" })
                repo.foreground(true); runCurrent(); advanceTimeBy(60_000); runCurrent(); assertEquals(1,t.sockets.size)
            } finally { repo.dispose() }
        }
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws","instrumentation-only-service-token")
            val socket = t.sockets.single(); socket.observer.opened()
            socket.frame(json("type" to "ready","protocolVersion" to 25,"browser" to false,"sessions" to JSONArray()))
            runCurrent(); assertEquals("online",repo.state.value.status); assertTrue(socket.sent.any { it.optString("type") == "device" })
        } finally { repo.dispose() }
    }
}
