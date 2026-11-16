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
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

data class DeskColors(val background:Color,val foreground:Color,val card:Color,val userBubble:Color,val muted:Color,val mutedForeground:Color,val border:Color,val secondary:Color,val accent:Color,val sidebar:Color,val dark:Boolean,val primary:Color=Color(0xffad563b),val onPrimary:Color=Color.White)
// Claude-inspired warm paper, charcoal and terracotta; CCtunnel retains its own layout.
// Deepen the light-mode accent for readable small labels (4.78:1 against warm paper).
private val light=DeskColors(Color(0xfffaf9f5),Color(0xff262624),Color(0xfffffefa),Color(0xfff0eee6),Color(0xfff1efe7),Color(0xff6b6960),Color(0xffdedbd1),Color(0xfff1efe7),Color(0xfffaf3ec),Color(0xfff3f1e9),false)
private val dark=DeskColors(Color(0xff262624),Color(0xfff1eee5),Color(0xff30302d),Color(0xff3c3832),Color(0xff353530),Color(0xffb9b6aa),Color(0xff4a4942),Color(0xff353530),Color(0xff493a31),Color(0xff21211f),true,Color(0xffe0a184),Color(0xff251b16))
internal object DeskShape {
    val Control=RoundedCornerShape(12.dp)
    val Field=RoundedCornerShape(16.dp)
    val Card=RoundedCornerShape(20.dp)
    val Sheet=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)
}
val LocalDeskColors=staticCompositionLocalOf { light }
@Composable fun DeskTheme(content:@Composable ()->Unit) {
    val colors=if(isSystemInDarkTheme()) dark else light
    CompositionLocalProvider(LocalDeskColors provides colors,LocalTextSelectionColors provides TextSelectionColors(colors.primary,colors.primary.copy(alpha=.24f)),LocalReducedMotion provides rememberReducedMotion(),content=content)
}

@Composable fun DeskText(text:String,modifier:Modifier=Modifier,size:Int=16,color:Color=LocalDeskColors.current.foreground,weight:FontWeight=FontWeight.Normal,lineHeight:Int=(size*1.4).toInt(),maxLines:Int=Int.MAX_VALUE) {
    Text(text,modifier,color=color,fontSize=size.sp,fontWeight=weight,fontFamily=FontFamily.SansSerif,lineHeight=lineHeight.sp,maxLines=maxLines,overflow=TextOverflow.Ellipsis,style=TextStyle(platformStyle=PlatformTextStyle(includeFontPadding=false),lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None)))
}
/** Text button: spring press scale, quick highlight, smooth enabled/selected transitions and an optional loading spinner. */
@Composable fun DeskAction(label:String,enabled:Boolean=true,primary:Boolean=false,selected:Boolean=false,modifier:Modifier=Modifier,loading:Boolean=false,onClick:()->Unit) {
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val filled=primary||selected
    val background by animateColorAsState(if(filled)(if(pressed)c.primary.copy(alpha=.86f)else c.primary)else if(pressed)c.accent else c.secondary,DeskMotion.highlight(pressed),label="action background")
    val textColor by animateColorAsState(if(filled)c.onPrimary else c.foreground,DeskMotion.state(),label="action text")
    val alpha=animatedEnabledAlpha(enabled||loading)
    Box(modifier.heightIn(min=48.dp).pressScale(pressed,enabled&&!loading,if(selected)DeskMotion.ChipPressScale else DeskMotion.ButtonPressScale).graphicsLayer{this.alpha=alpha;compositingStrategy=CompositingStrategy.ModulateAlpha}.background(background,DeskShape.Control).clickable(enabled=enabled&&!loading,interactionSource=interaction,indication=null,onClick=onClick).semantics{text=AnnotatedString(label);role=Role.Button;if(selected)this.selected=true}.padding(horizontal=18.dp,vertical=12.dp),contentAlignment=Alignment.Center) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
            AnimatedVisibility(loading,enter=fadeIn(DeskMotion.fadeIn())+expandHorizontally(DeskMotion.layoutSize),exit=fadeOut(DeskMotion.fadeOut())+shrinkHorizontally(DeskMotion.layoutSize)){Box(Modifier.padding(end=8.dp)){DeskSpinner("正在处理",16.dp)}}
            NativeSpanText(label,size=14,lineHeight=20,color=textColor,weight=if(filled)600 else 500,selectable=false,exposeText=false)
        }
    }
}
/** Quiet floating surfaces retain shaped shadows without competing with the content. */
internal fun Modifier.deskFloatingSurface(shape:Shape=DeskShape.Control,elevation:Dp=3.dp)=composed {
    val colors=LocalDeskColors.current
    graphicsLayer {
        this.shape=shape
        shadowElevation=elevation.toPx()
        // Android applies its light-source opacity to these colors again. A very
        // faint color here loses the soft halo needed to separate floating surfaces.
        ambientShadowColor=Color.Black.copy(alpha=if(colors.dark).8f else .7f)
        spotShadowColor=Color.Black.copy(alpha=if(colors.dark).5f else .35f)
    }.background(colors.card,shape)
}

@Composable internal fun DeskSurfaceIconButton(
    name:String,label:String,modifier:Modifier=Modifier,enabled:Boolean=true,
    size:Dp=48.dp,iconSize:Dp=24.dp,tint:Color=LocalDeskColors.current.foreground,
    strokeWidth:Float=2f,onClick:()->Unit,
) {
    val c=LocalDeskColors.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.pressedState()
    val alpha=animatedEnabledAlpha(enabled,.4f)
    val highlight=animatedHighlight(pressed&&enabled,false,c.accent)
    Box(modifier.size(size).pressScale(pressed,enabled,DeskMotion.IconPressScale)
        .floatingAlpha{alpha}.deskFloatingSurface().border(1.dp,c.border,DeskShape.Control).background(highlight,DeskShape.Control)
        .clickable(enabled=enabled,interactionSource=interaction,indication=null,onClick=onClick)
        .semantics{contentDescription=label;role=Role.Button},contentAlignment=Alignment.Center) {
        DeskIcon(name,size=iconSize,tint=tint,strokeWidth=strokeWidth)
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
