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
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import kotlinx.coroutines.delay

private val deliveryLabels=mapOf("queued" to "已排队","submitted" to "已提交","received" to "已送达","not_sent" to "未发送","unconfirmed" to "送达状态未知")
@Composable fun ChatTranscriptRow(row:ChatRow,active:Boolean,disabled:Boolean,current:Boolean,modifier:Modifier=Modifier,onHeightChange:()->Unit={},showActions:Boolean=false,actionText:String=row.content,send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current
    Column((if(current)modifier.fillMaxWidth().border(1.dp,c.foreground,RoundedCornerShape(12.dp)).background(c.muted,RoundedCornerShape(12.dp)).padding(9.dp)else modifier.fillMaxWidth()).onSizeChanged{onHeightChange()}){
        when(row.kind){
            "user"->{BoxWithConstraints(Modifier.fillMaxWidth().padding(top=12.dp,bottom=20.dp)){val limit=maxWidth*.85f;Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){Box(Modifier.widthIn(max=limit).background(c.userBubble,RoundedCornerShape(24.dp)).padding(horizontal=16.dp,vertical=12.dp)){SelectionContainer{DeskText(row.content,size=16,lineHeight=24)}}}};if(row.delivery.isNotEmpty())DeskText(deliveryLabels[row.delivery]?:row.delivery,Modifier.align(Alignment.End).padding(bottom=8.dp),12,c.mutedForeground)}
            "reply"->{
                NativeMarkdown(row.content.ifEmpty{"..."},Modifier.fillMaxWidth())
                if(showActions&&actionText.isNotBlank())ReplyActions(actionText)
                else Spacer(Modifier.height(8.dp))
            }
            "tool"->row.tool?.let{ToolCard(it,row.runId,active,disabled,current,send)}
            "thinking"->{var open by remember(row.id){mutableStateOf(false)};val shown=open||current;Column(Modifier.padding(bottom=12.dp)){
                DisclosureHeader(shown,"思考过程",{open=!open}){DeskText("思考过程",size=14,color=c.mutedForeground,weight=FontWeight.Medium);DeskChevron(shown,size=14.dp)}
                DeskExpandable(shown){SelectionContainer{DeskText(row.content,Modifier.padding(top=8.dp),size=14,color=c.mutedForeground,lineHeight=24)}}
            }}
            else->SelectionContainer{DeskText(row.content,Modifier.padding(bottom=12.dp),if(row.kind=="error")14 else 12,if(row.kind=="error")if(c.dark)Color(0xffff6467)else Color(0xffe7000b)else c.mutedForeground,lineHeight=20)}
        }
    }
}

/** Only supported native actions are shown; copies and shares contain the full finished reply. */
@Composable private fun ReplyActions(text:String){
    val c=LocalDeskColors.current
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val view=LocalView.current
    val keyboard=LocalSoftwareKeyboardController.current
    var menu by remember{mutableStateOf(false)}
    var select by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    fun share(){
        try{
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"分享回复"))
            error=""
        }catch(_:Exception){error="没有可用的分享应用。"}
    }
    Row(Modifier.offset(x=(-8).dp).padding(top=8.dp,bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(0.dp),verticalAlignment=Alignment.CenterVertically){
        MessageCopyAction(text)
        MessageActionButton("share-2","分享回复"){share()}
        // The popup's parent is exactly the 36 dp button, so it follows that button when
        // the transcript scrolls, the IME changes the viewport, or the window is resized.
        Box{
            MessageActionButton("ellipsis-vertical","更多回复操作"){keyboard?.hide();menu=true}
            ReplyActionsPopup(menu,{menu=false}){
                SheetRow("copy","复制回复"){
                    clipboard.setText(AnnotatedString(text));view.announceForAccessibility("已复制");menu=false
                }
                SheetRow("text-select","选择文本"){menu=false;select=true}
                SheetRow("share-2","分享回复"){menu=false;share()}
            }
        }
    }
    if(error.isNotEmpty())DeskText(error,Modifier.padding(bottom=12.dp),13,c.mutedForeground)
    DeskSheetOverlay(select,{select=false},heightFraction=.75f,scrimLabel="关闭文本选择"){sheet->
        SheetHeader("选择文本",sheet)
        NativeScrollContent(Modifier.weight(1f).fillMaxWidth(),PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=24.dp),0.dp){
            NativeSpanText(text,size=16,lineHeight=24)
        }
    }
}

/** Positions a reply menu beside its invoking button, within the usable window. */
internal class ReplyMenuPositionProvider(
    private val leftInset:Int,
    private val topInset:Int,
    private val rightInset:Int,
    private val bottomInset:Int,
    private val margin:Int,
    private val gap:Int,
    private val shadowInset:Int=0,
    private val positioned:(IntRect,IntRect)->Unit={_,_->},
):PopupPositionProvider {
    override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset {
        // Position the visible menu, not its transparent window padding. Keeping
        // this inset out of the anchor math also preserves its transform origin.
        val panelSize=IntSize((popupContentSize.width-2*shadowInset).coerceAtLeast(0),(popupContentSize.height-2*shadowInset).coerceAtLeast(0))
        val left=leftInset+margin
        val top=topInset+margin
        val right=windowSize.width-rightInset-margin
        val bottom=windowSize.height-bottomInset-margin
        val lastX=maxOf(left,right-panelSize.width)
        val lastY=maxOf(top,bottom-panelSize.height)
        val x=(anchorBounds.center.x-panelSize.width/2).coerceIn(left,lastX)
        val below=anchorBounds.bottom+gap
        val above=anchorBounds.top-panelSize.height-gap
        val y=when {
            below+panelSize.height<=bottom->below
            above>=top->above
            bottom-anchorBounds.bottom>=anchorBounds.top-top->below
            else->above
        }.coerceIn(top,lastY)
        val result=IntOffset(x,y)
        positioned(anchorBounds,IntRect(result,panelSize))
        return result-IntOffset(shadowInset,shadowInset)
    }
}

