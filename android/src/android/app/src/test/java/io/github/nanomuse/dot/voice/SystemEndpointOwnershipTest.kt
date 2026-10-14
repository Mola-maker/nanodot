package io.github.nanomuse.dot.voice

import android.content.ContextWrapper
import android.speech.ControlledSpeechRecognizer
import com.openminis.app.speech.*
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Drives the actual System VAD listener, stopListening boundary and core cleanup/watchdog. */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemEndpointOwnershipTest {
    private fun set(engine: SystemSpeechRecognitionEngine, name: String, value: Any?) {
        engine.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(engine, value)
    }
    private fun endpoints(engine: SystemSpeechRecognitionEngine, generation: Long): VoiceActivityListener =
        engine.javaClass.getDeclaredMethod("endpointCallbacks", Long::class.javaPrimitiveType)
            .also { it.isAccessible = true }.invoke(engine, generation) as VoiceActivityListener

    private fun automaticFinish(silence: Boolean, failStop: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)) { it() }
        val recognizer = ControlledSpeechRecognizer().also { it.failStop = failStop }
        val gate = CompletableDeferred<Unit>(); var cleanupStarted = false; var resumed = 0
        val resources = engine.javaClass.getDeclaredField("captureResources").also { it.isAccessible = true }
            .get(engine) as CaptureResources
        resources.track({ cleanupStarted = true }, { gate.await() })
        val states = mutableListOf<RecognitionState>(); val errors = mutableListOf<RecognitionError>()
        val core = OwnedCapture(this, VoiceLease(), object : CaptureOutput {
            override suspend fun pause() {}
            override fun resume() { resumed++ }
        }, states::add)
        val port = object : CaptureEngine {
            override fun start(locale: Locale, continuous: Boolean?, listener: SpeechRecognitionEngine.Listener) {
                set(engine, "listener", listener); set(engine, "recognizer", recognizer)
                listener.onReadyForSpeech()
            }
            override fun stop() = engine.stop()
            override suspend fun cancelAndJoin() = engine.cancelAndJoin()
        }
        val listener = object : SpeechRecognitionEngine.Listener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) { fail("No platform final was supplied") }
            override fun onError(error: RecognitionError, message: String?) { errors.add(error) }
        }
        val ticket = core.start("main", port, Locale.US, null, listener)!!; runCurrent()
        try {
            val callback = endpoints(engine, 0)
            if (silence) callback.onVoiceEnd(byteArrayOf(), SegmentEndReason.SILENCE_DETECTED, 3f)
            else callback.onSessionLimit(SessionLimit.MAX_DURATION)
            runCurrent()
            assertEquals(1, recognizer.stops)
            assertEquals("automatic endpoint must enter bounded finishing", RecognitionState.FINISHING, states.last())
            advanceTimeBy(15_001); runCurrent()
            assertTrue("missing final must reach actual cleanup", cleanupStarted)
            assertEquals(0, resumed)
            var busy: RecognitionError? = null
            val rejected = object : SpeechRecognitionEngine.Listener {
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) {}
                override fun onError(error: RecognitionError, message: String?) { busy = error }
            }
            assertNull(core.start("dot", port, Locale.US, false, rejected))
            assertEquals(RecognitionError.RECOGNIZER_BUSY, busy)
            assertTrue(errors.isEmpty())
            gate.complete(Unit); runCurrent()
            assertEquals(RecognitionState.IDLE, states.last()); assertEquals(1, resumed)
            assertEquals(listOf(RecognitionError.UNKNOWN), errors)
        } finally {
            gate.complete(Unit); core.cancel(ticket); runCurrent(); Dispatchers.resetMain()
        }
    }
    @Test fun sessionLimitWithoutFinalSettlesOnlyAfterRealCleanup() = automaticFinish(false, false)
    @Test fun silenceWithoutFinalSettlesOnlyAfterRealCleanup() = automaticFinish(true, false)
    @Test fun automaticStopThrowSettlesOnlyAfterRealCleanup() = automaticFinish(false, true)
    @Test fun queuedRetiredVadEndpointCannotStopReplacementCapture() {
        val pending = ArrayDeque<() -> Unit>()
        val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)) { pending.add(it) }
        val recognizer = ControlledSpeechRecognizer(); set(engine, "recognizer", recognizer)
        endpoints(engine, 0).onSessionLimit(SessionLimit.MAX_DURATION)
        endpoints(engine, 0).onVoiceEnd(byteArrayOf(), SegmentEndReason.SILENCE_DETECTED, 3f)
        set(engine, "captureGeneration", 1L)
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        assertEquals(0, recognizer.stops)
    }

}
