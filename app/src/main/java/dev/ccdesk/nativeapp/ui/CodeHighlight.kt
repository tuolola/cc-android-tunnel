package dev.ccdesk.nativeapp.ui

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import io.noties.prism4j.Prism4j
import io.noties.prism4j.GrammarLocator
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.markwon.syntax.Prism4jSyntaxHighlight

// Palettes are copied from the baseline's githubGist / irBlack highlighter themes.
private class DeskCodeTheme(private val dark:Boolean):Prism4jTheme {
    override fun background()=if(dark)0xff1a1a1a.toInt()else 0xfff6f8fa.toInt()
    override fun textColor()=if(dark)0xfff8f8f8.toInt()else 0xff333333.toInt()
    override fun apply(language:String,syntax:Prism4j.Syntax,builder:SpannableStringBuilder,start:Int,end:Int){
        val type=syntax.alias()?:syntax.type()
        val color=when(type){
            "comment","prolog","doctype","cdata"->if(dark)0xff7c7c7c else 0xff969896
            "keyword","tag","builtin"->if(dark)0xff96cbfe else 0xffd73a49
            "string","char","attr-value"->if(dark)0xffa8ff60 else 0xff032f62
            "number"->if(dark)0xffff73fd else 0xff005cc5
            "boolean","constant","symbol"->if(dark)0xffc6c5fe else 0xff0086b3
            "class-name","function","attr-name","property","selector"->if(dark)0xffffffb6 else 0xff6f42c1
            "variable"->if(dark)0xffc6c5fe else 0xffdf5000
            "regex","url"->if(dark)0xffe9c062 else 0xff032f62
            else->return
        }
        builder.setSpan(ForegroundColorSpan(color.toInt()),start,end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
private val prism by lazy {Prism4j(Class.forName("dev.ccdesk.nativeapp.ui.DeskGrammarLocator").getDeclaredConstructor().newInstance() as GrammarLocator)}
private val highlights=mutableMapOf<Boolean,Prism4jSyntaxHighlight>()
fun highlightCode(code:String,language:String,dark:Boolean):CharSequence {
    if(language.lowercase() in setOf("kotlin","kt","kts"))return SpannableStringBuilder(code).apply{
        nativeKotlinTokens(code).forEach{token->nativeCodeColor(token.kind,dark)?.let{color->setSpan(ForegroundColorSpan(color),token.start,token.end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)}}
    }
    val highlighter=highlights.getOrPut(dark){Prism4jSyntaxHighlight.create(prism,DeskCodeTheme(dark),"typescript")}
    val lang=when(language.lowercase()){ "js"->"javascript";"ts"->"typescript";"sh","shell"->"bash";"py"->"python";"html","xml"->"markup";"yml"->"yaml";else->language.ifEmpty{"typescript"} }
    return highlighter.highlight(lang,code)
}
