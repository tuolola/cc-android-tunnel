// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.ui

import androidx.compose.runtime.*
import dev.ccdesk.nativeapp.core.DeskEvent
import org.json.JSONObject

data class ToolData(val id:String,val name:String="工具调用",val input:String="",val output:String="",val result:String="",val status:String="",val elapsed:Double=0.0,val taskId:String="",val approvalId:String="",val allowed:Boolean?=null,val target:String="",val exitCode:Int?=null)
@Stable class ChatRow(val id:String,val runId:String,val kind:String,initial:String="") {
    var content by mutableStateOf(initial)
    var delivery by mutableStateOf("")
    var tool by mutableStateOf<ToolData?>(null)
    fun searchable()=tool?.let{listOf(it.name,it.input,it.output,it.result).joinToString("\n")}?:content
}
data class RunTask(val id:String,val description:String,val kind:String,val progress:String="")
val runLabels=mapOf("running" to "运行中","awaiting_approval" to "等待审批","completed" to "已完成","cancelled" to "已停止","failed" to "运行失败")

/** A stable row store; appending stream deltas mutates only the affected row. History
 * reconciliation rebuilds only when the server replaces or prepends an event prefix. */
@Stable class TranscriptProjection {
    val rows=mutableStateListOf<ChatRow>()
    var context by mutableStateOf<JSONObject?>(null)
    var usage by mutableStateOf<JSONObject?>(null)
    var nativeModel by mutableStateOf("")
    var status by mutableStateOf("")
    var tasks by mutableStateOf<List<RunTask>>(emptyList())
    private val tools=mutableMapOf<String,ChatRow>()
    private val approvals=mutableMapOf<String,ChatRow>()
    private val users=mutableMapOf<String,ChatRow>()
    private var block:Pair<String,ChatRow>?=null
    private var previous=emptyList<DeskEvent>()
    private var previousActive:String?=null
    private var previousEvents=emptyList<DeskEvent>()
    private var previousFragments=emptyList<DeskEvent>()
    private val eventRows=mutableMapOf<String,ChatRow>()
    private fun eventKey(e:DeskEvent)=listOf(e.runId,e.sequence.toString(),e.payload.optString("type"),e.payload.optString("messageId"),e.payload.optString("toolId"),e.payload.optString("taskId"),e.payload.optString("stream")).joinToString("/")
    private data class Checkpoint(val context:JSONObject?,val usage:JSONObject?,val model:String,val status:String,val tasks:List<RunTask>)
    private val checkpoints=mutableMapOf<String,Checkpoint>()
    fun update(events:List<DeskEvent>,fragments:List<DeskEvent>,active:String?) {
        // Aggregated fragment replacements normally only extend one string. Avoid
        // traversing the committed transcript or replacing any row in this hot path.
        if(events===previousEvents&&active==previousActive&&fragments.size==previousFragments.size&&fragments.isNotEmpty()){
            val changed=fragments.indices.filter{fragments[it]!==previousFragments[it]}
            val deltas=changed.mapNotNull{i->val before=previousFragments[i];val after=fragments[i];val type=after.payload.optString("type");val old=before.payload.optString("text");val next=after.payload.optString("text");val row=eventRows[eventKey(before)]
                if(type in listOf("text.delta","thinking.delta","tool.input.delta","tool.output")&&eventKey(before)==eventKey(after)&&next.startsWith(old)&&row!=null) Triple(row,type,next.substring(old.length)) else null
            }
            if(deltas.size==changed.size){deltas.forEach{(row,type,text)->when(type){"text.delta","thinking.delta"->row.content+=text;"tool.input.delta"->row.tool=row.tool!!.copy(input=row.tool!!.input.takeUnless{it=="{}"}.orEmpty()+text);"tool.output"->row.tool=row.tool!!.copy(output=row.tool!!.output+text)}};previousFragments=fragments;previous=events+fragments;return}
        }
        val all=events+fragments
        var append=previous.size<=all.size && previousActive==active
        if(append&&previous.isNotEmpty()) append=previous.last()===all[previous.lastIndex]
        if(append&&events!==previousEvents)append=previous.indices.all{previous[it]===all[it]}
        var from=if(append)previous.size else 0
        if(!append){
            var common=0
            while(common<minOf(previous.size,all.size)&&previous[common]===all[common])common++
            if(previousActive!=active)common=minOf(common,all.indexOfFirst{it.runId==previousActive||it.runId==active}.takeIf{it>=0}?:common)
            if(common>0){val changed=all.getOrNull(common)?.runId?:previous.getOrNull(common)?.runId;while(common>0&&all[common-1].runId==changed)common--}
            from=common
            val affected=(previous.drop(common)+all.drop(common)).map{it.runId}.toSet()
            rows.removeAll{it.runId in affected};tools.entries.removeAll{it.value.runId in affected};approvals.entries.removeAll{it.value.runId in affected};users.entries.removeAll{it.value.runId in affected};block=null
            eventRows.entries.removeAll{it.value.runId in affected}
            val checkpoint=all.getOrNull(common)?.runId?.let{checkpoints[it]}
            context=checkpoint?.context;usage=checkpoint?.usage;nativeModel=checkpoint?.model.orEmpty();status=checkpoint?.status.orEmpty();tasks=checkpoint?.tasks.orEmpty()
            affected.forEach{checkpoints.remove(it)}
        }
        for(i in from until all.size){val e=all[i];checkpoints.putIfAbsent(e.runId,Checkpoint(context,usage,nativeModel,status,tasks));apply(e,active)}
        previous=all;previousActive=active;previousEvents=events;previousFragments=fragments
    }
    private fun add(e:DeskEvent,kind:String,text:String="",id:String="${e.runId}/$kind/${e.sequence}"):ChatRow = ChatRow(id,e.runId,kind,text).also{rows.add(it)}
    private fun tool(e:DeskEvent,id:String):ChatRow=tools.getOrPut("${e.runId}/$id") {add(e,"tool",id="${e.runId}/$id/tool").also{it.tool=ToolData(id)}}
    private fun apply(e:DeskEvent,active:String?) {
        val p=e.payload
        when(p.optString("type")) {
            "message.user"->{block=null;val id=p.optString("messageId");users[id]=add(e,"user",if(p.optBoolean("continued"))"继续上一轮" else p.optString("text"),"${e.runId}/user/$id")}
            "message.delivery"->users[p.optString("messageId")]?.delivery=p.optString("status")
            "text.delta","thinking.delta"->{val key="${e.runId}/${p.optString("type")}/${p.optString("messageId")}";if(block?.first!=key)block=key to add(e,if(p.optString("type")=="text.delta")"reply" else "thinking");block!!.second.content+=p.optString("text");eventRows[eventKey(e)]=block!!.second}
            "tool.requested"->{block=null;val row=tool(e,p.optString("toolId"));row.tool=row.tool!!.copy(name=p.optString("name"),input=p.optString("input"),target=p.optString("target"))}
            "tool.input"->{val row=tool(e,p.optString("toolId"));row.tool=row.tool!!.copy(input=p.optString("input"))}
            "tool.input.delta"->{val row=tool(e,p.optString("toolId"));row.tool=row.tool!!.copy(input=row.tool!!.input.takeUnless{it=="{}"}.orEmpty()+p.optString("text"));eventRows[eventKey(e)]=row}
            "tool.output"->{val row=tool(e,p.optString("toolId"));row.tool=row.tool!!.copy(output=row.tool!!.output+p.optString("text"));eventRows[eventKey(e)]=row}
            "tool.progress"->{val row=tool(e,p.optString("toolId"));row.tool=row.tool!!.copy(elapsed=p.optDouble("elapsedSeconds",0.0))}
            "tool.task"->{val row=tool(e,p.optString("toolId"));row.tool=row.tool!!.copy(taskId=p.optString("taskId"))}
            "tool.result"->{val row=tool(e,p.optString("toolId"));row.tool=row.tool!!.copy(result=p.optString("output"),status=p.optString("status"),exitCode=if(p.isNull("exitCode"))null else p.optInt("exitCode"))}
            "approval.requested"->{val row=tool(e,p.optString("toolId"));val id=p.optString("approvalId");row.tool=row.tool!!.copy(approvalId=id);approvals[id]=row}
            "approval.resolved"->approvals[p.optString("approvalId")]?.let{it.tool=it.tool!!.copy(allowed=p.optBoolean("allowed"))}
            "run.status"->{status=p.optString("status");if(status in listOf("completed","cancelled","failed")){block=null;add(e,"status",listOfNotNull(runLabels[status],p.optString("reason").takeIf{it.isNotEmpty()}).joinToString(" · "))}}
            "run.error"->add(e,"error",p.optString("message"))
            "native.notice"->add(e,"notice",p.optString("text"))
            "native.compact"->add(e,"notice","上下文已压缩"+if(p.isNull("previousTokens"))"" else " · 原有 ${p.optLong("previousTokens")} tokens")
            "native.session"->nativeModel=p.optString("model")
            "native.metrics"->{context=p.optJSONObject("context")?:context;usage=p.optJSONObject("usage")?:usage}
            "native.tasks"->if(e.runId==active){val a=p.optJSONArray("tasks");tasks=List(a?.length()?:0){i->val t=a!!.getJSONObject(i);RunTask(t.optString("id"),t.optString("description"),t.optString("kind"),t.optString("progress"))}}
            "native.progress"->if(e.runId==active)tasks=tasks.map{if(it.id==p.optString("taskId"))it.copy(progress=p.optString("summary").ifEmpty{p.optString("tool")})else it}
        }
    }
}
