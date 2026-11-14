// Copyright 2026 CC Desk Android contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ccdesk.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
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
    Box(Modifier.fillMaxSize().pointerInput(repository){awaitPointerEventScope{while(true){val event=awaitPointerEvent(PointerEventPass.Initial);if(event.changes.any{it.pressed&&!it.previousPressed})repository.activity()}}}) {
    DrawerSurface(drawer,{drawer=it},drawerContent={DrawerContent(repository,state,route,{drawer=false},navigate)}) {
        Box(Modifier.fillMaxSize().background(LocalDeskColors.current.background)) {
            if(primaryRoute=="chats") ChatsScreen(repository,navigate,{navigate("")},{drawer=true})
            else ChatScreen(repository,state,{drawer=true},navigate)
            if(route.isNotEmpty()&&route!="chats") SecondaryScreen(route,repository,navigate,{route=primaryRoute},{navigate("")})
        }
    }
    }
}

@Composable internal fun DrawerSurface(open:Boolean,onChange:(Boolean)->Unit,drawerContent:@Composable ()->Unit,content:@Composable ()->Unit) {
    val widthDp=LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth=if(widthDp<640.dp)widthDp else 300.dp
    val density=LocalDensity.current
    val width=with(density){drawerWidth.toPx()}
    val slop=with(density){7.dp.toPx()};val vertical=with(density){8.dp.toPx()};val edge=with(density){32.dp.toPx()};val travel=with(density){60.dp.toPx()};val speed=with(density){500.dp.toPx()}
    val keyboard=LocalSoftwareKeyboardController.current
    val context=LocalContext.current
    var drag by remember{mutableStateOf<Float?>(null)}
    val position=remember{Animatable(if(open)0f else -width)}
    var settleRequest by remember{mutableIntStateOf(0)}
    LaunchedEffect(open,width,settleRequest){
        drag?.let{position.snapTo(it)}
        drag=null
        position.animateTo(if(open)0f else -width,tween(220,easing=Easing{1f-(1f-it)*(1f-it)*(1f-it)}))
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
                    if(accepted){val vx=tracker.velocity().x;val next=if(kotlin.math.abs(vx)>speed)vx>0 else if(kotlin.math.abs(dx)>travel)dx>0 else open;if(next!=open)lightImpact(context);onChange(next)}
                    break
                }
            }
            } finally {tracker.close();if(accepted)settleRequest++}
        }
    }) {
        Box(Modifier.fillMaxSize().then(if(open)Modifier.clearAndSetSemantics{}else Modifier)){content()}
        val x=drag?:position.value
        if(x> -width){
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=((x+width)/width*.35f).coerceIn(0f,.35f))).clickable{onChange(false)})
        }
        // Keep local search/filter/expansion state mounted, as the baseline hidden drawer does.
        Box(Modifier.width(drawerWidth).fillMaxHeight().offset{IntOffset(x.toInt(),0)}.then(if(!open)Modifier.clearAndSetSemantics{}else Modifier)){drawerContent()}
    }
}

/** Android waveform used by the baseline Expo light-impact implementation. */
@Suppress("DEPRECATION") private fun lightImpact(context:android.content.Context){
    val vibrator=if(android.os.Build.VERSION.SDK_INT>=31)(context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator else context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    if(vibrator?.hasVibrator()!=true)return
    if(android.os.Build.VERSION.SDK_INT>=26)vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0,50),intArrayOf(0,30),-1))else vibrator.vibrate(longArrayOf(0,20),-1)
}

