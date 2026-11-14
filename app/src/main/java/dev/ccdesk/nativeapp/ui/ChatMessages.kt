@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ccdesk.nativeapp.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.util.Base64
import android.widget.TextView
import android.widget.ImageView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import dev.ccdesk.nativeapp.core.json
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

private val deliveryLabels=mapOf("queued" to "已排队","submitted" to "已提交","received" to "已送达","not_sent" to "未发送","unconfirmed" to "送达状态未知")
@Composable fun ChatTranscriptRow(row:ChatRow,active:Boolean,disabled:Boolean,current:Boolean,modifier:Modifier=Modifier,onHeightChange:()->Unit={},send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current
    Column((if(current)modifier.fillMaxWidth().border(1.dp,c.foreground,RoundedCornerShape(12.dp)).background(c.muted,RoundedCornerShape(12.dp)).padding(9.dp)else modifier.fillMaxWidth()).onSizeChanged{onHeightChange()}){
        when(row.kind){
            "user"->{BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom=8.dp)){val limit=maxWidth*.8f;Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){Box(Modifier.widthIn(max=limit).background(c.userBubble,RoundedCornerShape(16.dp)).padding(12.dp)){SelectionContainer{DeskText(row.content,size=16,lineHeight=22)}}}};if(row.delivery.isNotEmpty())DeskText(deliveryLabels[row.delivery]?:row.delivery,Modifier.align(Alignment.End).padding(bottom=8.dp),12,c.mutedForeground)}
            "reply"->NativeMarkdown(row.content.ifEmpty{"..."},Modifier.fillMaxWidth().padding(bottom=8.dp))
            "tool"->row.tool?.let{ToolCard(it,row.runId,active,disabled,current,send)}
            "thinking"->{var open by remember(row.id){mutableStateOf(false)};Column(Modifier.padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.clickable{open=!open}.padding(vertical=4.dp)){DeskText("思考过程  ${if(open||current)"⌃" else "⌄"}",size=14,color=c.mutedForeground,weight=FontWeight.Medium)};if(open||current)SelectionContainer{DeskText(row.content,size=14,color=c.mutedForeground,lineHeight=24)}}}
            else->SelectionContainer{DeskText(row.content,Modifier.padding(bottom=12.dp),if(row.kind=="error")14 else 12,if(row.kind=="error")if(c.dark)Color(0xffff6467)else Color(0xffe7000b)else c.mutedForeground,lineHeight=20)}
        }
    }
}
@Composable private fun ToolCard(tool:ToolData,runId:String,active:Boolean,disabled:Boolean,reveal:Boolean,send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current;var expanded by remember(tool.id){mutableStateOf(false)}
    val awaiting=active&&tool.approvalId.isNotEmpty()&&tool.allowed==null&&tool.status.isEmpty()
    val label=if(tool.status.isNotEmpty())mapOf("completed" to "已完成","failed" to "失败","cancelled" to "已停止","unknown" to "状态未知","denied" to "已拒绝")[tool.status]?:tool.status else if(awaiting)if(tool.name=="AskUserQuestion")"等待回答"else "等待审批"else if(tool.allowed==false)"已拒绝"else if(active)"进行中"else "已结束"
    val open=awaiting||expanded||reveal
    Column(Modifier.fillMaxWidth().padding(bottom=12.dp).border(1.dp,c.border,RoundedCornerShape(16.dp)).background(c.card,RoundedCornerShape(16.dp)).padding(17.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.semantics{contentDescription=tool.name+"详情";role=Role.Button;stateDescription=if(open)"已展开"else "已折叠"},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){DeskText(tool.name,Modifier.weight(1f),14,weight=FontWeight.SemiBold,lineHeight=20);DeskText(label+(if(tool.elapsed>0)" · ${tool.elapsed.toString().removeSuffix(".0")}s"else "")+if(open)"  ⌃"else "  ⌄",size=12,color=c.mutedForeground)}
        if(open){if(tool.target.isNotEmpty())DeskText(tool.target,size=12,color=c.mutedForeground);if(tool.input.isNotEmpty()&&(!awaiting||tool.name!="AskUserQuestion"||questionsFrom(tool.input).isEmpty()))MonoOutput(tool.input);if(tool.output.isNotEmpty())ToolOutput(tool.output);if(tool.result.isNotEmpty()&&tool.result!=tool.output)ToolOutput(tool.result);if(tool.exitCode!=null)DeskText("退出码 ${tool.exitCode}",size=12,color=c.mutedForeground)}
        if(awaiting)key(tool.approvalId){Approval(tool,runId,disabled,send)}
        if(tool.taskId.isNotEmpty()&&active&&tool.status.isEmpty())FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){DeskAction("转入后台",!disabled){send("run.task.background",json("runId" to runId,"toolId" to tool.id))};DeskAction("停止任务",!disabled){send("run.task.stop",json("runId" to runId,"taskId" to tool.taskId))}}
    }
}
data class Question(val text:String,val multi:Boolean,val options:List<Pair<String,String>>)
fun questionsFrom(input:String):List<Question> = runCatching{val a=JSONObject(input).optJSONArray("questions")?:return emptyList();(0 until a.length()).mapNotNull{val q=a.optJSONObject(it)?:return@mapNotNull null;val text=q.optString("question").takeIf{it.isNotBlank()}?:return@mapNotNull null;val options=q.optJSONArray("options");Question(text,q.optBoolean("multiSelect"),(0 until (options?.length()?:0)).mapNotNull{i->val o=options!!.optJSONObject(i);if(o?.opt("label") is String)o.getString("label") to o.optString("description")else null})}}.getOrDefault(emptyList())
@Composable private fun Approval(tool:ToolData,runId:String,disabled:Boolean,send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current;val questions=remember(tool.input){questionsFrom(tool.input)};val choices=remember{mutableStateMapOf<String,List<String>>()};val free=remember{mutableStateMapOf<String,String>()}
    fun reply(allowed:Boolean,answers:JSONObject?=null){send("approval.reply",json("runId" to runId,"approvalId" to tool.approvalId,"allowed" to allowed).apply{if(answers!=null)put("answers",answers)})}
    if(tool.name!="AskUserQuestion"){FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){DeskAction("允许执行",!disabled,primary=true){reply(true)};DeskAction("拒绝",!disabled){reply(false)}};return}
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
        questions.forEach{q->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            DeskText(q.text,weight=FontWeight.SemiBold);if(q.multi)DeskText("可多选",size=12,color=c.mutedForeground)
            q.options.forEach{(label,description)->val selected=label in choices[q.text].orEmpty();Column(Modifier.fillMaxWidth().border(1.dp,if(selected)c.foreground else c.border,RoundedCornerShape(12.dp)).toggleable(value=selected,enabled=!disabled,role=if(q.multi)Role.Checkbox else Role.RadioButton){val current=choices[q.text].orEmpty();choices[q.text]=if(selected)current-label else if(q.multi)current+label else listOf(label)}.padding(13.dp)){
                DeskText((if(selected)"✓  "else "")+label,size=14,lineHeight=20);if(description.isNotEmpty())DeskText(description,Modifier.padding(top=4.dp),12,c.mutedForeground)
            }}
            DeskField(free[q.text].orEmpty(),{free[q.text]=it},"自己填写或补充回答",Modifier.fillMaxWidth().border(1.dp,c.border,RoundedCornerShape(12.dp)).padding(horizontal=13.dp,vertical=9.dp),enabled=!disabled,multiline=true,maxLength=4000)
        }}
        val answers=questions.associate{it.text to (choices[it.text].orEmpty()+free[it.text].orEmpty().trim()).filter{v->v.isNotEmpty()}.joinToString(", ")}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){DeskAction("提交回答",!disabled&&questions.isNotEmpty()&&answers.values.all{it.isNotEmpty()&&it.length<=4000},primary=true){reply(true,JSONObject(answers))};DeskAction("不回答",!disabled){reply(false)}}
    }
}
@Composable private fun MonoOutput(text:String){NativeSpanText(text,size=12,lineHeight=20,monospace=true)}
internal fun parseToolOutput(text:String):List<JSONObject>?=runCatching{
    val array=JSONArray(text)
    (0 until array.length()).map{array.getJSONObject(it)}.takeIf{parts->parts.all{p->
        p.optString("type")=="text"&&p.opt("text") is String||p.optString("type")=="image"&&p.optJSONObject("source")?.let{s->s.optString("type")=="base64"&&s.opt("data") is String&&s.optString("media_type") in listOf("image/png","image/jpeg","image/webp","image/gif")}==true
    }}
}.getOrNull()
@Composable private fun ToolOutput(text:String){
    val parts=remember(text){parseToolOutput(text)}
    if(parts==null)MonoOutput(text)else parts.forEach{p->if(p.optString("type")=="text")SelectionContainer{DeskText(p.optString("text"),size=14,lineHeight=20)}else{val bytes=remember(p.toString()){runCatching{Base64.decode(p.getJSONObject("source").getString("data"),Base64.DEFAULT)}.getOrNull()};if(bytes!=null)AndroidView(modifier=Modifier.fillMaxWidth().height(220.dp),factory={ImageView(it).apply{scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="工具返回的图片"}},update={view->if(view.tag!==bytes){(view.drawable as? Animatable)?.stop();val drawable=runCatching{if(Build.VERSION.SDK_INT>=28)ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))else Movie.decodeByteArray(bytes,0,bytes.size)?.let{AnimatedMovieDrawable(it)}?:BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.let{BitmapDrawable(view.resources,it)}}.getOrNull();view.setImageDrawable(drawable);(drawable as? Animatable)?.start();view.tag=bytes}},onRelease={view->(view.drawable as? Animatable)?.stop();view.setImageDrawable(null)})}}
}
@Suppress("DEPRECATION") private class AnimatedMovieDrawable(private val movie:Movie):Drawable(),Animatable {
    private var running=false;private var started=0L;private val tick=Runnable{invalidateSelf()}
    override fun draw(canvas:Canvas){if(running)movie.setTime(((SystemClock.uptimeMillis()-started)%(movie.duration().takeIf{it>0}?:1000)).toInt());val scale=minOf(bounds.width().toFloat()/movie.width(),bounds.height().toFloat()/movie.height());canvas.save();canvas.translate(bounds.left+(bounds.width()-movie.width()*scale)/2,bounds.top+(bounds.height()-movie.height()*scale)/2);canvas.scale(scale,scale);movie.draw(canvas,0f,0f);canvas.restore();if(running)scheduleSelf(tick,SystemClock.uptimeMillis()+16)}
    override fun getIntrinsicWidth()=movie.width()
    override fun getIntrinsicHeight()=movie.height()
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(colorFilter:ColorFilter?){}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    override fun start(){if(!running){running=true;started=SystemClock.uptimeMillis();invalidateSelf()}}
    override fun stop(){running=false;unscheduleSelf(tick)}
    override fun isRunning()=running
}

data class MarkdownBlock(val text:String,val code:Boolean=false,val language:String="")
fun splitMarkdown(source:String):List<MarkdownBlock>{
    val result=mutableListOf<MarkdownBlock>();val text=StringBuilder();val code=StringBuilder();var fence="";var language=""
    source.lines().forEach{line->
        val match=Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(line)
        if(fence.isEmpty()&&match!=null){if(text.isNotEmpty()){result+=MarkdownBlock(text.toString().trimEnd());text.clear()};fence=match.groupValues[1];language=match.groupValues[2].trim().substringBefore(' ')}
        else if(fence.isNotEmpty()&&match!=null&&match.groupValues[1].first()==fence.first()&&match.groupValues[1].length>=fence.length){result+=MarkdownBlock(code.toString().trimEnd('\n'),true,language);code.clear();fence=""}
        else if(fence.isNotEmpty())code.append(line).append('\n') else text.append(line).append('\n')
    }
    if(fence.isNotEmpty())result+=MarkdownBlock(code.toString().trimEnd('\n'),true,language)
    if(text.isNotEmpty())result+=MarkdownBlock(text.toString().trimEnd())
    return result
}
