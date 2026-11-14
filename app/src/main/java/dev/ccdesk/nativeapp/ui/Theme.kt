// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

data class DeskColors(val background:Color,val foreground:Color,val card:Color,val userBubble:Color,val muted:Color,val mutedForeground:Color,val border:Color,val secondary:Color,val accent:Color,val sidebar:Color,val dark:Boolean)
private val light=DeskColors(Color(0xfffaf9f5),Color(0xff262620),Color.White,Color(0xffeeede8),Color(0xfff0efe9),Color(0xff68665e),Color(0xffd9d7ce),Color(0xfff3f2ec),Color(0xffe9e7df),Color(0xfff3f2ec),false)
private val dark=DeskColors(Color(0xff191919),Color(0xffeeeae3),Color(0xff303030),Color(0xff2a2a28),Color(0xff282827),Color(0xffaaa8a2),Color(0xff424240),Color(0xff262625),Color(0xff383836),Color(0xff202020),true)
val LocalDeskColors=staticCompositionLocalOf { light }
@Composable fun DeskTheme(content:@Composable ()->Unit) { CompositionLocalProvider(LocalDeskColors provides if(isSystemInDarkTheme()) dark else light,LocalTextSelectionColors provides TextSelectionColors(Color(0xff008577),Color(0xff008577).copy(alpha=.4f)),LocalReducedMotion provides rememberReducedMotion(), content=content) }

@Composable fun DeskText(text:String,modifier:Modifier=Modifier,size:Int=16,color:Color=LocalDeskColors.current.foreground,weight:FontWeight=FontWeight.Normal,lineHeight:Int=(size*1.4).toInt(),maxLines:Int=Int.MAX_VALUE) {
    // RN CustomLineHeightSpan applies half-leading to the paragraph's first and last line, too.
    // Compose's default paragraph-edge trimming otherwise shortens multi-line blocks.
    Text(text,modifier,color=color,fontSize=size.sp,fontWeight=weight,lineHeight=lineHeight.sp,maxLines=maxLines,overflow=TextOverflow.Ellipsis,style=TextStyle(platformStyle=PlatformTextStyle(includeFontPadding=false),lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None)))
}
/** Text button: spring press scale, quick highlight, smooth enabled/selected transitions and an optional loading spinner. */
@Composable fun DeskAction(label:String,enabled:Boolean=true,primary:Boolean=false,selected:Boolean=false,modifier:Modifier=Modifier,loading:Boolean=false,onClick:()->Unit) {
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val filled=primary||selected
    val background by animateColorAsState(if(filled)(if(pressed)c.foreground.copy(alpha=.86f)else c.foreground)else if(pressed)c.accent else c.secondary,DeskMotion.highlight(pressed),label="action background")
    val textColor by animateColorAsState(if(filled)c.background else c.foreground,DeskMotion.state(),label="action text")
    val alpha=animatedEnabledAlpha(enabled||loading)
    Box(modifier.pressScale(pressed,enabled&&!loading,if(selected)DeskMotion.ChipPressScale else DeskMotion.ButtonPressScale).graphicsLayer{this.alpha=alpha;compositingStrategy=CompositingStrategy.ModulateAlpha}.background(background,RoundedCornerShape(12.dp)).clickable(enabled=enabled&&!loading,interactionSource=interaction,indication=null,onClick=onClick).semantics{text=AnnotatedString(label);role=Role.Button;if(selected)this.selected=true}.padding(horizontal=16.dp,vertical=12.dp),contentAlignment=Alignment.Center) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
            AnimatedVisibility(loading,enter=fadeIn(DeskMotion.fadeIn())+expandHorizontally(DeskMotion.layoutSize),exit=fadeOut(DeskMotion.fadeOut())+shrinkHorizontally(DeskMotion.layoutSize)){Box(Modifier.padding(end=8.dp)){DeskSpinner("正在处理",16.dp)}}
            NativeSpanText(label,size=14,lineHeight=20,color=textColor,weight=if(filled)600 else 500,selectable=false,exposeText=false)
        }
    }
}
/** Icon button: spring press scale, animated highlight/selection and a cross-scaled icon when [name] changes. */
@Composable fun DeskIconButton(name:String,label:String,enabled:Boolean=true,iconSize:Dp=20.dp,tint:Color=LocalDeskColors.current.foreground,strokeWidth:Float=2f,cornerRadius:Dp?=null,pressedColor:Color=LocalDeskColors.current.muted,selected:Boolean=false,onClick:()->Unit) {
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val highlight=animatedHighlight(pressed&&enabled,selected,pressedColor)
    val alpha=animatedEnabledAlpha(enabled,.4f)
    val shape=cornerRadius?.let{RoundedCornerShape(it)}?:CircleShape
    Box(Modifier.size(44.dp).graphicsLayer{this.alpha=alpha}.clickable(enabled=enabled,interactionSource=interaction,indication=null,onClick=onClick).semantics{contentDescription=label;role=Role.Button;if(selected)this.selected=true},contentAlignment=Alignment.Center) {
        Box(Modifier.matchParentSize().pressScale(pressed,enabled,DeskMotion.IconPressScale).background(highlight,shape))
        AnimatedContent(name,Modifier.pressScale(pressed,enabled,DeskMotion.IconPressScale),transitionSpec={(fadeIn(DeskMotion.fadeIn())+scaleIn(DeskMotion.icon(),initialScale=.7f))togetherWith(fadeOut(DeskMotion.fadeOut())+scaleOut(DeskMotion.icon(),targetScale=.7f))},label="icon"){DeskIcon(it,size=iconSize,tint=tint,strokeWidth=strokeWidth)}
    }
}
/** Row highlight: appears quickly on press, eases out, and keeps a minimum visible duration for quick taps. */
internal fun Modifier.deskPressable(color:Color,radius:Dp=8.dp,selected:Boolean=false,enabled:Boolean=true,onClick:()->Unit)=composed {
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val highlight=animatedHighlight(pressed&&enabled,selected,color)
    graphicsLayer{alpha=if(enabled)1f else .45f}.background(highlight,RoundedCornerShape(radius)).clickable(enabled=enabled,interactionSource=interaction,indication=null,onClick=onClick)
}
@Composable fun DeskSpinner(label:String="正在加载",size:Dp=20.dp){AndroidView(modifier=Modifier.size(size),factory={android.widget.ProgressBar(it,null,android.R.attr.progressBarStyle).apply{contentDescription=label}},update={it.contentDescription=label})}
