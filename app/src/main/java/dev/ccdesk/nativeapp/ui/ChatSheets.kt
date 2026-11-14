// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ccdesk.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import dev.ccdesk.nativeapp.R
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

@Composable fun DeskSheet(title:String,close:()->Unit,content:@Composable ColumnScope.()->Unit){
    val c=LocalDeskColors.current
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        NativeSheetWindow()
        BoxWithConstraints(Modifier.nativeDialogSize().background(Color.Black.copy(alpha=.35f)).imePadding()){
            Box(Modifier.fillMaxSize().clickable(interactionSource=remember{MutableInteractionSource()},indication=null,onClick=close).semantics{contentDescription="关闭面板";role=Role.Button})
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max=720.dp).fillMaxWidth().heightIn(max=maxHeight*.88f).background(c.card,RoundedCornerShape(topStart=24.dp,topEnd=24.dp)).navigationBarsPadding()){
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically){SNativeText(title,Modifier.weight(1f).semantics{heading()},size=18,lineHeight=28,weight=FontWeight.SemiBold,heading=true);DeskIconButton("x","关闭",onClick=close)}
                NativeScrollContent(Modifier.fillMaxWidth(),PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=20.dp),16.dp,content)
            }
        }
    }
}
val effortNames=linkedMapOf("low" to "低","medium" to "中","high" to "高","xhigh" to "很高","max" to "最大")
private val aliases=listOf(ModelInfo("opus","Opus",description="适合复杂推理与深入分析"),ModelInfo("sonnet","Sonnet",description="兼顾推理能力与响应速度"),ModelInfo("haiku","Haiku",description="适合快速回答与简短任务"))

