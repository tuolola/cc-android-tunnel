// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ccdesk.nativeapp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.launch
import org.json.JSONObject

val effortNames=linkedMapOf("low" to "低","medium" to "中","high" to "高","xhigh" to "很高","max" to "最大")
internal val permissionNames:Map<String,String> = linkedMapOf("auto" to "自动","default" to "标准审批","plan" to "计划模式","acceptEdits" to "自动接受编辑","bypassPermissions" to "跳过审批")
private val modelPages=mapOf("models" to 0,"more" to 1,"custom" to 2)

/**
 * Model and reasoning-effort picker. Pages slide in the direction of navigation; system back
 * returns to the previous page before closing. While a save is pending the sheet cannot be
 * dismissed by back, scrim, drag or its close button.
 */
@Composable fun ModelSheet(model:String?,effort:String?,catalog:Catalog?,defaultModel:String?,nativeModel:String,disabled:Boolean,close:()->Unit,onSave:suspend(String?,String?)->Unit,onLoad:(suspend()->Unit)?,createsSession:Boolean,visible:Boolean=true,lockedReason:String="",catalogLoading:Boolean=false,catalogError:String?=null){
    val c=LocalDeskColors.current
    val scope=rememberCoroutineScope()
    val keyboard=LocalSoftwareKeyboardController.current
    val view=LocalView.current
    val haptics=rememberHaptics()
    var page by remember{mutableStateOf("models")}
    var custom by remember{mutableStateOf(model.orEmpty())}
    var error by remember{mutableStateOf("")}
    var saving by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(false)}
    val models=catalog?.models.orEmpty()
    val shown=models.filter{it.value.isNotEmpty()&&it.value!="default"}.distinctBy{it.value}
    val chosen=modelForChoice(models,model,defaultModel,nativeModel)
    val default=modelForChoice(models,null,defaultModel,nativeModel)
    val levels=effortNames.keys.filter{it in chosen?.supportedEffortLevels.orEmpty()}
    val reading=loading||catalogLoading
    val locked=disabled||saving
    fun dismiss(){if(!saving){keyboard?.hide();close()}}
    fun back(){if(!saving)if(page=="custom"){keyboard?.hide();page="more";error=""}else if(page=="more"){page="models";error=""}else dismiss()}
    fun refresh(){
        if(onLoad==null||disabled||saving||reading)return
        loading=true;error=""
        scope.launch{try{onLoad()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=e.message?:"操作失败，请重试。"}finally{loading=false}}
    }
    fun save(m:String?,e:String?){
        if(disabled||saving)return
        if(m==model&&e==effort){dismiss();return}
        saving=true;error=""
        scope.launch{try{onSave(m,e);haptics.confirm();keyboard?.hide();close();view.announceForAccessibility("已更新会话模型设置")}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(f:Exception){error=f.message?:"操作失败，请重试。"}finally{saving=false}}
    }
    fun select(m:String?){val next=modelForChoice(models,m,defaultModel,nativeModel);save(m,if(effort!=null&&next!=null&&effort !in next.supportedEffortLevels)null else effort)}
    LaunchedEffect(visible){
        if(!visible)return@LaunchedEffect
        page="models";error="";custom=model.orEmpty()
    }
    val readLabel=if(reading)"正在读取模型…"else if(models.isNotEmpty())"刷新服务端模型"else "重试读取模型"
    DeskSheetOverlay(visible,{dismiss()},dismissible=!saving,onBack={back()},maxWidth=640.dp,heightFraction=.85f){sheet->
        Column(Modifier.fillMaxWidth().then(sheet.dragHandle)){
            SheetGrabber()
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=if(page=="models")24.dp else 8.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                AnimatedVisibility(page!="models",enter=fadeIn(DeskMotion.fadeIn())+expandHorizontally(DeskMotion.layoutSize),exit=fadeOut(DeskMotion.fadeOut())+shrinkHorizontally(DeskMotion.layoutSize)){ModelHeaderButton("arrow-left","返回模型菜单",40,20,true,hitSlop=3){back()}}
                AnimatedContent(page,Modifier.weight(1f),transitionSpec={fadeIn(DeskMotion.fadeIn())togetherWith fadeOut(DeskMotion.fadeOut())},label="model title"){p->
                    SNativeText(when(p){"custom"->"自定义模型";"more"->"更多模型";else->"模型"},Modifier.semantics{heading()},size=18,lineHeight=26,color=c.foreground,weight=FontWeight.SemiBold,heading=true)
                }
                ModelHeaderButton("x","关闭模型面板",32,16,!saving,c.mutedForeground,hitSlop=5){dismiss()}
            }
        }
        AnimatedContent(page,Modifier.weight(1f).fillMaxWidth(),transitionSpec={
            val forward=(modelPages[targetState]?:0)>(modelPages[initialState]?:0)
            (slideInHorizontally(DeskMotion.contentOffset){if(forward)it/4 else -it/4}+fadeIn(DeskMotion.fadeIn()))togetherWith(slideOutHorizontally(DeskMotion.contentOffset){if(forward)-it/4 else it/4}+fadeOut(DeskMotion.fadeOut()))
        },label="model page"){p->
            NativeScrollContent(Modifier.fillMaxSize(),PaddingValues(start=12.dp,end=12.dp,top=4.dp,bottom=16.dp),0.dp){
                if(p=="models"){
                    if(disabled&&lockedReason.isNotEmpty())SNativeText(lockedReason,Modifier.padding(horizontal=12.dp).padding(top=2.dp,bottom=8.dp),size=13,lineHeight=18,color=c.mutedForeground)
                    ModelRow("使用服务端默认模型",listOf(modelName(default).ifEmpty{defaultModel?:nativeModel},default?.resolvedModel.orEmpty()).filter{it.isNotEmpty()}.distinct().joinToString(" · "),model==null,locked){select(null)}
                    if(models.isEmpty())SNativeText(when{reading->"正在获取服务端模型列表…";!catalogError.isNullOrEmpty()->catalogError;createsSession->"创建首个会话后将自动获取模型列表。";else->"尚未获取模型列表，连接成功且有空闲会话时将自动读取。"},Modifier.padding(horizontal=12.dp).padding(top=8.dp).semantics{liveRegion=LiveRegionMode.Polite},size=13,color=c.mutedForeground)
                    else if(!catalogError.isNullOrEmpty())SNativeText("刷新失败，显示上次读取的模型列表。$catalogError",Modifier.padding(horizontal=12.dp).padding(top=8.dp),size=13,color=c.mutedForeground)
                }
                if(p!="custom"){
                    shown.forEach{row->ModelRow(modelName(row),listOf(row.resolvedModel?.takeIf{it.isNotEmpty()}?:row.value.takeIf{models.isNotEmpty()&&it!=row.displayName}.orEmpty(),row.description.orEmpty()).filter{it.isNotEmpty()}.joinToString(" · "),model!=null&&(chosen?.value==row.value||model==row.value),locked){select(row.value)}}
                    if(p=="models"&&onLoad!=null)ModelRow(readLabel,disabled=locked||reading,compact=true,icon="rotate-cw",loading=reading){refresh()}
                    if(p=="models"){
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
                        if(onLoad!=null)ModelRow(readLabel,disabled=locked||reading,compact=true,icon="rotate-cw",loading=reading){refresh()}
                    }
                }else Column(Modifier.padding(horizontal=12.dp).padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    ModelCustomField(custom,{custom=it},!locked){if(custom.isNotBlank())select(custom.trim())}
                    val enabled=!locked&&custom.isNotBlank()
                    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
                    val alpha=animatedEnabledAlpha(enabled)
                    Box(Modifier.fillMaxWidth().heightIn(min=44.dp).pressScale(pressed,enabled).graphicsLayer{this.alpha=alpha;compositingStrategy=CompositingStrategy.ModulateAlpha}.clip(RoundedCornerShape(12.dp)).background(c.foreground).clickable(enabled=enabled,interactionSource=interaction,indication=null){select(custom.trim())}.semantics{role=Role.Button;contentDescription="使用此模型"},contentAlignment=Alignment.Center){
                        NativeSpanText("使用此模型",size=14,lineHeight=null,color=c.background,weight=600,selectable=false,exposeText=false)
                    }
                }
            }
        }
        DeskExpandable(error.isNotEmpty()||saving){
            Box(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=8.dp),contentAlignment=Alignment.Center){
                if(error.isNotEmpty())SNativeText(error,Modifier.fillMaxWidth().semantics{liveRegion=LiveRegionMode.Assertive},size=14,lineHeight=20,color=if(c.dark)Color(0xffff6467)else Color(0xffe7000b),selectable=true)
                else Box(Modifier.semantics{contentDescription="正在保存模型设置"}){SBusy()}
            }
        }
    }
}

