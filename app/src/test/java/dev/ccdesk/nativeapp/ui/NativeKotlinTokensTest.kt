package dev.ccdesk.nativeapp.ui

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeKotlinTokensTest {
    private fun color(hex:String)=(0xff000000L or hex.removePrefix("#").toLong(16)).toInt()

    @Test fun nativeTokensMatchCapturedBaselineColorsInBothThemes(){
        // Captured from the old app's installed lowlight + githubGist / irBlack grammars.
        // This static fixture needs neither Node nor JavaScript at test/build/runtime.
        val fixtures=JSONArray(javaClass.classLoader!!.getResource("native-kotlin-highlighting.json")!!.readText())
        for(index in 0 until fixtures.length()){
            val fixture=fixtures.getJSONObject(index);val code=fixture.getString("code")
            for(dark in listOf(false,true)){
                val expected=IntArray(code.length){color(if(dark)"#f8f8f8"else"#333333")}
                val reference=fixture.getJSONArray("tokens")
                for(i in 0 until reference.length()){val token=reference.getJSONObject(i);for(at in token.getInt("start") until token.getInt("end"))expected[at]=color(token.getString(if(dark)"dark"else"light"))}
                val actual=IntArray(code.length){color(if(dark)"#f8f8f8"else"#333333")}
                nativeKotlinTokens(code).forEach{token->assertTrue(token.start>=0&&token.end<=code.length);nativeCodeColor(token.kind,dark)?.let{shade->for(at in token.start until token.end)actual[at]=shade}}
                for(at in code.indices)assertEquals("fixture=$index dark=$dark offset=$at text=${code.substring((at-8).coerceAtLeast(0),(at+12).coerceAtMost(code.length))}",expected[at],actual[at])
            }
        }
    }

    @Test fun callsRemainPlainButDeclarationsHaveTitles(){
        val code="fun main() { println(\"ok\"); main() }"
        val titles=nativeKotlinTokens(code).filter{it.kind=="title"}
        assertEquals(listOf("main"),titles.map{code.substring(it.start,it.end)})
        assertEquals(code.indexOf("main"),titles.single().start)
    }

    @Test fun streamingPrefixesStayInBoundsAndNeverExecuteInput(){
        val code="@Deprecated(\"$"+"{value}\")\nfun <T> work(x: T) = \"$"+"{2 + call(3)}\" /* nested /* comment */ */"
        for(end in 0..code.length){val partial=code.substring(0,end);nativeKotlinTokens(partial).forEach{assertTrue(it.start>=0&&it.start<it.end&&it.end<=partial.length)}}
    }
}
