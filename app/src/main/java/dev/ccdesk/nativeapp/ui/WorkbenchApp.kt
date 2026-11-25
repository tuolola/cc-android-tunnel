// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ccdesk.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import dev.ccdesk.nativeapp.R
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.util.UUID

@Composable fun WorkbenchApp(repository:WorkbenchRepository,requestedRoute:String?=null,onRouteConsumed:()->Unit={}) = DeskTheme {
    val state by repository.state.collectAsState()
    val keyboard=LocalSoftwareKeyboardController.current
    var route by rememberSaveable{mutableStateOf("")}
    var primaryRoute by rememberSaveable{mutableStateOf("")}
    var drawer by rememberSaveable{mutableStateOf(false)}
    var composerFocusRequest by remember{mutableIntStateOf(0)}
    var chatBusy by remember{mutableStateOf(false)}
    var secondaryPresent by remember{mutableStateOf(false)}
    val navigate:(String)->Unit={val target=it.trim('/');keyboard?.hide();if(target in setOf("","remote"))primaryRoute=target;route=target;drawer=false}
    val newChat:()->Unit={if(!chatBusy){repository.newDraft();composerFocusRequest++;navigate("")}}
    LaunchedEffect(requestedRoute){if(requestedRoute!=null){navigate(requestedRoute);onRouteConsumed()}}
    BackHandler(drawer||route.isNotEmpty()){if(drawer){keyboard?.hide();drawer=false}else if(route!=primaryRoute)route=primaryRoute else{primaryRoute="";route=""}}
    val secondary=route.isNotEmpty()&&route!="remote"
    val shownSecondary=rememberLastNonNull(route.takeIf{secondary})?:"settings"
    val remote=primaryRoute=="remote"
    Box(Modifier.fillMaxSize()) {
    OverlayHost {
    DrawerSurface(drawer,{drawer=it;if(!it)keyboard?.hide()},drawerContent={DrawerContent(repository,state,route,navigate,newChat,!chatBusy)}) {
        Box(Modifier.fillMaxSize().background(LocalDeskColors.current.background)) {
            // The chat stays composed under pushed pages so drafts, scroll position and sheets survive.
            Box(Modifier.fillMaxSize().then(if(remote||secondary||secondaryPresent)Modifier.clearAndSetSemantics{}else Modifier)){ChatScreen(repository,state,{drawer=true},navigate,newChat,composerFocusRequest,chatVisible=!remote&&!secondary&&!secondaryPresent&&!drawer,onBusyChange={chatBusy=it})}
            AnimatedVisibility(remote,
                enter=slideInHorizontally(DeskMotion.pageOffset){(it*DeskMotion.PageShiftFraction).toInt()}+fadeIn(DeskMotion.fadeIn()),
                exit=slideOutHorizontally(DeskMotion.pageOffset){(it*DeskMotion.PageShiftFraction).toInt()}+fadeOut(DeskMotion.fadeOut()),label="remote page"){
                RemoteScreen(repository,navigate,{navigate("")},{drawer=true},active=remote&&!drawer&&!secondary)
            }
            SecondaryScreen(shownSecondary,repository,navigate,{route=primaryRoute},{navigate("")},visible=secondary,backEnabled=!drawer,onPresentChange={secondaryPresent=it})
        }
    }
    }
    }
    // Child handlers are disabled while the drawer covers them; registration timing of
    // AnimatedVisibility subcompositions must not decide which page receives Back.
    BackHandler(drawer){keyboard?.hide();drawer=false}
}

/**
 * Edge-swipe drawer. The finger drives the position directly; release hands the fling
 * velocity to a spring, and a new touch interrupts and reverses an in-flight settle.
 * The scrim only intercepts input while the drawer is (becoming) open.
 */
