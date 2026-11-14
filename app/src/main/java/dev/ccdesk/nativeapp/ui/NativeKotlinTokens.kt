package dev.ccdesk.nativeapp.ui

/** Native port of the baseline highlight.js 10 Kotlin grammar's visible token categories.
 * The original BSD-3-Clause notice is bundled in raw/highlight_grammar_license.txt.
 * Tokenization never evaluates the displayed code and has no JavaScript dependency.
 */
internal data class CodeToken(val start:Int,val end:Int,val kind:String)

internal fun nativeCodeColor(kind:String,dark:Boolean):Int? = when(kind){
    "comment","meta"->if(dark)0xff7c7c7c.toInt()else 0xff969896.toInt()
    "keyword","selector-tag"->if(dark)0xff96cbfe.toInt()else 0xffd73a49.toInt()
    "type"->if(dark)0xffffffb6.toInt()else 0xffd73a49.toInt()
    "literal","symbol","bullet"->if(dark)0xffc6c5fe.toInt()else 0xff0086b3.toInt()
    "title"->if(dark)0xffffffb6.toInt()else 0xff6f42c1.toInt()
    "string"->if(dark)0xffa8ff60.toInt()else 0xff032f62.toInt()
    "number"->if(dark)0xffff73fd.toInt()else 0xff005cc5.toInt()
    "variable","template-variable"->if(dark)0xffc6c5fe.toInt()else 0xffdf5000.toInt()
    "subst"->if(dark)0xffdaefa3.toInt()else null
    "doctag"->if(dark)0xffffffb6.toInt()else null
    else->null // function/class/params/built_in/meta-string inherit their enclosing color.
}

internal fun nativeKotlinTokens(code:String):List<CodeToken> = KotlinScanner(code).scan()

private class KotlinScanner(private val code:String){
    private val tokens=ArrayList<CodeToken>()
    private val length=code.length
    private fun add(start:Int,end:Int,kind:String){if(end>start)tokens+=CodeToken(start,end,kind)}
    private fun matches(pattern:Regex,at:Int)=if(at<=length)pattern.matchAt(code,at)else null
    private fun word(at:Int)=matches(identifier,at)?.value
    private fun afterSpace(at:Int):Int{var i=at;while(i<length&&code[i].isWhitespace()&&code[i]!='\n')i++;return i}

    fun scan():List<CodeToken>{var i=0;while(i<length)i=one(i);return tokens}

    private fun one(at:Int):Int {
        val i=at
        special(i)?.let{return it}
        val name=word(i)
        if(name!=null){
            val end=i+name.length
            if(name in labelledKeywords){
                add(i,end,"keyword")
                if(end<length&&code[end]=='@'){val label=word(end+1);if(label!=null){add(end,end+label.length+1,"symbol");return end+label.length+1}}
                return end
            }
            if(end<length&&code[end]=='@'){add(i,end+1,"symbol");return end+1}
            when{
                name=="fun"->{add(i,end,"keyword");return function(end)}
                name in classWords->{add(i,end,"keyword");return classHead(end)}
                name in keywords->add(i,end,"keyword")
                name in literals->add(i,end,"literal")
            }
            return end
        }
        return i+1
    }

    private fun special(at:Int):Int? {
        if(code.startsWith("//",at)){val end=code.indexOf('\n',at).takeIf{it>=0}?:length;add(at,end,"comment");return end}
        if(code.startsWith("/*",at))return comment(at)
        if(code[at]=='"'||code[at]=='\'')return string(at)
        if(code[at]=='@'){
            val site=matches(annotationSite,at)
            if(site!=null){val end=site.range.last+1;add(at,end,"meta");return end}
            val label=word(at+1)
            if(label!=null){
                val end=at+label.length+1
                val previous=code.substring(0,at).takeLastWhile{it.isLetter()}
                if(previous in setOf("break","continue","return","this")){add(at,end,"symbol");return end}
                val open=afterSpace(end)
                if(open<length&&code[open]=='('){
                    var cursor=open+1;var depth=1;val nested=ArrayList<CodeToken>();val base=tokens.size
                    while(cursor<length&&depth>0){
                        when(code[cursor]){
                            '"','\''->cursor=string(cursor,"meta-string")
                            '('->{depth++;cursor++}
                            ')'->{depth--;cursor++}
                            else->cursor++
                        }
                    }
                    nested+=tokens.subList(base,tokens.size);while(tokens.size>base)tokens.removeAt(tokens.lastIndex)
                    add(at,cursor,"meta");tokens+=nested;return cursor
                }
                add(at,end,"meta");return end
            }
        }
        if(code.startsWith("#!/usr/bin/env",at)&&(at==0||code[at-1]=='\n')){val end=code.indexOf('\n',at).takeIf{it>=0}?:length;add(at,end,"meta");return end}
        for(pattern in numbers){val hit=matches(pattern,at);if(hit!=null){val end=hit.range.last+1;add(at,end,"number");return end}}
        return null
    }

    private fun comment(start:Int):Int {
        var i=start+2;var depth=1
        while(i<length&&depth>0){when{code.startsWith("/*",i)->{depth++;i+=2};code.startsWith("*/",i)->{depth--;i+=2};else->i++}}
        add(start,i,"comment")
        if(code.startsWith("/**",start))docTag.findAll(code.substring(start,i)).forEach{add(start+it.range.first,start+it.range.last+1,"doctag")}
        return i
    }

