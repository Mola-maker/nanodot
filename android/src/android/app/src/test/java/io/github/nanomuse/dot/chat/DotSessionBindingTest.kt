package io.github.nanomuse.dot.chat

import org.junit.Assert.*
import org.junit.Test

class DotSessionBindingTest {
    @Test fun switchingRejectsLateCaptureFromPreviousSession() {
        val gate = DotSessionBinding { it }
        val a = gate.bind("A")
        val b = gate.bind("B")
        assertFalse(gate.accepts(a))
        assertTrue(gate.accepts(b))
    }
    @Test fun cancellationRejectsLateCaptureEvenAfterReopeningSameSession() {
        val gate = DotSessionBinding { it }
        val a = gate.bind("A")
        gate.cancel(a)
        assertFalse(gate.accepts(a))
        val next = gate.bind("A")
        assertNotEquals(a.generation, next.generation)
        assertFalse(gate.accepts(a))
        assertTrue(gate.accepts(next))
    }
    @Test fun canonicalPromotionPreservesCurrentGenerationAndHistory() {
        val aliases = mutableMapOf<String,String>()
        val gate = DotSessionBinding { aliases[it] ?: it }
        val draft = gate.bind("__new__A")
        aliases["__new__A"] = "A"
        assertTrue(gate.accepts(draft))
        assertEquals(draft, gate.bind("A"))
        gate.cancel(SessionBinding("A", draft.generation))
        assertFalse(gate.accepts(draft))
    }
    @Test fun aliasPromotionStillAcceptsOriginalCaptureTicket() {
        val aliases = mutableMapOf<String, String>()
        val gate = DotSessionBinding { aliases[it] ?: it }
        val original = gate.bind("draft")
        aliases["draft"] = "saved"
        gate.bind("saved")
        assertTrue(gate.accepts(original))
        gate.cancel(original)
        assertFalse(gate.accepts(original))
    }
    @Test fun staleCancellationCannotCancelNewSession() {
        val gate = DotSessionBinding { it }
        val a = gate.bind("A")
        val b = gate.bind("B")
        gate.cancel(a)
        assertTrue(gate.accepts(b))
    }
}