@Composable internal fun DrawerSurface(open:Boolean,onChange:(Boolean)->Unit,drawerContent:@Composable ()->Unit,content:@Composable ()->Unit) {
    val widthDp=LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth=if(widthDp<640.dp)widthDp*.8f else 320.dp
    val density=LocalDensity.current
    val width=with(density){drawerWidth.toPx()}
    val slop=with(density){7.dp.toPx()};val vertical=with(density){8.dp.toPx()};val edge=with(density){32.dp.toPx()};val travel=with(density){DeskMotion.DrawerDistance.toPx()};val speed=with(density){DeskMotion.DrawerFlingVelocity.toPx()}
    val keyboard=LocalSoftwareKeyboardController.current
    val haptics=rememberHaptics()
    var drag by remember{mutableStateOf<Float?>(null)}
    val position=remember{Animatable(if(open)0f else -width)}
    var settleRequest by remember{mutableIntStateOf(0)}
    val releaseVelocity=remember{floatArrayOf(0f)}
    LaunchedEffect(open,width,settleRequest){
        drag?.let{position.snapTo(it)}
        drag=null
        val velocity=releaseVelocity[0].also{releaseVelocity[0]=0f}
        position.animateTo(if(open)0f else -width,DeskMotion.drawer(),velocity)
    }
    LaunchedEffect(open){if(open)keyboard?.hide()}
    Box(Modifier.fillMaxSize().clipToBounds().pointerInput(open,width){
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            if(!open&&position.value<=-width&&down.position.x>edge)return@awaitEachGesture
            var start=0f
            val tracker=PlatformPanVelocity();tracker.add(down)
            var accepted=false;var dx=0f
            try {
            while(true){
                val event=awaitPointerEvent();val change=event.changes.firstOrNull{it.id==down.id}?:break
                if(!change.pressed&&change.isConsumed)break // Android ACTION_CANCEL is a consumed synthetic release.
                tracker.add(change)
                dx=change.position.x-down.position.x
                if(!accepted&&kotlin.math.abs(change.position.y-down.position.y)>vertical)break
                if(!accepted&&kotlin.math.abs(dx)>slop){start=drag?:position.value;accepted=true}
                if(accepted){change.consume();drag=(start+dx).coerceIn(-width,0f)}
                if(!change.pressed){
                    if(accepted){val vx=tracker.velocity().x;val next=DeskMotion.drawerTarget(open,dx,vx,travel,speed);releaseVelocity[0]=vx;if(next!=open)haptics.gestureEnd();onChange(next)}
                    break
                }
            }
            } finally {tracker.close();if(accepted)settleRequest++}
        }
    }) {
        val x=(drag?:position.value).coerceIn(-width,0f)
        val covering=open||x> -width
        Box(Modifier.fillMaxSize().then(if(covering)Modifier.clearAndSetSemantics{}else Modifier)){content()}
        if(x> -width){
            Box(Modifier.fillMaxSize().graphicsLayer{alpha=((x+width)/width).coerceIn(0f,1f)}.background(Color.Black.copy(alpha=DeskMotion.DrawerScrim))
                .then(if(open)Modifier.pointerInput(Unit){detectTapGestures{onChange(false)}}.semantics{contentDescription="关闭侧栏";onClick{onChange(false);true}}else BlockTapInput))
        }
        // Keep local search/filter/expansion state mounted, as the baseline hidden drawer does.
        Box(Modifier.width(drawerWidth).fillMaxHeight().offset{IntOffset(x.toInt(),0)}.then(if(!open)BlockTapInput.clearAndSetSemantics{}else Modifier)){drawerContent()}
    }
}

@Composable private fun DrawerContent(repo:WorkbenchRepository,state:WorkbenchState,route:String,navigate:(String)->Unit,newChat:()->Unit,newChatEnabled:Boolean) {
    val c=LocalDeskColors.current
    val keyboard=LocalSoftwareKeyboardController.current
    var recentLimit by rememberSaveable(state.endpoint,state.authenticated){mutableIntStateOf(30)}
    var searching by rememberSaveable{mutableStateOf(false)}
    var query by rememberSaveable(state.endpoint,state.authenticated){mutableStateOf("")}
    val search=query.trim()
    val excludedDeviceIds=remember(state.serverDeviceId,state.localDeviceId,state.devices,state.sessions){state.excludedComputerDeviceIds()}
    val sessions=state.sessions.filter{!it.belongsToClient(excludedDeviceIds)&&(search.isEmpty()||it.title.contains(search,ignoreCase=true))}
    val pinned=sessions.filter{it.id in state.pins}.sortedByDescending{it.updatedAt}
    val recent=sessions.filter{it.id !in state.pins}.sortedByDescending{it.updatedAt}
    Column(Modifier.fillMaxSize().background(c.sidebar).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(start=32.dp,end=24.dp),verticalAlignment=Alignment.CenterVertically){
            if(searching){
                DeskField(query,{query=it},"搜索会话内容",Modifier.weight(1f),autoFocus=true)
                DeskIconButton("x","关闭会话搜索"){keyboard?.hide();query="";searching=false}
            }else{
                DeskText(androidx.compose.ui.res.stringResource(R.string.app_name),Modifier.weight(1f),size=23,weight=FontWeight.Bold,maxLines=1)
                DrawerCircleButton("search","搜索会话"){searching=true}
            }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().testTag("sidebar-conversations"),contentPadding=PaddingValues(top=0.dp,bottom=24.dp)) {
                item{DrawerItem("远程控制","monitor",route=="remote"){navigate("remote")}}
                item{DrawerItem("通知","bell",badge=state.notices.count{!it.read}){navigate("notifications")}}
                item{DrawerItem("帮助中心","circle-help",route=="help"){navigate("help")}}
                if(pinned.isNotEmpty()){
                    item{DeskText("置顶",Modifier.padding(start=32.dp,top=32.dp,bottom=14.dp),size=16,weight=FontWeight.Medium)}
                    items(pinned,key={"pin:"+it.id}){session->DrawerItem(session.title,"pin",state.selectedId==session.id){repo.openSession(session.id);navigate("")}}
                }
                item{DeskText("聊天",Modifier.padding(start=32.dp,top=32.dp,bottom=14.dp),size=16,weight=FontWeight.Medium)}
                items(if(search.isEmpty())recent.take(recentLimit)else recent,key={"recent:"+it.id}){session->DrawerItem(session.title,selected=state.selectedId==session.id){repo.openSession(session.id);navigate("")}}
                if(recent.isEmpty()&&pinned.isEmpty())item{DeskText(if(search.isEmpty())"你的聊天会显示在这里"else"没有匹配的会话",Modifier.padding(horizontal=32.dp,vertical=12.dp),size=14,color=c.mutedForeground)}
                // ready.sessions already contains the server's session list. Reveal older entries
                // here without navigating away or confusing this with message-history pagination.
                if(search.isEmpty()&&recent.size>recentLimit)item(key="load-more-conversations"){DrawerItem("加载更多会话"){recentLimit+=30}}
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(c.sidebar.copy(alpha=0f),c.sidebar))))
        }
        Row(Modifier.fillMaxWidth().padding(start=32.dp,end=24.dp,bottom=8.dp,top=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Row(Modifier.heightIn(min=48.dp).alpha(if(newChatEnabled)1f else .45f).clip(CircleShape).background(c.primary).deskPressable(c.primary.copy(alpha=.8f),24.dp,enabled=newChatEnabled,onClick=newChat).semantics{contentDescription="新建会话";role=Role.Button;if(!newChatEnabled)disabled()}.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                DeskIcon("square-pen",size=24.dp,tint=c.onPrimary)
                DeskText("聊天",size=16,color=c.onPrimary,weight=FontWeight.SemiBold)
            }
            Box{
                DrawerCircleButton("circle-user-round","工作台菜单",size=48.dp,iconSize=26.dp){navigate("settings")}
                Box(Modifier.align(Alignment.TopEnd).padding(3.dp)){AppUpdateBadge()}
            }
        }
    }
}
/** The familiar sidebar keeps circular floating controls without changing other pages. */
@Composable private fun DrawerCircleButton(name:String,label:String,size:Dp=44.dp,iconSize:Dp=24.dp,onClick:()->Unit){
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val highlight=animatedHighlight(pressed,false,c.accent)
    Box(Modifier.size(size).pressScale(pressed,true,DeskMotion.IconPressScale)
        .deskFloatingSurface(CircleShape,18.dp).background(highlight,CircleShape)
        .clickable(interactionSource=interaction,indication=null,onClick=onClick)
        .semantics{contentDescription=label;role=Role.Button},contentAlignment=Alignment.Center){
        DeskIcon(name,size=iconSize)
    }
}
@Composable private fun DrawerItem(label:String,icon:String?=null,selected:Boolean=false,badge:Int=0,onClick:()->Unit){
    val c=LocalDeskColors.current
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).deskPressable(c.accent,0.dp,selected=selected,onClick=onClick).semantics{role=Role.Button;this.selected=selected}.padding(start=32.dp,end=24.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
        icon?.let{DeskIcon(it,size=24.dp,strokeWidth=2f)}
        NativeSpanText(label,Modifier.weight(1f),size=16,lineHeight=24,maxLines=1,selectable=false)
        if(badge>0)DeskText("$badge",size=12,color=c.mutedForeground)
    }
}
@Composable fun DeskDivider(){Box(Modifier.padding(horizontal=16.dp,vertical=12.dp).fillMaxWidth().height(1.dp).background(LocalDeskColors.current.border))}
@Composable fun DeskField(value:String,onChange:(String)->Unit,placeholder:String,modifier:Modifier=Modifier,enabled:Boolean=true,size:Int=16,multiline:Boolean=false,maxLength:Int=16000,autoFocus:Boolean=false){val c=LocalDeskColors.current;val focus=remember{FocusRequester()};LaunchedEffect(autoFocus){if(autoFocus)focus.requestFocus()};BasicTextField(value,{if(it.length<=maxLength)onChange(it)},modifier.focusRequester(focus).semantics{contentDescription=placeholder},enabled=enabled,singleLine=!multiline,textStyle=TextStyle(color=c.foreground,fontSize=size.sp,lineHeight=24.sp,platformStyle=PlatformTextStyle(includeFontPadding=true)),cursorBrush=SolidColor(c.primary),decorationBox={inner->Box{if(value.isEmpty())DeskText(placeholder,size=size,color=c.mutedForeground,lineHeight=24);inner()}})}

