// Copyright 2026 CC Desk Android contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.LineHeightSpan
import android.widget.TextView
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.Disposable
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import java.net.URI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** CommonMark parses the document; every block is a native layout and inline spans are native text. */
@Composable fun NativeMarkdown(markdown:String,modifier:Modifier=Modifier){
    val context=LocalContext.current;val c=LocalDeskColors.current;val density=LocalDensity.current
    val renderer=remember(c,density.density,density.fontScale){
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(CoilImagesPlugin.create(context,NativeMarkdownImages.loader(context)))
            .usePlugin(object:AbstractMarkwonPlugin(){
                override fun configureVisitor(builder:MarkwonVisitor.Builder){builder.on(SoftLineBreak::class.java){visitor,_->visitor.builder().append('\n')}}
                override fun configureTheme(builder:MarkwonTheme.Builder){
                    // In the baseline, nested text overrides link color but inherits its underline.
                    builder.linkColor(c.foreground.toArgb()).isLinkUnderlined(true)
                        .codeTextColor(c.foreground.toArgb()).codeBackgroundColor(c.accent.toArgb())
                        .codeTextSize(with(density){15.sp.toPx().toInt()}).codeTypeface(Typeface.MONOSPACE)
                }
                override fun configureConfiguration(builder:MarkwonConfiguration.Builder){
                    builder.linkResolver{view,link->try{
                        val uri=URI(link);require(uri.scheme in listOf("https","http")&&!uri.host.isNullOrBlank()&&uri.userInfo==null)
                        view.context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))
                    }catch(_:Exception){AlertDialog.Builder(view.context).setTitle("无法打开链接").setMessage("请确认这是完整的网址，并检查设备上的浏览器。").setPositiveButton("确定",null).show()}}
                }
            }).build()
    }
    val tree=remember(markdown,renderer){renderer.parse(markdown)}
    Column(modifier){MarkdownChildren(tree,renderer)}
}

private fun Node.children():List<Node> = buildList{var child=firstChild;while(child!=null){add(child);child=child.next}}

@Composable private fun MarkdownChildren(parent:Node,renderer:Markwon,inList:Boolean=false){
    parent.children().forEach{node->MarkdownNode(node,renderer,inList)}
}

@Composable private fun MarkdownNode(node:Node,renderer:Markwon,inList:Boolean=false){
    val c=LocalDeskColors.current
    when(node){
        is Paragraph->MarkdownInline(node,renderer,Modifier.fillMaxWidth().padding(vertical=if(inList)0.dp else 8.dp))
        is Heading->MarkdownInline(node,renderer,Modifier.fillMaxWidth(),weight=if(node.level<=2)700 else 600,lineHeight=if(node.level==2)28 else 22)
        is FencedCodeBlock->MarkdownCode(node.literal.trimEnd('\n'),node.info.substringBefore(' '))
        is IndentedCodeBlock->MarkdownCode(node.literal.trimEnd('\n'),"")
        is BlockQuote->Row(Modifier.fillMaxWidth().padding(vertical=8.dp).background(c.muted)){
            // Border occupies layout width, as on Android React Native; 8 dp padding follows it.
            Column(Modifier.fillMaxWidth().drawBehind{drawRect(c.border,size=androidx.compose.ui.geometry.Size(4.dp.toPx(),size.height))}.padding(start=12.dp,end=8.dp)){MarkdownChildren(node,renderer,inList)}
        }
        is OrderedList, is BulletList->{
            val ordered=node is OrderedList;val start=(node as? OrderedList)?.startNumber?:1
            Column(Modifier.fillMaxWidth()){
                node.children().forEachIndexed{i,item->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                        NativeSpanText(if(ordered)"${start+i}." else "•",Modifier.alignByBaseline(),size=if(ordered)16 else 18,lineHeight=null,monospace=ordered,weight=if(ordered)400 else 900,color=c.mutedForeground)
                        Column(Modifier.weight(1f).alignByBaseline()){MarkdownChildren(item,renderer,true)}
                    }
                }
            }
        }
        is TableBlock->MarkdownTable(node,renderer)
        is ThematicBreak->Box(Modifier.fillMaxWidth().padding(vertical=8.dp).height(1.dp).background(c.border))
        is HtmlBlock->Unit // The baseline has no HTML render rule.
        is LinkReferenceDefinition->Unit
        else->if(node.firstChild!=null)MarkdownChildren(node,renderer,inList)
    }
}

