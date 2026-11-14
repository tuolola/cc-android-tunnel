package dev.ccdesk.nativeapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Explicit opt-in, one exact read/write/network-free shell command in a new test session.
 * No service payload, prompt, credential, command result or exception body is logged.
 */
@RunWith(AndroidJUnit4::class)
class RealToolAcceptanceTest {
    @Test fun exactPrintfToolExecutionAndConditionalApproval() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Real tool acceptance requires -e runRealTool true", arguments.getString("runRealTool") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AndroidWorkbenchTransport(context)
        val isolated = IsolatedContext(context)
        val transport = AndroidWorkbenchTransport(isolated)
        val repository = WorkbenchRepository(transport)
        val report = json("passed" to false, "toolExecutionPassed" to false,
            "approvalTriggered" to false, "approvalPassed" to false, "approvalNotTriggered" to false)
        val observed = JSONArray()
        report.put("events", observed)
        var sessionId: String? = null
        var endpoint: String? = null
        var originalCredential: String? = null
        var primaryFailure: Throwable? = null
        fun save() { context.cacheDir.resolve("native-real-tool-acceptance.json").writeText(report.toString(2)) }
        try {
            endpoint = original.getEndpoint()
            require(endpoint.startsWith("wss://")) { "This test requires the configured direct WSS service" }
            require(URI(endpoint).host == arguments.getString("expectedHost")) { "expectedHost must explicitly match the configured service" }
            originalCredential = original.credential(endpoint)
            require(originalCredential.isNotBlank()) { "Connect the native app before opting into this test" }
            transport.setEndpoint(endpoint)
            transport.setFingerprint(endpoint, original.fingerprint(endpoint))
            transport.saveCredential(endpoint, originalCredential)
            repository.start()
            withTimeout(30_000) { repository.state.first { it.status == "online" } }
            sessionId = repository.createSession("Native exact tool acceptance " + now().replace(':','-'))
            report.put("sessionId", safeId(sessionId))
            // Do not request bypassPermissions/acceptEdits. The server may still allow this
            // harmless command under its existing policy; report that as no approval observed.
            repository.command("session.configure", json("sessionId" to sessionId, "permissionMode" to "default"))
            val beforeSequence = repository.state.value.histories[sessionId]?.events?.lastOrNull()?.sequence ?: 0
            repository.sendMessage(
                "Native Android tool acceptance test. Use the Bash tool exactly once with this exact command:\n```sh\n" +
                    EXACT_COMMAND + "\n```\nDo not add shell syntax or change the command. Do not read or write files, " +
                    "access the network, inspect the project, or call any other tool. If approval is required, " +
                    "wait for it. After the command succeeds, reply only with a short English confirmation."
            )
            val observedKeys = mutableSetOf<String>()
            val handledApprovals = mutableSetOf<String>()
            val acceptedApprovals = mutableSetOf<String>()
            val confirmedToolInputs = mutableMapOf<String,String>()
            val resolvedApprovals = mutableSetOf<String>()
            var runId: String? = null
            withTimeout(180_000) {
                while (true) {
                    val state = repository.state.value
                    check(state.status == "online") { "Real service connection was lost during tool acceptance" }
                    val history = state.histories[sessionId] ?: History()
                    val durable = history.events.filter { it.sequence > beforeSequence }
                    val all = durable + history.fragments
                    runId = runId ?: state.sessions.find { it.id == sessionId }?.activeRun?.id
                        ?: durable.firstOrNull { it.payload.optString("type") in setOf("message.user","run.status","tool.requested") }?.runId
                    val own = all.filter { it.runId == runId }
                    val tools = linkedMapOf<String,ObservedTool>()
                    // Rebuild from this small acceptance run to handle fragment -> durable
                    // handoff without double-counting input/output text.
                    for (event in own) {
                        val payload = event.payload
                        val type = payload.optString("type")
                        val toolId = payload.stringOrNull("toolId")
                        if (type.startsWith("tool.") && toolId != null) {
                            val tool = tools.getOrPut(toolId) { ObservedTool(toolId) }
                            when (type) {
                                "tool.requested" -> {
                                    tool.name = payload.optString("name",payload.optString("toolName"))
                                    tool.input = inputString(payload.opt("input"))
                                    tool.requested = true
                                }
                                "tool.input" -> tool.input = inputString(payload.opt("input"))
                                "tool.input.delta" -> tool.input = tool.input.takeUnless { it == "{}" }.orEmpty() + payload.optString("text")
                                "tool.output" -> { tool.output += payload.optString("text"); tool.outputEvents++ }
                                "tool.progress" -> tool.progressObserved = true
                                "tool.result" -> {
                                    tool.result = payload.optString("output")
                                    tool.resultStatus = payload.optString("status")
                                    tool.exitCode = if (payload.isNull("exitCode")) null else payload.optInt("exitCode")
                                    tool.resultObserved = true
                                }
                            }
                        }
                        if (type in RECORDED_TYPES) {
                            val key = "${event.runId}/${event.sequence}/$type/${toolId.orEmpty()}"
                            if (observedKeys.add(key)) observed.put(json("type" to type,
                                "runId" to safeId(event.runId), "toolId" to toolId?.let(::safeId),
                                "approvalId" to payload.stringOrNull("approvalId")?.let(::safeId),
                                "sequence" to event.sequence))
                        }
                        if (type == "approval.resolved" && payload.optBoolean("allowed")) {
                            payload.stringOrNull("approvalId")?.let(resolvedApprovals::add)
                        }
                    }
                    tools.values.forEach { tool ->
                        if (tool.input.isEmpty() || tool.input == "{}") confirmedToolInputs[tool.id]?.let { tool.input = it }
                    }
                    val approvals = own.filter { it.payload.optString("type") == "approval.requested" }
                    var rejectedApproval = false
                    for (event in approvals) {
                        val p = event.payload
                        val approvalId = p.stringOrNull("approvalId") ?: error("Approval request has no identifier")
                        if (!handledApprovals.add(approvalId)) continue
                        report.put("approvalTriggered", true)
                        val tool = p.stringOrNull("toolId")?.let(tools::get)
                        val directName = p.stringOrNull("toolName") ?: p.stringOrNull("name")
                        val directInput = if (p.has("input")) inputString(p.opt("input")) else null
                        val name = directName ?: tool?.name
                        val input = directInput ?: tool?.input
                        val correlated = tool != null && tool.requested && event.runId == runId
                        val consistent = (directName == null || directName == tool?.name) &&
                            (directInput == null || tool?.input.isNullOrEmpty() || tool?.input == "{}" || sameInput(directInput,tool!!.input))
                        val safe = correlated && consistent && name == "Bash" && exactInput(input) &&
                            tools.size == 1 && approvals.map { it.payload.optString("approvalId") }.distinct().size == 1 && acceptedApprovals.isEmpty()
                        withTimeout(10_000) {
                            repository.command("approval.reply", json("sessionId" to sessionId,
                                "runId" to event.runId, "approvalId" to approvalId, "allowed" to safe))
                        }
                        if (!safe) {
                            report.put("unexpectedToolRejected", true)
                            rejectedApproval = true
                            save()
                            continue
                        }
                        acceptedApprovals += approvalId
                        confirmedToolInputs[tool!!.id] = input!!
                        tool.input = input
                        report.put("approvedCount", acceptedApprovals.size)
                        save()
                    }
                    check(!rejectedApproval) { "Tool approval did not match the one permitted exact command" }
                    // Unknown tool requests fail even when server policy bypasses approvals.
                    // We cannot retroactively prevent a server-auto-approved tool; cleanup cancels
                    // this own run and the report never claims that such a run was safe/passing.
                    if (tools.size > 1 || tools.values.any { it.requested && (it.name != "Bash" || hasCommand(it.input) && !exactInput(it.input)) }) {
                        report.put("unexpectedToolObserved", true)
                        error("Unexpected tool request outside the permitted test scope")
                    }
                    val completed = own.any { it.payload.optString("type") == "run.status" && it.payload.optString("status") == "completed" }
                    check(own.none { it.payload.optString("type") == "run.status" && it.payload.optString("status") in setOf("failed","cancelled") }) { "Tool acceptance run did not complete" }
                    val tool = tools.values.singleOrNull()
                    if (tool?.resultObserved == true) {
                        check(tool.name == "Bash" && exactInput(tool.input)) { "Executed tool input did not match the permitted exact command" }
                        check(tool.resultStatus == "completed" && (tool.exitCode == null || tool.exitCode == 0)) { "The exact tool command failed" }
                    }
                    if (completed) {
                        requireNotNull(tool) { "Run completed without an actual tool request" }
                        check(tool.requested && tool.resultObserved && exactInput(tool.input)) { "Missing complete tool execution evidence" }
                        val output = outputText(tool.result).ifBlank { outputText(tool.output) }
                        check(output.replace("\r\n","\n").trim() == EXPECTED_OUTPUT) { "Exact verification output was not observed" }
                        val approvalPassed = acceptedApprovals.isNotEmpty() && resolvedApprovals.containsAll(acceptedApprovals)
                        check(acceptedApprovals.isEmpty() || approvalPassed) { "Allowed approval resolution was not observed" }
                        report.put("runId", safeId(runId.orEmpty())).put("toolId", safeId(tool.id))
                            .put("toolRequested", true).put("toolProgressObserved", tool.progressObserved)
                            .put("toolOutputObserved", tool.outputEvents > 0 || tool.result.isNotBlank())
                            .put("streamOutputEventCount", tool.outputEvents)
                            .put("outputCharacterCount", tool.output.length).put("resultCharacterCount", tool.result.length)
                            .put("expectedOutputObserved", true).put("toolResultCompleted", true)
                            .put("runCompleted", true).put("toolExecutionPassed", true)
                            .put("approvalPassed", approvalPassed)
                            .put("approvalNotTriggered", approvals.isEmpty())
                            .put("resolvedAllowedApprovalCount", resolvedApprovals.intersect(acceptedApprovals).size)
                        break
                    }
                    delay(50)
                }
            }
            check(original.getEndpoint() == endpoint && original.credential(endpoint) == originalCredential) { "Regular application login changed unexpectedly" }
            report.put("regularLoginPreserved", true).put("passed", true)
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupComplete = true
                val ownSession = sessionId
                if (ownSession != null) {
                    repository.state.value.sessions.find { it.id == ownSession }?.activeRun?.let { run ->
                        val cancelled = runCatching {
                            withTimeout(10_000) { repository.command("run.cancel",json("sessionId" to ownSession,"runId" to run.id)) }
                        }.isSuccess
                        report.put("cleanupCancelAccepted", cancelled)
                        cleanupComplete = cleanupComplete && cancelled
                    }
                }
                withContext(Dispatchers.Main.immediate) { repository.dispose() }
                cleanupComplete = runCatching {
                    withTimeout(10_000) { repository.awaitPersistenceDrain(); transport.awaitClosed() }
                }.isSuccess && cleanupComplete
                if (endpoint != null && originalCredential != null) {
                    val preserved = runCatching {
                        withTimeout(5_000) { original.getEndpoint() == endpoint && original.credential(endpoint!!) == originalCredential }
                    }.getOrDefault(false)
                    report.put("regularLoginPreserved", preserved)
                    cleanupComplete = preserved && cleanupComplete
                }
                original.close()
                cleanupComplete = runCatching { withTimeout(10_000) { original.awaitClosed() } }.isSuccess && cleanupComplete
                cleanupComplete = runCatching { isolated.clean() }.isSuccess && cleanupComplete
                report.put("isolatedCleanupComplete", cleanupComplete)
                if (!cleanupComplete) report.put("passed", false)
                save()
                if (!cleanupComplete && primaryFailure == null) error("Tool acceptance cleanup did not complete")
            }
        }
    }

    private data class ObservedTool(val id: String, var name: String = "", var input: String = "",
        var requested: Boolean = false, var output: String = "", var outputEvents: Int = 0,
        var result: String = "", var resultStatus: String = "", var resultObserved: Boolean = false,
        var progressObserved: Boolean = false, var exitCode: Int? = null)

    private class IsolatedContext(private val base: Context) : ContextWrapper(base) {
        private val prefix = "real-tool-native-" + UUID.randomUUID()
        private val names = mutableSetOf<String>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "$prefix-$name"
            synchronized(names) { names += scoped }
            return base.getSharedPreferences(scoped,mode)
        }
        fun clean() { synchronized(names) { names.forEach { check(base.deleteSharedPreferences(it)) { "Unable to remove isolated preferences" } } } }
    }

    private fun inputString(value: Any?): String = when (value) {
        is JSONObject -> value.toString()
        is String -> value
        else -> ""
    }
    private fun exactInput(raw: String?): Boolean = runCatching {
        val input = JSONObject(raw ?: return false)
        val keys = input.keys().asSequence().toSet()
        keys.all { it in setOf("command","description","timeout","run_in_background","dangerouslyDisableSandbox") } &&
            input.opt("command") == EXACT_COMMAND &&
            (!input.has("description") || input.opt("description") is String) &&
            (!input.has("timeout") || input.opt("timeout") is Number && input.getDouble("timeout") in 1.0..120000.0) &&
            (!input.has("run_in_background") || input.opt("run_in_background") == false) &&
            (!input.has("dangerouslyDisableSandbox") || input.opt("dangerouslyDisableSandbox") == false)
    }.getOrDefault(false)
    private fun sameInput(a: String, b: String) = exactInput(a) && exactInput(b)
    private fun hasCommand(raw: String) = runCatching { JSONObject(raw).has("command") }.getOrDefault(false)
    private fun outputText(raw: String): String = runCatching {
        val parts = JSONArray(raw)
        (0 until parts.length()).joinToString("") { index ->
            val part = parts.getJSONObject(index)
            if (part.optString("type") == "text") part.optString("text") else ""
        }
    }.getOrDefault(raw)
    private fun safeId(value: String): String = if (value.matches(Regex("[A-Za-z0-9._:-]{1,160}"))) value
        else "sha256:" + MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val EXACT_COMMAND = "printf 'Native tool verified\\n'"
        const val EXPECTED_OUTPUT = "Native tool verified"
        val RECORDED_TYPES = setOf("tool.requested","tool.input","tool.input.delta","tool.output","tool.progress",
            "tool.result","approval.requested","approval.resolved","run.status")
    }
}