/** Text link used in toolbars and status strips: animated highlight, no default Material indication. */
@Composable private fun DeskLink(label:String,modifier:Modifier=Modifier,icon:String?=null,weight:FontWeight=FontWeight.Normal,color:Color=LocalDeskColors.current.mutedForeground,onClick:()->Unit){
    val c=LocalDeskColors.current
    Row(modifier.clip(RoundedCornerShape(8.dp)).deskPressable(c.muted,8.dp,onClick=onClick).semantics{role=Role.Button}.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        icon?.let{DeskIcon(it,size=16.dp,tint=color)}
        DeskText(label,size=14,color=color,weight=weight)
    }
}

@Composable private fun ChatScreen(repo:WorkbenchRepository,state:WorkbenchState,openDrawer:()->Unit,navigate:(String)->Unit,newChat:()->Unit,focusRequest:Int,chatVisible:Boolean,onBusyChange:(Boolean)->Unit) {
    val c=LocalDeskColors.current;val scope=rememberCoroutineScope();val keyboard=LocalSoftwareKeyboardController.current;val haptics=rememberHaptics()
    val session=state.sessions.find{it.id==state.selectedId}?:Session("",projectPath="服务端工作目录",permissionMode="auto")
    val clientSession=state.authenticated&&state.isClientSession(session)
    LaunchedEffect(chatVisible){if(!chatVisible)keyboard?.hide()}
    val history=state.histories[session.id];val active=session.activeRun
    val guest=!state.authenticated;val online=state.status=="online";val atServer=session.id.isEmpty()||(session.device!=null&&session.device.id==state.serverDeviceId);val device=state.devices.find{it.id==session.device?.id}
    val readOnly=!online||(!atServer&&device?.ready!=true)||active?.surface=="terminal"
    val moveTargets = remember(state.devices, state.projects, state.localDeviceId, state.serverDeviceId, session.device, session.projectPath) {
        val onlineDevices = state.devices.filter { it.ready == true && !it.server }.mapTo(mutableSetOf()) { it.id }
        state.projects.filter { it.device.id in onlineDevices && !it.device.server && it.device.id != state.localDeviceId &&
            it.device.id != state.serverDeviceId && !it.contains(session) }
    }
    val reason=when{!online->if(state.status=="connecting")"正在重新连接工作台" else "工作台连接已断开";active?.surface=="terminal"->"会话已在终端打开";!atServer&&device==null->"设备离线";!atServer&&device?.ready!=true->"设备执行通道未就绪";else->""}
    val drafts=remember(state.endpoint,state.authenticated){mutableStateMapOf<String,String>()};val draftKey=session.id
    val input=drafts[draftKey].orEmpty()
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var sheet by remember{mutableStateOf("")};var search by remember(session.id){mutableStateOf(false)};var query by remember(session.id){mutableStateOf("")};var match by remember{mutableIntStateOf(0)}
    val canMove = online && session.id.isNotEmpty() && active == null && !busy
    var sessionStats by remember(state.endpoint, session.id) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(sheet, online, session.id, active?.id) {
        if (sheet != "settings" || !online || session.id.isEmpty()) return@LaunchedEffect
        try { sessionStats = repo.command("session.stats", json("sessionId" to session.id)).takeIf { it.optString("type") == "session.stats" } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { sessionStats = null }
    }
    var draftModel by remember(state.endpoint,state.authenticated,focusRequest){mutableStateOf<String?>(null)};var draftEffort by remember(state.endpoint,state.authenticated,focusRequest){mutableStateOf<String?>(null)};var draftChosen by remember(state.endpoint,state.authenticated,focusRequest){mutableStateOf(false)}
    val creationLock=remember(state.endpoint,state.authenticated,focusRequest){Mutex()}
    var draftCreationId by remember(state.endpoint,state.authenticated,focusRequest){mutableStateOf<String?>(null)}
    var firstTarget by remember(state.endpoint,state.authenticated,focusRequest){mutableStateOf<String?>(null)}
    var creatingSession by remember(state.endpoint,state.authenticated,focusRequest){mutableStateOf(false)}
    SideEffect{onBusyChange(busy||creatingSession)}
    LaunchedEffect(focusRequest){if(focusRequest>0)drafts[""]=""}
    var cancelRequested by remember{mutableStateOf<String?>(null)}
    var composerFocused by remember{mutableStateOf(false)}
    var menu by remember{mutableStateOf("")}
    suspend fun ensureSession():String=creationLock.withLock {
        session.id.takeIf{it.isNotEmpty()}?:firstTarget?.takeIf{id->state.sessions.any{it.id==id}}?:run {
            val request=draftCreationId?:UUID.randomUUID().toString().also{draftCreationId=it}
            creatingSession=true
            try{repo.createSession(requestId=request,onCreated={id->firstTarget=id;draftCreationId=null;drafts[id]=drafts[id]?:drafts[""].orEmpty();drafts[""]=""})}finally{creatingSession=false}
        }
    }
    LaunchedEffect(session.id){if(session.id.isNotEmpty()&&session.id!=firstTarget){draftChosen=false;draftModel=null;draftEffort=null;error="";sheet=""}}
    LaunchedEffect(active?.id){if(active?.id!=cancelRequested)cancelRequested=null}
    val projection=remember(session.id){TranscriptProjection()}
    LaunchedEffect(history,active?.id){projection.update(history?.events.orEmpty(),history?.fragments.orEmpty(),active?.id)}
    val list=rememberLazyListState();var initial by remember(session.id){mutableStateOf(true)}
    val endThreshold=with(LocalDensity.current){60.dp.toPx()}
    val nearEnd by remember(list,endThreshold){derivedStateOf{val info=list.layoutInfo;val end=info.visibleItemsInfo.lastOrNull();end==null||(end.index>=info.totalItemsCount-1&&end.offset+end.size-info.viewportEndOffset<endThreshold)}}
    var following by remember(session.id){mutableStateOf(true)}
    var rowLayoutVersion by remember(session.id){mutableIntStateOf(0)}
    // Aligns the end of the final row with the viewport in the same frame. Jumping to the
    // final row first is only needed when it is not laid out, so streamed chunks never flash
    // the start of a long reply before settling at its end.
    suspend fun scrollToEnd(animated:Boolean=false){
        val target=list.layoutInfo.totalItemsCount-1
        if(target<0)return
        if(animated)list.animateScrollToItem(target)
        else if(list.layoutInfo.visibleItemsInfo.none{it.index==target})list.scrollToItem(target)
        val info=list.layoutInfo;val item=info.visibleItemsInfo.lastOrNull{it.index==target}
        if(item!=null){val overflow=item.offset+item.size-info.viewportEndOffset;if(overflow>0)list.scrollBy(overflow.toFloat())}
        else{withFrameNanos{};val again=list.layoutInfo;again.visibleItemsInfo.lastOrNull{it.index==target}?.let{list.scrollBy((it.offset+it.size-again.viewportEndOffset).coerceAtLeast(0).toFloat())}}
        following=true
    }
    // following is recreated for each session; restart observers so they read and update
    // that session's state instead of retaining the previously opened chat's state.
    LaunchedEffect(list,session.id){snapshotFlow{Triple(list.isScrollInProgress,list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)}.collect{if(it.first)following=nearEnd}}
    // Match maintainScrollAtEnd.onLayout for IME and composer-height changes, without moving a reader who scrolled away.
    LaunchedEffect(list,session.id){snapshotFlow{list.layoutInfo.viewportSize.height}.collect{if(it>0&&following)scrollToEnd()}}
    LaunchedEffect(session.id,rowLayoutVersion){if(following)scrollToEnd()}
    val rows=projection.rows
    val word=if(search)query.trim().lowercase()else "";val hits=if(word.isEmpty())emptyList()else rows.indices.filter{rows[it].searchable().lowercase().contains(word)};val focus=hits.getOrNull(match.coerceAtMost((hits.size-1).coerceAtLeast(0)))
    var searchedPage by remember(session.id){mutableStateOf("")}
    LaunchedEffect(session.id,rows.size,history?.fragments,history?.events?.size){if(rows.isNotEmpty()&&(initial||following)){scrollToEnd();initial=false}}
    LaunchedEffect(session.id,focus){if(focus!=null){following=false;list.animateScrollToItem(focus+(if(history?.hasEarlier==true&&online)1 else 0),scrollOffset=-(list.layoutInfo.viewportSize.height*.3f).toInt())}}
    LaunchedEffect(word,history?.events?.firstOrNull()?.sequence,history?.loading){val cursor="$word/${history?.events?.firstOrNull()?.sequence}";if(word.isNotEmpty()&&hits.isEmpty()&&online&&history?.hasEarlier==true&&!history.loading&&searchedPage!=cursor){delay(280);searchedPage=cursor;try{repo.loadEarlier(session.id)}catch(e:Exception){error=e.message.orEmpty()}}}
    suspend fun send(type:String,fields:JSONObject=JSONObject()):Boolean {if(readOnly||busy)return false;busy=true;error="";return try {val id=ensureSession();if(type=="message.send"&&draftChosen&&(session.id.isEmpty()||id==firstTarget)){repo.configureModel(id,draftModel,draftEffort);draftChosen=false};fields.put("sessionId",id);repo.command(type,fields);true}catch(e:Exception){error=e.message.orEmpty();false}finally{busy=false}}
    val command:(String,JSONObject)->Unit={type,fields->scope.launch{send(type,fields)}}
    val openSheet:(String)->Unit={keyboard?.hide();sheet=it}
    val label=when(active?.waiting){"question"->"等待回答";"plan"->"等待确认计划";"background"->"后台任务运行中";else->active?.status?.let{runLabels[it]?:it}.orEmpty()}
    val canContinue=active==null&&projection.status in listOf("failed","cancelled")&&!readOnly
    // Model selection state, shown inside the composer.
    val model=if(draftChosen&&(session.id.isEmpty()||session.id==firstTarget))draftModel else session.model;val effort=if(draftChosen&&(session.id.isEmpty()||session.id==firstTarget))draftEffort else session.effort
    val catalogKey=session.device?.id?:state.serverDeviceId?:session.id
    val catalog=state.modelCatalogs[catalogKey]
    val serverSessions=state.sessions.filter{it.device?.server==true||(state.serverDeviceId!=null&&it.device?.id==state.serverDeviceId)}
    val querySession=if(session.id.isEmpty())serverSessions.firstOrNull{it.activeRun==null}?.id else session.id
    val catalogBlocked=session.id.isEmpty()&&serverSessions.isNotEmpty()&&querySession==null
    val modelLabel=modelName(modelForChoice(catalog?.models.orEmpty(),model,state.defaultModel,projection.nativeModel)).ifEmpty{model?:state.defaultModel?:projection.nativeModel.ifEmpty{"默认模型"}}
    val modelLocked=readOnly||active!=null||busy
    val modelLockReason=when{readOnly->"${reason.ifEmpty{"会话为只读"}}，暂时无法更改模型。";active!=null->"正在生成回复，结束后可更改模型和思考强度。";busy->"正在发送，请稍候。";else->""}
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            DeskIconButton("panel-left","打开导航",iconSize=24.dp,onClick=openDrawer)
            Column(Modifier.weight(1f)){
                Row(Modifier.padding(start=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    DeskIcon(if(clientSession)"monitor"else "message-circle",size=14.dp,tint=c.primary)
                    DeskText(if(clientSession)session.device?.name?:"电脑会话"else "CCtunnel · 手机会话",size=12,color=c.mutedForeground,maxLines=1)
                }
                if(!guest)ModelPickerEntry(modelLabel,effortNames[effort]?:"默认",sheet=="model",{openSheet("model")},Modifier.fillMaxWidth())
                else DeskText("CCtunnel",Modifier.padding(start=10.dp),size=18,weight=FontWeight.SemiBold)
            }
            Row(verticalAlignment=Alignment.CenterVertically){
                DeskIconButton("square-pen","新建会话",enabled=!busy&&!creatingSession,iconSize=24.dp,onClick=newChat)
                DeskIconButton("ellipsis-vertical","会话菜单",iconSize=24.dp,selected=menu=="chat"){keyboard?.hide();menu="chat"}
            }
        }
        DeskExpandable(search){Row(Modifier.fillMaxWidth().background(c.card).padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){DeskField(query,{query=it;match=0},"查找对话内容",Modifier.weight(1f).padding(horizontal=4.dp),autoFocus=true);if(word.isNotEmpty())DeskText(if(history?.loading==true)"查找中"else if(hits.isEmpty())"无匹配"else "${match.coerceAtMost(hits.lastIndex)+1}/${hits.size}",size=12,color=c.mutedForeground);DeskIconButton("chevron-up","上一个结果",hits.size>1){match=(match+hits.size-1)%hits.size};DeskIconButton("chevron-down","下一个结果",hits.size>1){match=(match+1)%hits.size};DeskIconButton("x","关闭查找"){search=false;query=""}}}
        DeskExpandable(search&&word.isNotEmpty()&&hits.isNotEmpty()&&history?.hasEarlier==true&&online){Box(Modifier.fillMaxWidth().background(c.card).padding(horizontal=12.dp)){DeskAction("查找更早记录",history?.loading!=true,loading=history?.loading==true){scope.launch{try{repo.loadEarlier(session.id)}catch(e:Exception){error=e.message.orEmpty()}}}}}
        DeskExpandable(!guest&&(reason.isNotEmpty()||label.isNotEmpty()||canContinue)){Row(Modifier.fillMaxWidth().background(c.muted).padding(horizontal=16.dp,vertical=6.dp).animateContentSize(DeskMotion.layoutSize),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            AnimatedContent(if(reason.isNotEmpty())"$reason · 只读"else label.ifEmpty{"上一轮已结束"},Modifier.weight(1f),transitionSpec={fadeIn(DeskMotion.fadeIn())togetherWith fadeOut(DeskMotion.fadeOut())},contentAlignment=Alignment.CenterStart,label="status"){DeskText(it,size=12,color=c.mutedForeground)}
            if(!online)DeskLink("连接设置",weight=FontWeight.SemiBold,color=c.foreground){navigate("connection")}
            if(canContinue)DeskAction("继续上一轮",!busy){command("run.continue",JSONObject())}
            if(projection.tasks.isNotEmpty())DeskLink("后台任务 ${projection.tasks.size}",color=c.foreground){openSheet("settings")}
        }}
        val failure=error.ifEmpty{state.error?:history?.loadError.orEmpty()}
        val shownFailure=rememberLastNonNull(failure.ifEmpty{null}).orEmpty()
        DeskExpandable(failure.isNotEmpty()){SelectionContainer{DeskText(shownFailure,Modifier.padding(horizontal=16.dp,vertical=8.dp),14,if(c.dark)Color(0xffff6467)else Color(0xffe7000b))}}
        DeskExpandable(online&&history?.loadError!=null&&!history.loading){Box(Modifier.padding(horizontal=16.dp,vertical=4.dp)){DeskAction("重新读取会话"){repo.openSession(session.id)}}}
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)){
                if(history?.hasEarlier==true&&online)item(key="earlier"){DeskAction(if(history.loading)"正在加载…"else "加载更早的记录",!history.loading,loading=history.loading){scope.launch{try{repo.loadEarlier(session.id)}catch(e:Exception){error=e.message.orEmpty()}}}}
                // Stable row ids: a streamed chunk updates one row in place; only newly inserted rows fade in.
                itemsIndexed(rows,key={_,r->r.id},contentType={_,r->r.kind}){i,row->ChatTranscriptRow(row,active?.id==row.runId,readOnly||busy,focus==i,modifier=Modifier.animateItem(fadeInSpec=DeskMotion.fadeIn(),placementSpec=null,fadeOutSpec=DeskMotion.fadeOut()),onHeightChange={rowLayoutVersion++},showActions=row.kind=="reply"&&active?.id!=row.runId&&rows.drop(i+1).none{it.runId==row.runId&&it.kind=="reply"},actionText=rows.filter{it.runId==row.runId&&it.kind=="reply"}.joinToString("\n\n"){it.content},planMode=session.modeBeforePlan,send=command)}
            }
            if(rows.isEmpty()&&session.id.isEmpty()&&history?.loading!=true){
                Column(Modifier.align(Alignment.Center).padding(horizontal=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Box(Modifier.size(48.dp).background(c.accent,DeskShape.Field),contentAlignment=Alignment.Center){DeskIcon("link-2",size=28.dp,tint=c.primary)}
                    DeskText("从这里开始",size=26,weight=FontWeight.SemiBold)
                    DeskText("在手机上对话，也能从侧栏打开电脑会话。",size=15,color=c.mutedForeground,lineHeight=24)
                }
            }
            androidx.compose.animation.AnimatedVisibility(rows.isEmpty()&&(history?.loading==true||readOnly&&!guest),Modifier.align(Alignment.BottomStart).fillMaxWidth(),enter=fadeIn(DeskMotion.fadeIn()),exit=ExitTransition.None){
                DeskText(if(history?.loading==true)"正在读取会话…"else "暂无已加载的消息",Modifier.padding(horizontal=24.dp,vertical=20.dp),size=16,color=c.mutedForeground)
            }
            JumpToLatestButton(!nearEnd,Modifier.align(Alignment.BottomEnd).padding(end=16.dp,bottom=8.dp)){
                scope.launch{scrollToEnd(true);following=true}
            }
        }
        Column(Modifier.fillMaxWidth().widthIn(max=800.dp).align(Alignment.CenterHorizontally).padding(start=12.dp,end=12.dp,bottom=8.dp,top=4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).deskFloatingSurface(RoundedCornerShape(26.dp),18.dp).animateContentSize(DeskMotion.layoutSize).padding(horizontal=4.dp,vertical=2.dp),verticalAlignment=Alignment.Bottom){
                if(clientSession)PermissionPickerEntry(permissionNames[session.permissionMode]?:session.permissionMode,sheet=="permissions",{openSheet("permissions")},Modifier.widthIn(max=112.dp).align(Alignment.CenterVertically))
                Box(Modifier.weight(1f).align(Alignment.CenterVertically).padding(start=if(clientSession)4.dp else 12.dp)){
                    if(guest)Box(Modifier.fillMaxWidth().heightIn(min=32.dp).deskPressable(c.muted){navigate("connection")}.padding(horizontal=4.dp),contentAlignment=Alignment.CenterStart){DeskText("连接工作台，开始对话…",size=16,color=c.mutedForeground,lineHeight=24)}
                    else NativeComposer(input,{drafts[draftKey]=it},if(readOnly)"只读会话"else "给 Claude 发消息…",!readOnly,Modifier.fillMaxWidth().heightIn(min=26.dp,max=144.dp),onFocusChange={composerFocused=it},focusRequest=if(chatVisible)focusRequest else 0)
                }
                val stop=input.isBlank()&&active!=null
                val stopping=stop&&cancelRequested==active?.id
                val disabled=readOnly||busy||(input.isBlank()&&!stop)||stopping
                Box(Modifier.padding(horizontal=4.dp,vertical=5.dp)){
                    SendButton(if(busy||stopping)SendMode.Busy else if(stop)SendMode.Stop else SendMode.Send,!disabled,stop){
                        if(stop){val run=active!!.id;cancelRequested=run;haptics.tap();scope.launch{if(!send("run.cancel",json("runId" to run)))cancelRequested=null}}
                        else{haptics.confirm();val text=input;scope.launch{if(send("message.send",json("text" to text.trim()))){if(drafts[draftKey]==text)drafts[draftKey]="";val target=repo.state.value.selectedId;if(draftKey.isEmpty()&&target!=null&&drafts[target]==text)drafts[target]=""}}}
                    }
                }
            }
        }
    }
    DeskAnchoredOverlay(menu=="chat",{menu=""},width=224.dp,corner=20.dp){
        ChatMenuItem("在聊天中查找","search"){menu="";search=true}
        ChatMenuItem("刷新会话","refresh-cw",enabled=!guest&&!readOnly&&!busy&&!creatingSession&&session.id.isNotBlank()){
            menu="";scope.launch{send("session.status",json("quota" to true))}
        }
        if (session.id.isNotEmpty() && moveTargets.isNotEmpty()) {
            ChatMenuItem("移到项目…", "folder", enabled = canMove) { menu = ""; openSheet("move") }
        }
    }
    DeskSheetOverlay(sheet == "move", { if (!busy) sheet = "" }, dismissible = !busy, scrimLabel = "关闭项目选择") { motion ->
        SheetHeader("移到项目", motion, closeEnabled = !busy)
        NativeScrollContent(Modifier.fillMaxWidth(), PaddingValues(horizontal = 16.dp, vertical = 8.dp), 8.dp) {
            DeskText("会话后续将在目标电脑上执行。", size = 14, color = c.mutedForeground)
            if (moveTargets.isEmpty()) DeskText("暂无在线电脑的可用项目。", size = 14, color = c.mutedForeground)
            moveTargets.groupBy { it.device.id }.values.forEach { projects ->
                DeskText(state.devices.find { it.id == projects.first().device.id }?.name.orEmpty().ifBlank { "未命名电脑" }, weight = FontWeight.SemiBold)
                projects.forEach { project ->
                    SheetRow("folder", project.label, detail = project.path, enabled = canMove) {
                        busy = true; error = ""
                        scope.launch {
                            try { repo.moveSessionToProject(session.id, project); sheet = "" }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "无法移动会话。" }
                            finally { busy = false }
                        }
                    }
                }
            }
            if (error.isNotBlank()) DeskText(error, size = 14)
        }
    }
    ModelSheet(model,effort,catalog,state.defaultModel,projection.nativeModel,modelLocked,{sheet=""},onSave={m,e->if(session.id.isEmpty()){draftModel=m;draftEffort=e;draftChosen=true}else{repo.configureModel(session.id,m,e);draftChosen=false}},onLoad=if(online&&!guest&&!readOnly&&active==null&&!catalogBlocked&&querySession!=null)({repo.loadModelCatalog(querySession)})else null,createsSession=session.id.isEmpty()&&serverSessions.isEmpty(),visible=sheet=="model",lockedReason=modelLockReason,catalogLoading=catalogKey in state.modelCatalogLoading,catalogError=state.modelCatalogErrors[catalogKey])
    PermissionSheet(sheet=="permissions"&&clientSession,session,readOnly,reason,busy,error,{sheet=""}){type,fields->
        scope.launch{if(clientSession&&active==null&&send(type,fields)){haptics.confirm();sheet=""}}
    }
    SessionSettingsSheet(sheet=="settings"&&!guest,session,projection,readOnly,reason,busy,error,{sheet=""},{sheet="";search=true},stats=sessionStats){type,fields->scope.launch{if(send(type,fields)&&type=="session.compact"){haptics.confirm();sheet=""}}}
}

