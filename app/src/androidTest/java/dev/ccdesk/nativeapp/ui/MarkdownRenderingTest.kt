package dev.ccdesk.nativeapp.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownRenderingTest {
    @get:Rule val compose=createComposeRule()

    @Test fun softLineBreaksDoNotModifyTildeLongOrStreamingCodeFences(){
        val source="Before\nAfter\n\n~~~kotlin\nval token = \"a  b\"\nprintln(token)\n~~~\n\n````kotlin\nval fence = \"```\"\n````\n\n~~~text\npending value\n"
        compose.setContent{DeskTheme{NativeMarkdown(source)}}
        compose.runOnIdle{
            val actual=ArrayList<String>()
            fun collect(view:View){if(view is TextView)actual+=view.text.toString();if(view is ViewGroup)for(i in 0 until view.childCount)collect(view.getChildAt(i))}
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach{collect(it.window.decorView)}
            assertTrue("Soft line breaks must remain newlines",actual.contains("Before\nAfter"))
            assertTrue("Tilde fences must preserve exact code whitespace",actual.contains("val token = \"a  b\"\nprintln(token)"))
            assertTrue("A shorter fence inside code must remain code",actual.contains("val fence = \"```\""))
            assertTrue("Incomplete streaming fences must remain unmodified",actual.contains("pending value"))
        }
    }

    @Test fun nonSelectableNativeLabelExposesTextWithoutInterceptingParentTouch(){
        var clicks=0
        compose.setContent{DeskTheme{Row(Modifier.clickable{clicks++}){NativeSpanText("Native accessible label",selectable=false)}}}
        compose.onNodeWithText("Native accessible label").assertIsDisplayed().performTouchInput{click()}
        compose.runOnIdle{assertEquals(1,clicks)}
    }
}
