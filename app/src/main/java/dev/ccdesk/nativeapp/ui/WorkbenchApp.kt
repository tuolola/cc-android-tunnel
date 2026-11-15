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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.util.UUID

private val baselineFadeEasing=Easing{if(it<.5f)2*it*it else 1-(-2*it+2)*(-2*it+2)/2}

@Composable fun WorkbenchApp(repository:WorkbenchRepository,requestedRoute:String?=null,onRouteConsumed:()->Unit={}) = DeskTheme {
    val state by repository.state.collectAsState()
    var route by rememberSaveable{mutableStateOf("")}
    var primaryRoute by rememberSaveable{mutableStateOf("")}
    var drawer by rememberSaveable{mutableStateOf(false)}
    val navigate:(String)->Unit={val target=it.trim('/');if(target.isEmpty()||target=="chats")primaryRoute=target;route=target;drawer=false}
    LaunchedEffect(requestedRoute){if(requestedRoute!=null){navigate(requestedRoute);onRouteConsumed()}}
    BackHandler(drawer||route.isNotEmpty()){if(drawer)drawer=false else if(route!=primaryRoute)route=primaryRoute else{primaryRoute="";route=""}}
    val secondary=route.isNotEmpty()&&route!="chats"
    val shownSecondary=rememberLastNonNull(route.takeIf{secondary})?:"settings"
    val chats=primaryRoute=="chats"
    Box(Modifier.fillMaxSize().pointerInput(repository){awaitPointerEventScope{while(true){val event=awaitPointerEvent(PointerEventPass.Initial);if(event.changes.any{it.pressed&&!it.previousPressed})repository.activity()}}}) {
    OverlayHost {
    DrawerSurface(drawer,{drawer=it},drawerContent={DrawerContent(repository,state,route,{drawer=false},navigate)}) {
        Box(Modifier.fillMaxSize().background(LocalDeskColors.current.background)) {
            // The chat stays composed under pushed pages so drafts, scroll position and sheets survive.
            Box(Modifier.fillMaxSize().then(if(chats||secondary)Modifier.clearAndSetSemantics{}else Modifier)){ChatScreen(repository,state,{drawer=true},navigate)}
            AnimatedVisibility(chats,
                enter=slideInHorizontally(DeskMotion.pageOffset){(it*DeskMotion.PageShiftFraction).toInt()}+fadeIn(DeskMotion.fadeIn()),
                exit=slideOutHorizontally(DeskMotion.pageOffset){(it*DeskMotion.PageShiftFraction).toInt()}+fadeOut(DeskMotion.fadeOut()),
                label="sessions page"){
                ChatsScreen(repository,navigate,{navigate("")},{drawer=true},active=chats)
            }
            SecondaryScreen(shownSecondary,repository,navigate,{route=primaryRoute},{navigate("")},visible=secondary)
        }
    }
    }
    }
}

/**
 * Edge-swipe drawer. The finger drives the position directly; release hands the fling
 * velocity to a spring, and a new touch interrupts and reverses an in-flight settle.
 * The scrim only intercepts input while the drawer is (becoming) open.
 */
@Composable internal fun DrawerSurface(open:Boolean,onChange:(Boolean)->Unit,drawerContent:@Composable ()->Unit,content:@Composable ()->Unit) {
    val widthDp=LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth=if(widthDp<640.dp)widthDp else 300.dp
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
            if(!open&&down.position.x>edge)return@awaitEachGesture
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
        Box(Modifier.fillMaxSize().then(if(open)Modifier.clearAndSetSemantics{}else Modifier)){content()}
        val x=(drag?:position.value).coerceIn(-width,0f)
        if(x> -width){
            Box(Modifier.fillMaxSize().graphicsLayer{alpha=((x+width)/width).coerceIn(0f,1f)}.background(Color.Black.copy(alpha=DeskMotion.DrawerScrim))
                .then(if(open)Modifier.pointerInput(Unit){detectTapGestures{onChange(false)}}.semantics{contentDescription="关闭侧栏";onClick{onChange(false);true}}else Modifier))
        }
        // Keep local search/filter/expansion state mounted, as the baseline hidden drawer does.
        Box(Modifier.width(drawerWidth).fillMaxHeight().offset{IntOffset(x.toInt(),0)}.then(if(!open)Modifier.clearAndSetSemantics{}else Modifier)){drawerContent()}
    }
}