/** Permission picker shares the model picker's press feedback, chevron and accessible touch target. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun PermissionPickerEntry(label:String,expanded:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    val c=LocalDeskColors.current
    val inherited=LocalViewConfiguration.current
    val touchConfiguration=remember(inherited){object:ViewConfiguration by inherited{
        override val minimumTouchTargetSize=DpSize(44.dp,44.dp)
    }}
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val highlight=animatedHighlight(pressed,expanded,c.muted)
    CompositionLocalProvider(LocalViewConfiguration provides touchConfiguration){
        Row(modifier.pressScale(pressed,true,DeskMotion.ButtonPressScale).clip(CircleShape).background(highlight).animateContentSize(DeskMotion.layoutSize).clickable(interactionSource=interaction,indication=null,onClick=onClick).semantics{
            contentDescription="选择权限模式，$label"
            role=Role.Button
            stateDescription=if(expanded)"已展开"else "已收起"
        }.height(32.dp).padding(start=10.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            NativeSpanText(label,Modifier.weight(1f,fill=false),size=13,weight=500,lineHeight=19,maxLines=1,selectable=false,exposeText=false)
            DeskChevron(expanded,size=14.dp)
        }
    }
}

internal enum class SendMode{Send,Stop,Busy}

@Composable private fun ChatMenuItem(label:String,icon:String,enabled:Boolean=true,onClick:()->Unit){
    val c=LocalDeskColors.current
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).deskPressable(c.muted,12.dp,enabled=enabled,onClick=onClick).semantics{contentDescription=label;role=Role.Button;if(!enabled)disabled()}.padding(horizontal=24.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
        DeskIcon(icon,size=24.dp)
        DeskText(label,Modifier.weight(1f),size=16,lineHeight=24)
    }
}

/** Send/stop control: the icon cross-scales between send, stop and busy; color and press feedback are continuous. */
@Composable internal fun SendButton(mode:SendMode,enabled:Boolean,stopLabel:Boolean,onClick:()->Unit){
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val background by animateColorAsState(if(enabled)c.primary else c.muted,DeskMotion.state(),label="send background")
    val tint by animateColorAsState(if(enabled)c.onPrimary else c.mutedForeground,DeskMotion.state(),label="send tint")
    Box(Modifier.size(32.dp).pressScale(pressed,enabled,DeskMotion.IconPressScale).background(background,CircleShape).clickable(enabled=enabled,interactionSource=interaction,indication=null,onClick=onClick).semantics{contentDescription=if(stopLabel)"停止运行"else "发送";role=Role.Button},contentAlignment=Alignment.Center){
        AnimatedContent(mode,transitionSpec={(fadeIn(DeskMotion.fadeIn())+scaleIn(DeskMotion.icon(),.5f))togetherWith(fadeOut(DeskMotion.fadeOut())+scaleOut(DeskMotion.icon(),.5f))using SizeTransform(clip=false)},contentAlignment=Alignment.Center,label="send icon"){m->
            when(m){SendMode.Busy->DeskSpinner("正在发送消息");SendMode.Stop->DeskIcon("square",size=16.dp,tint=tint);SendMode.Send->DeskIcon("arrow-up",tint=tint)}
        }
    }
}