@Composable fun ModelSheet(model:String?,effort:String?,catalog:Catalog?,defaultModel:String?,nativeModel:String,disabled:Boolean,close:()->Unit,onSave:suspend(String?,String?)->Unit,onLoad:(suspend()->Unit)?,createsSession:Boolean,autoReadAllowed:Boolean=true,onAutoRead:()->Unit={}){
    val c=LocalDeskColors.current
    val scope=rememberCoroutineScope()
    val keyboard=LocalSoftwareKeyboardController.current
    val view=LocalView.current
    var page by remember{mutableStateOf("models")}
    var custom by remember{mutableStateOf(model.orEmpty())}
    var error by remember{mutableStateOf("")}
    var saving by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(false)}
    val models=catalog?.models.orEmpty()
    val shown=if(models.isEmpty())aliases else models.filter{it.value.isNotEmpty()&&it.value!="default"}.distinctBy{it.value}
    val chosen=modelForChoice(models,model,defaultModel,nativeModel)
    val default=modelForChoice(models,null,defaultModel,nativeModel)
    val levels=if(chosen!=null)effortNames.keys.filter{it in chosen.supportedEffortLevels}else effortNames.keys.toList()
    val locked=disabled||saving
    fun dismiss(){if(!saving){keyboard?.hide();close()}}
    fun back(){if(!saving)if(page=="custom"){keyboard?.hide();page="more";error=""}else if(page=="more"){page="models";error=""}else dismiss()}
    fun refresh(){
        if(onLoad==null||disabled||saving||loading)return
        loading=true;error=""
        scope.launch{try{onLoad()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=e.message?:"操作失败，请重试。"}finally{loading=false}}
    }
    fun save(m:String?,e:String?){
        if(disabled||saving)return
        if(m==model&&e==effort){dismiss();return}
        saving=true;error=""
        scope.launch{try{onSave(m,e);keyboard?.hide();close();view.announceForAccessibility("已更新会话模型设置")}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(f:Exception){error=f.message?:"操作失败，请重试。"}finally{saving=false}}
    }
    fun select(m:String?){val next=modelForChoice(models,m,defaultModel,nativeModel);save(m,if(effort!=null&&next!=null&&effort !in next.supportedEffortLevels)null else effort)}
    LaunchedEffect(Unit){
        val recent=runCatching{java.time.Duration.between(Instant.parse(catalog?.at),Instant.now()).toMillis()<300_000}.getOrDefault(false)
        if(onLoad!=null&&!disabled&&!createsSession&&!recent&&autoReadAllowed){onAutoRead();refresh()}
    }
    val readLabel=if(loading)"正在读取模型…"else if(createsSession)"新建会话并读取模型"else if(models.isNotEmpty())"刷新服务端模型"else "读取服务端模型"
    val readDetail=if(createsSession)"将在工作台创建空会话，不发送消息。"else ""
    Dialog(onDismissRequest={back()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        NativeSheetWindow()
        BoxWithConstraints(Modifier.nativeDialogSize().background(Color.Black.copy(alpha=.38f)).imePadding()){
            Box(Modifier.fillMaxSize().clickable(interactionSource=remember{MutableInteractionSource()},indication=null){dismiss()})
            val bottomPadding=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(12.dp)
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max=640.dp).fillMaxWidth().height(maxHeight*.85f).background(c.card,RoundedCornerShape(topStart=12.dp,topEnd=12.dp)).padding(bottom=bottomPadding)){
                val density=LocalDensity.current
                Box(Modifier.fillMaxWidth().height(19.dp).pointerInput(saving){awaitEachGesture{
                    val down=awaitFirstDown(requireUnconsumed=false);val velocity=LastMoveVelocity(down.uptimeMillis,down.position);var accepted=false
                    while(true){val event=awaitPointerEvent();val change=event.changes.firstOrNull{it.id==down.id}?:break;if(!change.pressed&&change.isConsumed)break;velocity.add(change.uptimeMillis,change.position,change.pressed);val dy=change.position.y-down.position.y;val dx=change.position.x-down.position.x
                        if(dy>with(density){7.dp.toPx()}&&dy>kotlin.math.abs(dx))accepted=true
                        if(accepted)change.consume()
                        if(!change.pressed){if(accepted&&(dy>with(density){44.dp.toPx()}||(dy>with(density){12.dp.toPx()}&&velocity.velocity.y>with(density){700.dp.toPx()})))dismiss();break}
                    }
                }},contentAlignment=Alignment.Center){Box(Modifier.width(36.dp).height(4.dp).background(c.mutedForeground.copy(alpha=.34f),RoundedCornerShape(4.dp)))}
                Row(Modifier.fillMaxWidth().heightIn(min=34.dp).padding(start=20.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    if(page!="models")ModelHeaderButton("arrow-left","返回模型菜单",40,20,true,hitSlop=3){back()}
                    SNativeText(when(page){"custom"->"自定义模型";"more"->"更多模型";else->"模型"},Modifier.weight(1f).semantics{heading()},size=13,color=c.mutedForeground,weight=FontWeight.SemiBold,heading=true)
                    ModelHeaderButton("x","关闭模型面板",32,16,!saving,c.mutedForeground,hitSlop=5){dismiss()}
                }
                key(page){NativeScrollContent(Modifier.weight(1f).fillMaxWidth(),PaddingValues(start=8.dp,end=8.dp,top=2.dp,bottom=10.dp),0.dp){
                    if(page=="models"){
                        ModelRow("使用服务端默认模型",listOf(modelName(default).ifEmpty{defaultModel?:nativeModel},default?.resolvedModel.orEmpty()).filter{it.isNotEmpty()}.distinct().joinToString(" · "),model==null,locked){select(null)}
                        if(models.isEmpty())SNativeText("通用模型别名",Modifier.padding(horizontal=12.dp).padding(top=8.dp),size=13,color=c.mutedForeground)
                    }
                    if(page!="custom"){
                        shown.forEach{row->ModelRow(modelName(row),listOf(row.resolvedModel?.takeIf{it.isNotEmpty()}?:row.value.takeIf{models.isNotEmpty()&&it!=row.displayName}.orEmpty(),row.description.orEmpty()).filter{it.isNotEmpty()}.joinToString(" · "),model!=null&&(chosen?.value==row.value||model==row.value),locked){select(row.value)}}
                        if(page=="models"&&onLoad!=null)ModelRow(readLabel,readDetail,disabled=locked||loading,compact=true,icon="rotate-cw"){refresh()}
                        if(page=="models"){
                            if(model!=null&&chosen==null&&modelForChoice(shown,model)==null)ModelRow(model,"当前自定义模型",true,locked){select(model)}
                            ModelDivider()
                            SNativeText("思考强度",Modifier.padding(start=12.dp,end=12.dp,top=8.dp,bottom=4.dp).semantics{heading()},size=13,color=c.mutedForeground,weight=FontWeight.SemiBold,heading=true)
                            ModelRow("默认",checked=effort==null,disabled=locked,compact=true){save(model,null)}
                            levels.forEach{level->ModelRow(effortNames.getValue(level),checked=effort==level,disabled=locked,compact=true){save(model,level)}}
                            if(levels.isEmpty())SNativeText("此模型未提供可选的思考强度。",Modifier.padding(horizontal=12.dp,vertical=8.dp),size=13,color=c.mutedForeground,lineHeight=18)
                            ModelDivider();ModelRow("更多模型",compact=true,trailing=true){page="more";error=""}
                        }else{
                            if(shown.isNotEmpty())ModelDivider()
                            ModelRow("自定义模型标识",compact=true,icon="pencil",trailing=true){custom=model.orEmpty();page="custom";error=""}
                            if(onLoad!=null)ModelRow(readLabel,readDetail,disabled=locked||loading,compact=true,icon="rotate-cw"){refresh()}
                        }
                    }else Column(Modifier.padding(horizontal=12.dp).padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        ModelCustomField(custom,{custom=it},!locked){if(custom.isNotBlank())select(custom.trim())}
                        val enabled=!locked&&custom.isNotBlank()
                        Box(Modifier.fillMaxWidth().heightIn(min=44.dp).graphicsLayer{alpha=if(enabled)1f else .45f;compositingStrategy=CompositingStrategy.ModulateAlpha}.clip(RoundedCornerShape(12.dp)).background(c.foreground).clickable(enabled=enabled,interactionSource=remember{MutableInteractionSource()},indication=null){select(custom.trim())}.semantics{role=Role.Button;contentDescription="使用此模型"},contentAlignment=Alignment.Center){
                            NativeSpanText("使用此模型",size=14,lineHeight=null,color=c.background,weight=600,selectable=false,exposeText=false)
                        }
                    }
                }}
                if(error.isNotEmpty()||saving)Box(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=8.dp),contentAlignment=Alignment.Center){
                    if(error.isNotEmpty())SNativeText(error,Modifier.fillMaxWidth().semantics{liveRegion=LiveRegionMode.Assertive},size=14,lineHeight=20,color=if(c.dark)Color(0xffff6467)else Color(0xffe7000b),selectable=true)
                    else Box(Modifier.semantics{contentDescription="正在保存模型设置"}){SBusy()}
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun ModelHeaderButton(icon:String,label:String,extent:Int,iconSize:Int,enabled:Boolean,tint:Color=LocalDeskColors.current.foreground,hitSlop:Int,onClick:()->Unit){
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val inherited=LocalViewConfiguration.current
    val touchConfiguration=remember(inherited,extent,hitSlop){object:ViewConfiguration by inherited{
        override val minimumTouchTargetSize=DpSize((extent+2*hitSlop).dp,(extent+2*hitSlop).dp)
    }}
    CompositionLocalProvider(LocalViewConfiguration provides touchConfiguration){
        Box(Modifier.size(extent.dp).clip(RoundedCornerShape(100.dp)).background(if(pressed)LocalDeskColors.current.muted else Color.Transparent).clickable(enabled=enabled,interactionSource=interaction,indication=null,onClick=onClick).semantics{contentDescription=label;role=Role.Button},contentAlignment=Alignment.Center){DeskIcon(icon,size=iconSize.dp,tint=tint)}
    }
}

@Composable private fun ModelCustomField(value:String,onChange:(String)->Unit,enabled:Boolean,onSubmit:()->Unit){
    val c=LocalDeskColors.current
    val cursorColor=nativeInputCursorColor()
    val context=LocalContext.current
    var focused by remember{mutableStateOf(false)}
    val hintTint=remember(context,c.dark){
        val attributes=context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint))
        try{attributes.getColorStateList(0)?:androidx.appcompat.widget.AppCompatEditText(context).hintTextColors}finally{attributes.recycle()}
    }
    val hintState=buildList{if(enabled)add(android.R.attr.state_enabled);if(focused)add(android.R.attr.state_focused)}.toIntArray()
    val hintColor=Color(hintTint.getColorForState(hintState,hintTint.defaultColor))
    BasicTextField(value,{onChange(it.take(200))},Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp,c.border,RoundedCornerShape(12.dp)).background(c.background).padding(1.dp).padding(12.dp).onFocusChanged{focused=it.isFocused}.semantics{contentDescription="模型标识"},enabled=enabled,singleLine=true,
        textStyle=TextStyle(color=c.foreground,fontSize=16.sp,lineHeight=24.sp,platformStyle=PlatformTextStyle(includeFontPadding=true)),cursorBrush=SolidColor(cursorColor),
        keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={onSubmit()}),
        decorationBox={inner->Box{if(value.isEmpty())SNativeText("输入服务端模型标识",size=16,lineHeight=24,color=hintColor);inner()}})
}
@Composable private fun NativeSheetWindow(animation:Int=R.style.NativeSheetWindowAnimation){val view=LocalView.current;val dark=LocalDeskColors.current.dark;SideEffect{(view.parent as? DialogWindowProvider)?.window?.apply{setDimAmount(0f);clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this,false);setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,android.view.WindowManager.LayoutParams.MATCH_PARENT);statusBarColor=android.graphics.Color.TRANSPARENT;navigationBarColor=android.graphics.Color.TRANSPARENT;androidx.core.view.WindowCompat.getInsetsController(this,decorView).isAppearanceLightStatusBars=!dark;setWindowAnimations(animation)}}}
@Composable private fun Modifier.nativeDialogSize():Modifier {
    val context=LocalContext.current;val density=LocalDensity.current
    val manager=context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    val bounds=if(android.os.Build.VERSION.SDK_INT>=30)manager.currentWindowMetrics.bounds else android.graphics.Rect(0,0,context.resources.displayMetrics.widthPixels,context.resources.displayMetrics.heightPixels)
    return requiredSize(with(density){bounds.width().toDp()},with(density){bounds.height().toDp()})
}