@Composable private fun DrawerContent(repo:WorkbenchRepository,state:WorkbenchState,route:String,close:()->Unit,navigate:(String)->Unit) {
    val c=LocalDeskColors.current;val scope=rememberCoroutineScope()
    var query by remember{mutableStateOf("")};var recents by remember{mutableStateOf(true)};var pins by remember{mutableStateOf(false)};var profile by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    val secondary=route.isNotEmpty()&&route!="chats"
    fun create(){if(state.status!="online"){navigate("connection");return};scope.launch{busy=true;try{repo.createSession();navigate("")}catch(e:Exception){error=e.message.orEmpty()}finally{busy=false}}}
    Column(Modifier.fillMaxSize().background(c.sidebar).safeDrawingPadding()) {
        Row(Modifier.height(56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){DeskIconButton("x","收起侧栏",strokeWidth=1.7f,cornerRadius=8.dp,pressedColor=c.accent,onClick=close);androidx.compose.material3.Text("CC Desk",Modifier.weight(1f),color=c.foreground,fontSize=23.sp,lineHeight=32.sp,fontFamily=FontFamily.Serif);DeskIconButton("settings","打开设置",iconSize=18.dp,tint=c.mutedForeground,strokeWidth=1.6f,cornerRadius=8.dp,pressedColor=c.accent){navigate("settings")}}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom=16.dp)) {
            if(secondary){DrawerItem("返回工作台","arrow-left"){navigate("")};DeskDivider();listOf("settings" to "设置","connection" to "连接工作台","usage" to "账号与用量","access" to "访问管理","notifications" to "通知","help" to "帮助").forEach{(target,label)->DrawerItem(label,mapOf("settings" to "settings","connection" to "link-2","usage" to "circle-user-round","access" to "fingerprint","notifications" to "bell","help" to "book-open")[target],route==target){navigate(target)}}}
            else {
                Row(Modifier.padding(horizontal=8.dp).padding(bottom=6.dp).height(40.dp).background(c.muted,RoundedCornerShape(8.dp)).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    DeskIcon("search",size=18.dp,tint=c.mutedForeground,strokeWidth=1.7f)
                    DeskField(query,{query=it;recents=true},"搜索会话",Modifier.weight(1f).padding(horizontal=4.dp),size=15)
                    if(query.isNotEmpty())Box(Modifier.clickable{query=""}){DeskIcon("x",size=16.dp,tint=c.mutedForeground)}
                }
                DrawerItem("新建会话","plus",enabled=!busy){create()};DrawerItem("全部会话","message-circle",route=="chats"){navigate("chats")};DrawerItem("通知","bell",badge=state.notices.count{!it.read}){navigate("notifications")}
                Row(Modifier.padding(top=16.dp,start=8.dp,end=8.dp).height(44.dp),verticalAlignment=Alignment.CenterVertically){Row(Modifier.weight(1f).fillMaxHeight().deskPressable(c.accent){recents=!recents}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){DeskText("最近会话",size=13,color=c.mutedForeground);DeskIcon(if(recents)"chevron-down" else "chevron-right",size=12.dp,tint=c.mutedForeground)};DeskIconButton("list-filter",if(pins)"显示所有最近会话" else "仅显示置顶会话",iconSize=16.dp,tint=c.mutedForeground,strokeWidth=1.7f,cornerRadius=8.dp,pressedColor=c.accent,selected=pins){pins=!pins;recents=true}}
                if(recents){val matches=state.sessions.filter{(!pins||it.id in state.pins)&&it.title.contains(query.trim(),true)}.sortedWith(compareByDescending<Session>{it.id in state.pins}.thenByDescending{it.updatedAt});val recent=if(query.isBlank())matches.take(18)else matches
                    recent.forEach{s->DrawerItem(s.title,if(s.id in state.pins)"pin" else null,state.selectedId==s.id){navigate("");scope.launch{try{repo.selectSession(s.id)}catch(e:Exception){error=e.message.orEmpty()}}}}
                    if(recent.isEmpty())DeskText(if(query.isNotBlank())"未找到会话" else if(pins)"暂无置顶会话" else "暂无最近会话",Modifier.padding(horizontal=20.dp,vertical=10.dp),14,c.mutedForeground)
                    else Row(Modifier.padding(horizontal=8.dp).fillMaxWidth().heightIn(min=44.dp).deskPressable(c.accent){navigate("chats")}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){DeskText("查看全部",size=14,color=c.mutedForeground,lineHeight=20);DeskIcon("chevron-right",size=14.dp,tint=c.mutedForeground)}
                }
            }
            if(error.isNotEmpty())DeskText(error,Modifier.padding(16.dp),14)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        val host=runCatching{URI(state.endpoint).authority}.getOrNull().orEmpty().ifEmpty{"工作台"}
        Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(horizontal=8.dp).deskPressable(c.accent){profile=true}.semantics{contentDescription="工作台菜单";role=Role.Button}.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Box(Modifier.size(28.dp).background(c.foreground,CircleShape),contentAlignment=Alignment.Center){DeskIcon("circle-user-round",size=16.dp,tint=c.background,strokeWidth=1.5f)};DeskText(host,Modifier.weight(1f),15,maxLines=1);DeskIcon("chevron-up",size=16.dp,tint=c.mutedForeground)
        }
        if(profile)WorkspaceMenuSheet(host,state.status=="online",{profile=false}){DrawerItem("设置","settings"){profile=false;navigate("settings")};DrawerItem("获取帮助","book-open"){profile=false;navigate("help")};DrawerItem("连接设置","link-2"){profile=false;navigate("connection")}}
    }
}
@Composable private fun DrawerItem(label:String,icon:String?=null,selected:Boolean=false,enabled:Boolean=true,badge:Int=0,onClick:()->Unit){val c=LocalDeskColors.current;Row(Modifier.padding(horizontal=8.dp).fillMaxWidth().heightIn(min=46.dp).deskPressable(c.accent,selected=selected,enabled=enabled,onClick=onClick).semantics{role=Role.Button;this.selected=selected}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){icon?.let{DeskIcon(it,strokeWidth=1.65f)};NativeSpanText(label,Modifier.weight(1f),size=15,lineHeight=21,maxLines=1,selectable=false);if(badge>0)DeskText("$badge",size=12,color=c.mutedForeground)}}
@Composable fun DeskDivider(){Box(Modifier.padding(horizontal=16.dp,vertical=12.dp).fillMaxWidth().height(1.dp).background(LocalDeskColors.current.border))}
@Composable fun DeskField(value:String,onChange:(String)->Unit,placeholder:String,modifier:Modifier=Modifier,enabled:Boolean=true,size:Int=16,multiline:Boolean=false,maxLength:Int=16000,autoFocus:Boolean=false){val c=LocalDeskColors.current;val focus=remember{FocusRequester()};LaunchedEffect(autoFocus){if(autoFocus)focus.requestFocus()};BasicTextField(value,{if(it.length<=maxLength)onChange(it)},modifier.focusRequester(focus).semantics{contentDescription=placeholder},enabled=enabled,singleLine=!multiline,textStyle=TextStyle(color=c.foreground,fontSize=size.sp,lineHeight=24.sp,platformStyle=PlatformTextStyle(includeFontPadding=true)),cursorBrush=SolidColor(Color(0xff008577)),decorationBox={inner->Box{if(value.isEmpty())DeskText(placeholder,size=size,color=c.mutedForeground,lineHeight=24);inner()}})}