@Composable private fun MarkdownInline(node:Node,renderer:Markwon,modifier:Modifier=Modifier,weight:Int=400,lineHeight:Int=22){
    val spans=remember(node,renderer){SpannableStringBuilder().apply{node.children().forEach{append(renderer.render(it))}}}
    val images=remember(spans){spans.getSpans(0,spans.length,AsyncDrawableSpan::class.java).sortedBy{spans.getSpanStart(it)}}
    if(images.isEmpty())NativeSpanText(spans,modifier,weight=weight,lineHeight=lineHeight,renderer=renderer)
    else Column(modifier){
        var start=0
        images.forEach{image->
            val at=spans.getSpanStart(image);val end=spans.getSpanEnd(image)
            if(at>start){val text=spans.subSequence(start,at).trim('\n');if(text.isNotEmpty())NativeSpanText(text,weight=weight,lineHeight=lineHeight,renderer=renderer)}
            MarkdownImage(image.drawable.destination,spans.subSequence(at,end).toString(),spans.getSpans(at,end,android.text.style.ClickableSpan::class.java).firstOrNull())
            start=end
        }
        if(start<spans.length){val text=spans.subSequence(start,spans.length).trim('\n');if(text.isNotEmpty())NativeSpanText(text,weight=weight,lineHeight=lineHeight,renderer=renderer)}
    }
}

@Composable private fun MarkdownImage(destination:String,alt:String,link:android.text.style.ClickableSpan?){
    val context=LocalContext.current;val c=LocalDeskColors.current
    val loader=remember(context.applicationContext){NativeMarkdownImages.loader(context)}
    AndroidView(modifier=Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)).background(c.accent).then(if(link!=null)Modifier.clickable{link.onClick(android.view.View(context))}else Modifier).semantics{contentDescription=alt},factory={ImageView(it).apply{scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription=alt}},update={view->
        val previous=view.tag as? Pair<*,*>
        if(previous?.first!=destination){(previous?.second as? Disposable)?.dispose();view.setImageDrawable(null);val request=loader.enqueue(ImageRequest.Builder(context).data(destination).target(view).build());view.tag=destination to request}
        view.contentDescription=alt
    },onRelease={view->((view.tag as? Pair<*,*>)?.second as? Disposable)?.dispose();view.setImageDrawable(null)})
}

/** One application-scoped native cache/client; individual views still cancel their own requests. */
private object NativeMarkdownImages {
    @Volatile private var shared:ImageLoader?=null
    fun loader(context:android.content.Context):ImageLoader=shared?:synchronized(this){shared?:ImageLoader.Builder(context.applicationContext).build().also{shared=it}}
}

@Composable private fun MarkdownTable(node:TableBlock,renderer:Markwon){
    val c=LocalDeskColors.current
    val rows=remember(node){node.children().flatMap{section->section.children().map{(section is TableHead) to it}}}
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp).clip(RoundedCornerShape(8.dp)).border(1.dp,c.border,RoundedCornerShape(8.dp)).padding(1.dp)){
        rows.forEach{(header,row)->
            val cells=row.children();val border=c.border
            Layout(modifier=Modifier.fillMaxWidth().background(if(header)c.secondary else Color.Transparent).drawBehind{
                val stroke=1.dp.toPx();drawRect(border,topLeft=androidx.compose.ui.geometry.Offset(0f,size.height-stroke),size=androidx.compose.ui.geometry.Size(size.width,stroke))
                for(i in 1..cells.size){val x=(size.width*i/cells.size).roundToInt()-stroke;drawRect(border,topLeft=androidx.compose.ui.geometry.Offset(x,0f),size=androidx.compose.ui.geometry.Size(stroke,size.height))}
            },content={cells.forEach{cell->MarkdownInline(cell,renderer,Modifier.padding(start=10.dp,top=10.dp,end=11.dp,bottom=11.dp),weight=if(header)600 else 400)}}){measurables,constraints->
                val count=measurables.size.coerceAtLeast(1);val width=constraints.maxWidth
                val offsets=(0..count).map{(width.toFloat()*it/count).roundToInt()}
                val children=measurables.mapIndexed{i,measurable->val cellWidth=offsets[i+1]-offsets[i];measurable.measure(Constraints(minWidth=cellWidth,maxWidth=cellWidth,maxHeight=Constraints.Infinity))}
                val height=children.maxOfOrNull{it.height}?:0
                layout(width,height){children.forEachIndexed{i,child->child.placeRelative(offsets[i],0)}}
            }
        }
    }
}

