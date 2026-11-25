package dev.ccdesk.nativeapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ccdesk.nativeapp.core.*
import dev.ccdesk.nativeapp.R
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** These tests manipulate the real Android editor and its InputConnection, not a simulated text field. */
@RunWith(AndroidJUnit4::class)
class NativeComposerTest {
    @get:Rule val compose=createComposeRule()
    private val label="Native composer fixture"

    private fun setEditor(initial:String=""){
        compose.setContent{DeskTheme{var value by remember{mutableStateOf(initial)};NativeComposer(value,{value=it},label,true,Modifier.width(300.dp))}}
    }
    private fun nativeEditor():EditText {
        fun find(view:View):EditText? {
            if(view is EditText)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        val activities=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        return activities.firstNotNullOfOrNull{find(it.window.decorView)}?:error("Native editor missing")
    }

    @Test fun lengthLimitTruncatesInsertionWithoutAnInvalidSelection(){
        setEditor()
        compose.onNodeWithContentDescription(label).performTextReplacement("a".repeat(16001))
        compose.runOnIdle{val view=nativeEditor();assertEquals(16000,view.length());assertEquals(16000,view.selectionStart);view.setSelection(15999,16000)}
        compose.onNodeWithContentDescription(label).performTextInput("BBBB")
        compose.runOnIdle{val view=nativeEditor();assertEquals(16000,view.length());assertEquals('B',view.text.last());assertTrue(view.selectionEnd<=view.length())}
    }

    @Test fun tallInputCapsAt144DpAndScrollsToTheCaret(){
        val text=(1..100).joinToString("\n"){"Line $it"}
        setEditor(text)
        compose.waitForIdle()
        compose.runOnIdle{val view=nativeEditor();val maximum=(144*view.resources.displayMetrics.density).toInt();assertEquals(maximum,view.height);assertTrue(view.lineCount>6);view.setSelection(view.length());view.bringPointIntoView(view.length())}
        compose.waitForIdle()
        compose.runOnIdle{val view=nativeEditor();assertTrue("Tall input must scroll internally",view.scrollY>0);assertTrue(view.canScrollVertically(-1));assertEquals(text.length,view.length())}
    }

    @Test fun emptyHintWrapsAtLargeFontScaleWithoutClippingAndKeepsTheHeightCap(){
        val application=InstrumentationRegistry.getInstrumentation().targetContext
        val config=Configuration(application.resources.configuration).apply{fontScale=1.4f}
        val context=ContextThemeWrapper(application.createConfigurationContext(config),R.style.AppTheme)
        val hint=mutableStateOf("给 Claude 发消息…")
        var changes=0
        compose.setContent{
            CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides config){
                DeskTheme{NativeComposer("",{changes++},hint.value,true,Modifier.width(130.dp))}
            }
        }
        compose.waitForIdle()
        compose.runOnIdle{
            val view=nativeEditor()
            val maximum=(144*view.resources.displayMetrics.density).toInt()
            assertTrue(view.text.isEmpty())
            assertEquals(1.4f,view.resources.configuration.fontScale,.001f)
            assertTrue("A narrow large-font hint needs room for at least two native lines",view.height>=view.lineHeight*2)
            assertTrue("Wrapping hints retain the composer height cap",view.height<=maximum)
            hint.value=(1..20).joinToString("\n"){"第 $it 行提示"}
        }
        compose.waitForIdle()
        compose.runOnIdle{
            val view=nativeEditor()
            assertEquals((144*view.resources.displayMetrics.density).toInt(),view.height)
            assertTrue(view.text.isEmpty())
            assertEquals("Changing the placeholder must not edit the draft",0,changes)
        }
    }

