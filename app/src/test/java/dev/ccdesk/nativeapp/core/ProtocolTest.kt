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
    private val serviceCredential = "test-service-credential-0123456789"
    private class FakeSocket(val url: String, val observer: SocketObserver) : SocketConnection {
        val sent = mutableListOf<JSONObject>(); var closed = false
        var onSend: ((JSONObject) -> Unit)? = null
        override fun send(text: String): Boolean { val frame = JSONObject(text); sent += frame; if (!closed) onSend?.invoke(frame); return !closed }
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
        var endpoint = ""; var pin = ""
        val credentialReads = mutableListOf<String>()
        var credentialBarrier: (suspend (String) -> Unit)? = null
        var deviceIdentity = "android-device"
        var deviceIdBarrier: (suspend (String) -> Unit)? = null
        var endpointBarrier: (suspend (String) -> Unit)? = null
        var preferenceBarrier: (suspend (String,Preferences) -> Unit)? = null
        var clearOriginHandler: (suspend () -> Unit)? = null
        override suspend fun getEndpoint() = endpoint
        override suspend fun setEndpoint(endpoint: String) { endpointBarrier?.invoke(endpoint); this.endpoint = endpoint }
        override suspend fun readPreferences(endpoint: String) = preferences[endpoint] ?: Preferences()
        override suspend fun writePreferences(endpoint: String, preferences: Preferences) { preferenceBarrier?.invoke(endpoint,preferences); this.preferences[endpoint] = preferences; preferenceWrites += preferences }
        override suspend fun credential(endpoint: String): String { credentialReads += endpoint; credentialBarrier?.invoke(endpoint); return credentials[endpoint].orEmpty() }
        override suspend fun saveCredential(endpoint: String, credential: String) { if (credential.isEmpty()) credentials.remove(endpoint) else credentials[endpoint] = credential }
        override suspend fun deviceId(): String { val id = deviceIdentity; deviceIdBarrier?.invoke(id); return id }
        override suspend fun fingerprint(endpoint: String) = pin
        override suspend fun setFingerprint(endpoint: String, fingerprint: String) { pin = fingerprint; clearOrigin(endpoint) }
        override suspend fun clearOrigin(endpoint: String) { clearOriginHandler?.invoke(); credentials.clear() }
        override suspend fun socket(endpoint: String, observer: SocketObserver): SocketConnection = FakeSocket(endpoint,observer).also { sockets += it }
        override fun close() {}
    }
    private fun session(id: String = "s") = json("id" to id,"title" to "chat","permissionMode" to "plan","device" to json("id" to "server"),"activeRun" to JSONObject.NULL)
    private fun event(sequence: Long, text: String = "part", id: String = "s") = json("sessionId" to id,"runId" to "r","sequence" to sequence,"createdAt" to "2026-01-01T00:00:00Z","payload" to json("type" to "text.delta","messageId" to "m","text" to text))
    private fun ready(socket: FakeSocket) {
        socket.observer.opened()
        socket.frame(json("type" to "ready","protocolVersion" to 26,"sessions" to JSONArray().put(session()),"version" to "test"))
    }

    private fun historyRequest(socket: FakeSocket, type: String = "session.subscribe", sessionId: String? = null) =
        socket.sent.last { it.optString("type") == type && (sessionId == null || it.optString("sessionId") == sessionId) }
    private fun snapshot(request: JSONObject, events: JSONArray = JSONArray().put(event(1)), mode: String = "replace", hasEarlier: Boolean = false) =
        json("type" to "session.snapshot", "requestId" to request.getString("requestId"),
            "session" to session(request.getString("sessionId")), "mode" to mode, "hasEarlier" to hasEarlier, "events" to events)
    private fun historyPart(request: JSONObject, data: Any, last: Any = false, sessionId: String = request.getString("sessionId")) =
        json("type" to "session.history.part", "requestId" to request.getString("requestId"), "sessionId" to sessionId, "data" to data, "last" to last)
    private fun completeHistory(socket: FakeSocket, request: JSONObject, page: JSONObject = snapshot(request)) {
        socket.frame(historyPart(request, JSONArray().put(page).toString(), true))
        socket.response(request.getString("requestId"))
    }
    private fun fragment(text: String, messageId: String = "live", sessionId: String = "s") =
        json("type" to "session.fragment", "sessionId" to sessionId, "runId" to "r",
            "payload" to json("type" to "text.delta", "messageId" to messageId, "text" to text))

    @Test fun historyPartsRenewTheIdleDeadlineBeyondNinetySecondsAndPreserveSplitUnicode() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
            val read = async { repo.selectSession("s") }; runCurrent(); val request = historyRequest(socket)
            val text = "跨片中文🙂𠮷é"
            val body = JSONArray().put(snapshot(request, JSONArray().put(event(1, text)))).toString()
            val boundary = body.indexOf("🙂") + 1
            advanceTimeBy(80_000); runCurrent()
            socket.frame(historyPart(request, "[", false).put("requestId", "another-request")); runCurrent()
            assertFalse(socket.sent.any { it.optString("type") == "session.history.next" })
            socket.frame(historyPart(request, body.substring(0, boundary))); runCurrent()
            advanceTimeBy(80_000); runCurrent()
            assertFalse(read.isCompleted); assertTrue(repo.state.value.histories.getValue("s").events.isEmpty())
            socket.frame(historyPart(request, body.substring(boundary, body.length - 1))); runCurrent()
            advanceTimeBy(80_000); runCurrent(); assertFalse(read.isCompleted)
            socket.frame(historyPart(request, body.takeLast(1), true)); socket.response(request.getString("requestId")); runCurrent(); read.await()
            assertEquals(text, repo.state.value.histories.getValue("s").events.single().payload.getString("text"))
            val acknowledgements = socket.sent.filter { it.optString("type") == "session.history.next" }
            assertEquals(3, acknowledgements.size)
            assertTrue(acknowledgements.all { it.getString("requestId") == request.getString("requestId") && it.length() == 2 })
            assertFalse(repo.state.value.histories.getValue("s").loading)
            assertFalse(socket.sent.any { it.optString("type") == "session.history.cancel" })
        } finally { repo.dispose() }
    }

    @Test fun lastPartIsCommittedBeforeNextCanSynchronouslyDeliverTheOriginalResponse() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
            val reading = async { repo.selectSession("s") }; runCurrent(); val request = historyRequest(socket)
            var replies = 0
            socket.onSend = { frame ->
                if (frame.optString("type") == "session.history.next") {
                    assertEquals("Snapshot must already be committed at the last ACK", "Committed before ACK",
                        repo.state.value.histories.getValue("s").events.single().payload.getString("text"))
                    replies++
                    socket.response(frame.getString("requestId"))
                }
            }
            socket.frame(historyPart(request, JSONArray().put(snapshot(request, JSONArray().put(event(1, "Committed before ACK")))).toString(), true))
            runCurrent(); reading.await()
            assertEquals(1, replies)
            assertFalse(repo.state.value.histories.getValue("s").loading)
            assertNull(repo.state.value.histories.getValue("s").loadError)
            assertFalse(socket.sent.any { it.optString("type") == "session.history.cancel" })
        } finally { repo.dispose() }
    }

    @Test fun pendingHistoryDoesNotBlockConcurrentCatalogStatusCapabilities() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
            repo.openSession("s"); runCurrent(); val request = historyRequest(socket)
            socket.frame(historyPart(request, "[")); runCurrent()
            val catalog = async { repo.loadModelCatalog("s") }; runCurrent()
            val status = socket.sent.single { it.optString("type") == "session.status" }
            val capabilities = event(20).put("payload", json("type" to "native.capabilities",
                "models" to JSONArray().put(json("value" to "parallel-model", "displayName" to "Parallel model"))))
            socket.frame(json("type" to "session.event", "event" to capabilities))
            socket.response(status.getString("requestId")); runCurrent(); catalog.await()
            assertEquals("parallel-model", repo.state.value.modelCatalogs.getValue("server").models.single().value)
            assertTrue(repo.state.value.modelCatalogLoading.isEmpty()); assertTrue(repo.state.value.modelCatalogErrors.isEmpty())
            assertTrue("The separate history read still owns its loading state", repo.state.value.histories.getValue("s").loading)
            assertTrue(repo.state.value.histories.getValue("s").events.isEmpty())
            val page = JSONArray().put(snapshot(request)).toString()
            socket.frame(historyPart(request, page.drop(1), true)); socket.response(request.getString("requestId")); runCurrent()
            assertEquals(listOf(1L, 20L), repo.state.value.histories.getValue("s").events.map { it.sequence })
            assertFalse(repo.state.value.histories.getValue("s").loading)
            assertEquals("parallel-model", repo.state.value.modelCatalogs.getValue("server").models.single().value)
        } finally { repo.dispose() }
    }

    @Test fun invalidOuterPartsAndMalformedInnerPagesNeverPartiallyCommitSnapshots() = runTest {
        val cases: List<(JSONObject) -> JSONObject> = listOf(
            { historyPart(it, 7, true) }, { historyPart(it, "", true) }, { historyPart(it, "x".repeat(32769), true) },
            { historyPart(it, "[]", "true") }, { historyPart(it, "[]", true, "wrong-session") },
            { historyPart(it, "[not json", true) }, { historyPart(it, "[]", true) },
            { historyPart(it, JSONArray().put(snapshot(it).put("requestId", "wrong-request")).toString(), true) },
            { historyPart(it, JSONArray().put(snapshot(it).put("session", session("wrong-session"))).toString(), true) },
            { historyPart(it, JSONArray().put(snapshot(it, JSONArray().put(event(1, id = "wrong-session")))).toString(), true) },
            { historyPart(it, JSONArray().put(snapshot(it, JSONArray().put(event(1).apply { remove("payload") }))).toString(), true) },
            { request -> historyPart(request, JSONArray().put(snapshot(request).put("session", session().put("title", "Must not commit")))
                .put(json("type" to "session.updated", "session" to session())).toString(), true) }
        )
        for ((index, malformed) in cases.withIndex()) {
            val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
            try {
                repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
                repo.openSession("s"); runCurrent(); val request = historyRequest(socket)
                socket.frame(malformed(request)); runCurrent()
                val history = repo.state.value.histories.getValue("s")
                assertFalse("Case $index must finish loading", history.loading)
                assertFalse("Case $index must not mark history loaded", history.loaded)
                assertTrue("Case $index must not commit any events", history.events.isEmpty())
                assertEquals("Case $index must not update session metadata", "chat", repo.state.value.sessions.single().title)
                assertNotNull("Case $index must report a retriable failure", history.loadError)
                assertEquals(1, socket.sent.count { it.optString("type") == "session.history.cancel" })
                assertFalse(socket.sent.any { it.optString("type") == "session.history.next" })
                completeHistory(socket, request); runCurrent()
                assertFalse(repo.state.value.histories.getValue("s").loaded)
            } finally { repo.dispose() }
        }
    }

    @Test fun cancellingAnOlderPageSendsOneControlAndPreservesLoadedHistory() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
            repo.openSession("s"); runCurrent(); val subscribe = historyRequest(socket)
            completeHistory(socket, subscribe, snapshot(subscribe, JSONArray().put(event(8, "Keep this")), hasEarlier = true)); runCurrent()
            val page = async { repo.loadEarlier("s") }; runCurrent(); val request = historyRequest(socket, "session.history")
            socket.frame(historyPart(request, "[")); runCurrent()
            page.cancel(); runCurrent()
            assertTrue(page.isCancelled)
            assertEquals(1, socket.sent.count { it.optString("type") == "session.history.cancel" && it.optString("requestId") == request.getString("requestId") })
            assertFalse(repo.state.value.histories.getValue("s").loading)
            completeHistory(socket, request, snapshot(request, JSONArray().put(event(1)), "prepend")); runCurrent()
            assertEquals(listOf(8L), repo.state.value.histories.getValue("s").events.map { it.sequence })
            assertTrue(repo.state.value.histories.getValue("s").hasEarlier)
        } finally { repo.dispose() }
    }

    @Test fun newDraftAndDeletionCancelPartialHistoryAndRejectLateFrames() = runTest {
        for (delete in listOf(false, true)) {
            val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
            try {
                repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
                repo.openSession("s"); runCurrent(); val request = historyRequest(socket)
                socket.frame(historyPart(request, "[")); runCurrent()
                if (delete) socket.frame(json("type" to "session.deleted", "sessionId" to "s")) else repo.newDraft()
                runCurrent()
                assertNull(repo.state.value.selectedId)
                assertEquals(1, socket.sent.count { it.optString("type") == "session.history.cancel" })
                completeHistory(socket, request); socket.frame(fragment("Old subscription")); runCurrent()
                assertNull(repo.state.value.selectedId)
                if (delete) {
                    assertTrue(repo.state.value.sessions.isEmpty()); assertTrue(repo.state.value.histories.isEmpty())
                } else {
                    val history = repo.state.value.histories.getValue("s")
                    assertFalse(history.loading); assertTrue(history.events.isEmpty()); assertTrue(history.fragments.isEmpty())
                }
                assertEquals(1, socket.sent.count { it.optString("type") == "session.history.next" })
            } finally { repo.dispose() }
        }
    }

    @Test fun oldGenerationPartsCannotAppendToTheReconnectedHistoryTransfer() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val first = t.sockets.single(); ready(first); runCurrent()
            repo.openSession("s"); runCurrent(); val oldRequest = historyRequest(first)
            first.frame(historyPart(oldRequest, "[")); runCurrent()
            repo.reconnect(); val second = t.sockets.last(); ready(second); runCurrent()
            val current = historyRequest(second)
            val body = JSONArray().put(snapshot(current, JSONArray().put(event(2, "Current connection")))).toString()
            second.frame(historyPart(current, body.take(25))); runCurrent()
            // Even a stale socket frame that guesses the current request ID must be ignored.
            first.frame(historyPart(current, body.drop(25), true)); completeHistory(first, oldRequest); runCurrent()
            assertTrue(repo.state.value.histories.getValue("s").loading)
            assertTrue(repo.state.value.histories.getValue("s").events.isEmpty())
            second.frame(historyPart(current, body.drop(25), true)); second.response(current.getString("requestId")); runCurrent()
            assertEquals("Current connection", repo.state.value.histories.getValue("s").events.single().payload.getString("text"))
            assertEquals(2, second.sent.count { it.optString("type") == "session.history.next" })
        } finally { repo.dispose() }
    }

    @Test fun rapidSessionRoundTripKeepsTheNewestReadAsLoadingOwner() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket)
            socket.frame(json("type" to "session.updated", "session" to session("b"))); runCurrent()
            repo.openSession("s"); runCurrent(); val firstA = historyRequest(socket)
            socket.frame(historyPart(firstA, "[")); runCurrent()
            repo.openSession("b"); runCurrent(); val b = historyRequest(socket)
            repo.openSession("s"); runCurrent(); val latestA = historyRequest(socket)
            assertNotEquals(firstA.getString("requestId"), latestA.getString("requestId"))
            completeHistory(socket, firstA); completeHistory(socket, b, snapshot(b, JSONArray().put(event(3, id = "b")))); runCurrent()
            assertEquals("s", repo.state.value.selectedId)
            assertTrue("Old completion must not clear the newest read's spinner", repo.state.value.histories.getValue("s").loading)
            assertFalse(repo.state.value.histories.getValue("b").loading)
            assertTrue(repo.state.value.histories.getValue("s").events.isEmpty())
            completeHistory(socket, latestA, snapshot(latestA, JSONArray().put(event(9, "Newest A")))); runCurrent()
            assertFalse(repo.state.value.histories.getValue("s").loading)
            assertEquals(listOf(9L), repo.state.value.histories.getValue("s").events.map { it.sequence })
            assertEquals(2, socket.sent.count { it.optString("type") == "session.history.cancel" })
        } finally { repo.dispose() }
    }

    @Test fun subscriptionReplaysCapturedFragmentsBeforeBufferedEventsFragmentsAndMetadata() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
            repo.openSession("s"); runCurrent(); val request = historyRequest(socket)
            val page = JSONArray().put(snapshot(request, JSONArray().put(event(1, "Snapshot"))))
                .put(fragment("Captured", "settling")).put(fragment("Head", "growing")).toString()
            socket.frame(historyPart(request, page.take(20))); runCurrent()
            socket.frame(json("type" to "session.event", "event" to event(2, "Settled").put("payload", json("type" to "text.delta", "messageId" to "settling", "text" to "Settled"))))
            socket.frame(fragment("Tail", "growing"))
            socket.frame(json("type" to "session.updated", "session" to session().put("title", "Newest metadata").put("permissionMode", "auto")))
            runCurrent()
            assertEquals("chat", repo.state.value.sessions.single().title)
            assertTrue(repo.state.value.histories.getValue("s").events.isEmpty()); assertTrue(repo.state.value.histories.getValue("s").fragments.isEmpty())
            socket.frame(historyPart(request, page.drop(20), true)); socket.response(request.getString("requestId")); runCurrent()
            val history = repo.state.value.histories.getValue("s")
            assertEquals(listOf(1L, 2L), history.events.map { it.sequence })
            assertEquals("HeadTail", history.fragments.single().payload.getString("text"))
            assertEquals("growing", history.fragments.single().payload.getString("messageId"))
            assertEquals("Newest metadata", repo.state.value.sessions.single().title)
            assertEquals("auto", repo.state.value.sessions.single().permissionMode)
        } finally { repo.dispose() }
    }

    @Test fun failedPartialSubscriptionDoesNotAdvanceTheRetryCursorFromBufferedLiveEvents() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
            repo.openSession("s"); runCurrent(); val request = historyRequest(socket)
            socket.frame(historyPart(request, "[")); socket.frame(json("type" to "session.event", "event" to event(5)))
            socket.frame(json("type" to "response", "requestId" to request.getString("requestId"), "ok" to false, "message" to "History stalled")); runCurrent()
            socket.frame(json("type" to "session.event", "event" to event(6))); runCurrent()
            repo.openSession("s"); runCurrent(); val retry = historyRequest(socket)
            assertEquals(0L, retry.getLong("afterSequence"))
            assertTrue(repo.state.value.histories.getValue("s").events.isEmpty())
            completeHistory(socket, retry, snapshot(retry, JSONArray().put(event(1)).put(event(5)).put(event(6)))); runCurrent()
            assertEquals(listOf(1L, 5L, 6L), repo.state.value.histories.getValue("s").events.map { it.sequence })
            assertNull(repo.state.value.histories.getValue("s").loadError)
        } finally { repo.dispose() }
    }

    @Test fun localDeviceIdentityPrecedesPresenceSurvivesReconnectAndResetsWithTheService() = runTest {
        val transport = FakeTransport()
        val repository = WorkbenchRepository(transport, StandardTestDispatcher(testScheduler))
        try {
            repository.connectService("wss://example.test/ws", serviceCredential)
            assertEquals("Identity must be available before the first handshake frame", "android-device", repository.state.value.localDeviceId)
            val first = transport.sockets.single()
            ready(first)
            first.frame(json("type" to "devices", "devices" to JSONArray()
                .put(json("id" to "server", "server" to true, "ready" to true))
                .put(json("id" to "android-device", "name" to "CC Android Tunnel", "ready" to false))
                .put(json("id" to "computer", "name" to "CC Android Tunnel", "ready" to false))))
            runCurrent()
            assertEquals("android-device", first.sent.single { it.optString("type") == "device" }.getString("id"))
            assertEquals(listOf("computer"), remoteComputers(repository.state.value).map { it.device.id })

            repository.foreground(false); runCurrent()
            first.observer.failed(java.io.IOException("Offline fixture")); runCurrent()
            assertEquals("offline", repository.state.value.status)
            assertEquals("android-device", repository.state.value.localDeviceId)
            assertEquals(listOf("computer"), remoteComputers(repository.state.value).map { it.device.id })

            val gate = CompletableDeferred<Unit>()
            transport.deviceIdBarrier = { gate.await() }
            val reconnect = async { repository.reconnect() }; runCurrent()
            assertEquals("connecting", repository.state.value.status)
            assertEquals("Reconnect must keep filtering cached phone presence", "android-device", repository.state.value.localDeviceId)
            gate.complete(Unit); runCurrent(); reconnect.await()
            ready(transport.sockets.last()); runCurrent()
            assertEquals("online", repository.state.value.status)
            assertEquals("android-device", repository.state.value.localDeviceId)

            repository.logout()
            assertNull(repository.state.value.localDeviceId)
            assertTrue(repository.state.value.devices.isEmpty())
            assertTrue(repository.state.value.sessions.isEmpty())
        } finally { repository.dispose() }
    }

    @Test fun staleDeviceIdentityReadCannotOverwriteTheNewServiceGeneration() = runTest {
        val transport = FakeTransport()
        val gate = CompletableDeferred<Unit>()
        transport.deviceIdentity = "old-phone"
        transport.deviceIdBarrier = { id -> if (id == "old-phone") gate.await() }
        val repository = WorkbenchRepository(transport, StandardTestDispatcher(testScheduler))
        try {
            val oldConnection = async { repository.connectService("wss://old.test/ws", serviceCredential) }
            runCurrent()
            assertNull(repository.state.value.localDeviceId)
            assertTrue(transport.sockets.isEmpty())
            transport.deviceIdentity = "current-phone"
            repository.connectService("wss://current.test/ws", serviceCredential)
            assertEquals("current-phone", repository.state.value.localDeviceId)
            gate.complete(Unit); runCurrent(); oldConnection.await()
            assertEquals("wss://current.test/ws", repository.state.value.endpoint)
            assertEquals("current-phone", repository.state.value.localDeviceId)
            assertEquals(listOf("wss://current.test/ws"), transport.sockets.map { it.url })
            ready(transport.sockets.single()); runCurrent()
            assertEquals("current-phone", transport.sockets.single().sent.single { it.optString("type") == "device" }.getString("id"))
            assertEquals("online", repository.state.value.status)
        } finally { gate.complete(Unit); repository.dispose() }
    }

    @Test fun foregroundPreservesHealthySocketPendingReadAndLiveFragments() = runTest {
        val transport = FakeTransport()
        val repository = WorkbenchRepository(transport, StandardTestDispatcher(testScheduler))
        try {
            repository.connectService("wss://example.test/ws",serviceCredential)
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
            repository.connectService("wss://example.test/ws",serviceCredential)
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
            repository.connectService("wss://example.test/ws",serviceCredential)
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
            repository.connectService("wss://example.test/ws",serviceCredential)
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
            repository.connectService("wss://example.test/ws",serviceCredential)
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
    @Test fun addressesRequireTheServiceSocketAndRejectWebLoginsCredentialsAndDowngrades() {
        assertEquals("wss://example.test:8787/ws",normalizeServiceAddress(" WSS://example.test:8787/ws "))
        listOf("http://example.test/ws", "https://example.test/ws", "https://example.test/workbench/",
            "ws://example.test/ws", "wss://example.test/", "wss://a:b@example.test/ws", "wss://example.test/api/ws",
            "wss://example.test/ws?token=secret", "wss://example.test/ws#secret", "wss://example.test\\evil/ws").forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { normalizeServiceAddress(it) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
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
    @Test fun serviceHandshakeAuthenticatesWithTokenAndLogoutForgetsIt() = runTest {
        val t = FakeTransport()
        val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws","local-service-test-credential")
            val s = t.sockets.single(); ready(s); runCurrent()
            assertEquals("auth",s.sent[0].getString("type")); assertEquals(26,s.sent[0].getInt("protocolVersion")); assertFalse(s.sent[0].getBoolean("tunnel"))
            assertEquals("android-device",s.sent[1].getString("id"))
            assertEquals(2,s.sent.size)
            assertEquals("online",repo.state.value.status)
            s.frame(json("type" to "devices","devices" to JSONArray().put(json("id" to "server","server" to true,"ready" to true)))); runCurrent()
            assertEquals("server",repo.state.value.serverDeviceId)
            s.frame(json("type" to "connection.error","code" to "unauthorized","message" to "拒绝")); runCurrent()
            repo.foreground(false); repo.foreground(true); runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(1,t.sockets.size); assertFalse(repo.state.value.authenticated)
            repo.logout(); assertTrue(t.credentials.isEmpty()); assertEquals("setup",repo.state.value.status)
        } finally { repo.dispose() }
    }
    @Test fun reconnectUsesDurableCursorNeverReplaysMutationAndIgnoresGlobalEvents() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws",serviceCredential); val first = t.sockets.single(); ready(first); runCurrent()
            assertEquals("wss://example.test/ws",first.url)
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
            repo.connectService("wss://example.test/ws",serviceCredential); val s = t.sockets.single()
            s.frame(json("type" to "ready","protocolVersion" to 24,"sessions" to JSONArray(),"browser" to JSONObject())); runCurrent()
            repo.foreground(true); runCurrent(); advanceTimeBy(60_000); runCurrent(); assertEquals(1,t.sockets.size)
            repo.reconnect(); t.sockets.last().observer.failed(CertificateFailure("pin mismatch")); runCurrent()
            repo.foreground(true); runCurrent(); advanceTimeBy(60_000); runCurrent(); assertEquals(2,t.sockets.size)
        } finally { repo.dispose() }
    }
    @Test fun catalogRequestsDeduplicateGateWritesAndPreserveLatestPermissions() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws",serviceCredential); val s = t.sockets.single(); ready(s); runCurrent()
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
    @Test fun connectionPreloadsOneRealCatalogPerAvailableDeviceWithoutSelectingOrCreatingChats() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        fun deviceSession(id: String, device: String, server: Boolean = false) = session(id).put("device", json("id" to device, "server" to server))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential)
            val socket = t.sockets.single()
            val rows = JSONArray().put(deviceSession("s", "server", true)).put(deviceSession("s2", "server", true))
                .put(deviceSession("remote", "pc")).put(deviceSession("offline", "offline"))
                .put(deviceSession("phone", "android-device"))
            socket.frame(json("type" to "ready", "protocolVersion" to 26, "sessions" to rows)); runCurrent()
            assertEquals(listOf("s"), socket.sent.filter { it.optString("type") == "session.status" }.map { it.getString("sessionId") })
            val presence = json("type" to "devices", "devices" to JSONArray()
                .put(json("id" to "server", "server" to true, "ready" to true))
                .put(json("id" to "pc", "ready" to true)).put(json("id" to "android-device", "ready" to true)))
            socket.frame(presence); socket.frame(presence); runCurrent()
            val reads = socket.sent.filter { it.optString("type") == "session.status" }
            assertEquals(listOf("s", "remote"), reads.map { it.getString("sessionId") })
            assertTrue(reads.none { it.getBoolean("quota") })
            assertEquals(setOf("server", "pc"), repo.state.value.modelCatalogLoading)
            for ((index, request) in reads.withIndex()) {
                val capabilities = event(10L + index, id = request.getString("sessionId")).put("payload", json("type" to "native.capabilities",
                    "models" to JSONArray().put(json("value" to "real-$index", "displayName" to "Real model $index"))))
                socket.frame(json("type" to "session.event", "event" to capabilities)); socket.response(request.getString("requestId"))
            }
            runCurrent()
            assertEquals("real-0", repo.state.value.modelCatalogs.getValue("server").models.single().value)
            assertEquals("real-1", repo.state.value.modelCatalogs.getValue("pc").models.single().value)
            assertTrue(repo.state.value.modelCatalogLoading.isEmpty()); assertTrue(repo.state.value.modelCatalogErrors.isEmpty())
            assertNull(repo.state.value.selectedId); assertTrue(repo.state.value.histories.isEmpty())
            assertFalse(socket.sent.any { it.optString("type") in setOf("server.session.create", "message.send", "session.configure", "session.subscribe") })
        } finally { repo.dispose() }
    }
    @Test fun emptyAccountWaitsForFirstSessionAndBusySessionsWaitUntilIdle() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single()
            socket.frame(json("type" to "ready", "protocolVersion" to 26, "sessions" to JSONArray()))
            socket.frame(json("type" to "devices", "devices" to JSONArray().put(json("id" to "server", "server" to true, "ready" to true)))); runCurrent()
            assertFalse(socket.sent.any { it.optString("type") in setOf("server.session.create", "session.status") })
            val busy = session().put("activeRun", json("id" to "busy", "status" to "running"))
            socket.frame(json("type" to "session.updated", "session" to busy)); runCurrent()
            assertFalse(socket.sent.any { it.optString("type") == "session.status" })
            socket.frame(json("type" to "session.updated", "session" to session())); runCurrent()
            assertEquals("s", socket.sent.single { it.optString("type") == "session.status" }.getString("sessionId"))
            assertNull(repo.state.value.selectedId)
        } finally { repo.dispose() }
    }
    @Test fun sameDeviceCatalogReadsShareOneRequestWithoutBlockingOtherConversations() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single()
            socket.frame(json("type" to "ready", "protocolVersion" to 26, "sessions" to JSONArray().put(session()).put(session("s2"))))
            socket.frame(json("type" to "devices", "devices" to JSONArray().put(json("id" to "server", "server" to true)))); runCurrent()
            val read = socket.sent.single { it.optString("type") == "session.status" }
            val duplicate = async { repo.loadModelCatalog("s2") }; runCurrent()
            val otherChat = async { repo.command("message.send", json("sessionId" to "s2", "text" to "Fixture message")) }; runCurrent()
            assertEquals(1, socket.sent.count { it.optString("type") == "session.status" })
            assertEquals("s2", socket.sent.last().getString("sessionId")); assertEquals("message.send", socket.sent.last().getString("type"))
            socket.response(); runCurrent(); otherChat.await()
            assertFalse(duplicate.isCompleted)
            socket.frame(json("type" to "session.event", "event" to event(20).put("payload", json("type" to "native.capabilities",
                "models" to JSONArray().put(json("value" to "actual", "displayName" to "Actual model"))))))
            socket.response(read.getString("requestId")); runCurrent(); duplicate.await()
            assertEquals("actual", repo.state.value.modelCatalogs.getValue("server").models.single().value)
        } finally { repo.dispose() }
    }
    @Test fun catalogFailureHasVisibleStateNoAutomaticRetryLoopAndManualRetryCanRecover() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val socket = t.sockets.single(); ready(socket)
            val presence = json("type" to "devices", "devices" to JSONArray().put(json("id" to "server", "server" to true)))
            socket.frame(presence); runCurrent()
            socket.response(); runCurrent()
            assertTrue(repo.state.value.modelCatalogErrors.getValue("server").contains("未返回模型目录"))
            assertTrue(repo.state.value.modelCatalogs.isEmpty()); assertTrue(repo.state.value.modelCatalogLoading.isEmpty())
            socket.frame(presence); socket.frame(json("type" to "session.updated", "session" to session())); runCurrent()
            assertEquals(1, socket.sent.count { it.optString("type") == "session.status" })
            val retry = async { repo.loadModelCatalog("s") }; runCurrent()
            assertTrue(repo.state.value.modelCatalogErrors.isEmpty()); assertEquals(setOf("server"), repo.state.value.modelCatalogLoading)
            socket.frame(json("type" to "session.event", "event" to event(20).put("payload", json("type" to "native.capabilities",
                "models" to JSONArray().put(json("value" to "real", "displayName" to "Real model"))))))
            socket.response(); runCurrent(); retry.await()
            assertEquals("real", repo.state.value.modelCatalogs.getValue("server").models.single().value)
            assertTrue(repo.state.value.modelCatalogErrors.isEmpty()); assertTrue(repo.state.value.modelCatalogLoading.isEmpty())
        } finally { repo.dispose() }
    }
    @Test fun reconnectRefreshesCatalogAndOldReadCannotContaminateNewServiceState() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t, StandardTestDispatcher(testScheduler))
        val presence = json("type" to "devices", "devices" to JSONArray().put(json("id" to "server", "server" to true)))
        try {
            repo.connectService("wss://example.test/ws", serviceCredential); val old = t.sockets.single()
            ready(old); old.frame(presence); runCurrent()
            assertEquals(1, old.sent.count { it.optString("type") == "session.status" })
            repo.reconnect(); val next = t.sockets.last(); ready(next); next.frame(presence); runCurrent()
            assertEquals(1, next.sent.count { it.optString("type") == "session.status" })
            assertEquals(setOf("server"), repo.state.value.modelCatalogLoading)
            old.response(); runCurrent()
            assertTrue(repo.state.value.modelCatalogErrors.isEmpty()); assertEquals(setOf("server"), repo.state.value.modelCatalogLoading)
            repo.connectService("wss://new.test/ws", serviceCredential); runCurrent()
            next.response(); runCurrent()
            assertTrue(repo.state.value.modelCatalogLoading.isEmpty()); assertTrue(repo.state.value.modelCatalogErrors.isEmpty())
            assertTrue(repo.state.value.modelCatalogs.isEmpty())
        } finally { repo.dispose() }
    }
    @Test fun staleCredentialReadCannotAuthenticatePreviousEndpointAndCommandsReturnPayload() = runTest {
        val t = FakeTransport(); val slow = CompletableDeferred<Unit>()
        t.credentialBarrier = { if (it.contains("slow")) slow.await() }
        val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            val switching = async { repo.connectService("wss://slow.test/ws",serviceCredential) }; runCurrent()
            repo.connectService("wss://new.test/ws",serviceCredential); slow.complete(Unit); runCurrent(); switching.await()
            assertEquals("wss://new.test/ws",repo.state.value.endpoint); assertEquals(1,t.sockets.size)
            val s = t.sockets.single(); assertEquals("wss://new.test/ws",s.url); ready(s); runCurrent()
            val request = async { repo.command("web.settings.get") }; runCurrent(); val id = s.sent.last().getString("requestId")
            s.frame(json("type" to "web.settings.state","requestId" to id,"values" to json("totpEnabled" to true))); s.response(id); runCurrent()
            assertEquals("web.settings.state",request.await().getString("type")); assertTrue(repo.state.value.webSettings!!.getBoolean("totpEnabled"))
        } finally { repo.dispose() }
    }
    @Test fun savedHttpsLoginIsRejectedWithoutOpeningASocketOrReadingCredentials() = runTest {
        val t = FakeTransport().apply { endpoint = "https://legacy.test/workbench/" }
        val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.start()
            assertEquals("setup",repo.state.value.status)
            assertEquals("",repo.state.value.endpoint)
            assertTrue(repo.state.value.error.orEmpty().contains("wss://"))
            assertTrue(t.sockets.isEmpty()); assertTrue(t.credentialReads.isEmpty())
            assertFalse(repo.state.value.authenticated)
        } finally { repo.dispose() }
    }
    @Test fun invalidWebLoginCannotReplaceTheConnectedServiceOrStoreItsCredential() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws",serviceCredential)
            val socket = t.sockets.single(); ready(socket); runCurrent()
            assertTrue(runCatching { repo.setEndpoint("https://other.test/") }.isFailure)
            assertTrue(runCatching { repo.connectService("https://other.test/ws","other-service-credential-0123456789") }.isFailure)
            assertTrue(runCatching { repo.setFingerprint("https://other.test/","AB".repeat(32)) }.isFailure)
            assertEquals("online",repo.state.value.status); assertFalse(socket.closed)
            assertEquals(mapOf("wss://example.test/ws" to serviceCredential),t.credentials)
            assertEquals(1,t.sockets.size)
        } finally { repo.dispose() }
    }
    @Test fun endpointAndCredentialClearingWritesCannotBeReordered() = runTest {
        val t = FakeTransport(); val barrier = CompletableDeferred<Unit>(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        t.endpointBarrier = { if (it.contains("first")) barrier.await() }
        try {
            val first = async { repo.connectService("wss://first.test/ws",serviceCredential) }; runCurrent()
            val second = async { repo.connectService("wss://second.test/ws",serviceCredential) }; runCurrent()
            assertEquals("",t.endpoint)
            barrier.complete(Unit); runCurrent(); first.await(); second.await()
            assertEquals("wss://second.test/ws",t.endpoint); assertEquals(1,t.sockets.size)
            val s = t.sockets.single(); ready(s); runCurrent()
            val prefsBarrier = CompletableDeferred<Unit>()
            t.preferenceBarrier = { _,prefs -> if (prefs.notices.isNotEmpty()) prefsBarrier.await() }
            s.frame(json("type" to "session.event","event" to event(1).put("payload",json("type" to "run.status","status" to "completed")))); runCurrent()
            val logout = async { repo.logout() }; runCurrent(); assertFalse(logout.isCompleted)
            prefsBarrier.complete(Unit); runCurrent(); logout.await()
            assertEquals("",t.endpoint); assertFalse(t.credentials.containsKey("wss://second.test/ws")); assertTrue(repo.state.value.notices.isEmpty())
        } finally { repo.dispose() }
    }
    @Test fun staleSocketFramesCannotMutateNewConnectionAndHandshakeTimeoutBacksOff() = runTest {
        val t = FakeTransport(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws",serviceCredential); val first = t.sockets.single()
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
            repo.connectService("wss://example.test/ws",serviceCredential); val s = t.sockets.single(); ready(s); runCurrent()
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
            repo.connectService("wss://example.test/ws",serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
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
            repo.connectService("wss://example.test/ws",serviceCredential); val socket = t.sockets.single(); ready(socket); runCurrent()
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
            repo.connectService("wss://first.test/ws",serviceCredential); val first = t.sockets.single(); ready(first); runCurrent()
            val subscribe = async { repo.selectSession("s") }; runCurrent()
            first.frame(json("type" to "session.snapshot","session" to session(),"mode" to "replace","events" to JSONArray().put(event(10,"Saved history"))))
            first.response(); runCurrent(); subscribe.await()
            first.frame(json("type" to "session.fragment","sessionId" to "s","runId" to "r","payload" to json("type" to "text.delta","messageId" to "partial","text" to "Temporary"))); runCurrent()
            val pending = async { runCatching { repo.command("session.stats",json("sessionId" to "s")) } }; runCurrent()
            first.observer.closed(1006,"Lost connection"); runCurrent()
            assertTrue(pending.await().isFailure)
            val offline = repo.state.value.histories.getValue("s")
            assertEquals(listOf(10L),offline.events.map { it.sequence }); assertTrue(offline.fragments.isEmpty()); assertFalse(offline.loading)
            repo.connectService("wss://second.test/ws",serviceCredential); val second = t.sockets.last(); ready(second); runCurrent()
            first.frame(json("type" to "session.snapshot","session" to session(),"mode" to "replace","events" to JSONArray().put(event(99,"Old endpoint data"))))
            first.frame(json("type" to "account.state","account" to "Old endpoint account")); runCurrent()
            assertEquals("wss://second.test/ws",repo.state.value.endpoint); assertTrue(repo.state.value.histories.isEmpty()); assertNull(repo.state.value.account)
            assertNull(repo.state.value.selectedId); assertEquals("online",repo.state.value.status)
        } finally { repo.dispose() }
    }
    @Test fun disposalDrainsQueuedPreferenceChangesInOrder() = runTest {
        val t = FakeTransport(); val barrier = CompletableDeferred<Unit>(); val repo = WorkbenchRepository(t,StandardTestDispatcher(testScheduler))
        try {
            repo.connectService("wss://example.test/ws",serviceCredential); ready(t.sockets.single()); runCurrent()
            t.preferenceBarrier = { _,_ -> barrier.await() }
            repo.togglePin("s"); runCurrent()
            repo.togglePin("s"); runCurrent()
            assertTrue(t.preferenceWrites.isEmpty())
            repo.dispose()
            barrier.complete(Unit)
            repo.awaitPersistenceDrain()
            assertEquals(listOf(listOf("s"), emptyList<String>()),t.preferenceWrites.map { it.pins })
            assertTrue(t.preferences.getValue("wss://example.test/ws").pins.isEmpty())
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
            json("protocolVersion" to "26"), json("protocolVersion" to 26.5), json("protocolVersion" to 25),
            json("protocolVersion" to 26,"browser" to true),
            json("protocolVersion" to 26,"browser" to "browser"),
            json("protocolVersion" to 26,"browser" to JSONObject())
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
            socket.frame(json("type" to "ready","protocolVersion" to 26,"browser" to false,"sessions" to JSONArray()))
            runCurrent(); assertEquals("online",repo.state.value.status); assertTrue(socket.sent.any { it.optString("type") == "device" })
        } finally { repo.dispose() }
    }
}
