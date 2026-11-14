package dev.ccdesk.nativeapp.ui

import dev.ccdesk.nativeapp.core.*
import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    private fun event(sequence:Long,type:String,vararg fields:Pair<String,Any?>,run:String="r")=DeskEvent("s",run,sequence,"",json("type" to type,*fields))
    @Test fun aggregateReplacementRetainsRowIdentityAndDoesNotDuplicateText(){
        val p=TranscriptProjection();val events=listOf(event(1,"message.user","messageId" to "m","text" to "hello"))
        val a=event(-1,"text.delta","messageId" to "reply","text" to "A");p.update(events,listOf(a),"r")
        val first=p.rows[0];val reply=p.rows[1]
        val b=event(-1,"text.delta","messageId" to "reply","text" to "AB");p.update(events,listOf(b),"r")
        assertSame(first,p.rows[0]);assertSame(reply,p.rows[1]);assertEquals("AB",reply.content)
        p.update(events+event(2,"text.delta","messageId" to "reply","text" to "AB"),emptyList(),"r")
        assertEquals(listOf("hello","AB"),p.rows.map{it.content})
    }
    @Test fun replacementInActiveRunKeepsOlderRunRows(){
        val old=listOf(event(1,"message.user","messageId" to "old","text" to "old",run="old"),event(2,"run.status","status" to "completed",run="old"))
        val current=old+event(3,"tool.requested","toolId" to "t","name" to "Read","input" to "{}")
        val p=TranscriptProjection();p.update(current,listOf(event(-1,"tool.output","toolId" to "t","text" to "one")),"r");val first=p.rows.first()
        p.update(current,listOf(event(-1,"tool.output","toolId" to "t","text" to "one two")),"r")
        assertSame(first,p.rows.first());assertEquals("one two",p.rows.last().tool?.output)
    }
    @Test fun approvalToolAndBackgroundFramesAreResolved(){
        val p=TranscriptProjection();p.update(listOf(event(1,"tool.requested","toolId" to "t","name" to "Bash","input" to "{}"),event(2,"approval.requested","toolId" to "t","approvalId" to "a"),event(3,"approval.resolved","approvalId" to "a","allowed" to false),event(4,"tool.result","toolId" to "t","status" to "denied","output" to "no","exitCode" to 1)),emptyList(),"r")
        assertEquals(1,p.rows.size);val tool=p.rows.single().tool!!;assertEquals(false,tool.allowed);assertEquals("a",tool.approvalId);assertEquals(1,tool.exitCode);assertEquals("denied",tool.status)
    }
    @Test fun prependHistoryMaintainsChronologicalTextBlocks(){
        val later=event(20,"text.delta","messageId" to "x","text" to " world");val earlier=event(10,"text.delta","messageId" to "x","text" to "Hello")
        val p=TranscriptProjection();p.update(listOf(later),emptyList(),null);p.update(listOf(earlier,later),emptyList(),null)
        assertEquals("Hello world",p.rows.single().content)
    }
    @Test fun duplicateTransientSequenceDoesNotMixToolStreams(){
        val events=listOf(event(1,"tool.requested","toolId" to "a","name" to "A"),event(2,"tool.requested","toolId" to "b","name" to "B"))
        val a=event(-2,"tool.output","toolId" to "a","text" to "first");val b=event(-2,"tool.output","toolId" to "b","text" to "second")
        val p=TranscriptProjection();p.update(events,listOf(a,b),"r");p.update(events,listOf(event(-2,"tool.output","toolId" to "a","text" to "first!"),b),"r")
        assertEquals(listOf("first!","second"),p.rows.map{it.tool?.output})
    }
    @Test fun snapshotCorrectingAnEarlierEventIsNotIgnored(){
        val tail=event(2,"run.status","status" to "completed")
        val p=TranscriptProjection();p.update(listOf(event(1,"message.user","messageId" to "m","text" to "old"),tail),emptyList(),null)
        p.update(listOf(event(1,"message.user","messageId" to "m","text" to "corrected"),tail),emptyList(),null)
        assertEquals("corrected",p.rows.first().content)
    }
    @Test fun malformedQuestionsCannotProduceAnApprovalAnswer(){
        assertTrue(questionsFrom("not JSON").isEmpty());assertTrue(questionsFrom("{\"questions\":[null,{}, {\"question\":\"\"}]}").isEmpty())
        val q=questionsFrom("{\"questions\":[{\"question\":\"Choose\",\"multiSelect\":true,\"options\":[{\"label\":\"A\"},{\"description\":\"bad\"}]}]}").single()
        assertTrue(q.multi);assertEquals(listOf("A"),q.options.map{it.first})
    }
    @Test fun malformedToolImagesKeepTheOriginalTextVisible(){
        assertNull(parseToolOutput("[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":123}}]"))
        assertNull(parseToolOutput("[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/svg+xml\",\"data\":\"abc\"}}]"))
        assertEquals("text",parseToolOutput("[{\"type\":\"text\",\"text\":\"result\"}]")!!.single().getString("type"))
    }
}