@Composable private fun DrawerContent(repo:WorkbenchRepository,state:WorkbenchState,route:String,close:()->Unit,navigate:(String)->Unit) {
    val c=LocalDeskColors.current;val scope=rememberCoroutineScope();val haptics=rememberHaptics()
    var query by remember{mutableStateOf("")};var recents by remember{mutableStateOf(true)};var pins by remember{mutableStateOf(false)};var profile by remember{mutableStateOf(false)};var handoff by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    val secondary=route.isNotEmpty()&&route!="chats"
    fun create(){if(busy)return;if(state.status!="online"){navigate("connection");return};scope.launch{busy=true;try{repo.createSession();navigate("")}catch(e:Exception){error=e.message.orEmpty()}finally{busy=false}}}
    Column(Modifier.fillMaxSize().background(c.sidebar).safeDrawingPadding()) {
        Row(Modifier.height(56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){DeskIconButton("x","收起侧栏",strokeWidth=1.7f,cornerRadius=8.dp,pressedColor=c.accent,onClick=close);androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.app_name),Modifier.weight(1f),color=c.foreground,fontSize=23.sp,lineHeight=32.sp,fontFamily=FontFamily.Serif,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Box{DeskIconButton("settings","打开设置",iconSize=18.dp,tint=c.mutedForeground,strokeWidth=1.6f,cornerRadius=8.dp,pressedColor=c.accent){navigate("settings")};Box(Modifier.align(Alignment.TopEnd).padding(5.dp)){AppUpdateBadge()}}}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom=16.dp)) {
            if(secondary){DrawerItem("返回工作台","arrow-left"){navigate("")};DeskDivider();listOf("settings" to "设置","connection" to "连接工作台","usage" to "账号与用量","access" to "访问管理","notifications" to "通知","help" to "帮助").forEach{(target,label)->DrawerItem(label,mapOf("settings" to "settings","connection" to "link-2","usage" to "circle-user-round","access" to "fingerprint","notifications" to "bell","help" to "book-open")[target],route==target){navigate(target)}}}
            else {
                Row(Modifier.padding(horizontal=8.dp).padding(bottom=6.dp).height(40.dp).background(c.muted,RoundedCornerShape(8.dp)).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    DeskIcon("search",size=18.dp,tint=c.mutedForeground,strokeWidth=1.7f)
                    DeskField(query,{query=it;recents=true},"搜索会话",Modifier.weight(1f).padding(horizontal=4.dp),size=15)
                    AnimatedVisibility(query.isNotEmpty(),enter=fadeIn(DeskMotion.fadeIn())+scaleIn(DeskMotion.icon(),.6f),exit=fadeOut(DeskMotion.fadeOut())+scaleOut(DeskMotion.icon(),.6f)){Box(Modifier.size(28.dp).clip(CircleShape).deskPressable(c.accent,14.dp){query=""}.semantics{contentDescription="清除搜索";role=Role.Button},contentAlignment=Alignment.Center){DeskIcon("x",size=16.dp,tint=c.mutedForeground)}}
                }
                DrawerItem("新建会话","plus",enabled=!busy,loading=busy){create()};DrawerItem("全部会话","message-circle",route=="chats"){navigate("chats")};DrawerItem("通知","bell",badge=state.notices.count{!it.read}){navigate("notifications")}
                Row(Modifier.padding(top=16.dp,start=8.dp,end=8.dp).height(44.dp),verticalAlignment=Alignment.CenterVertically){Row(Modifier.weight(1f).fillMaxHeight().deskPressable(c.accent){recents=!recents}.semantics{role=Role.Button;stateDescription=if(recents)"已展开" else "已收起"}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){DeskText("最近会话",size=13,color=c.mutedForeground);DeskChevron(recents,size=12.dp,collapsed="chevron-right",expandedRotation=90f)};DeskIconButton("list-filter",if(pins)"显示所有最近会话" else "仅显示置顶会话",iconSize=16.dp,tint=c.mutedForeground,strokeWidth=1.7f,cornerRadius=8.dp,pressedColor=c.accent,selected=pins){pins=!pins;recents=true;haptics.toggle(pins)}}
                DeskExpandable(recents){Column{val matches=state.sessions.filter{(!pins||it.id in state.pins)&&it.title.contains(query.trim(),true)}.sortedWith(compareByDescending<Session>{it.id in state.pins}.thenByDescending{it.updatedAt});val recent=if(query.isBlank())matches.take(18)else matches
                    recent.forEach{s->key(s.id){DrawerItem(s.title,if(s.id in state.pins)"pin" else null,state.selectedId==s.id){repo.openSession(s.id);navigate("")}}}
                    if(recent.isEmpty())DeskText(if(query.isNotBlank())"未找到会话" else if(pins)"暂无置顶会话" else "暂无最近会话",Modifier.padding(horizontal=20.dp,vertical=10.dp),14,c.mutedForeground)
                    else Row(Modifier.padding(horizontal=8.dp).fillMaxWidth().heightIn(min=44.dp).deskPressable(c.accent){navigate("chats")}.semantics{role=Role.Button}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){DeskText("查看全部",size=14,color=c.mutedForeground,lineHeight=20);DeskIcon("chevron-right",size=14.dp,tint=c.mutedForeground)}
                }}
            }
            DeskExpandable(error.isNotEmpty()){DeskText(error,Modifier.padding(16.dp),14)}
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        val host=runCatching{URI(state.endpoint).authority}.getOrNull().orEmpty().ifEmpty{"工作台"}
        Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(horizontal=8.dp).deskPressable(c.accent,selected=profile){handoff=false;profile=true}.semantics{contentDescription="工作台菜单";role=Role.Button}.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Box(Modifier.size(28.dp).background(c.foreground,CircleShape),contentAlignment=Alignment.Center){DeskIcon("circle-user-round",size=16.dp,tint=c.background,strokeWidth=1.5f)};DeskText(host,Modifier.weight(1f),15,maxLines=1);DeskChevron(profile,size=16.dp,collapsed="chevron-up")
        }
        // Menu items hand the dimmed window over to the settings panel instead of stacking two scrims.
        WorkspaceMenuSheet(profile,host,state.status=="online",{profile=false},scrimHandoff=handoff){DrawerItem("设置","settings"){handoff=true;profile=false;navigate("settings")};DrawerItem("获取帮助","book-open"){handoff=true;profile=false;navigate("help")};DrawerItem("连接设置","link-2"){handoff=true;profile=false;navigate("connection")}}
    }
}
@Composable private fun DrawerItem(label:String,icon:String?=null,selected:Boolean=false,enabled:Boolean=true,badge:Int=0,loading:Boolean=false,onClick:()->Unit){val c=LocalDeskColors.current;Row(Modifier.padding(horizontal=8.dp).fillMaxWidth().heightIn(min=46.dp).deskPressable(c.accent,selected=selected,enabled=enabled||loading,onClick=onClick).semantics{role=Role.Button;this.selected=selected;if(!enabled)disabled()}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){if(loading)DeskSpinner(label,20.dp)else icon?.let{DeskIcon(it,strokeWidth=1.65f)};NativeSpanText(label,Modifier.weight(1f),size=15,lineHeight=21,maxLines=1,selectable=false);if(badge>0)DeskText("$badge",size=12,color=c.mutedForeground)}}
@Composable fun DeskDivider(){Box(Modifier.padding(horizontal=16.dp,vertical=12.dp).fillMaxWidth().height(1.dp).background(LocalDeskColors.current.border))}
@Composable fun DeskField(value:String,onChange:(String)->Unit,placeholder:String,modifier:Modifier=Modifier,enabled:Boolean=true,size:Int=16,multiline:Boolean=false,maxLength:Int=16000,autoFocus:Boolean=false){val c=LocalDeskColors.current;val focus=remember{FocusRequester()};LaunchedEffect(autoFocus){if(autoFocus)focus.requestFocus()};BasicTextField(value,{if(it.length<=maxLength)onChange(it)},modifier.focusRequester(focus).semantics{contentDescription=placeholder},enabled=enabled,singleLine=!multiline,textStyle=TextStyle(color=c.foreground,fontSize=size.sp,lineHeight=24.sp,platformStyle=PlatformTextStyle(includeFontPadding=true)),cursorBrush=SolidColor(Color(0xff008577)),decorationBox={inner->Box{if(value.isEmpty())DeskText(placeholder,size=size,color=c.mutedForeground,lineHeight=24);inner()}})}

