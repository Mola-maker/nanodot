package io.github.nanomuse.dot.voice.proof

import android.content.ContextWrapper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.openminis.app.speech.TextToSpeechManager
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Actual manager policy with a controlled SDK boundary; does not synthesize or emulate callbacks. */
class VoiceProducerBehaviorTest {
    private class Client : TextToSpeech(ContextWrapper(null), OnInitListener {}) {
        data class Request(val text: String, val queue: Int, val id: String?)
        val requests = mutableListOf<Request>()
        var result = SUCCESS
        var selectedLocale: Locale? = null
        override fun speak(text: CharSequence?, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            requests.add(Request(text.toString(), queueMode, utteranceId))
            return result
        }
        override fun setLanguage(loc: Locale?): Int { selectedLocale = loc; return SUCCESS }
    }
    private fun ready(client: Client?): TextToSpeechManager = TextToSpeechManager().also { manager ->
        manager.javaClass.getDeclaredField("isInitialized").also { it.isAccessible = true }.setBoolean(manager, true)
        manager.javaClass.getDeclaredField("tts").also { it.isAccessible = true }.set(manager, client)
    }
    @Test fun noOwnerPreservesOneOriginalIdPerSubmissionAndQueueModes() {
        assertFalse(VoiceProofTap.active)
        val client = Client(); val manager = ready(client)
        manager.speak("今天下午三点提醒我喝水。")
        manager.speakQueued("Hello.")
        assertEquals(listOf(Client.Request("今天下午三点提醒我喝水。", TextToSpeech.QUEUE_FLUSH, client.requests[0].id),
            Client.Request("Hello.", TextToSpeech.QUEUE_ADD, client.requests[1].id)), client.requests)
        assertTrue(client.requests.all { it.id?.matches(Regex("[0-9a-f-]{36}")) == true })
        assertNotEquals(client.requests[0].id, client.requests[1].id)
        assertEquals(Locale.US, client.selectedLocale)
        assertTrue(manager.isSpeaking.value)
    }
    @Test fun nullClientAndRejectedSubmissionRetainOriginalFailureState() {
        val absent = ready(null)
        absent.speak("你好")
        assertFalse(absent.isSpeaking.value)
        val client = Client().also { it.result = TextToSpeech.ERROR }; val rejected = ready(client)
        rejected.speak("你好")
        assertEquals(1, client.requests.size)
        assertEquals(Locale.SIMPLIFIED_CHINESE, client.selectedLocale)
        assertFalse(rejected.isSpeaking.value)
    }
    @Test fun originalListenerKeepsStartAndErrorBehaviorWhileUnownedPcmIsIgnored() {
        val client = Client(); val manager = ready(client)
        val listener = manager.javaClass.getDeclaredMethod("createProgressListener", TextToSpeech::class.java)
            .also { it.isAccessible = true }.invoke(manager, client) as UtteranceProgressListener
        listener.onStart("unowned")
        assertTrue(manager.isSpeaking.value)
        listener.onBeginSynthesis("unowned", 16000, 2, 1)
        listener.onAudioAvailable("unowned", byteArrayOf(1, 2))
        assertTrue(manager.isSpeaking.value)
        listener.onError("unowned", 5)
        assertFalse(manager.isSpeaking.value)
        assertFalse(VoiceProofTap.active)
    }
}
