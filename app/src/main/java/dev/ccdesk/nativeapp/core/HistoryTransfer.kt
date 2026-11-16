// Copyright 2026 CC Android Tunnel contributors.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.
package dev.ccdesk.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject

/** Protocol 26 slices a JSON array by UTF-16 units, not by events or UTF-8 bytes. */
internal class HistoryTransfer(
    private val requestId: String,
    private val sessionId: String,
    private val prepend: Boolean,
    private val maxChars: Int = 16 * 1024 * 1024,
) {
    private val text = StringBuilder()
    private var ended = false

    fun append(frame: JSONObject): List<JSONObject>? {
        check(!ended && frame.opt("requestId") == requestId && frame.opt("sessionId") == sessionId) { "历史分块与请求不匹配，请重新读取。" }
        val data = frame.opt("data") as? String
        check(data != null && data.length in 1..32768 && frame.opt("last") is Boolean) { "历史分块格式无效，请重新读取。" }
        check(text.length.toLong() + data.length <= maxChars) { "本页历史超过手机接收上限，请在电脑端查看。" }
        text.append(data)
        if (!frame.getBoolean("last")) return null
        ended = true
        val values = JSONArray(text.toString())
        check(values.length() > 0) { "历史分块缺少快照，请重新读取。" }
        val frames = (0 until values.length()).map { values.getJSONObject(it) }
        val snapshot = frames.first()
        check(snapshot.opt("type") == "session.snapshot" && snapshot.opt("requestId") == requestId &&
            snapshot.optJSONObject("session")?.opt("id") == sessionId &&
            snapshot.optString("mode") in (if (prepend) setOf("prepend") else setOf("replace", "append"))) { "历史快照与请求不匹配，请重新读取。" }
        // Validate the entire page before any state is committed, including captured fragments.
        Session.from(snapshot.getJSONObject("session"))
        val events = snapshot.getJSONArray("events")
        for (index in 0 until events.length()) {
            check(DeskEvent.from(events.getJSONObject(index)).sessionId == sessionId) { "历史事件归属不匹配，请重新读取。" }
        }
        frames.drop(1).forEach {
            check(!prepend && it.opt("type") == "session.fragment" && it.opt("sessionId") == sessionId &&
                it.opt("runId") is String && it.optJSONObject("payload") != null) { "历史附带消息格式无效，请重新读取。" }
        }
        return frames
    }
}