@Composable private fun ChatScreen(repo:WorkbenchRepository,state:WorkbenchState,openDrawer:()->Unit,navigate:(String)->Unit) {
    val c=LocalDeskColors.current;val scope=rememberCoroutineScope();val keyboard=LocalSoftwareKeyboardController.current
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
    suspend fun ensureSession():String=creationLock.withLock {
        session.id.takeIf{it.isNotEmpty()}?:firstTarget?.takeIf{id->state.sessions.any{it.id==id}}?:run {
            val request=draftCreationId?:UUID.randomUUID().toString().also{draftCreationId=it}
            repo.createSession(requestId=request,onCreated={id->firstTarget=id;draftCreationId=null;drafts[id]=drafts[id]?:drafts[""].orEmpty();drafts[""]=""})
        }
    }
    LaunchedEffect(session.id){if(session.id.isNotEmpty()&&session.id!=firstTarget){draftChosen=false;draftModel=null;draftEffort=null;error="";sheet=""}}
    val projection=remember(session.id){TranscriptProjection()}
    LaunchedEffect(history,active?.id){projection.update(history?.events.orEmpty(),history?.fragments.orEmpty(),active?.id)}
    val list=rememberLazyListState();var initial by remember(session.id){mutableStateOf(true)}
    val endThreshold=with(LocalDensity.current){60.dp.toPx()}
    val nearEnd by remember(list,endThreshold){derivedStateOf{val info=list.layoutInfo;val end=info.visibleItemsInfo.lastOrNull();end==null||(end.index>=info.totalItemsCount-1&&end.offset+end.size-info.viewportEndOffset<endThreshold)}}
    var following by remember(session.id){mutableStateOf(true)}
    var rowLayoutVersion by remember(session.id){mutableIntStateOf(0)}
    suspend fun scrollToEnd(animated:Boolean=false){
        val target=list.layoutInfo.totalItemsCount-1
        if(target<0)return
        if(animated)list.animateScrollToItem(target)else list.scrollToItem(target)
        withFrameNanos{}
        val info=list.layoutInfo;val item=info.visibleItemsInfo.lastOrNull{it.index==target}
        if(item!=null)list.scrollBy((item.offset+item.size-info.viewportEndOffset).coerceAtLeast(0).toFloat())
        following=true
    }
    LaunchedEffect(list){snapshotFlow{Triple(list.isScrollInProgress,list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)}.collect{if(it.first)following=nearEnd}}
    // Match maintainScrollAtEnd.onLayout for IME and composer-height changes, without moving a reader who scrolled away.
    LaunchedEffect(list){snapshotFlow{list.layoutInfo.viewportSize.height}.collect{if(it>0&&following)scrollToEnd()}}
    LaunchedEffect(rowLayoutVersion){if(following)scrollToEnd()}
    val rows=projection.rows
    val word=if(search)query.trim().lowercase()else "";val hits=if(word.isEmpty())emptyList()else rows.indices.filter{rows[it].searchable().lowercase().contains(word)};val focus=hits.getOrNull(match.coerceAtMost((hits.size-1).coerceAtLeast(0)))
    var searchedPage by remember(session.id){mutableStateOf("")}
    LaunchedEffect(rows.size,history?.fragments,history?.events?.size){if(rows.isNotEmpty()&&(initial||following)){scrollToEnd();initial=false}}
    LaunchedEffect(focus){if(focus!=null){following=false;list.animateScrollToItem(focus+(if(history?.hasEarlier==true&&online)1 else 0),scrollOffset=-(list.layoutInfo.viewportSize.height*.3f).toInt())}}
    LaunchedEffect(word,history?.events?.firstOrNull()?.sequence,history?.loading){val cursor="$word/${history?.events?.firstOrNull()?.sequence}";if(word.isNotEmpty()&&hits.isEmpty()&&online&&history?.hasEarlier==true&&!history.loading&&searchedPage!=cursor){delay(280);searchedPage=cursor;try{repo.loadEarlier(session.id)}catch(e:Exception){error=e.message.orEmpty()}}}
    suspend fun send(type:String,fields:JSONObject=JSONObject()):Boolean {if(readOnly||busy)return false;busy=true;error="";return try {val id=ensureSession();if(type=="message.send"&&draftChosen&&(session.id.isEmpty()||id==firstTarget)){repo.configureModel(id,draftModel,draftEffort);draftChosen=false};fields.put("sessionId",id);repo.command(type,fields);true}catch(e:Exception){error=e.message.orEmpty();false}finally{busy=false}}
    val command:(String,JSONObject)->Unit={type,fields->scope.launch{send(type,fields)}}
    val label=when(active?.waiting){"question"->"等待回答";"background"->"后台任务运行中";else->active?.status?.let{runLabels[it]?:it}.orEmpty()}
    val canContinue=active==null&&projection.status in listOf("failed","cancelled")&&!readOnly
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.height(48.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){DeskIconButton("panel-left","打开导航",onClick=openDrawer);if(session.id.isNotEmpty()&&(rows.isNotEmpty()||active!=null))Row(Modifier.weight(1f).height(44.dp).clickable{sheet="options"}.padding(horizontal=4.dp).semantics{contentDescription="查看会话选项";role=Role.Button},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){NativeSpanText(session.title,Modifier.weight(1f,fill=false).layout{measurable,constraints->val child=measurable.measure(constraints);layout(child.width,child.height+(child.height%2)){child.placeRelative(0,0)}},size=17,lineHeight=null,weight=600,selectable=false,maxLines=1);DeskIcon("chevron-down",size=16.dp,tint=c.mutedForeground)}else Spacer(Modifier.weight(1f));if(guest)Row(Modifier.height(44.dp).clickable{navigate("connection")}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){DeskIcon("link-2",size=16.dp,tint=c.mutedForeground);DeskText("连接工作台",size=14,color=c.mutedForeground)}else DeskIconButton("square-pen","新建会话",online&&!busy){scope.launch{busy=true;try{repo.createSession()}catch(e:Exception){error=e.message.orEmpty()}finally{busy=false}}}}
        if(search)Row(Modifier.fillMaxWidth().background(c.card).padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){DeskField(query,{query=it;match=0;repo.activity()},"查找对话内容",Modifier.weight(1f).padding(horizontal=4.dp),autoFocus=true);if(word.isNotEmpty())DeskText(if(history?.loading==true)"查找中"else if(hits.isEmpty())"无匹配"else "${match.coerceAtMost(hits.lastIndex)+1}/${hits.size}",size=12,color=c.mutedForeground);DeskIconButton("chevron-up","上一个结果",hits.size>1){match=(match+hits.size-1)%hits.size};DeskIconButton("chevron-down","下一个结果",hits.size>1){match=(match+1)%hits.size};DeskIconButton("x","关闭查找"){search=false;query=""}}
        if(search&&word.isNotEmpty()&&hits.isNotEmpty()&&history?.hasEarlier==true&&online)Box(Modifier.fillMaxWidth().background(c.card).padding(horizontal=12.dp)){DeskAction("查找更早记录",!history.loading){scope.launch{try{repo.loadEarlier(session.id)}catch(e:Exception){error=e.message.orEmpty()}}}}
        if(!guest&&(reason.isNotEmpty()||label.isNotEmpty()||canContinue))Row(Modifier.fillMaxWidth().background(c.muted).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){DeskText(if(reason.isNotEmpty())"$reason · 只读"else label.ifEmpty{"上一轮已结束"},Modifier.weight(1f),12,c.mutedForeground);if(!online)Box(Modifier.clickable{navigate("connection")}){DeskText("连接设置",size=14,weight=FontWeight.SemiBold)};if(canContinue)DeskAction("继续上一轮",!busy){command("run.continue",JSONObject())};if(projection.tasks.isNotEmpty())Box(Modifier.clickable{sheet="options"}){DeskText("后台任务 ${projection.tasks.size}",size=14)}}
        val failure=error.ifEmpty{state.error?:history?.loadError.orEmpty()};if(failure.isNotEmpty())SelectionContainer{DeskText(failure,Modifier.padding(horizontal=16.dp,vertical=8.dp),14,if(c.dark)Color(0xffff6467)else Color(0xffe7000b))}
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)){
                if(history?.hasEarlier==true&&online)item(key="earlier"){DeskAction(if(history.loading)"正在加载…"else "加载更早的记录",!history.loading){scope.launch{try{repo.loadEarlier(session.id)}catch(e:Exception){error=e.message.orEmpty()}}}}
                itemsIndexed(rows,key={_,r->r.id},contentType={_,r->r.kind}){i,row->ChatTranscriptRow(row,active?.id==row.runId,readOnly||busy,focus==i,modifier=if(row.kind=="user"||row.kind=="reply")Modifier.animateItem(fadeInSpec=tween(200,easing=baselineFadeEasing),placementSpec=null,fadeOutSpec=tween(150,easing=baselineFadeEasing))else Modifier,onHeightChange={rowLayoutVersion++},send=command)}
            }
            val welcomeVisibility=remember{MutableTransitionState(false)}.apply{targetState=rows.isEmpty()}
            androidx.compose.animation.AnimatedVisibility(welcomeVisibility,Modifier.padding(top=maxHeight*.16f).fillMaxWidth(),enter=fadeIn(tween(220,easing=baselineFadeEasing)),exit=ExitTransition.None){Row(Modifier.fillMaxWidth().widthIn(max=800.dp).padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.width(30.dp).height(40.dp)){Image(painterResource(R.drawable.splash_icon),null,Modifier.requiredSize(40.dp))}
                androidx.compose.material3.Text(if(history?.loading==true)"正在读取会话…"else if(readOnly&&!guest)"暂无已加载的消息"else "今天想聊点什么？",color=c.foreground,fontSize=25.sp,lineHeight=34.sp,fontFamily=FontFamily.Serif)
            }}
            androidx.compose.animation.AnimatedVisibility(!nearEnd,Modifier.align(Alignment.BottomEnd).padding(end=16.dp,bottom=8.dp),enter=fadeIn(tween(300,easing=baselineFadeEasing)),exit=fadeOut(tween(300,easing=baselineFadeEasing))){Box(Modifier.size(40.dp).shadow(3.dp,CircleShape).background(c.card,CircleShape).clickable{scope.launch{scrollToEnd(true);following=true}}.semantics{contentDescription="回到最新消息"},contentAlignment=Alignment.Center){DeskIcon("chevron-down",size=16.dp)}}
        }
        Column(Modifier.fillMaxWidth().widthIn(max=800.dp).align(Alignment.CenterHorizontally).padding(horizontal=16.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(min=88.dp).border(1.dp,c.border,RoundedCornerShape(14.dp)).background(c.card,RoundedCornerShape(14.dp)).padding(1.dp).padding(start=7.dp,end=7.dp,top=10.dp,bottom=7.dp)){
                if(guest)Box(Modifier.fillMaxWidth().heightIn(min=28.dp).clickable{navigate("connection")}.padding(horizontal=8.dp),contentAlignment=Alignment.CenterStart){DeskText("连接工作台，开始对话…",size=16,color=c.mutedForeground,lineHeight=24)}
                else NativeComposer(input,{drafts[draftKey]=it;repo.activity()},if(readOnly)"只读会话"else "给 Claude 发消息…",!readOnly,Modifier.fillMaxWidth().heightIn(min=26.dp,max=144.dp))
                Row(Modifier.fillMaxWidth().padding(top=5.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(36.dp).clickable{if(guest)navigate("connection")else sheet="options"}.semantics{contentDescription="会话选项"},contentAlignment=Alignment.Center){DeskIcon("plus")};val stop=input.isBlank()&&active!=null;val disabled=readOnly||busy||(input.isBlank()&&!stop)
                    Box(Modifier.size(36.dp).background(if(disabled)c.muted else c.foreground,CircleShape).clickable(enabled=!disabled){if(stop)command("run.cancel",json("runId" to active!!.id))else {val text=input;scope.launch{if(send("message.send",json("text" to text.trim()))){if(drafts[draftKey]==text)drafts[draftKey]="";val target=repo.state.value.selectedId;if(draftKey.isEmpty()&&target!=null&&drafts[target]==text)drafts[target]=""}}}}.semantics{contentDescription=if(stop)"停止运行"else "发送"},contentAlignment=Alignment.Center){if(busy)DeskSpinner("正在发送消息")else DeskIcon(if(stop)"square"else "arrow-up",tint=if(disabled)c.mutedForeground else c.background)}
                }
            }
            val model=if(draftChosen&&(session.id.isEmpty()||session.id==firstTarget))draftModel else session.model;val effort=if(draftChosen&&(session.id.isEmpty()||session.id==firstTarget))draftEffort else session.effort
            val catalog=state.modelCatalogs[session.device?.id?:state.serverDeviceId?:session.id]
            val serverSessions=state.sessions.filter{state.serverDeviceId!=null&&it.device?.id==state.serverDeviceId}
            val querySession=if(session.id.isEmpty())serverSessions.firstOrNull{it.activeRun==null}?.id else session.id
            val catalogBlocked=session.id.isEmpty()&&serverSessions.isNotEmpty()&&querySession==null
            val modelLabel=modelName(modelForChoice(catalog?.models.orEmpty(),model,state.defaultModel,projection.nativeModel)).ifEmpty{model?:state.defaultModel?:projection.nativeModel.ifEmpty{"默认模型"}}
            BoxWithConstraints(Modifier.height(40.dp).fillMaxWidth()) {
                val modelMaximum=maxWidth*.76f
                Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).clickable{if(guest)navigate("connection")else sheet="project"}.semantics{contentDescription="查看项目，项目";role=Role.Button}.padding(start=4.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        NativeSpanText("项目",Modifier.weight(1f,fill=false),size=13,color=c.mutedForeground,lineHeight=19,selectable=false,maxLines=1,exposeText=false)
                        DeskIcon("chevron-down",size=14.dp,tint=c.mutedForeground)
                    }
                    ModelPickerEntry(modelLabel,effortNames[effort]?:"默认",sheet=="model",{keyboard?.hide();sheet="model"},Modifier.widthIn(max=modelMaximum))
                }
            }
            if(sheet=="model")ModelSheet(model,effort,catalog,state.defaultModel,projection.nativeModel,readOnly||active!=null||busy,{sheet=""},onSave={m,e->if(session.id.isEmpty()){draftModel=m;draftEffort=e;draftChosen=true}else{repo.configureModel(session.id,m,e);draftChosen=false}},onLoad=if(online&&!guest&&!readOnly&&active==null&&!catalogBlocked)({repo.loadModelCatalog(querySession?:ensureSession())})else null,createsSession=session.id.isEmpty()&&querySession==null,autoReadAllowed=querySession!=null&&lastAutoReadSession!=querySession,onAutoRead={lastAutoReadSession=querySession})
        }
    }
    if(sheet=="project")DeskSheet("项目",{sheet=""}){Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){DeskIcon("folder",tint=c.mutedForeground);Column{DeskText(session.device?.name?:"服务端工作区",weight=FontWeight.Medium);SelectionContainer{DeskText(session.projectPath,size=14,color=c.mutedForeground)}}};DeskAction("查看全部会话",modifier=Modifier.fillMaxWidth()){sheet="";navigate("chats")}}
    if(sheet=="options")SessionOptionsSheet(session,projection,readOnly,busy,error,{sheet=""},{sheet="";search=true;repo.activity()}){type,fields->scope.launch{if(send(type,fields)&&type in listOf("session.configure","session.compact"))sheet=""}}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun ModelPickerEntry(modelLabel:String,effortLabel:String,expanded:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    val c=LocalDeskColors.current
    val density=LocalDensity.current
    val inherited=LocalViewConfiguration.current
    var measured by remember{mutableStateOf(IntSize.Zero)}
    // RN hitSlop=3 expands the measured pressable; it does not enlarge its layout.
    val touchSize=with(density){DpSize(measured.width.toDp()+6.dp,measured.height.toDp()+6.dp)}
    val touchConfiguration=remember(inherited,touchSize){object:ViewConfiguration by inherited{
        override val minimumTouchTargetSize=touchSize
    }}
    CompositionLocalProvider(LocalViewConfiguration provides touchConfiguration) {
        Row(modifier.onSizeChanged{measured=it}.deskPressable(c.muted,999.dp,onClick=onClick).semantics {
            contentDescription="选择模型，$modelLabel，思考强度$effortLabel"
            role=Role.Button
            stateDescription=if(expanded)"已展开" else "已收起"
        }.padding(end=4.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            NativeSpanText(modelLabel,Modifier.weight(1f,fill=false),size=13,weight=500,lineHeight=19,maxLines=1,selectable=false,exposeText=false)
            NativeSpanText("· $effortLabel",size=13,color=c.mutedForeground,lineHeight=19,selectable=false,exposeText=false)
            DeskIcon("chevron-down",size=14.dp,tint=c.mutedForeground)
        }
    }
}
