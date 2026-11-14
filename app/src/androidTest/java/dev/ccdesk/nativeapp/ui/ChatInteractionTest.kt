package dev.ccdesk.nativeapp.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** UI actions exercise real production composables and repository command encoding.
 * The transport is entirely in-memory and never opens a socket or reads credentials. */
@RunWith(AndroidJUnit4::class)
class ChatInteractionTest {
    @get:Rule val compose=createComposeRule()

    @Test fun approvalAllowsTheExactPendingTool(){
        val sent=CopyOnWriteArrayList<Pair<String,JSONObject>>()
        val row=ChatRow("r/t/tool","r","tool").apply{tool=ToolData("t","Read",input="{\"file_path\":\"example.txt\"}",approvalId="approval-42")}
        compose.setContent{DeskTheme{ChatTranscriptRow(row,true,false,false){type,fields->sent+=type to fields}}}
        compose.onNodeWithContentDescription("Read详情").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        compose.onNodeWithText("允许执行").performClick()
        compose.runOnIdle{assertEquals(1,sent.size);assertEquals("approval.reply",sent.single().first);assertEquals("r",sent.single().second.getString("runId"));assertEquals("approval-42",sent.single().second.getString("approvalId"));assertTrue(sent.single().second.getBoolean("allowed"))}
    }

