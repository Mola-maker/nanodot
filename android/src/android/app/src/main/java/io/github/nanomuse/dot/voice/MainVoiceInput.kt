package io.github.nanomuse.dot.voice

import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.SpeechRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow

/** One mounted main composer. Disposal and delayed callbacks can only affect its own generation. */
class MainVoiceInput {
    val ticket = MutableStateFlow<VoiceLease.Ticket?>(null)
    private var generation = 0L
    private var closed = false
    fun pending(): Long = ++generation
    fun accepts(request: Long) = !closed && generation == request
    fun start(request: Long, selection: VoiceSelection, text: (String, Boolean) -> Unit,
        error: (RecognitionError, String?) -> Unit) {
        if (!accepts(request)) return
        SpeechRecognitionManager.startOwned("main", null,
            { value, final -> if (accepts(request)) text(value, final) },
            { kind, message -> if (accepts(request)) error(kind, message) }, selection,
            { next -> if (accepts(request)) ticket.value = next else SpeechRecognitionManager.cancelOwned(next) })
    }
    fun stop() { ticket.value?.let(SpeechRecognitionManager::stopOwned) }
    fun cancel() { ++generation; ticket.value?.let(SpeechRecognitionManager::cancelOwned) }
    fun close() { closed = true; cancel() }
}
