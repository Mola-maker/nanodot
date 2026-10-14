package io.github.nanomuse.dot.voice

import io.github.nanomuse.dot.chat.SessionBinding
import com.openminis.app.speech.RecognitionError

/** Task5B supplies an admitted service/permission capture port and the Task4 composer gate. */
class DotVoiceSession(private val port: Port, private val accepts: (SessionBinding) -> Boolean,
    private val input: (SessionBinding) -> String, private val write: (SessionBinding, String) -> Boolean,
    private val error: (RecognitionError, String?) -> Unit) {
    interface Port {
        fun start(continuous: Boolean, text: (String, Boolean) -> Unit, error: (RecognitionError, String?) -> Unit): VoiceLease.Ticket?
        fun stop(ticket: VoiceLease.Ticket)
        fun cancel(ticket: VoiceLease.Ticket)
    }
    private var generation = 0L
    private var ticket: VoiceLease.Ticket? = null
    fun start(binding: SessionBinding) {
        cancel()
        if (!accepts(binding)) return
        val request = generation
        val baseline = input(binding)
        var finalDelivered = false
        ticket = port.start(false, { text, final ->
            if (request == generation && accepts(binding) && final && !finalDelivered) {
                finalDelivered = true
                if (text.isNotBlank()) write(binding, listOf(baseline.trimEnd(), text.trim()).filter { it.isNotBlank() }.joinToString(" "))
            }
        }, { kind, message ->
            if (request == generation && accepts(binding)) error(kind, message)
        })
    }
    fun currentTicket(): VoiceLease.Ticket? = ticket
    fun stop() { ticket?.let(port::stop) }
    fun cancel() {
        ++generation
        val previous = ticket
        ticket = null
        previous?.let(port::cancel)
    }
}
