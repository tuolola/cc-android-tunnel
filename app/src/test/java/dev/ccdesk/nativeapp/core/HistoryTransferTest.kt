package dev.ccdesk.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryTransferTest {
    private fun snapshot() = json("type" to "session.snapshot", "requestId" to "request", "session" to json("id" to "session"),
        "mode" to "replace", "events" to JSONArray())
    private fun part(data: String, last: Boolean = false) = json("requestId" to "request", "sessionId" to "session", "data" to data, "last" to last)

    @Test fun localMemoryLimitFailsExplicitlyWithoutTreatingPartialJsonAsAHistoryPage() {
        val reader = HistoryTransfer("request", "session", false, maxChars = 12)
        assertNull(reader.append(part("[123456789")))
        assertTrue(runCatching { reader.append(part("01234", true)) }.exceptionOrNull()?.message.orEmpty().contains("接收上限"))
    }

    @Test fun wrongInnerSessionMalformedEventsAndUnexpectedFramesAreRejectedBeforeCommit() {
        val invalid = listOf(
            JSONArray().put(snapshot().put("session", json("id" to "other"))),
            JSONArray().put(snapshot().put("events", JSONArray().put(json("sessionId" to "other")))),
            JSONArray().put(snapshot()).put(json("type" to "session.deleted", "sessionId" to "session")),
            JSONArray().put(snapshot()).put(json("type" to "session.fragment", "sessionId" to "other", "runId" to "r", "payload" to JSONObject())),
        )
        invalid.forEach { page ->
            assertTrue(runCatching { HistoryTransfer("request", "session", false).append(part(page.toString(), true)) }.isFailure)
        }
    }

    @Test fun aLargeSingleTurnAboveTheServerSoftPageBudgetRemainsValid() {
        val text = "中文🙂".repeat(140_000)
        val page = JSONArray().put(snapshot().put("events", JSONArray().put(json("sessionId" to "session", "runId" to "run", "sequence" to 1,
            "payload" to json("type" to "text.delta", "text" to text))))).toString()
        val reader = HistoryTransfer("request", "session", false)
        var result: List<JSONObject>? = null
        val chunks = page.chunked(32768)
        chunks.forEachIndexed { index, chunk -> result = reader.append(part(chunk,index == chunks.lastIndex)) }
        assertEquals(text, result!!.single().getJSONArray("events").getJSONObject(0).getJSONObject("payload").getString("text"))
    }
}