/** Text link used in toolbars and status strips: animated highlight, no default Material indication. */
@Composable private fun DeskLink(label:String,modifier:Modifier=Modifier,icon:String?=null,weight:FontWeight=FontWeight.Normal,color:Color=LocalDeskColors.current.mutedForeground,onClick:()->Unit){
    val c=LocalDeskColors.current
    Row(modifier.clip(RoundedCornerShape(8.dp)).deskPressable(c.muted,8.dp,onClick=onClick).semantics{role=Role.Button}.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        icon?.let{DeskIcon(it,size=16.dp,tint=color)}
        DeskText(label,size=14,color=color,weight=weight)
    }
}

@Composable private fun ChatScreen(repo:WorkbenchRepository,state:WorkbenchState,openDrawer:()->Unit,navigate:(String)->Unit) {
    val c=LocalDeskColors.current;val scope=rememberCoroutineScope();val keyboard=LocalSoftwareKeyboardController.current;val haptics=rememberHaptics()
    val session=state.sessions.find{it.id==state.selectedId}?:Session("",projectPath="服务端工作目录",permissionMode="auto")
    val history=state.histories[session.id];val active=session.activeRun
    val guest=!state.authenticated;val online=state.status=="online";val atServer=session.id.isEmpty()||(session.device!=null&&session.device.id==state.serverDeviceId);val device=state.devices.find{it.id==session.device?.id}
    val readOnly=!online||(!atServer&&device?.ready!=true)||active?.surface=="terminal"
    val reason=when{!online->if(state.status=="connecting")"正在重新连接工作台" else "工作台连接已断开";active?.surface=="terminal"->"会话已在终端打开";!atServer&&device==null->"设备离线";!atServer&&device?.ready!=true->"设备执行通道未就绪";else->""}
    val drafts=remember(state.endpoint,state.authenticated){mutableStateMapOf<String,String>()};val draftKey=session.id
    val input=drafts[draftKey].orEmpty()
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var sheet by remember{mutableStateOf("")};var search by remember(session.id){mutableStateOf(false)};var query by remember(session.id){mutableStateOf("")};var match by remember{mutableIntStateOf(0)}
    var draftModel by remember(state.endpoint,state.authenticated){mutableStateOf<String?>(null)};var draftEffort by remember(state.endpoint,state.authenticated){mutableStateOf<String?>(null)};var draftChosen by remember(state.endpoint,state.authenticated){mutableStateOf(false)}
    val creationLock=remember(state.endpoint,state.authenticated){Mutex()}
    var draftCreationId by remember(state.endpoint,state.authenticated){mutableStateOf<String?>(null)}
    var firstTarget by remember(state.endpoint,state.authenticated){mutableStateOf<String?>(null)}
    var lastAutoReadSession by remember { mutableStateOf<String?>(null) }
    var cancelRequested by remember{mutableStateOf<String?>(null)}
    var composerFocused by remember{mutableStateOf(false)}
    suspend fun ensureSession():String=creationLock.withLock {
        session.id.takeIf{it.isNotEmpty()}?:firstTarget?.takeIf{id->state.sessions.any{it.id==id}}?:run {
            val request=draftCreationId?:UUID.randomUUID().toString().also{draftCreationId=it}
            repo.createSession(requestId=request,onCreated={id->firstTarget=id;draftCreationId=null;drafts[id]=drafts[id]?:drafts[""].orEmpty();drafts[""]=""})
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
    val label=when(active?.waiting){"question"->"等待回答";"background"->"后台任务运行中";else->active?.status?.let{runLabels[it]?:it}.orEmpty()}
    val canContinue=active==null&&projection.status in listOf("failed","cancelled")&&!readOnly
    // Model selection state, shown inside the composer.
    val model=if(draftChosen&&(session.id.isEmpty()||session.id==firstTarget))draftModel else session.model;val effort=if(draftChosen&&(session.id.isEmpty()||session.id==firstTarget))draftEffort else session.effort
    val catalog=state.modelCatalogs[session.device?.id?:state.serverDeviceId?:session.id]
    val serverSessions=state.sessions.filter{state.serverDeviceId!=null&&it.device?.id==state.serverDeviceId}
    val querySession=if(session.id.isEmpty())serverSessions.firstOrNull{it.activeRun==null}?.id else session.id
    val catalogBlocked=session.id.isEmpty()&&serverSessions.isNotEmpty()&&querySession==null
    val modelLabel=modelName(modelForChoice(catalog?.models.orEmpty(),model,state.defaultModel,projection.nativeModel)).ifEmpty{model?:state.defaultModel?:projection.nativeModel.ifEmpty{"默认模型"}}
    val modelLocked=readOnly||active!=null||busy
    val modelLockReason=when{readOnly->"${reason.ifEmpty{"会话为只读"}}，暂时无法更改模型。";active!=null->"正在生成回复，结束后可更改模型和思考强度。";busy->"正在发送，请稍候。";else->""}
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            DeskIconButton("panel-left","打开导航",onClick=openDrawer)
            val title=if(session.id.isNotEmpty()&&(rows.isNotEmpty()||active!=null))session.title else ""
            AnimatedContent(title,Modifier.weight(1f).padding(horizontal=4.dp),transitionSpec={fadeIn(DeskMotion.fadeIn())togetherWith fadeOut(DeskMotion.fadeOut())},contentAlignment=Alignment.CenterStart,label="session title"){t->
                if(t.isNotEmpty())NativeSpanText(t,Modifier.semantics{heading()}.layout{measurable,constraints->val child=measurable.measure(constraints);layout(child.width,child.height+(child.height%2)){child.placeRelative(0,0)}},size=17,lineHeight=null,weight=600,selectable=false,maxLines=1)
            }
            if(guest)DeskLink("连接工作台",icon="link-2"){navigate("connection")}
            else DeskIconButton("settings","会话设置",selected=sheet=="settings"){openSheet("settings")}
        }
        DeskExpandable(search){Row(Modifier.fillMaxWidth().background(c.card).padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){DeskField(query,{query=it;match=0;repo.activity()},"查找对话内容",Modifier.weight(1f).padding(horizontal=4.dp),autoFocus=true);if(word.isNotEmpty())DeskText(if(history?.loading==true)"查找中"else if(hits.isEmpty())"无匹配"else "${match.coerceAtMost(hits.lastIndex)+1}/${hits.size}",size=12,color=c.mutedForeground);DeskIconButton("chevron-up","上一个结果",hits.size>1){match=(match+hits.size-1)%hits.size};DeskIconButton("chevron-down","下一个结果",hits.size>1){match=(match+1)%hits.size};DeskIconButton("x","关闭查找"){search=false;query=""}}}
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
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)){
                if(history?.hasEarlier==true&&online)item(key="earlier"){DeskAction(if(history.loading)"正在加载…"else "加载更早的记录",!history.loading,loading=history.loading){scope.launch{try{repo.loadEarlier(session.id)}catch(e:Exception){error=e.message.orEmpty()}}}}
                // Stable row ids: a streamed chunk updates one row in place; only newly inserted rows fade in.
                itemsIndexed(rows,key={_,r->r.id},contentType={_,r->r.kind}){i,row->ChatTranscriptRow(row,active?.id==row.runId,readOnly||busy,focus==i,modifier=Modifier.animateItem(fadeInSpec=tween(200,easing=baselineFadeEasing),placementSpec=null,fadeOutSpec=tween(150,easing=baselineFadeEasing)),onHeightChange={rowLayoutVersion++},send=command)}
            }
            val welcomeVisibility=remember{MutableTransitionState(false)}.apply{targetState=rows.isEmpty()}
            androidx.compose.animation.AnimatedVisibility(welcomeVisibility,Modifier.padding(top=maxHeight*.16f).fillMaxWidth(),enter=fadeIn(tween(220,easing=baselineFadeEasing)),exit=ExitTransition.None){Row(Modifier.fillMaxWidth().widthIn(max=800.dp).padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.width(30.dp).height(40.dp)){Image(painterResource(R.drawable.splash_icon),null,Modifier.requiredSize(40.dp))}
                androidx.compose.material3.Text(if(history?.loading==true)"正在读取会话…"else if(readOnly&&!guest)"暂无已加载的消息"else "今天想聊点什么？",color=c.foreground,fontSize=25.sp,lineHeight=34.sp,fontFamily=FontFamily.Serif)
            }}
            androidx.compose.animation.AnimatedVisibility(!nearEnd,Modifier.align(Alignment.BottomEnd).padding(end=16.dp,bottom=8.dp),enter=fadeIn(DeskMotion.fadeIn())+scaleIn(DeskMotion.icon(),.7f),exit=fadeOut(DeskMotion.fadeOut())+scaleOut(DeskMotion.icon(),.7f)){
                val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
                Box(Modifier.size(40.dp).pressScale(pressed,true,DeskMotion.IconPressScale).shadow(3.dp,CircleShape).background(c.card,CircleShape).clickable(interactionSource=interaction,indication=null){scope.launch{scrollToEnd(true);following=true}}.semantics{contentDescription="回到最新消息";role=Role.Button},contentAlignment=Alignment.Center){DeskIcon("chevron-down",size=16.dp)}
            }
        }
        Column(Modifier.fillMaxWidth().widthIn(max=800.dp).align(Alignment.CenterHorizontally).padding(start=16.dp,end=16.dp,bottom=10.dp)) {
            val border by animateColorAsState(if(composerFocused&&!readOnly)c.mutedForeground.copy(alpha=.55f)else c.border,DeskMotion.state(),label="composer border")
            Column(Modifier.fillMaxWidth().heightIn(min=88.dp).border(1.dp,border,RoundedCornerShape(14.dp)).background(c.card,RoundedCornerShape(14.dp)).animateContentSize(DeskMotion.layoutSize).padding(1.dp).padding(start=7.dp,end=7.dp,top=10.dp,bottom=7.dp)){
                if(guest)Box(Modifier.fillMaxWidth().heightIn(min=28.dp).clip(RoundedCornerShape(8.dp)).deskPressable(c.muted){navigate("connection")}.padding(horizontal=8.dp),contentAlignment=Alignment.CenterStart){DeskText("连接工作台，开始对话…",size=16,color=c.mutedForeground,lineHeight=24)}
                else NativeComposer(input,{drafts[draftKey]=it;repo.activity()},if(readOnly)"只读会话"else "给 Claude 发消息…",!readOnly,Modifier.fillMaxWidth().heightIn(min=26.dp,max=144.dp),onFocusChange={composerFocused=it})
                // Give each picker its own bounded space so long labels never move the send button.
                Row(Modifier.fillMaxWidth().padding(top=5.dp),horizontalArrangement=Arrangement.spacedBy(6.dp,Alignment.End),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.weight(.44f),contentAlignment=Alignment.CenterStart){
                        PermissionPickerEntry(permissionNames[session.permissionMode]?:session.permissionMode.ifEmpty{"自动"},sheet=="permissions",{openSheet("permissions")})
                    }
                    Box(Modifier.weight(.56f),contentAlignment=Alignment.CenterEnd){
                        ModelPickerEntry(modelLabel,effortNames[effort]?:"默认",sheet=="model",{openSheet("model")})
                    }
                    val stop=input.isBlank()&&active!=null
                    val stopping=stop&&cancelRequested==active?.id
                    val disabled=readOnly||busy||(input.isBlank()&&!stop)||stopping
                    SendButton(if(busy||stopping)SendMode.Busy else if(stop)SendMode.Stop else SendMode.Send,!disabled,stop){
                        if(stop){val run=active!!.id;cancelRequested=run;haptics.tap();scope.launch{if(!send("run.cancel",json("runId" to run)))cancelRequested=null}}
                        else {haptics.confirm();val text=input;scope.launch{if(send("message.send",json("text" to text.trim()))){if(drafts[draftKey]==text)drafts[draftKey]="";val target=repo.state.value.selectedId;if(draftKey.isEmpty()&&target!=null&&drafts[target]==text)drafts[target]=""}}}
                    }
                }
            }
        }
    }
    ModelSheet(model,effort,catalog,state.defaultModel,projection.nativeModel,modelLocked,{sheet=""},onSave={m,e->if(session.id.isEmpty()){draftModel=m;draftEffort=e;draftChosen=true}else{repo.configureModel(session.id,m,e);draftChosen=false}},onLoad=if(online&&!guest&&!readOnly&&active==null&&!catalogBlocked)({repo.loadModelCatalog(querySession?:ensureSession())})else null,createsSession=session.id.isEmpty()&&querySession==null,autoReadAllowed=querySession!=null&&lastAutoReadSession!=querySession,onAutoRead={lastAutoReadSession=querySession},visible=sheet=="model",lockedReason=modelLockReason)
    PermissionSheet(sheet=="permissions",session,readOnly,reason,busy,error,{sheet=""}){type,fields->
        scope.launch{if(active==null&&send(type,fields)){haptics.confirm();sheet=""}}
    }
    SessionSettingsSheet(sheet=="settings"&&!guest,session,projection,readOnly,reason,busy,error,{sheet=""},{sheet="";search=true;repo.activity()}){type,fields->scope.launch{if(send(type,fields)&&type=="session.compact"){haptics.confirm();sheet=""}}}
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

