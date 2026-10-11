package io.github.nanomuse.dot.voice

import io.github.nanomuse.dot.chat.*
import com.openminis.app.speech.RecognitionError
import org.junit.Assert.*
import org.junit.Test

class DotVoiceSessionTest {
    private class Port : DotVoiceSession.Port {
        val lease = VoiceLease(); val callbacks = mutableListOf<(String, Boolean) -> Unit>()
        val errors = mutableListOf<(RecognitionError, String?) -> Unit>(); var continuous = true; var stops = 0
        override fun start(continuous: Boolean, text: (String, Boolean) -> Unit, error: (RecognitionError, String?) -> Unit): VoiceLease.Ticket? {
            this.continuous = continuous; callbacks.add(text); errors.add(error); return lease.acquireTicket("dot")
        }
        override fun stop(ticket: VoiceLease.Ticket) { if (lease.owns(ticket)) stops++ }
        override fun cancel(ticket: VoiceLease.Ticket) { lease.release(ticket) }
    }
    @Test fun finalIsEditableOnceAndDefaultDoesNotRearm() {
        val gate = DotSessionBinding { it }; val a = gate.bind("A"); val port = Port(); var composer = "Draft"
        val voice = DotVoiceSession(port, gate::accepts, { composer }, { _, text -> composer = text; true }, { _, _ -> })
        voice.start(a); assertEquals(1, port.callbacks.size); assertFalse(port.continuous)
        port.callbacks[0]("partial", false); assertEquals("Draft", composer)
        port.callbacks[0]("Chinese final", true); port.callbacks[0]("duplicate", true)
        assertEquals("Draft Chinese final", composer); assertEquals(1, port.callbacks.size)
    }
    @Test fun oldSessionAndSameOwnerReopenCallbacksCannotWriteOrError() {
        val gate = DotSessionBinding { it }; val port = Port(); var composer = ""; var errors = 0
        val voice = DotVoiceSession(port, gate::accepts, { composer }, { _, text -> composer = text; true }, { _, _ -> errors++ })
        voice.start(gate.bind("A")); assertEquals(1, port.callbacks.size); voice.cancel(); voice.start(gate.bind("A"))
        port.callbacks[0]("old", true); port.errors[0](RecognitionError.NETWORK, "old")
        assertEquals("", composer); assertEquals(0, errors)
        gate.bind("B"); port.callbacks[1]("late A", true); port.errors[1](RecognitionError.NETWORK, "old")
        assertEquals("", composer); assertEquals(0, errors)
    }
    @Test fun canonicalPromotionRetainsCapturedBinding() {
        val aliases = mutableMapOf<String, String>(); val gate = DotSessionBinding { aliases[it] ?: it }
        val a = gate.bind("draft"); val port = Port(); var composer = ""
        val voice = DotVoiceSession(port, gate::accepts, { composer }, { binding, text ->
            if (gate.accepts(binding)) { composer = text; true } else false
        }, { _, _ -> })
        voice.start(a); assertEquals(1, port.callbacks.size); aliases["draft"] = "canonical"; gate.bind("canonical")
        port.callbacks[0]("kept", true); assertEquals("kept", composer)
    }
}