/** Floating transcript control; its shadow remains outside the surface during fades. */
@Composable internal fun JumpToLatestButton(visible:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val c=LocalDeskColors.current
    val transition=remember{MutableTransitionState(false)}.apply{targetState=visible}
    DeskFloatingVisibility(transition,modifier){
        val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
        Box(Modifier.size(40.dp).pressScale(pressed,true,DeskMotion.IconPressScale).shadow(3.dp,CircleShape).background(c.card,CircleShape)
            .then(if(visible)Modifier.clickable(interactionSource=interaction,indication=null,onClick=onClick).semantics{contentDescription="回到最新消息";role=Role.Button}
                else BlockPointerInput.clearAndSetSemantics{}),contentAlignment=Alignment.Center){DeskIcon("chevron-down",size=16.dp)}
    }
}

/**
 * Model and reasoning-effort pill. The label truncates instead of pushing adjacent controls;
 * width changes animate. Its touch target grows without changing the visual layout.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun ModelPickerEntry(modelLabel:String,effortLabel:String,expanded:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    val c=LocalDeskColors.current
    val density=LocalDensity.current
    val inherited=LocalViewConfiguration.current
    var measured by remember{mutableStateOf(IntSize.Zero)}
    val touchSize=with(density){DpSize(measured.width.toDp()+6.dp,maxOf(measured.height.toDp()+6.dp,44.dp))}
    val touchConfiguration=remember(inherited,touchSize){object:ViewConfiguration by inherited{
        override val minimumTouchTargetSize=touchSize
    }}
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val highlight=animatedHighlight(pressed,expanded,c.muted)
    CompositionLocalProvider(LocalViewConfiguration provides touchConfiguration) {
        Row(modifier.onSizeChanged{measured=it}.pressScale(pressed,true,DeskMotion.ButtonPressScale).clip(CircleShape).background(highlight).animateContentSize(DeskMotion.layoutSize).clickable(interactionSource=interaction,indication=null,onClick=onClick).semantics {
            contentDescription="选择模型，$modelLabel，思考强度$effortLabel"
            role=Role.Button
            stateDescription=if(expanded)"已展开" else "已收起"
        }.height(32.dp).padding(start=10.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            NativeSpanText(modelLabel,Modifier.weight(1f,fill=false),size=13,weight=500,lineHeight=19,maxLines=1,selectable=false,exposeText=false)
            NativeSpanText("· $effortLabel",size=13,color=c.mutedForeground,lineHeight=19,maxLines=1,selectable=false,exposeText=false)
            DeskChevron(expanded,size=14.dp)
        }
    }
}
