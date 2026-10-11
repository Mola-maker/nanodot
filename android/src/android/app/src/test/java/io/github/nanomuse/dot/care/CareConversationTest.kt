package io.github.nanomuse.dot.care

import org.junit.Assert.*
import org.junit.Test

class CareConversationTest {
    @Test fun leaderAndBubbleWaitForContourAndReducedMotionHasNoTimingGate() {
        val start=careCueProgress(0,true)
        assertEquals(0f,start.outline,0f); assertEquals(0f,start.leader,0f); assertEquals(0f,start.bubble,0f)
        val edge=careCueProgress(600,true)
        assertEquals(1f,edge.outline,0f); assertEquals(0f,edge.leader,0f); assertEquals(0f,edge.bubble,0f)
        val line=careCueProgress(800,true)
        assertEquals(1f,line.outline,0f); assertEquals(1f,line.leader,0f); assertEquals(0f,line.bubble,0f)
        assertEquals(CareCueProgress(1f,1f,1f),careCueProgress(980,true))
        assertEquals(CareCueProgress(1f,1f,1f),careCueProgress(0,false))
    }
    @Test fun onlyObservedTextEntersHistoryAndRepeatedRenderDoesNotDuplicateIt() {
        val history = CareConversation()
        history.userGoal("Find search")
        history.assistant("question:1", "Which app?")
        history.assistant("question:1", "Which app?")
        assertEquals(listOf("Find search", "Which app?"), history.rows.map { it.text })
        assertTrue(history.rows.first().user)
        assertFalse(history.rows.last().user)
        assertFalse(history.rows.any { it.verified })
    }
    @Test fun verifiedOutcomeRequiresExplicitEvidenceEntryAndPreservesPreviousInstruction() {
        val history = CareConversation()
        history.assistant("step:1", "Touch Search")
        history.assistant("evidence:after-snapshot", "Touch Search", verified = true)
        history.assistant("evidence:after-snapshot", "Touch Search", verified = true)
        history.assistant("step:2", "Enter your query")
        assertEquals(3, history.rows.size)
        assertEquals(1, history.rows.count { it.verified })
        assertEquals("Enter your query", history.rows.last().text)
    }
    @Test fun userChangingGoalKeepsVisibleContextAndBlankStateDoesNotInventMessages() {
        val history = CareConversation()
        history.userGoal("Open delivery")
        history.assistant("step:1", "Touch Delivery")
        history.userGoal("Find groceries")
        history.assistant("empty", "")
        assertEquals(listOf("Open delivery", "Touch Delivery", "Find groceries"), history.rows.map { it.text })
    }
}
