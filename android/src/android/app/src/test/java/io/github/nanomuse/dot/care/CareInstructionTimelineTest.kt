package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.SceneKey
import org.junit.Assert.*
import org.junit.Test

class CareInstructionTimelineTest {
    @Test fun finiteCueRevealsThenHoldsAndDisappearsWithoutChangingTranscript() {
        val history = CareConversation().apply { assistant("one", "Touch Search") }
        val cue = CareInstructionTimeline(10, true)
        assertEquals(CareInstructionFrame(0f,0,false), cue.frame(0))
        assertEquals(5, cue.frame(300).graphemes)
        assertEquals(CareInstructionFrame(1f,10,false), cue.frame(1000))
        assertTrue(cue.frame(cue.duration - 90).alpha in 0.01f..0.99f)
        assertEquals(CareInstructionFrame(0f,10,true), cue.frame(cue.duration))
        assertTrue(cue.frame(Long.MAX_VALUE).finished)
        assertEquals("Touch Search", history.rows.single().text)
        assertFalse(history.rows.single().verified)
    }
    @Test fun reducedMotionShowsCompleteTextImmediatelyAndHasFiniteLifetime() {
        val cue = CareInstructionTimeline(200, false)
        assertEquals(CareInstructionFrame(1f,200,false),cue.frame(0))
        assertEquals(cue.frame(0),cue.frame(cue.duration-1))
        assertTrue(cue.duration <= 6000)
        assertTrue(cue.frame(cue.duration).finished)
        assertTrue(CareInstructionTimeline(1024,true).duration <= 8100)
    }
    @Test fun staleSceneCannotStartOrReplayAndUnchangedRendersCannotRestart() {
        val a = CareInstructionIdentity("generation1:step1", SceneKey("fixture",1,0,1))
        val b = a.copy(scene = SceneKey("fixture",1,0,2))
        val playback = CareInstructionPlayback()
        playback.bind(a)
        assertTrue(playback.admit(a,100))
        playback.bind(a)
        assertFalse(playback.admit(a,200))
        assertEquals(100L,playback.startedAt)
        playback.bind(b)
        assertFalse(playback.admit(a,300)); assertFalse(playback.replay(a,300))
        assertTrue(playback.admit(b,400))
        playback.retire() // Lost safe space or expired: same step must not pop back on every layout.
        assertNull(playback.startedAt); assertFalse(playback.admit(b,500))
        assertTrue(playback.replay(b,600)); assertEquals(600L,playback.startedAt)
        playback.clear()
        assertNull(playback.identity); assertNull(playback.startedAt)
        assertFalse(playback.replay(b,700))
    }
}