@Composable private fun MarkdownCode(code:String,language:String){
    val c=LocalDeskColors.current;val highlighted=remember(code,language,c.dark){highlightCode(code,language,c.dark)}
    Box(Modifier.fillMaxWidth().padding(vertical=4.dp).clip(RoundedCornerShape(8.dp)).background(if(c.dark)Color(0xff1a1a1a)else Color(0xfff6f8fa)).horizontalScroll(rememberScrollState()).padding(12.dp)){
        NativeSpanText(highlighted,size=14,lineHeight=20,monospace=true,color=if(c.dark)Color(0xfff8f8f8)else Color(0xff333333))
    }
}

@Composable internal fun NativeSpanText(text:CharSequence,modifier:Modifier=Modifier,size:Int=16,lineHeight:Int?=22,weight:Int=400,monospace:Boolean=false,color:Color=LocalDeskColors.current.foreground,renderer:Markwon?=null,selectable:Boolean=true,maxLines:Int=Int.MAX_VALUE,exposeText:Boolean=true){
    val density=LocalDensity.current
    val height=lineHeight?.let{with(density){ceil(it.sp.toPx()).toInt()}}
    val native=remember{arrayOfNulls<TextView>(1)}
    val content=remember(text,height){SpannableStringBuilder(text).apply{if(isNotEmpty()&&height!=null)setSpan(MarkdownLineHeight(height),0,length,Spannable.SPAN_INCLUSIVE_INCLUSIVE)}}
    AndroidView(modifier=modifier.then(if(!selectable&&exposeText)Modifier.semantics{this.text=AnnotatedString(text.toString())}else Modifier).layout{measurable,constraints->
        val child=measurable.measure(constraints)
        val view=native[0];val first=view?.baseline?:-1;val last=if(view?.layout!=null&&view.lineCount>0)first+view.layout.getLineBaseline(view.lineCount-1)-view.layout.getLineBaseline(0)else first
        layout(child.width,child.height,if(first>=0)mapOf(FirstBaseline to first,LastBaseline to last)else emptyMap()){child.placeRelative(0,0)}
    },factory={TextView(it).apply{native[0]=this;setTextIsSelectable(selectable);if(!selectable)importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO;includeFontPadding=true;setPadding(0,0,0,0);if(renderer!=null)movementMethod=TableAwareMovementMethod.create()}},update={view->
        view.textSize=size.toFloat();view.setTextColor(color.toArgb());view.maxLines=maxLines;view.ellipsize=if(maxLines<Int.MAX_VALUE)android.text.TextUtils.TruncateAt.END else null;view.typeface=if(monospace)Typeface.MONOSPACE else if(android.os.Build.VERSION.SDK_INT>=28)Typeface.create(Typeface.DEFAULT,weight,false)else Typeface.create(if(weight==600)"sans-serif-medium"else"sans-serif",if(weight>=700)Typeface.BOLD else Typeface.NORMAL)
        if(view.tag!==content){if(renderer!=null)renderer.setParsedMarkdown(view,content)else view.text=content;view.tag=content}
    })
}

/** Matches Android baseline line boxes, including the first top and final bottom. */
private class MarkdownLineHeight(private val height:Int):LineHeightSpan {
    override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt){
        val leading=height-(-fm.ascent+fm.descent)
        fm.ascent-=ceil(leading/2.0).toInt();fm.descent+=floor(leading/2.0).toInt()
        if(start==0)fm.top=fm.ascent
        if(end==text.length)fm.bottom=fm.descent
    }
}
