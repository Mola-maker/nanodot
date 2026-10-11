package io.github.nanomuse.dot.voice

import android.content.ContextWrapper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.openminis.app.speech.*
import org.junit.Assert.*
import org.junit.Test

/** Android's mockable jar supplies platform calls; these are production callback/policy tests, not mic tests. */
class SystemCapturePolicyTest {
    private class Listener : SpeechRecognitionEngine.Listener {
        val final = mutableListOf<String>(); val errors = mutableListOf<RecognitionError>(); var ready = 0
        override fun onPartial(text: String) {}
        override fun onFinal(text: String) { final.add(text) }
        override fun onError(error: RecognitionError, message: String?) { errors.add(error) }
        override fun onReadyForSpeech() { ready++ }
    }
    private fun set(engine: SystemSpeechRecognitionEngine, name: String, value: Any?) {
        engine.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(engine, value)
    }
    private fun callbacks(engine: SystemSpeechRecognitionEngine, generation: Long): RecognitionListener =
        engine.javaClass.getDeclaredMethod("callbacksFor", Long::class.javaPrimitiveType).also { it.isAccessible = true }
            .invoke(engine, generation) as RecognitionListener
    @Test fun dotFalseFinalClosesInsteadOfRestartingPlatform() {
        val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)); val listener = Listener()
        set(engine, "listener", listener); set(engine, "platformGeneration", 42L)
        set(engine, "continuousSession", CapturePolicy.continuous(true, false)); set(engine, "bufferedPartial", "editable")
        callbacks(engine, 42L).onResults(null)
        assertEquals(listOf("editable"), listener.final); assertTrue(listener.errors.isEmpty())
    }
    @Test fun dotFalseNoMatchIsTerminalInsteadOfRestartingPlatform() {
        val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)); val listener = Listener()
        set(engine, "listener", listener); set(engine, "platformGeneration", 42L)
        set(engine, "continuousSession", CapturePolicy.continuous(true, false))
        callbacks(engine, 42L).onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(listOf(RecognitionError.NO_MATCH), listener.errors)
    }
    @Test fun retiredPlatformCallbacksCannotCallCurrentListenerOrRestart() {
        val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)); val listener = Listener()
        set(engine, "listener", listener); set(engine, "platformGeneration", 42L)
        val retired = callbacks(engine, 42L); set(engine, "platformGeneration", 43L)
        retired.onReadyForSpeech(null); retired.onResults(null); retired.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(0, listener.ready); assertTrue(listener.final.isEmpty()); assertTrue(listener.errors.isEmpty())
        callbacks(engine, 43L).onReadyForSpeech(null); assertEquals(1, listener.ready)
    }
}
