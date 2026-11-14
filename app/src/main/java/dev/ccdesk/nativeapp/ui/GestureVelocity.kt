@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.ccdesk.nativeapp.ui

import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange

/** The drawer's baseline uses Android's estimator, including batched historical movements. */
internal class PlatformPanVelocity : AutoCloseable {
    private val tracker=VelocityTracker.obtain()
    private var downTime=0L
    fun add(change:PointerInputChange) {
        if(change.pressed&&!change.previousPressed) {
            add(change.uptimeMillis,change.position,MotionEvent.ACTION_DOWN)
        } else {
            change.historical.forEach{add(it.uptimeMillis,it.position,MotionEvent.ACTION_MOVE)}
            add(change.uptimeMillis,change.position,if(change.pressed)MotionEvent.ACTION_MOVE else MotionEvent.ACTION_UP)
        }
    }
    internal fun add(time:Long,position:Offset,action:Int) {
        if(action==MotionEvent.ACTION_DOWN)downTime=time
        val event=MotionEvent.obtain(downTime,time,action,position.x,position.y,0)
        try{tracker.addMovement(event)}finally{event.recycle()}
    }
    fun velocity():Offset {tracker.computeCurrentVelocity(1000);return Offset(tracker.xVelocity,tracker.yVelocity)}
    override fun close(){tracker.recycle()}
}

/** The model sheet uses the last MOVE delta, as its baseline PanResponder does; UP keeps it. */
internal class LastMoveVelocity(time:Long,position:Offset) {
    private var previousTime=time
    private var previousPosition=position
    var velocity=Offset.Zero
        private set
    fun add(time:Long,position:Offset,pressed:Boolean) {
        if(!pressed)return
        val elapsed=time-previousTime
        if(elapsed>0)velocity=(position-previousPosition)*(1000f/elapsed)
        previousTime=time;previousPosition=position
    }
}
