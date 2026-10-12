@file:Suppress("UNUSED_PARAMETER")
package io.github.nanomuse.dot.voice.proof

import android.speech.tts.TextToSpeech
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.SpeechRecognitionEngine
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.chat.SessionBinding
import io.github.nanomuse.dot.voice.DotVoiceController
import io.github.nanomuse.dot.voice.VoiceLease
import io.github.nanomuse.dot.voice.VoiceStartGate

/** Ordinary debug and every release: stateless producers, no registry or PCM buffers. */
internal object VoiceProofTap {
    val active: Boolean get() = false
    fun reply(adapter: DotChatAdapter, player: ReadAloudPlayer, text: String) {}
    fun ownsPlayer(player: Any) = false
    fun system(player: Any, manager: Any, generation: Long, current: () -> Boolean, text: String) {}
    fun beforeSpeak(manager: Any, client: TextToSpeech, id: String, queue: Int, text: String) {}
    fun speakReturn(manager: Any, client: TextToSpeech, id: String, rc: Int) {}
    fun ownsTts(manager: Any, client: Any, id: String?) = false
    fun tts(manager: Any, client: Any, id: String?, kind: String, values: IntArray? = null) {}
    fun audio(manager: Any, client: Any, id: String?, bytes: ByteArray?) {}
    fun captureRequest(adapter: DotChatAdapter, binding: SessionBinding,
        request: VoiceStartGate.Request, controller: DotVoiceController) {}
    fun captureTicket(adapter: DotChatAdapter, binding: SessionBinding,
        request: VoiceStartGate.Request, ticket: VoiceLease.Ticket) {}
    fun captureEngine(captureOwner: String, engine: SpeechRecognitionEngine) {}
    fun captureBegin(engine: Any, generation: Long) {}
    fun factory(engine: Any, capture: Long, platform: Long, actualClient: Any, route: String) {}
    fun ownsCapture(engine: Any, capture: Long, platform: Long) = false
    fun asr(engine: Any, capture: Long, platform: Long, kind: String, text: () -> String?) {}
}