@Composable private fun ReplyActionsPopup(visible:Boolean,close:()->Unit,content:@Composable ColumnScope.()->Unit){
    val transition=remember{MutableTransitionState(false)}.apply{targetState=visible}
    if(!transition.currentState&&!transition.targetState&&transition.isIdle)return
    val density=LocalDensity.current
    val configuration=LocalConfiguration.current
    val direction=LocalLayoutDirection.current
    val insets=WindowInsets.safeDrawing
    val left=insets.getLeft(density,direction)
    val top=insets.getTop(density)
    val right=insets.getRight(density,direction)
    val bottom=insets.getBottom(density)
    val margin=with(density){12.dp.roundToPx()}
    val gap=with(density){8.dp.roundToPx()}
    // A separate Popup window clips drawing at its own bounds even when its
    // Compose layers do not clip. Reserve room for the 24 dp elevation's halo.
    val shadowPadding=48.dp
    val shadowInset=with(density){shadowPadding.roundToPx()}
    var origin by remember{mutableStateOf(TransformOrigin.Center)}
    val position=remember(left,top,right,bottom,margin,gap,shadowInset){
        ReplyMenuPositionProvider(left,top,right,bottom,margin,gap,shadowInset){anchor,popup->
            val x=if(popup.width>0)(anchor.center.x-popup.left).toFloat()/popup.width else .5f
            val y=when{popup.top>=anchor.bottom->0f;popup.bottom<=anchor.top->1f;else->.5f}
            origin=TransformOrigin(x.coerceIn(0f,1f),y)
        }
    }
    val maximumWidth=(configuration.screenWidthDp.dp-with(density){(left+right).toDp()}-24.dp).coerceAtLeast(48.dp)
    val maximumHeight=(configuration.screenHeightDp.dp-with(density){(top+bottom).toDp()}-24.dp).coerceAtLeast(54.dp)
    // The provider constrains the panel to the safe viewport. Let its transparent
    // shadow padding extend past that viewport without Android moving the menu.
    Popup(popupPositionProvider=position,onDismissRequest=close,properties=PopupProperties(focusable=visible,dismissOnBackPress=true,dismissOnClickOutside=true,clippingEnabled=false)){
        Box(Modifier.pointerInput(visible,shadowInset){
            awaitPointerEventScope {
                while(true)awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change->
                    val outside=change.position.x<shadowInset||change.position.x>=size.width-shadowInset||
                        change.position.y<shadowInset||change.position.y>=size.height-shadowInset
                    if(!visible||outside){
                        if(visible&&change.pressed&&!change.previousPressed)close()
                        change.consume()
                    }
                }
            }
        }.padding(shadowPadding)){
            DeskFloatingVisibility(transition,initialScale=.92f,targetScale=.96f,transformOrigin=origin){
                val shape=RoundedCornerShape(24.dp)
                Column(Modifier.width(minOf(216.dp,maximumWidth)).heightIn(max=maximumHeight).deskFloatingSurface(shape,24.dp).clip(shape)
                    .testTag("reply-actions-popup").then(if(visible)AbsorbPointerInput else BlockPointerInput.clearAndSetSemantics{})
                    .verticalScroll(rememberScrollState()).padding(vertical=8.dp),content=content)
            }
        }
    }
}

@Composable internal fun MessageCopyAction(text:String,label:String="复制回复"){
    val clipboard=LocalClipboardManager.current
    val view=LocalView.current
    var copied by remember(text){mutableStateOf(false)}
    LaunchedEffect(copied){if(copied){delay(1600);copied=false}}
    MessageActionButton(if(copied)"check"else "copy",if(copied)"已复制"else label){
        clipboard.setText(AnnotatedString(text));copied=true;view.announceForAccessibility("已复制")
    }
}

/** 36 dp visual spacing matches the reference while Compose preserves the minimum touch target. */
@Composable internal fun MessageActionButton(icon:String,label:String,onClick:()->Unit){
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    Box(Modifier.size(36.dp).pressScale(pressed,true,DeskMotion.IconPressScale).clip(RoundedCornerShape(18.dp)).background(animatedHighlight(pressed,false,c.muted)).clickable(interactionSource=interaction,indication=null,onClick=onClick).semantics{contentDescription=label;role=Role.Button},contentAlignment=Alignment.Center){
        DeskIcon(icon,size=20.dp,tint=c.mutedForeground,strokeWidth=1.8f)
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
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp).border(1.dp,c.border,RoundedCornerShape(24.dp)).background(c.card,RoundedCornerShape(24.dp)).padding(16.dp)){
        DisclosureHeader(open,tool.name+"详情",{expanded=!expanded},Modifier.fillMaxWidth()){
            DeskText(tool.name,Modifier.weight(1f),15,weight=FontWeight.Medium,lineHeight=22)
            AnimatedContent(label,transitionSpec={fadeIn(DeskMotion.fadeIn())togetherWith fadeOut(DeskMotion.fadeOut())},label="tool status"){DeskText(it,size=12,color=c.mutedForeground)}
            if(tool.elapsed>0)DeskText("· ${tool.elapsed.toString().removeSuffix(".0")}s",size=12,color=c.mutedForeground)
            DeskChevron(open,size=16.dp)
        }
        Column{
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
