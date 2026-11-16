package dev.ccdesk.nativeapp.ui

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

    @Test fun drawerSettlesOnAMonotonicCriticallyDampedSpring() {
        val open=mutableStateOf(false)
        compose.mainClock.autoAdvance=false
        compose.setContent{
            Box(Modifier.fillMaxWidth().height(40.dp).testTag("motion-surface")){
                DrawerSurface(open.value,{open.value=it},drawerContent={Box(Modifier.fillMaxSize().background(Color.Red))}){
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
        fun screenCoverage():Float {
            val pixels=compose.onNodeWithTag("motion-surface").captureToImage().toPixelMap()
            val y=pixels.height/2
            val edge=(0 until pixels.width).firstOrNull{pixels[it,y].green>.1f}?:pixels.width
            return edge.toFloat()/pixels.width
        }
        // The phone drawer occupies 80% of the screen; animation progress is relative
        // to that width, while coverage below independently checks its resting size.
        fun fraction():Float = screenCoverage()/.8f
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame()
        assertEquals(0f,fraction(),.001f)
        for((target,label) in listOf(true to "opening",false to "closing")){
            compose.runOnIdle{open.value=target}
            compose.mainClock.advanceTimeByFrame()
            val samples=(0 until 40).map{compose.mainClock.advanceTimeByFrame();fraction()}
            samples.zipWithNext().forEach{(a,b)->assertTrue("$label must not reverse or overshoot ($a -> $b)",if(target)b>=a-.002f else b<=a+.002f)}
            assertTrue("$label must be clearly under way after five frames",if(target)samples[4]>.3f else samples[4]<.7f)
            assertTrue("$label must show intermediate positions",samples.count{it in .05f..<.95f}>=3)
            assertEquals("$label must settle",if(target)1f else 0f,samples.last(),.001f)
            assertEquals("$label must retain the 80% phone drawer width",if(target).8f else 0f,screenCoverage(),.001f)
        }
    }

    @Test fun closingDrawerBlocksRapidTapsUntilTheFinalFrame() {
        val open=mutableStateOf(true)
        var backgroundClicks=0
        var drawerClicks=0
        compose.setContent{
            Box(Modifier.fillMaxWidth().height(100.dp).testTag("tap-surface")){
                DrawerSurface(open.value,{open.value=it},drawerContent={
                    Box(Modifier.fillMaxSize().background(Color.Red).clickable{drawerClicks++})
                }){Box(Modifier.fillMaxSize().background(Color.White).clickable{backgroundClicks++})}
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        // Start the live scrim's pointer coroutine before it switches to blocking
        // mode; a programmatic close alone cannot catch stale detector reuse.
        compose.onNodeWithTag("tap-surface").performTouchInput{click(Offset(width*.95f,centerY))}
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle{assertFalse(open.value)}
        compose.mainClock.advanceTimeBy(32)
        val partial=compose.onNodeWithTag("tap-surface").captureToImage().toPixelMap()
        assertTrue("Test must tap during the visible exit",partial[0,partial.height/2].green<.1f)
        compose.onNodeWithTag("tap-surface").performTouchInput{click(Offset(width*.95f,centerY))}
        compose.onNodeWithTag("tap-surface").performTouchInput{click(Offset(2f,centerY))}
        assertEquals("Closing scrim must block background actions",0,backgroundClicks)
        assertEquals("Departing drawer rows must also be inert",0,drawerClicks)
        compose.mainClock.autoAdvance=true
        compose.waitForIdle()
        compose.onNodeWithTag("tap-surface").performTouchInput{click(Offset(width*.95f,centerY))}
        compose.runOnIdle{assertEquals("Input returns when the drawer has left",1,backgroundClicks)}
    }

    @Test fun closingDrawerCanBeGrabbedAwayFromTheEdgeAndReversed() {
        val open=mutableStateOf(true)
        val changes=mutableListOf<Boolean>()
        var drawerClicks=0
        compose.setContent{
            Box(Modifier.fillMaxWidth().height(100.dp).testTag("reverse-surface")){
                DrawerSurface(open.value,{changes+=it;open.value=it},drawerContent={
                    Box(Modifier.fillMaxSize().background(Color.Red).clickable{drawerClicks++})
                }){Box(Modifier.fillMaxSize().background(Color.White))}
            }
        }
        fun edge():Float{
            val pixels=compose.onNodeWithTag("reverse-surface").captureToImage().toPixelMap()
            return ((0 until pixels.width).firstOrNull{pixels[it,pixels.height/2].green>.1f}?:pixels.width).toFloat()
        }
        compose.waitForIdle()
        val resting=edge()
        compose.mainClock.autoAdvance=false
        compose.runOnIdle{open.value=false}
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        val before=edge()
        assertTrue("Test must grab a partially closed drawer",before>0f&&before<resting)
        val grab=before*.6f
        assertTrue("Grab must be outside the normal 32dp opening edge",grab>with(compose.density){32.dp.toPx()})
        val distance=resting*.25f
        compose.onNodeWithTag("reverse-surface").performTouchInput{
            down(Offset(grab,centerY))
            moveBy(Offset(distance,0f),delayMillis=0)
        }
        compose.mainClock.advanceTimeByFrame()
        val held=edge()
        assertEquals("Reversal follows from the current position",minOf(resting,before+distance),held,2f)
        compose.mainClock.advanceTimeBy(64)
        assertEquals("A held finger owns the drawer while the old spring finishes",held,edge(),1f)
        compose.onNodeWithTag("reverse-surface").performTouchInput{
            repeat(4){moveBy(Offset.Zero,delayMillis=40)}
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle{
            assertEquals("A normal UP must not be mistaken for ACTION_CANCEL",listOf(true),changes)
            assertTrue(open.value)
            assertEquals("Reversing a closing drawer must not activate a row",0,drawerClicks)
        }
        compose.mainClock.autoAdvance=true
        compose.waitForIdle()
        assertEquals("The reversed drawer settles fully open",resting,edge(),1f)
    }
}