    @Test fun unrelatedRecompositionPreservesSelectionAndImeComposingRegion(){
        val generation=mutableIntStateOf(0)
        compose.setContent{DeskTheme{var value by remember{mutableStateOf("first line\nsecond line")};Column{DeskText("Generation ${generation.intValue}");NativeComposer(value,{value=it},label,true,Modifier.width(300.dp))}}}
        var previous:EditText?=null
        compose.runOnIdle{
            val view=nativeEditor();previous=view;view.requestFocus()
            val connection=view.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.setComposingRegion(1,5));view.setSelection(2,4)
            assertEquals(1,BaseInputConnection.getComposingSpanStart(view.text));assertEquals(5,BaseInputConnection.getComposingSpanEnd(view.text))
            generation.intValue++
        }
        compose.waitForIdle()
        compose.runOnIdle{val view=nativeEditor();assertSame(previous,view);assertEquals(2,view.selectionStart);assertEquals(4,view.selectionEnd);assertEquals(1,BaseInputConnection.getComposingSpanStart(view.text));assertEquals(5,BaseInputConnection.getComposingSpanEnd(view.text));view.onCreateInputConnection(EditorInfo())?.finishComposingText()}
    }

    @Test fun hotThemeChangeRefreshesReferenceHintWithoutReplacingEditorOrImeComposition(){
        val application=InstrumentationRegistry.getInstrumentation().targetContext
        fun themed(night:Int):Pair<Configuration,Context>{
            val config=Configuration(application.resources.configuration).apply{
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            return config to ContextThemeWrapper(application.createConfigurationContext(config),R.style.AppTheme)
        }
        val light=themed(Configuration.UI_MODE_NIGHT_NO)
        val dark=themed(Configuration.UI_MODE_NIGHT_YES)
        val useDark=mutableStateOf(false)
        val enabled=mutableStateOf(true)
        val value=mutableStateOf("")
        val changes=CopyOnWriteArrayList<String>()
        compose.setContent{
            val current=if(useDark.value)dark else light
            CompositionLocalProvider(LocalContext provides current.second,LocalConfiguration provides current.first){
                DeskTheme{NativeComposer(value.value,{value.value=it;changes+=it},label,enabled.value,Modifier.width(300.dp))}
            }
        }
        lateinit var original:EditText
        val lightHint=0xff6b6960.toInt()
        val darkHint=0xffb9b6aa.toInt()
        compose.runOnIdle{
            // The measured UI palette must update on the existing native editor.
            original=nativeEditor()
            assertTrue(original.text.isEmpty())
            assertEquals(lightHint,original.currentHintTextColor)
            useDark.value=true
        }
        compose.runOnIdle{
            val view=nativeEditor()
            assertSame("A theme switch must not recreate the editor",original,view)
            assertTrue(view.text.isEmpty())
            assertEquals(darkHint,view.currentHintTextColor)
            view.requestFocus();view.setText("Draft composing text")
            assertTrue(view.onCreateInputConnection(EditorInfo())!!.setComposingRegion(1,7))
            view.setSelection(2,5)
        }
        var editable:android.text.Editable?=null
        var changesBeforeSwitch=0
        compose.runOnIdle{editable=original.text;changesBeforeSwitch=changes.size;useDark.value=false}
        compose.runOnIdle{
            val view=nativeEditor()
            assertSame(original,view);assertSame(editable,view.text)
            assertEquals("Draft composing text",view.text.toString())
            assertEquals(2,view.selectionStart);assertEquals(5,view.selectionEnd)
            assertEquals(1,BaseInputConnection.getComposingSpanStart(view.text))
            assertEquals(7,BaseInputConnection.getComposingSpanEnd(view.text))
            assertEquals("Changing hint colors must not emit text edits",changesBeforeSwitch,changes.size)
            assertEquals(lightHint,view.currentHintTextColor)
            enabled.value=false
        }
        compose.runOnIdle{
            val view=nativeEditor()
            assertFalse(view.isEnabled)
            assertEquals(lightHint,view.currentHintTextColor)
            view.onCreateInputConnection(EditorInfo())?.finishComposingText()
        }
    }

    @Test fun richClipboardPasteRetainsPlainTextOnly(){
        setEditor()
        compose.runOnIdle{
            val view=nativeEditor();val clipboard=view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val original=clipboard.primaryClip
            try{
                view.requestFocus()
                val rich=SpannableString("Pasted native text").apply{setSpan(ForegroundColorSpan(android.graphics.Color.RED),0,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                clipboard.setPrimaryClip(ClipData.newPlainText("Test text",rich))
                assertTrue(view.onTextContextMenuItem(android.R.id.paste))
                assertEquals("Pasted native text",view.text.toString())
                assertTrue(view.text.getSpans(0,view.length(),ForegroundColorSpan::class.java).isEmpty())
            }finally{if(original!=null)clipboard.setPrimaryClip(original)else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip()else clipboard.setPrimaryClip(ClipData.newPlainText("",""))}
        }
    }

    @Test fun readOnlySessionCannotEditOrSendThroughTheProductionUi(){
        val transport=ReadOnlyTransport();val repository=WorkbenchRepository(transport)
        try{
            runBlocking{repository.start()};compose.waitUntil(5_000){repository.state.value.status=="online"};runBlocking{repository.selectSession("read-only")}
            compose.setContent{WorkbenchApp(repository)}
            val editor=compose.onNodeWithContentDescription("只读会话")
            editor.assertIsNotEnabled()
            editor.performSemanticsAction(SemanticsActions.SetText){action->assertFalse(action(AnnotatedString("must not send")))}
            compose.onNodeWithContentDescription("发送").assertIsNotEnabled().performTouchInput{click()}
            compose.runOnIdle{assertFalse(nativeEditor().isEnabled);assertEquals(0,nativeEditor().length());assertTrue(transport.sent.none{it.optString("type")=="message.send"})}
        }finally{compose.runOnIdle{repository.dispose()}}
    }

    /** Local protocol fixture; no sockets, credentials or real service are involved. */
    private class ReadOnlyTransport:WorkbenchTransport {
        val sent=CopyOnWriteArrayList<JSONObject>();private val main=Handler(Looper.getMainLooper())
        private fun session()=json("id" to "read-only","title" to "Offline device fixture","projectPath" to "/fixture","permissionMode" to "default","device" to json("id" to "offline-device","name" to "Unavailable device"),"activeRun" to JSONObject.NULL)
        override suspend fun getEndpoint()="wss://fixture.invalid/ws"
        override suspend fun setEndpoint(endpoint:String){}
        override suspend fun readPreferences(endpoint:String)=Preferences()
        override suspend fun writePreferences(endpoint:String,preferences:Preferences){}
        override suspend fun credential(endpoint:String)= "fixture-service-token-0123456789"
        override suspend fun saveCredential(endpoint:String,credential:String){}
        override suspend fun deviceId()="composer-fixture"
        override suspend fun fingerprint(endpoint:String)=""
        override suspend fun setFingerprint(endpoint:String,fingerprint:String){}
        override suspend fun clearOrigin(endpoint:String){}

        override suspend fun socket(endpoint:String,observer:SocketObserver):SocketConnection {
            fun frame(value:JSONObject){main.post{observer.message(value.toString())}}
            main.post{observer.opened();frame(json("type" to "ready","protocolVersion" to 29,"sessions" to JSONArray().put(session())));frame(json("type" to "devices","devices" to JSONArray().put(json("id" to "server","server" to true,"ready" to true))))}
            return object:SocketConnection{
                override fun send(text:String):Boolean {val request=JSONObject(text);sent+=request;val id=request.optString("requestId");if(id.isNotEmpty()){if(request.optString("type")=="session.subscribe")frame(json("type" to "session.snapshot","requestId" to id,"session" to session(),"mode" to "replace","events" to JSONArray(),"hasEarlier" to false));frame(json("type" to "response","requestId" to id,"ok" to true))};return true}
                override fun close(){}
            }
        }
        override fun close(){}
    }
}
