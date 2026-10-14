package io.github.nanomuse.dot.voice

import android.content.ContextWrapper
import com.openminis.app.speech.ProviderSpeechRecognitionEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class ProviderCaptureResourceTest {
    // No Android methods execute: only the real engine's constructor/cancel and owned resource seam.
    private fun context(): ContextWrapper {
        val type = Class.forName("sun.misc.Unsafe")
        val field = type.getDeclaredField("theUnsafe").also { it.isAccessible = true }
        return type.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), ContextWrapper::class.java) as ContextWrapper
    }
    @Test fun providerCancelClosesItsRegisteredDetector() {
        val resources = CaptureResources(); var open = true
        resources.track({ open = false }, {})
        val engine = ProviderSpeechRecognitionEngine(context(), resources)
        engine.cancel()
        assertFalse("Provider cancel must request detector release, not only clear recording", open)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun providerCompletionWaitsForDetectorRelease() = runTest {
        val resources = CaptureResources(); val closed = CompletableDeferred<Unit>(); var cancelled = false
        resources.track({ cancelled = true }, { closed.await() })
        val engine = ProviderSpeechRecognitionEngine(context(), resources)
        val completion = launch { engine.cancelAndJoin() }; runCurrent()
        assertTrue(cancelled); assertFalse(completion.isCompleted)
        closed.complete(Unit); runCurrent(); assertTrue(completion.isCompleted)
    }
}
