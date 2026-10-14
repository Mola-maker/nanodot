package io.github.nanomuse.dot.care

import org.junit.Assert.*
import org.junit.Test

class CareConversationTest {
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