/** Send/stop control: the icon cross-scales between send, stop and busy; color and press feedback are continuous. */
@Composable internal fun SendButton(mode:SendMode,enabled:Boolean,stopLabel:Boolean,onClick:()->Unit){
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val background by animateColorAsState(if(enabled)c.foreground else c.muted,DeskMotion.state(),label="send background")
    val tint by animateColorAsState(if(enabled)c.background else c.mutedForeground,DeskMotion.state(),label="send tint")
    Box(Modifier.size(36.dp).pressScale(pressed,enabled,DeskMotion.IconPressScale).background(background,CircleShape).clickable(enabled=enabled,interactionSource=interaction,indication=null,onClick=onClick).semantics{contentDescription=if(stopLabel)"停止运行"else "发送";role=Role.Button},contentAlignment=Alignment.Center){
        AnimatedContent(mode,transitionSpec={(fadeIn(DeskMotion.fadeIn())+scaleIn(DeskMotion.icon(),.5f))togetherWith(fadeOut(DeskMotion.fadeOut())+scaleOut(DeskMotion.icon(),.5f))using SizeTransform(clip=false)},contentAlignment=Alignment.Center,label="send icon"){m->
            when(m){SendMode.Busy->DeskSpinner("正在发送消息");SendMode.Stop->DeskIcon("square",size=16.dp,tint=tint);SendMode.Send->DeskIcon("arrow-up",tint=tint)}
        }
    }
}

/**
 * Model and reasoning-effort pill inside the composer. The label truncates instead of pushing
 * the send button; width changes animate. The touch target extends to 44dp tall (and 3dp to
 * each side) without changing layout.
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