@Composable fun WorkspaceMenuSheet(host:String,online:Boolean,close:()->Unit,content:@Composable ColumnScope.()->Unit){
    val c=LocalDeskColors.current;val wide=LocalConfiguration.current.screenWidthDp>=640;val progress=remember{Animatable(0f)};val offset=with(LocalDensity.current){25.dp.toPx()}
    LaunchedEffect(Unit){progress.animateTo(1f,tween(180,easing=Easing{if(it<.5f)2*it*it else 1-(-2*it+2)*(-2*it+2)/2}))}
    Dialog(close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        NativeSheetWindow(R.style.NativeMenuWindowAnimation)
        Box(Modifier.nativeDialogSize().background(Color.Black.copy(alpha=.55f))){
            Box(Modifier.fillMaxSize().clickable(onClick=close))
            val bottom=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(12.dp)
            Column(Modifier.align(if(wide)Alignment.Center else Alignment.BottomCenter).widthIn(max=if(wide)448.dp else androidx.compose.ui.unit.Dp.Infinity).fillMaxWidth().graphicsLayer{translationY=offset*(1-progress.value);alpha=progress.value}.border(1.dp,c.border,RoundedCornerShape(topStart=16.dp,topEnd=16.dp,bottomStart=if(wide)16.dp else 0.dp,bottomEnd=if(wide)16.dp else 0.dp)).background(c.card,RoundedCornerShape(topStart=16.dp,topEnd=16.dp,bottomStart=if(wide)16.dp else 0.dp,bottomEnd=if(wide)16.dp else 0.dp)).padding(top=8.dp,bottom=bottom)){
                Box(Modifier.padding(top=4.dp,bottom=12.dp).width(36.dp).height(4.dp).align(Alignment.CenterHorizontally).background(c.border,RoundedCornerShape(4.dp)))
                Column(Modifier.padding(horizontal=20.dp).padding(bottom=8.dp)){DeskText(host,size=16,weight=FontWeight.Medium,maxLines=1);DeskText(if(online)"已连接"else "尚未连接",size=13,color=c.mutedForeground,lineHeight=20)}
                Box(Modifier.padding(horizontal=16.dp,vertical=4.dp).fillMaxWidth().height(1.dp).background(c.border))
                content()
            }
        }
    }
}
@Composable private fun ModelDivider(){Box(Modifier.padding(horizontal=12.dp,vertical=8.dp).fillMaxWidth().height(1.dp).background(LocalDeskColors.current.border))}
@Composable private fun ModelRow(label:String,detail:String="",checked:Boolean?=null,disabled:Boolean=false,compact:Boolean=false,icon:String?=null,trailing:Boolean=false,onClick:()->Unit){val c=LocalDeskColors.current;val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState();Row(Modifier.fillMaxWidth().heightIn(min=if(compact)44.dp else 54.dp).alpha(if(disabled).5f else 1f).background(if(pressed)c.muted else Color.Transparent,RoundedCornerShape(12.dp)).clickable(enabled=!disabled,interactionSource=interaction,indication=null,onClick=onClick).semantics(mergeDescendants=true){text=AnnotatedString(label);role=if(checked==null)Role.Button else Role.RadioButton;if(checked!=null)selected=checked;if(detail.isNotEmpty())contentDescription=label+"，"+detail}.padding(horizontal=12.dp,vertical=if(compact)6.dp else 7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){if(icon!=null)DeskIcon(icon,size=18.dp);Column(Modifier.weight(1f)){NativeSpanText(label,size=16,weight=500,lineHeight=21,selectable=false,exposeText=false);if(detail.isNotEmpty())NativeSpanText(detail,Modifier.padding(top=2.dp),size=13,color=c.mutedForeground,lineHeight=17,maxLines=2,selectable=false,exposeText=false)};if(checked==true)DeskIcon("check",size=19.dp,tint=Color(0xff5388c9),strokeWidth=2.4f)else if(trailing)DeskIcon("chevron-right",size=16.dp,tint=c.mutedForeground)}}

@Composable fun SessionOptionsSheet(session:Session,info:TranscriptProjection,disabled:Boolean,busy:Boolean,error:String,close:()->Unit,find:()->Unit,send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current;var permission by remember(session.permissionMode){mutableStateOf(session.permissionMode)};val blocked=disabled||busy;val draft=session.id.isEmpty()
    val permissions=linkedMapOf("auto" to "自动","default" to "标准审批","plan" to "计划模式","acceptEdits" to "自动接受编辑","bypassPermissions" to "跳过审批")
    DeskSheet("会话选项",close){
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){SNativeText(if(draft)"服务端"else session.device?.name?.takeIf{it.isNotEmpty()}?:"所属设备未知",weight=FontWeight.SemiBold);SNativeText(session.projectPath,size=14,lineHeight=20,color=c.mutedForeground,selectable=true)}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){DeskAction("查找对话内容",!draft,onClick=find);DeskAction("刷新会话状态",!blocked&&!draft){send("session.status",json("quota" to true))}}
        SNativeText("权限模式",weight=FontWeight.SemiBold)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){permissions.forEach{(key,label)->DeskAction(label,!blocked,selected=permission==key){permission=key}}}
        if(permission=="bypassPermissions")SNativeText("此模式允许 Claude 跳过工具执行前的审批。",size=14,lineHeight=20,color=c.mutedForeground)
        DeskAction("保存会话配置",!blocked&&permission!=session.permissionMode&&session.activeRun==null,primary=true,modifier=Modifier.fillMaxWidth()){send("session.configure",json("permissionMode" to permission))}
        SNativeText("上下文与用量",weight=FontWeight.SemiBold)
        SNativeText(info.context?.let{"${"%,d".format(it.optLong("usedTokens"))} / ${"%,d".format(it.optLong("windowTokens"))} tokens · ${"%.1f".format(it.optDouble("percentage"))}%"}?:"尚无上下文统计",size=14,lineHeight=20,color=c.mutedForeground)
        info.usage?.let{SNativeText("输入 ${"%,d".format(it.optLong("inputTokens"))} · 输出 ${"%,d".format(it.optLong("outputTokens"))} tokens"+if(it.isNull("costUsd"))""else " · 估算 $${"%.4f".format(it.optDouble("costUsd"))}",size=14,lineHeight=20,color=c.mutedForeground)}
        DeskAction("压缩上下文",!blocked&&!draft&&session.activeRun==null,modifier=Modifier.fillMaxWidth()){send("session.compact",JSONObject())}
        if(info.tasks.isNotEmpty())SNativeText("后台任务",weight=FontWeight.SemiBold)
        info.tasks.forEach{task->Column(Modifier.fillMaxWidth().background(c.muted,RoundedCornerShape(12.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){SNativeText(task.description.ifEmpty{task.kind},size=14,lineHeight=20);if(task.progress.isNotEmpty())SNativeText(task.progress,size=12,lineHeight=16,color=c.mutedForeground);DeskAction("停止任务",!blocked&&session.activeRun!=null,modifier=Modifier.fillMaxWidth()){send("run.task.stop",json("runId" to session.activeRun?.id,"taskId" to task.id))}}}
        if(error.isNotEmpty())SNativeText(error,size=14,lineHeight=20,color=if(c.dark)Color(0xffff6467)else Color(0xffe7000b),live=true)
    }
}
