// Copyright 2026 CC Desk Android contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.ui

// Original Lucide paths exported from this repository's pinned baseline package (ISC).
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.*

private val iconPaths=mapOf(
    "panel-left" to listOf("M9 3 v18"),
    "square-pen" to listOf("M12 3 H5 a2 2 0 0 0 -2 2 v14 a2 2 0 0 0 2 2 h14 a2 2 0 0 0 2 -2 v-7","M18.375 2.625 a1 1 0 0 1 3 3 l-9.013 9.014 a2 2 0 0 1 -.853 .505 l-2.873 .84 a.5 .5 0 0 1 -.62 -.62 l.84 -2.873 a2 2 0 0 1 .506 -.852 z"),
    "plus" to listOf("M5 12 h14","M12 5 v14"),
    "arrow-up" to listOf("m5 12 l7 -7 l7 7","M12 19 V5"),
    "square" to emptyList(),
    "chevron-up" to listOf("m18 15 l-6 -6 l-6 6"),
    "chevron-down" to listOf("m6 9 l6 6 l6 -6"),
    "chevron-right" to listOf("m9 18 l6 -6 l-6 -6"),
    "x" to listOf("M18 6 L6 18","m6 6 l12 12"),
    "folder" to listOf("M20 20 a2 2 0 0 0 2 -2 V8 a2 2 0 0 0 -2 -2 h-7.9 a2 2 0 0 1 -1.69 -.9 L9.6 3.9 A2 2 0 0 0 7.93 3 H4 a2 2 0 0 0 -2 2 v13 a2 2 0 0 0 2 2 Z"),
    "link-2" to listOf("M9 17 H7 A5 5 0 0 1 7 7 h2","M15 7 h2 a5 5 0 1 1 0 10 h-2","M8 12 L16 12"),
    "arrow-left" to listOf("m12 19 l-7 -7 l7 -7","M19 12 H5"),
    "bell" to listOf("M10.268 21 a2 2 0 0 0 3.464 0","M3.262 15.326 A1 1 0 0 0 4 17 h16 a1 1 0 0 0 .74 -1.673 C19.41 13.956 18 12.499 18 8 A6 6 0 0 0 6 8 c0 4.499 -1.411 5.956 -2.738 7.326"),
    "book-open" to listOf("M12 5 v16","M20.001 19 A2 2 0 0 0 22 17 V5 a2 2 0 0 0 -1.999 -2 L16 3.002 A5 5 0 0 0 12 5 a5 5 0 0 0 -4 -2 H4 a2 2 0 0 0 -2 2 v12 a2 2 0 0 0 1.999 2 H8 a5 5 0 0 1 4 2 a5 5 0 0 1 4 -2 z"),
    "circle-user-round" to listOf("M17.925 20.056 a6 6 0 0 0 -11.851 .001"),
    "fingerprint" to listOf("M12 10 a2 2 0 0 0 -2 2 c0 1.02 -.1 2.51 -.26 4","M14 13.12 c0 2.38 0 6.38 -1 8.88","M17.29 21.02 c.12 -.6 .43 -2.3 .5 -3.02","M2 12 a10 10 0 0 1 18 -6","M2 16 h.01","M21.8 16 c.2 -2 .131 -5.354 0 -6","M5 19.5 C5.5 18 6 15 6 12 a6 6 0 0 1 .34 -2","M8.65 22 c.21 -.66 .45 -1.32 .57 -2","M9 6.8 a6 6 0 0 1 9 5.2 v2"),
    "list-filter" to listOf("M2 5 h20","M6 12 h12","M9 19 h6"),
    "message-circle" to listOf("M2.992 16.342 a2 2 0 0 1 .094 1.167 l-1.065 3.29 a1 1 0 0 0 1.236 1.168 l3.413 -.998 a2 2 0 0 1 1.099 .092 a10 10 0 1 0 -4.777 -4.719"),
    "pin" to listOf("M12 17 v5","M9 10.76 a2 2 0 0 1 -1.11 1.79 l-1.78 .9 A2 2 0 0 0 5 15.24 V16 a1 1 0 0 0 1 1 h12 a1 1 0 0 0 1 -1 v-.76 a2 2 0 0 0 -1.11 -1.79 l-1.78 -.9 A2 2 0 0 1 15 10.76 V7 a1 1 0 0 1 1 -1 a2 2 0 0 0 0 -4 H8 a2 2 0 0 0 0 4 a1 1 0 0 1 1 1 z"),
    "search" to listOf("m21 21 l-4.34 -4.34"),
    "settings" to listOf("M9.671 4.136 a2.34 2.34 0 0 1 4.659 0 a2.34 2.34 0 0 0 3.319 1.915 a2.34 2.34 0 0 1 2.33 4.033 a2.34 2.34 0 0 0 0 3.831 a2.34 2.34 0 0 1 -2.33 4.033 a2.34 2.34 0 0 0 -3.319 1.915 a2.34 2.34 0 0 1 -4.659 0 a2.34 2.34 0 0 0 -3.32 -1.915 a2.34 2.34 0 0 1 -2.33 -4.033 a2.34 2.34 0 0 0 0 -3.831 A2.34 2.34 0 0 1 6.35 6.051 a2.34 2.34 0 0 0 3.319 -1.915"),
    "check" to listOf("M20 6 L9 17 l-5 -5"),
    "pencil" to listOf("M21.174 6.812 a1 1 0 0 0 -3.986 -3.987 L3.842 16.174 a2 2 0 0 0 -.5 .83 l-1.321 4.352 a.5 .5 0 0 0 .623 .622 l4.353 -1.32 a2 2 0 0 0 .83 -.497 z","m15 5 l4 4"),
    "rotate-cw" to listOf("M21 12 a9 9 0 1 1 -9 -9 c2.52 0 4.93 1 6.74 2.74 L21 8","M21 3 v5 h-5"),
    "circle-help" to listOf("M9 9.5 a3 2.5 0 0 1 6 0 c0 2 -2 2 -3 3.5","M12 17 h.01"),
    "circle-user" to listOf("M7 20.662 V19 a2 2 0 0 1 2 -2 h6 a2 2 0 0 1 2 2 v1.662"),
    "chart-column" to listOf("M3 3 v16 a2 2 0 0 0 2 2 h16","M18 17 V9","M13 17 V5","M8 17 v-3"),
    "shield-check" to listOf("M20 13 c0 5 -3.5 7.5 -7.66 8.95 a1 1 0 0 1 -.67 -.01 C7.5 20.5 4 18 4 13 V6 a1 1 0 0 1 1 -1 c2 0 4.5 -1.2 6.24 -2.72 a1.17 1.17 0 0 1 1.52 0 C14.51 3.81 17 5 19 5 a1 1 0 0 1 1 1 z","m9 12 l2 2 l4 -4"),
    "info" to listOf("M12 16 v-4","M12 8 h.01"),
    "log-out" to listOf("m16 17 l5 -5 l-5 -5","M21 12 H9","M9 21 H5 a2 2 0 0 1 -2 -2 V5 a2 2 0 0 1 2 -2 h4"),
    "key-round" to listOf("M2.586 17.414 A2 2 0 0 0 2 18.828 V21 a1 1 0 0 0 1 1 h3 a1 1 0 0 0 1 -1 v-1 a1 1 0 0 1 1 -1 h1 a1 1 0 0 0 1 -1 v-1 a1 1 0 0 1 1 -1 h.172 a2 2 0 0 0 1.414 -.586 l.814 -.814 a6.5 6.5 0 1 0 -4 -4 z"),
    "globe" to listOf("M12 2 a14.5 14.5 0 0 0 0 20 a14.5 14.5 0 0 0 0 -20","M2 12 h20"),
    "monitor-smartphone" to listOf("M18 8 V6 a2 2 0 0 0 -2 -2 H4 a2 2 0 0 0 -2 2 v7 a2 2 0 0 0 2 2 h8","M10 19 v-3.96 v3.15","M7 19 h5"),
    "refresh-cw" to listOf("M3 12 a9 9 0 0 1 9 -9 a9.75 9.75 0 0 1 6.74 2.74 L21 8","M21 3 v5 h-5","M21 12 a9 9 0 0 1 -9 9 a9.75 9.75 0 0 1 -6.74 -2.74 L3 16","M8 16 H3 v5"),
    "gauge" to listOf("m12 14 l4 -4","M3.34 19 a10 10 0 1 1 17.32 0"),
    "menu" to listOf("M4 5 h16","M4 12 h16","M4 19 h16"),
    "ellipsis" to emptyList(),
    "message-square" to listOf("M22 17 a2 2 0 0 1 -2 2 H6.828 a2 2 0 0 0 -1.414 .586 l-2.202 2.202 A.71 .71 0 0 1 2 21.286 V5 a2 2 0 0 1 2 -2 h16 a2 2 0 0 1 2 2 z"),
    "copy" to listOf("M4 16 c-1.1 0 -2 -.9 -2 -2 V4 c0 -1.1 .9 -2 2 -2 h10 c1.1 0 2 .9 2 2")
)
// Exact SVG geometry from the baseline's lockfile-pinned Lucide 1.72.0.
// Render cached paths like the Android SVG renderer, including rects and circles.
private data class IconRoundRect(val x:Float,val y:Float,val width:Float,val height:Float,val rx:Float,val ry:Float=rx,val afterPaths:Boolean=false)
private val iconRoundRects=mapOf(
    "panel-left" to IconRoundRect(3f,3f,18f,18f,2f),
    "square" to IconRoundRect(3f,3f,18f,18f,2f),
    "monitor-smartphone" to IconRoundRect(16f,12f,6f,10f,2f,afterPaths=true),
    "copy" to IconRoundRect(8f,8f,14f,14f,2f,2f)
)
private data class IconCircle(val cx:Float,val cy:Float,val r:Float,val fill:Boolean=false,val afterPaths:Boolean=false)
private val iconCircles=mapOf(
    "circle-user-round" to listOf(IconCircle(12f,11f,4f,afterPaths=true),IconCircle(12f,12f,10f,afterPaths=true)),
    "search" to listOf(IconCircle(11f,11f,8f,afterPaths=true)),
    "settings" to listOf(IconCircle(12f,12f,3f,afterPaths=true)),
    "circle-help" to listOf(IconCircle(12f,12f,10f)),
    "circle-user" to listOf(IconCircle(12f,12f,10f),IconCircle(12f,10f,3f)),
    "info" to listOf(IconCircle(12f,12f,10f)),
    "globe" to listOf(IconCircle(12f,12f,10f)),
    "ellipsis" to listOf(IconCircle(12f,12f,1f),IconCircle(19f,12f,1f),IconCircle(5f,12f,1f)),
    "key-round" to listOf(IconCircle(16.5f,7.5f,.5f,fill=true,afterPaths=true))
)
private data class IconShape(val path:Path,val fill:Boolean=false)
private fun IconRoundRect.toShape():IconShape {
    val radius=CornerRadius(rx,ry)
    return IconShape(Path().apply {
        addRoundRect(RoundRect(x,y,x+width,y+height,radius,radius,radius,radius),Path.Direction.Clockwise)
    })
}
private fun IconCircle.toShape():IconShape = IconShape(Path().apply {
    addOval(Rect(cx-r,cy-r,cx+r,cy+r),Path.Direction.Clockwise)
},fill)
private fun iconShapes(name:String):List<IconShape> = buildList {
    val rect=iconRoundRects[name]
    val circles=iconCircles[name].orEmpty()
    if(rect!=null&&!rect.afterPaths)add(rect.toShape())
    circles.forEach{if(!it.afterPaths)add(it.toShape())}
    iconPaths[name].orEmpty().forEach{add(IconShape(PathParser().parsePathString(it).toPath()))}
    circles.forEach{if(it.afterPaths)add(it.toShape())}
    if(rect!=null&&rect.afterPaths)add(rect.toShape())
}
@Composable fun DeskIcon(name:String,modifier:Modifier=Modifier,size:Dp=20.dp,tint:Color=LocalDeskColors.current.foreground,strokeWidth:Float=2f) {
    val shapes=remember(name){iconShapes(name)}
    Canvas(modifier.size(size)) {
        scale(this.size.width/24f,this.size.height/24f,pivot=Offset.Zero) {
            val stroke=Stroke(width=strokeWidth,cap=StrokeCap.Round,join=StrokeJoin.Round)
            shapes.forEach {
                if(it.fill)drawPath(it.path,tint,style=Fill)
                drawPath(it.path,tint,style=stroke)
            }
        }
    }
}
