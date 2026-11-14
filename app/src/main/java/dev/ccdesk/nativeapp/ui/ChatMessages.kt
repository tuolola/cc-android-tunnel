// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
            "thinking"->{var open by remember(row.id){mutableStateOf(false)};val shown=open||current;Column(Modifier.padding(bottom=12.dp)){
                DisclosureHeader(shown,"思考过程",{open=!open}){DeskText("思考过程",size=14,color=c.mutedForeground,weight=FontWeight.Medium);DeskChevron(shown,size=14.dp)}
                DeskExpandable(shown){SelectionContainer{DeskText(row.content,Modifier.padding(top=8.dp),size=14,color=c.mutedForeground,lineHeight=24)}}
            }}
            else->SelectionContainer{DeskText(row.content,Modifier.padding(bottom=12.dp),if(row.kind=="error")14 else 12,if(row.kind=="error")if(c.dark)Color(0xffff6467)else Color(0xffe7000b)else c.mutedForeground,lineHeight=20)}
        }
    }
}
/** Pressable disclosure row with an animated highlight; [content] shows the label and a rotating chevron. */
@Composable private fun DisclosureHeader(expanded:Boolean,label:String,toggle:()->Unit,modifier:Modifier=Modifier,content:@Composable RowScope.()->Unit){
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val highlight=animatedHighlight(pressed,false,c.muted)
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(highlight).clickable(interactionSource=interaction,indication=null,onClick=toggle).semantics{contentDescription=label;role=Role.Button;stateDescription=if(expanded)"已展开"else "已折叠"}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp),content=content)
}
@Composable private fun ToolCard(tool:ToolData,runId:String,active:Boolean,disabled:Boolean,reveal:Boolean,send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current;var expanded by remember(tool.id){mutableStateOf(false)}
    val awaiting=active&&tool.approvalId.isNotEmpty()&&tool.allowed==null&&tool.status.isEmpty()
    val label=if(tool.status.isNotEmpty())mapOf("completed" to "已完成","failed" to "失败","cancelled" to "已停止","unknown" to "状态未知","denied" to "已拒绝")[tool.status]?:tool.status else if(awaiting)if(tool.name=="AskUserQuestion")"等待回答"else "等待审批"else if(tool.allowed==false)"已拒绝"else if(active)"进行中"else "已结束"
    val open=awaiting||expanded||reveal
    val taskControls=tool.taskId.isNotEmpty()&&active&&tool.status.isEmpty()
    Column(Modifier.fillMaxWidth().padding(bottom=12.dp).border(1.dp,c.border,RoundedCornerShape(16.dp)).background(c.card,RoundedCornerShape(16.dp)).padding(horizontal=11.dp,vertical=13.dp)){
        DisclosureHeader(open,tool.name+"详情",{expanded=!expanded},Modifier.fillMaxWidth()){
            DeskText(tool.name,Modifier.weight(1f).padding(start=6.dp),14,weight=FontWeight.SemiBold,lineHeight=20)
            AnimatedContent(label,transitionSpec={fadeIn(DeskMotion.fadeIn())togetherWith fadeOut(DeskMotion.fadeOut())},label="tool status"){DeskText(it,size=12,color=c.mutedForeground)}
            if(tool.elapsed>0)DeskText("· ${tool.elapsed.toString().removeSuffix(".0")}s",size=12,color=c.mutedForeground)
            DeskChevron(open,Modifier.padding(end=6.dp),size=14.dp)
        }
        Column(Modifier.padding(horizontal=6.dp)){
            DeskExpandable(open){Column(Modifier.padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){if(tool.target.isNotEmpty())DeskText(tool.target,size=12,color=c.mutedForeground);if(tool.input.isNotEmpty()&&(!awaiting||tool.name!="AskUserQuestion"||questionsFrom(tool.input).isEmpty()))MonoOutput(tool.input);if(tool.output.isNotEmpty())ToolOutput(tool.output);if(tool.result.isNotEmpty()&&tool.result!=tool.output)ToolOutput(tool.result);if(tool.exitCode!=null)DeskText("退出码 ${tool.exitCode}",size=12,color=c.mutedForeground)}}
            DeskExpandable(awaiting){key(tool.approvalId){Box(Modifier.padding(top=12.dp)){Approval(tool,runId,disabled||!awaiting,send)}}}
            DeskExpandable(taskControls){FlowRow(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){DeskAction("转入后台",!disabled&&taskControls){send("run.task.background",json("runId" to runId,"toolId" to tool.id))};DeskAction("停止任务",!disabled&&taskControls){send("run.task.stop",json("runId" to runId,"taskId" to tool.taskId))}}}
        }
    }
}
data class Question(val text:String,val multi:Boolean,val options:List<Pair<String,String>>)
fun questionsFrom(input:String):List<Question> = runCatching{val a=JSONObject(input).optJSONArray("questions")?:return emptyList();(0 until a.length()).mapNotNull{val q=a.optJSONObject(it)?:return@mapNotNull null;val text=q.optString("question").takeIf{it.isNotBlank()}?:return@mapNotNull null;val options=q.optJSONArray("options");Question(text,q.optBoolean("multiSelect"),(0 until (options?.length()?:0)).mapNotNull{i->val o=options!!.optJSONObject(i);if(o?.opt("label") is String)o.getString("label") to o.optString("description")else null})}}.getOrDefault(emptyList())
@Composable private fun Approval(tool:ToolData,runId:String,disabled:Boolean,send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current;val haptics=rememberHaptics();val questions=remember(tool.input){questionsFrom(tool.input)};val choices=remember{mutableStateMapOf<String,List<String>>()};val free=remember{mutableStateMapOf<String,String>()}
    fun reply(allowed:Boolean,answers:JSONObject?=null){send("approval.reply",json("runId" to runId,"approvalId" to tool.approvalId,"allowed" to allowed).apply{if(answers!=null)put("answers",answers)})}
    if(tool.name!="AskUserQuestion"){FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){DeskAction("允许执行",!disabled,primary=true){haptics.confirm();reply(true)};DeskAction("拒绝",!disabled){reply(false)}};return}
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
        questions.forEach{q->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            DeskText(q.text,weight=FontWeight.SemiBold);if(q.multi)DeskText("可多选",size=12,color=c.mutedForeground)
            q.options.forEach{(label,description)->val selected=label in choices[q.text].orEmpty()
                QuestionOption(label,description,selected,q.multi,!disabled){val current=choices[q.text].orEmpty();choices[q.text]=if(selected)current-label else if(q.multi)current+label else listOf(label);haptics.selection()}
            }
            DeskField(free[q.text].orEmpty(),{free[q.text]=it},"自己填写或补充回答",Modifier.fillMaxWidth().border(1.dp,c.border,RoundedCornerShape(12.dp)).padding(horizontal=13.dp,vertical=9.dp),enabled=!disabled,multiline=true,maxLength=4000)
        }}
        val answers=questions.associate{it.text to (choices[it.text].orEmpty()+free[it.text].orEmpty().trim()).filter{v->v.isNotEmpty()}.joinToString(", ")}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){DeskAction("提交回答",!disabled&&questions.isNotEmpty()&&answers.values.all{it.isNotEmpty()&&it.length<=4000},primary=true){haptics.confirm();reply(true,JSONObject(answers))};DeskAction("不回答",!disabled){reply(false)}}
    }
}
/** Selectable answer: border, fill and check mark transition continuously with the selection. */
@Composable private fun QuestionOption(label:String,description:String,selected:Boolean,multi:Boolean,enabled:Boolean,toggle:()->Unit){
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val border by animateColorAsState(if(selected)c.foreground else c.border,DeskMotion.state(),label="option border")
    val fill=animatedHighlight(pressed&&enabled,selected,c.muted,c.muted.copy(alpha=.6f))
    val mark by animateFloatAsState(if(selected)1f else 0f,DeskMotion.icon(),label="option check")
    val alpha=animatedEnabledAlpha(enabled,.6f)
    Column(Modifier.fillMaxWidth().pressScale(pressed,enabled,DeskMotion.CardPressScale).graphicsLayer{this.alpha=alpha}.clip(RoundedCornerShape(12.dp)).background(fill).border(1.dp,border,RoundedCornerShape(12.dp)).toggleable(value=selected,interactionSource=interaction,indication=null,enabled=enabled,role=if(multi)Role.Checkbox else Role.RadioButton,onValueChange={toggle()}).padding(13.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.width(22.dp*mark.coerceIn(0f,1f)),contentAlignment=Alignment.CenterStart){if(mark>.01f)DeskIcon("check",Modifier.graphicsLayer{scaleX=mark;scaleY=mark;this.alpha=mark.coerceIn(0f,1f)},size=15.dp,strokeWidth=2.6f)}
            DeskText(label,size=14,lineHeight=20)
        }
        if(description.isNotEmpty())DeskText(description,Modifier.padding(top=4.dp),12,c.mutedForeground)
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