    private fun string(start:Int,kind:String="string",nesting:Int=0):Int {
        val triple=code.startsWith("\"\"\"",start);val quote=code[start];val opening=if(triple)3 else 1
        var i=start+opening;val nested=ArrayList<CodeToken>();val before=tokens.size
        while(i<length){
            if(triple&&code.startsWith("\"\"\"",i)){i+=3;break}
            if(!triple&&code[i]==quote){i++;break}
            if(!triple&&code[i]=='\\'){i=(i+2).coerceAtMost(length);continue}
            if(!triple&&code[i]=='\n')break
            if(quote=='"'&&code[i]=='$'&&nesting<128){
                if(code.startsWith("${'$'}{",i)){i=substitution(i,nesting+1);continue}
                val variable=word(i+1)
                if(variable!=null){add(i,i+variable.length+1,"variable");i+=variable.length+1;continue}
            }
            i++
        }
        nested+=tokens.subList(before,tokens.size);while(tokens.size>before)tokens.removeAt(tokens.lastIndex)
        add(start,i,kind);tokens+=nested
        return i
    }

    private fun substitution(start:Int,nesting:Int):Int {
        var i=start+2;val before=tokens.size
        while(i<length&&code[i]!='}'){
            if(code[i]=='"'||code[i]=='\''){i=string(i,nesting=nesting);continue}
            val number=numbers.firstNotNullOfOrNull{matches(it,i)}
            if(number!=null){val end=number.range.last+1;add(i,end,"number");i=end}else i++
        }
        if(i<length)i++
        val nested=tokens.subList(before,tokens.size).toList();while(tokens.size>before)tokens.removeAt(tokens.lastIndex)
        add(start,i,"subst");tokens+=nested;return i
    }

    private fun function(start:Int):Int {
        var i=start
        while(i<length&&code[i]!='\n'){
            if(code[i]=='(')return params(i)
            if(code[i]=='<'){
                val end=(code.indexOf('>',i+1).takeIf{it>=0}?.plus(1)?:length)
                add(i,end,"type");Regex("\\breified\\b").findAll(code.substring(i,end)).forEach{add(i+it.range.first,i+it.range.last+1,"keyword")};i=end;continue
            }
            if(code.startsWith("/*",i)){i=comment(i);continue}
            val name=word(i)
            if(name!=null){val end=i+name.length;val next=afterSpace(end);if(next<length&&code[next]=='(')add(i,end,"title")else if(name in keywords)add(i,end,"keyword");i=end}else i++
        }
        return i
    }

    private fun params(start:Int):Int {
        var i=start+1;var depth=1;var type=false
        while(i<length&&depth>0){
            if(code[i]==')'){depth--;i++;continue}
            if(code[i]=='('){depth++;i++;continue}
            if(code[i]==':'){type=true;i++;continue}
            if(code[i]=='='||code[i]==','){type=false;i++;continue}
            val specialEnd=special(i);if(specialEnd!=null){i=specialEnd;continue}
            val name=word(i)
            if(name!=null){val end=i+name.length;when{type->add(i,end,"type");name in keywords->add(i,end,"keyword");name in literals->add(i,end,"literal")};i=end}else i++
        }
        return i
    }

    private fun classHead(start:Int):Int {
        var i=start
        while(i<length&&code[i] !in "\n:{("){
            if(code[i]=='<'){val end=code.indexOf('>',i+1).takeIf{it>=0}?:length;add(i+1,end,"type");i=(end+1).coerceAtMost(length);continue}
            val specialEnd=special(i);if(specialEnd!=null){i=specialEnd;continue}
            val name=word(i)
            if(name!=null){add(i,i+name.length,if(name in setOf("public","protected","internal","private","constructor"))"keyword"else"title");i+=name.length}else i++
        }
        return i
    }

    companion object {
        private val identifier=Regex("[A-Za-z_]\\w*")
        private val keywords=("abstract as val var vararg get set class object open private protected public noinline crossinline dynamic final enum if else do while for when throw try catch finally import package is in fun override companion reified inline lateinit init interface annotation data sealed internal infix operator out by constructor super tailrec where const inner suspend typealias external expect actual break continue return this").split(' ').toSet()
        private val classWords=setOf("class","interface","trait")
        private val literals=setOf("true","false","null")
        private val labelledKeywords=setOf("break","continue","return","this")
        private val docTag=Regex("@[A-Za-z]+")
        private val annotationSite=Regex("@(?:file|property|field|get|set|receiver|param|setparam|delegate)\\s*:(?:\\s*[A-Za-z_]\\w*)?")
        private const val digits="[0-9](_*[0-9])*"
        private const val hex="[0-9a-fA-F](_*[0-9a-fA-F])*"
        private val numbers=listOf(
            "(\\b($digits)((\\.($digits))|\\.)?|(\\.($digits)))[eE][+-]?($digits)[fFdD]?\\b",
            "\\b($digits)((\\.($digits))[fFdD]?\\b|\\.([fFdD]\\b)?)",
            "(\\.($digits))[fFdD]?\\b","\\b($digits)[fFdD]\\b",
            "\\b0[xX](($hex)\\.?|($hex)?\\.($hex))[pP][+-]?($digits)[fFdD]?\\b",
            "\\b(0|[1-9](_*[0-9])*)[lL]?\\b","\\b0[xX]($hex)[lL]?\\b","\\b0(_*[0-7])*[lL]?\\b","\\b0[bB][01](_*[01])*[lL]?\\b"
        ).map(::Regex)
    }
}
