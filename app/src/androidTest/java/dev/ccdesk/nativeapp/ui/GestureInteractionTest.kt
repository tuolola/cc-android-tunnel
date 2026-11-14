package dev.ccdesk.nativeapp.ui

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GestureInteractionTest {
    @get:Rule val compose=createComposeRule()

    @Test fun sparseAndroidFlickHasVelocityButSlowShortDragDoesNot() {
        PlatformPanVelocity().use{tracker->
            tracker.add(1000,Offset(40f,900f),MotionEvent.ACTION_DOWN)
            tracker.add(1040,Offset(200f,900f),MotionEvent.ACTION_MOVE)
            tracker.add(1041,Offset(200f,900f),MotionEvent.ACTION_UP)
            assertTrue("A sparse 40ms flick must exceed the 500dp/s threshold at density4",tracker.velocity().x>2000f)
        }
        PlatformPanVelocity().use{tracker->
            tracker.add(2000,Offset(40f,900f),MotionEvent.ACTION_DOWN)
            for(step in 1..20)tracker.add(2000L+step*40,Offset(40f+step*7,900f),MotionEvent.ACTION_MOVE)
            tracker.add(2801,Offset(180f,900f),MotionEvent.ACTION_UP)
            assertTrue(kotlin.math.abs(tracker.velocity().x)<2000f)
        }
    }

    @Test fun modelSheetKeepsTheLastMoveVelocityOnRelease() {
        val velocity=LastMoveVelocity(1000,Offset.Zero)
        velocity.add(1020,Offset(0f,20f),true)
        velocity.add(1040,Offset(0f,100f),true)
        assertEquals(4000f,velocity.velocity.y,.01f)
        velocity.add(1100,Offset(0f,100f),false)
        assertEquals(4000f,velocity.velocity.y,.01f)
    }

    @Test fun cancelledDrawerDragReturnsToItsStartingStateWithoutDispatchingAnAction() {
        val changes=mutableListOf<Boolean>()
        compose.setContent{
            Box(Modifier.fillMaxWidth().height(100.dp).testTag("cancel-surface")){
                DrawerSurface(false,{changes+=it},drawerContent={Box(Modifier.fillMaxSize().background(Color.Red))}){
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
        compose.onNodeWithTag("cancel-surface").performTouchInput{
            down(Offset(40f,80f));moveTo(Offset(400f,80f),delayMillis=100);cancel()
        }
        compose.waitForIdle()
        assertTrue("Cancellation must not dispatch open or trigger its haptic action",changes.isEmpty())
        val image=compose.onNodeWithTag("cancel-surface").captureToImage().toPixelMap()
        assertEquals(1f,image[0,image.height/2].green,.001f)
    }

    @Test fun dragDuringOpeningStartsAtActivationPositionWithoutJumpingBack() {
        val open=mutableStateOf(false)
        compose.mainClock.autoAdvance=false
        compose.setContent{
            Box(Modifier.fillMaxWidth().height(100.dp).testTag("interrupt-surface")){
                DrawerSurface(open.value,{open.value=it},drawerContent={Box(Modifier.fillMaxSize().background(Color.Red))}){Box(Modifier.fillMaxSize().background(Color.White))}
            }
        }
        fun edge():Int {
            val image=compose.onNodeWithTag("interrupt-surface").captureToImage().toPixelMap()
            return (0 until image.width).firstOrNull{image[it,image.height/2].green>.1f}?:image.width
        }
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle{open.value=true}
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeBy(40)
        compose.onNodeWithTag("interrupt-surface").performTouchInput{down(Offset(100f,80f))}
        compose.mainClock.advanceTimeBy(64)
        val atActivation=edge()
        compose.onNodeWithTag("interrupt-surface").performTouchInput{moveTo(Offset(20f,80f),delayMillis=0)}
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Drag must start at the current animation position",(atActivation-80).toFloat(),edge().toFloat(),2f)
        compose.onNodeWithTag("interrupt-surface").performTouchInput{cancel()}
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle{assertTrue(open.value)}
    }

    @Test fun drawerUses220msCubicOutForOpeningAndClosing() {
        val open=mutableStateOf(false)
        compose.mainClock.autoAdvance=false
        compose.setContent{
            Box(Modifier.fillMaxWidth().height(40.dp).testTag("motion-surface")){
                DrawerSurface(open.value,{open.value=it},drawerContent={Box(Modifier.fillMaxSize().background(Color.Red))}){
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
        fun fraction():Float {
            val pixels=compose.onNodeWithTag("motion-surface").captureToImage().toPixelMap()
            val y=pixels.height/2
            val edge=(0 until pixels.width).firstOrNull{pixels[it,y].green>.1f}?:pixels.width
            return edge.toFloat()/pixels.width
        }
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame()
        assertEquals(0f,fraction(),.001f)
        compose.runOnIdle{open.value=true}
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        // 1-(1-80/220)^3 = .7423; tolerate one displayed frame of quantization.
        assertTrue("Opening must follow cubic-out",fraction() in .70f.. .81f)
        compose.mainClock.advanceTimeBy(144)
        assertEquals(1f,fraction(),.001f)
        compose.runOnIdle{open.value=false}
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        assertTrue("Closing must follow cubic-out",fraction() in .19f.. .30f)
        compose.mainClock.advanceTimeBy(144)
        assertEquals(0f,fraction(),.001f)
    }
}