    @Test fun questionRequiresAnswersAndSerializesMultiselectAndFreeText(){
        val sent=CopyOnWriteArrayList<Pair<String,JSONObject>>()
        val input=json("questions" to JSONArray().put(json("question" to "Choose outputs","multiSelect" to true,"options" to JSONArray().put(json("label" to "Log")).put(json("label" to "Summary"))))).toString()
        val row=ChatRow("r/q/tool","r","tool").apply{tool=ToolData("q","AskUserQuestion",input=input,approvalId="question-7")}
        compose.setContent{DeskTheme{ChatTranscriptRow(row,true,false,false){type,fields->sent+=type to fields}}}
        compose.onNodeWithText("提交回答").assertIsNotEnabled()
        compose.onNodeWithText("Log").assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Checkbox)).performClick();compose.onNodeWithText("Summary").performClick()
        compose.onNodeWithText("✓  Log").assertIsOn();compose.onNodeWithText("✓  Summary").assertIsOn()
        compose.onNodeWithContentDescription("自己填写或补充回答").performTextInput("Notes")
        compose.onNodeWithText("提交回答").performClick()
        compose.runOnIdle{val frame=sent.single();assertEquals("approval.reply",frame.first);assertTrue(frame.second.getBoolean("allowed"));assertEquals("question-7",frame.second.getString("approvalId"));assertEquals("Log, Summary, Notes",frame.second.getJSONObject("answers").getString("Choose outputs"))}
    }

    @Test fun modelSelectionClearsAnUnsupportedExplicitEffort(){
        val selected=CopyOnWriteArrayList<Pair<String?,String?>>()
        val catalog=Catalog(listOf(ModelInfo("model-a","Model A",listOf("low")),ModelInfo("model-b","Model B",listOf("high"))),"2026-11-14T00:00:00Z")
        compose.setContent{DeskTheme{ModelSheet("model-a","low",catalog,null,"",false,{},onSave={m,e->selected+=m to e},onLoad=null,createsSession=false)}}
        compose.onNodeWithText("Model B").performClick()
        compose.waitUntil{selected.isNotEmpty()}
        assertEquals("model-b",selected.single().first);assertNull(selected.single().second)
    }

    @Test fun composerSendsTextThenCancelsTheActiveRun(){
        val transport=MemoryTransport();val repository=WorkbenchRepository(transport)
        try {
            runBlocking{repository.start()}
            compose.waitUntil(5_000){repository.state.value.status=="online"}
            runBlocking{repository.selectSession("session-1")}
            compose.setContent{WorkbenchApp(repository)}
            compose.onNodeWithContentDescription("给 Claude 发消息…").performTextInput("native compose message")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(5_000){transport.sent.any{it.optString("type")=="message.send"}}
            val message=transport.sent.single{it.optString("type")=="message.send"}
            assertEquals("session-1",message.getString("sessionId"));assertEquals("native compose message",message.getString("text"));assertTrue(message.getString("requestId").isNotBlank())
            compose.waitForIdle()
            transport.frame(json("type" to "session.updated","session" to transport.session().put("activeRun",json("id" to "run-active","status" to "running","surface" to "chat"))))
            compose.waitUntil(5_000){repository.state.value.sessions.single().activeRun!=null}
            compose.onNodeWithContentDescription("停止运行").performClick()
            compose.waitUntil(5_000){transport.sent.any{it.optString("type")=="run.cancel"}}
            val cancel=transport.sent.single{it.optString("type")=="run.cancel"}
            assertEquals("session-1",cancel.getString("sessionId"));assertEquals("run-active",cancel.getString("runId"))
        } finally {compose.runOnIdle{repository.dispose()}}
    }

    @Test fun longNativeMarkdownHistoryScrollsPastItsFinalMeasuredBlock(){
        val transport=MemoryTransport()
        val longText=(1..120).joinToString("\n"){"Line $it: native text that wraps and increases measured height."}+"\n```kotlin\nval done = true\n```\nFINAL_LINE_SENTINEL"
        fun event(sequence:Int,payload:JSONObject)=json("sessionId" to "session-1","runId" to "long-run","sequence" to sequence,"createdAt" to "2026-11-14T00:00:00Z","payload" to payload)
        transport.events=JSONArray().put(event(1,json("type" to "text.delta","messageId" to "answer","text" to longText))).put(event(2,json("type" to "run.status","status" to "completed")))
        val repository=WorkbenchRepository(transport)
        try {
            runBlocking{repository.start()};compose.waitUntil(5_000){repository.state.value.status=="online"};runBlocking{repository.selectSession("session-1")}
            compose.setContent{WorkbenchApp(repository)}
            compose.waitUntil(10_000){compose.onAllNodesWithText("已完成").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("已完成").assertIsDisplayed()
            compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        } finally {compose.runOnIdle{repository.dispose()}}
    }

    @Test fun markdownImageLoadsThroughNativeDecoderAndKeepsItsDocumentHeight(){
        val packageName=InstrumentationRegistry.getInstrumentation().targetContext.packageName
        compose.setContent{DeskTheme{NativeMarkdown("![Native image fixture](android.resource://$packageName/drawable/splash_icon)")}}
        val image=compose.onNodeWithContentDescription("Native image fixture")
        image.assertHeightIsEqualTo(200.dp)
        compose.waitUntil(5_000){
            val pixels=image.captureToImage().toPixelMap()
            // The bundled orange logo must replace the neutral loading background.
            (0 until pixels.width step 10).any{x->(0 until pixels.height step 10).any{y->val color=pixels[x,y];color.red>.6f&&color.green<.6f&&color.blue<.6f}}
        }
    }

    @Test fun viewportResizeFollowsTheEndButPreservesAReaderWhoScrolledAway(){
        val transport=MemoryTransport()
        val text=(1..100).joinToString("\n"){"Resize fixture line $it: native text with a stable measured width."}
        fun event(sequence:Int,payload:JSONObject)=json("sessionId" to "session-1","runId" to "resize-run","sequence" to sequence,"createdAt" to "2026-11-14T00:00:00Z","payload" to payload)
        transport.events=JSONArray().put(event(1,json("type" to "text.delta","messageId" to "answer","text" to text))).put(event(2,json("type" to "run.status","status" to "completed")))
        val repository=WorkbenchRepository(transport);val availableHeight=mutableIntStateOf(600)
        try{
            runBlocking{repository.start()};compose.waitUntil(5_000){repository.state.value.status=="online"};runBlocking{repository.selectSession("session-1")}
            compose.setContent{Box(Modifier.fillMaxWidth().height(availableHeight.intValue.dp)){WorkbenchApp(repository)}}
            compose.waitUntil(10_000){compose.onAllNodesWithText("已完成").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("已完成").assertIsDisplayed()
            compose.runOnIdle{availableHeight.intValue=500}
            compose.waitForIdle()
            compose.onNodeWithText("已完成").assertIsDisplayed()
            compose.onNodeWithContentDescription("回到最新消息").assertDoesNotExist()

            // A real touch scroll marks the reader as away; a programmatic jump would not test this contract.
            val transcript=compose.onNode(hasScrollToIndexAction())
            transcript.performTouchInput{swipeDown(durationMillis=350)}
            compose.waitForIdle()
            compose.onNodeWithContentDescription("回到最新消息").assertIsDisplayed()
            compose.onNodeWithText("已完成").assertIsNotDisplayed()
            val before=transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.runOnIdle{availableHeight.intValue=440}
            compose.waitForIdle()
            compose.onNodeWithText("已完成").assertIsNotDisplayed()
            compose.onNodeWithContentDescription("回到最新消息").assertIsDisplayed()
            val after=transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals("Resize must retain the reader's scroll anchor",before,after,0.0001f)
        }finally{compose.runOnIdle{repository.dispose()}}
    }

    private class MemoryTransport:WorkbenchTransport {
        val sent=CopyOnWriteArrayList<JSONObject>();var events=JSONArray();private val main=Handler(Looper.getMainLooper());private var observer:SocketObserver?=null
        fun session()=json("id" to "session-1","title" to "UI fixture","projectPath" to "/test","permissionMode" to "default","device" to json("id" to "server","name" to "Fixture"),"activeRun" to JSONObject.NULL)
        fun frame(value:JSONObject){main.post{observer?.message(value.toString())}}
        override suspend fun getEndpoint()="https://fixture.test/"
        override suspend fun setEndpoint(endpoint:String){}
        override suspend fun readPreferences(endpoint:String)=Preferences()
        override suspend fun writePreferences(endpoint:String,preferences:Preferences){}
        override suspend fun credential(endpoint:String)=""
        override suspend fun saveCredential(endpoint:String,credential:String){}
        override suspend fun deviceId()="fixture-device"
        override suspend fun fingerprint(endpoint:String)=""
        override suspend fun setFingerprint(endpoint:String,fingerprint:String){}
        override suspend fun clearOrigin(endpoint:String){}
        override suspend fun clearBrowserSession(endpoint:String){}
        override suspend fun http(endpoint:String,route:String,body:JSONObject?)=json("authenticated" to true,"session" to json("id" to "fixture-browser"))
        override suspend fun socket(endpoint:String,direct:Boolean,observer:SocketObserver):SocketConnection{
            this.observer=observer
            main.post{observer.opened();frame(json("type" to "ready","protocolVersion" to 25,"sessions" to JSONArray().put(session()),"browser" to json("sessionId" to "fixture-browser","serverDeviceId" to "server")))}
            return object:SocketConnection{
                override fun send(text:String):Boolean{val request=JSONObject(text);sent+=request;val id=request.optString("requestId");if(id.isNotEmpty()){if(request.optString("type")=="session.subscribe")frame(json("type" to "session.snapshot","requestId" to id,"session" to session(),"mode" to "replace","events" to events,"hasEarlier" to false));frame(json("type" to "response","requestId" to id,"ok" to true))};return true}
                override fun close(){}
            }
        }
        override fun close(){}
    }
}