/** Permission choices remain local until the user explicitly saves, including bypass mode. */
@Composable fun PermissionSheet(visible:Boolean,session:Session,readOnly:Boolean,reason:String,busy:Boolean,error:String,close:()->Unit,send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current
    val haptics=rememberHaptics()
    var permission by remember{mutableStateOf(session.permissionMode)}
    // A draft may acquire an id while saving. Keep its submitted choice until the next opening.
    LaunchedEffect(visible){if(visible)permission=session.permissionMode}
    val running=session.activeRun!=null
    val blocked=readOnly||busy||running
    val canSave=!blocked&&permission in permissionNames&&permission!=session.permissionMode
    val notice=when{
        readOnly->"${reason.ifEmpty{"会话为只读"}}，现在不能修改权限模式。"
        running->"正在生成回复。回复结束后可修改权限模式。"
        session.id.isEmpty()->"尚未创建会话。保存权限模式会先在工作台创建空会话，不发送消息。"
        else->""
    }
    val shownNotice=rememberLastNonNull(notice.ifEmpty{null}).orEmpty()
    val shownError=rememberLastNonNull(error.ifEmpty{null}).orEmpty()
    fun dismiss(){if(!busy)close()}
    DeskSheetOverlay(visible,{dismiss()},dismissible=!busy,onBack={dismiss()},maxWidth=640.dp,heightFraction=.85f,scrimLabel="关闭权限模式选择器"){sheet->
        Column(Modifier.fillMaxWidth().then(sheet.dragHandle)){
            SheetGrabber()
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=24.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                SNativeText("权限模式",Modifier.weight(1f).semantics{heading()},size=18,lineHeight=26,color=c.foreground,weight=FontWeight.SemiBold,heading=true)
                ModelHeaderButton("x","关闭权限面板",32,16,!busy,c.mutedForeground,hitSlop=5){dismiss()}
            }
        }
        NativeScrollContent(Modifier.weight(1f).fillMaxWidth(),PaddingValues(start=12.dp,end=12.dp,top=4.dp,bottom=16.dp),0.dp){
            DeskExpandable(notice.isNotEmpty()){
                SNativeText(shownNotice,Modifier.padding(horizontal=12.dp).padding(top=2.dp,bottom=8.dp),size=13,lineHeight=18,color=c.mutedForeground)
            }
            permissionNames.forEach{(key,label)->
                ModelRow(label,checked=permission==key,disabled=blocked){
                    if(!blocked&&permission!=key){permission=key;haptics.selection()}
                }
            }
            DeskExpandable(permission=="bypassPermissions"){
                SNativeText("此模式允许 Claude 跳过工具执行前的审批。选择后仍需点击「保存权限模式」确认生效。",Modifier.padding(horizontal=12.dp,vertical=8.dp),size=13,lineHeight=19,color=c.mutedForeground)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(top=8.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            DeskExpandable(error.isNotEmpty()){
                SNativeText(shownError,Modifier.fillMaxWidth().semantics{liveRegion=LiveRegionMode.Assertive},size=14,lineHeight=20,color=if(c.dark)Color(0xffff6467)else Color(0xffe7000b),selectable=true,live=true)
            }
            DeskAction("保存权限模式",canSave,primary=true,modifier=Modifier.fillMaxWidth(),loading=busy){
                if(canSave){haptics.tap();send("session.configure",json("permissionMode" to permission))}
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun ModelHeaderButton(icon:String,label:String,extent:Int,iconSize:Int,enabled:Boolean,tint:Color=LocalDeskColors.current.foreground,hitSlop:Int,onClick:()->Unit){
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.pressedState()
    val inherited=LocalViewConfiguration.current
    val touchConfiguration=remember(inherited,extent,hitSlop){object:ViewConfiguration by inherited{
        override val minimumTouchTargetSize=DpSize((extent+2*hitSlop).dp,(extent+2*hitSlop).dp)
    }}
    val highlight=animatedHighlight(pressed&&enabled,false,LocalDeskColors.current.muted)
    val alpha=animatedEnabledAlpha(enabled,.4f)
    CompositionLocalProvider(LocalViewConfiguration provides touchConfiguration){
        Box(Modifier.size(extent.dp).graphicsLayer{this.alpha=alpha}.pressScale(pressed,enabled,DeskMotion.IconPressScale).clip(RoundedCornerShape(100.dp)).background(highlight).clickable(enabled=enabled,interactionSource=interaction,indication=null,onClick=onClick).semantics{contentDescription=label;role=Role.Button},contentAlignment=Alignment.Center){DeskIcon(icon,size=iconSize.dp,tint=tint)}
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
    val border by animateColorAsState(if(focused)c.mutedForeground else c.border,DeskMotion.state(),label="field border")
    BasicTextField(value,{onChange(it.take(200))},Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp,border,RoundedCornerShape(12.dp)).background(c.background).padding(1.dp).padding(12.dp).onFocusChanged{focused=it.isFocused}.semantics{contentDescription="模型标识"},enabled=enabled,singleLine=true,
        textStyle=TextStyle(color=c.foreground,fontSize=16.sp,lineHeight=24.sp,platformStyle=PlatformTextStyle(includeFontPadding=true)),cursorBrush=SolidColor(cursorColor),
        keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={onSubmit()}),
        decorationBox={inner->Box{if(value.isEmpty())SNativeText("输入服务端模型标识",size=16,lineHeight=24,color=hintColor);inner()}})
}

/** Workspace menu from the drawer footer: a bottom sheet on phones, a centered card on wide screens. */
@Composable fun WorkspaceMenuSheet(visible:Boolean,host:String,online:Boolean,close:()->Unit,scrimHandoff:Boolean=false,content:@Composable ColumnScope.()->Unit){
    val c=LocalDeskColors.current
    DeskSheetOverlay(visible,close,scrimAlpha=DeskMotion.MenuScrim,maxWidth=448.dp,centeredWhenWide=true,bordered=true,scrimLabel="关闭工作台菜单",scrimHandoff=scrimHandoff){sheet->
        Column(Modifier.fillMaxWidth().then(sheet.dragHandle)){
            SheetGrabber()
            Column(Modifier.padding(horizontal=20.dp).padding(top=4.dp,bottom=8.dp)){DeskText(host,size=16,weight=FontWeight.Medium,maxLines=1);DeskText(if(online)"已连接"else "尚未连接",size=13,color=c.mutedForeground,lineHeight=20)}
        }
        Box(Modifier.padding(horizontal=16.dp,vertical=4.dp).fillMaxWidth().height(1.dp).background(c.border))
        content()
        Spacer(Modifier.height(8.dp))
    }
}
@Composable private fun ModelDivider(){Box(Modifier.padding(horizontal=12.dp,vertical=8.dp).fillMaxWidth().height(1.dp).background(LocalDeskColors.current.border))}
@Composable private fun ModelRow(label:String,detail:String="",checked:Boolean?=null,disabled:Boolean=false,compact:Boolean=false,icon:String?=null,trailing:Boolean=false,loading:Boolean=false,onClick:()->Unit){
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val highlight=animatedHighlight(pressed&&!disabled,false,c.muted)
    val alpha=animatedEnabledAlpha(!disabled||loading,.5f)
    Row(Modifier.fillMaxWidth().heightIn(min=if(compact)48.dp else 60.dp).graphicsLayer{this.alpha=alpha}.clip(RoundedCornerShape(20.dp)).background(highlight).clickable(enabled=!disabled,interactionSource=interaction,indication=null,onClick=onClick).semantics(mergeDescendants=true){text=AnnotatedString(label);role=if(checked==null)Role.Button else Role.RadioButton;if(checked!=null)selected=checked;if(detail.isNotEmpty())contentDescription=label+"，"+detail}.padding(horizontal=12.dp,vertical=if(compact)10.dp else 12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
        if(icon!=null)Box(Modifier.size(18.dp),contentAlignment=Alignment.Center){
            val spin by animateFloatAsState(if(loading)1f else 0f,DeskMotion.icon(),label="row loading")
            DeskIcon(icon,Modifier.graphicsLayer{this.alpha=1f-spin},size=18.dp)
            if(loading)Box(Modifier.graphicsLayer{this.alpha=spin}){DeskSpinner(label,18.dp)}
        }
        Column(Modifier.weight(1f)){NativeSpanText(label,size=16,weight=400,lineHeight=24,selectable=false,exposeText=false);if(detail.isNotEmpty())NativeSpanText(detail,Modifier.padding(top=2.dp),size=13,color=c.mutedForeground,lineHeight=19,maxLines=2,selectable=false,exposeText=false)}
        if(checked!=null){
            val mark by animateFloatAsState(if(checked)1f else 0f,DeskMotion.icon(),label="check")
            if(checked||mark>.01f)DeskIcon("check",Modifier.graphicsLayer{scaleX=.4f+.6f*mark;scaleY=.4f+.6f*mark;this.alpha=mark.coerceIn(0f,1f)},size=20.dp,tint=Color(0xff3562f6),strokeWidth=2.2f)
        }else if(trailing)DeskIcon("chevron-right",size=16.dp,tint=c.mutedForeground)
    }
}

/** Grouped settings section: a quiet label above either a rounded card or plain content. */
@Composable internal fun SettingsGroup(title:String?,card:Boolean=true,content:@Composable ColumnScope.()->Unit){
    val c=LocalDeskColors.current
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(title!=null)SNativeText(title,Modifier.padding(start=4.dp).semantics{heading()},size=13,lineHeight=18,color=c.mutedForeground,weight=FontWeight.Medium,heading=true)
        if(card)Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).border(1.dp,c.border,RoundedCornerShape(28.dp)).background(c.card),content=content)
        else Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable internal fun GroupDivider(){Box(Modifier.fillMaxWidth().height(1.dp).background(LocalDeskColors.current.border))}

/** Row inside a [SettingsGroup] card: highlight on press, smooth disabled state, spinner while its action runs. */
@Composable internal fun SheetRow(icon:String,label:String,detail:String?=null,enabled:Boolean=true,loading:Boolean=false,chevron:Boolean=false,onClick:()->Unit){
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val highlight=animatedHighlight(pressed&&enabled,false,c.accent)
    val alpha=animatedEnabledAlpha(enabled||loading)
    Row(Modifier.fillMaxWidth().heightIn(min=54.dp).graphicsLayer{this.alpha=alpha}.background(highlight).clickable(enabled=enabled&&!loading,interactionSource=interaction,indication=null,onClick=onClick).semantics{text=AnnotatedString(label);role=Role.Button}.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
        Box(Modifier.size(22.dp),contentAlignment=Alignment.Center){
            AnimatedContent(loading,transitionSpec={fadeIn(DeskMotion.fadeIn())togetherWith fadeOut(DeskMotion.fadeOut())},label="row icon"){busy->if(busy)DeskSpinner(label,20.dp)else DeskIcon(icon,size=22.dp,tint=c.foreground,strokeWidth=1.8f)}
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
            NativeSpanText(label,size=16,lineHeight=24,selectable=false,exposeText=false)
            if(detail!=null)NativeSpanText(detail,size=13,lineHeight=19,color=c.mutedForeground,selectable=false,exposeText=false)
        }
        if(chevron)DeskIcon("chevron-right",size=16.dp,tint=c.mutedForeground)
    }
}

/** Session status, conversation search, context usage and background-task controls. */
@Composable fun SessionSettingsSheet(visible:Boolean,session:Session,info:TranscriptProjection,readOnly:Boolean,reason:String,busy:Boolean,error:String,close:()->Unit,find:()->Unit,send:(String,JSONObject)->Unit){
    val c=LocalDeskColors.current
    var pending by remember{mutableStateOf("")}
    LaunchedEffect(visible){if(visible)pending=""}
    val blocked=readOnly||busy;val draft=session.id.isEmpty();val running=session.activeRun!=null
    fun fire(type:String,fields:JSONObject){pending=type;send(type,fields)}
    val notice=when{
        readOnly->"${reason.ifEmpty{"会话为只读"}}，现在只能查看会话信息和查找对话。"
        running->"正在生成回复。回复结束后可压缩上下文。"
        draft->"尚未创建会话。创建会话后可查看状态与上下文统计。"
        else->""
    }
    val shownNotice=rememberLastNonNull(notice.ifEmpty{null}).orEmpty()
    val shownError=rememberLastNonNull(error.ifEmpty{null}).orEmpty()
    DeskSheetOverlay(visible,close,scrimLabel="关闭会话设置"){sheet->
        SheetHeader("会话设置",sheet)
        NativeScrollContent(Modifier.fillMaxWidth(),PaddingValues(start=16.dp,end=16.dp,top=4.dp,bottom=20.dp),20.dp){
            SettingsGroup("会话"){
                SheetRow("search","查找对话内容",detail=if(draft)"开始对话后可用" else null,enabled=!draft,onClick=find)
                GroupDivider()
                SheetRow("refresh-cw","刷新会话状态",enabled=!blocked&&!draft,loading=busy&&pending=="session.status"){fire("session.status",json("quota" to true))}
            }
            DeskExpandable(notice.isNotEmpty()){SNativeText(shownNotice,Modifier.padding(horizontal=4.dp),size=13,lineHeight=19,color=c.mutedForeground)}
            SettingsGroup("上下文与用量",card=false){
                SNativeText(info.context?.let{"${"%,d".format(it.optLong("usedTokens"))} / ${"%,d".format(it.optLong("windowTokens"))} tokens · ${"%.1f".format(it.optDouble("percentage"))}%"}?:"尚无上下文统计",size=14,lineHeight=20,color=c.mutedForeground)
                info.usage?.let{SNativeText("输入 ${"%,d".format(it.optLong("inputTokens"))} · 输出 ${"%,d".format(it.optLong("outputTokens"))} tokens"+if(it.isNull("costUsd"))""else " · 估算 $${"%.4f".format(it.optDouble("costUsd"))}",size=14,lineHeight=20,color=c.mutedForeground)}
                DeskAction("压缩上下文",!blocked&&!draft&&!running,modifier=Modifier.fillMaxWidth(),loading=busy&&pending=="session.compact"){fire("session.compact",JSONObject())}
            }
            DeskExpandable(info.tasks.isNotEmpty()){
                SettingsGroup("后台任务（${info.tasks.size}）",card=false){
                    info.tasks.forEach{task->key(task.id){Column(Modifier.fillMaxWidth().background(c.muted,RoundedCornerShape(12.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        SNativeText(task.description.ifEmpty{task.kind},size=14,lineHeight=20)
                        if(task.progress.isNotEmpty())SNativeText(task.progress,size=12,lineHeight=16,color=c.mutedForeground)
                        DeskAction("停止任务",!blocked&&running,modifier=Modifier.fillMaxWidth(),loading=busy&&pending=="run.task.stop"){fire("run.task.stop",json("runId" to session.activeRun?.id,"taskId" to task.id))}
                    }}}
                }
            }
            DeskExpandable(error.isNotEmpty()){SNativeText(shownError,size=14,lineHeight=20,color=if(c.dark)Color(0xffff6467)else Color(0xffe7000b),live=true)}
        }
    }
}
