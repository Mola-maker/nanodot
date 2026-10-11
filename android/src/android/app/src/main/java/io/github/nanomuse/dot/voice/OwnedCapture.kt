package io.github.nanomuse.dot.voice

import com.openminis.app.speech.*
import java.util.Locale
import kotlinx.coroutines.*

/** Engine cleanup returns only after recorder, pipe and owned jobs have finished. */
interface CaptureEngine {
    fun start(locale: Locale, continuous: Boolean?, listener: SpeechRecognitionEngine.Listener)
    fun stop()
    suspend fun cancelAndJoin()
}
interface CaptureOutput {
    suspend fun pause()
    fun resume()
}

/** Calls and callbacks are serialized on [scope]'s dispatcher (the Android main dispatcher in production). */
class OwnedCapture(private val scope: CoroutineScope, val lease: VoiceLease, private val output: CaptureOutput,
    private val state: (RecognitionState) -> Unit = {}) {
    private class Capture(val ticket: VoiceLease.Ticket, val engine: CaptureEngine,
        val listener: SpeechRecognitionEngine.Listener, val continuous: Boolean?,
        val admitted: (VoiceLease.Ticket) -> Unit) {
        var preparation: Job? = null
        var finishTimeout: Job? = null
        var ending = false
        var cancelled = false
        var finishing = false
        var restartLocale: Locale? = null
    }
    private var active: Capture? = null

    fun current(owner: String): VoiceLease.Ticket? = active?.ticket?.takeIf { it.owner == owner }
    fun accepts(ticket: VoiceLease.Ticket): Boolean = active?.let { it.ticket === ticket && !it.ending } == true

    fun start(owner: String, engine: CaptureEngine, locale: Locale, continuous: Boolean? = null,
        listener: SpeechRecognitionEngine.Listener, admitted: (VoiceLease.Ticket) -> Unit = {}): VoiceLease.Ticket? {
        val ticket = lease.acquireTicket(owner) ?: run {
            listener.onError(RecognitionError.RECOGNIZER_BUSY, null)
            return null
        }
        val capture = Capture(ticket, engine, listener, continuous, admitted)
        active = capture
        admitted(ticket)
        state(RecognitionState.STARTING)
        val guarded = object : SpeechRecognitionEngine.Listener {
            override fun onPartial(text: String) = event(capture) { listener.onPartial(text) }
            override fun onRmsDb(rms: Float) = event(capture) { listener.onRmsDb(rms) }
            override fun onReadyForSpeech() = event(capture) {
                if (!capture.finishing) state(RecognitionState.RECORDING)
                listener.onReadyForSpeech()
            }
            override fun onFinishing() = event(capture) { beginFinishing(capture, requestStop = false) }
            override fun onFinal(text: String) = event(capture) {
                terminate(capture) { listener.onFinal(text) }
            }
            override fun onError(error: RecognitionError, message: String?) = event(capture) {
                terminate(capture) { listener.onError(error, message) }
            }
        }
        capture.preparation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                output.pause()
                if (accepts(ticket)) engine.start(locale, continuous, guarded)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { guarded.onError(RecognitionError.UNKNOWN, failure.message) }
        }
        capture.preparation!!.start()
        return ticket
    }
    private fun event(capture: Capture, action: () -> Unit) {
        scope.launch {
            if (active !== capture || capture.ending) return@launch
            try { action() } catch (failure: Throwable) {
                terminate(capture) { capture.listener.onError(RecognitionError.UNKNOWN, failure.message) }
            }
        }
    }
    fun stop(ticket: VoiceLease.Ticket) {
        val capture = active?.takeIf { it.ticket === ticket } ?: return
        beginFinishing(capture, requestStop = true)
    }
    private fun beginFinishing(capture: Capture, requestStop: Boolean) {
        if (active !== capture || capture.ending || capture.finishing) return
        capture.finishing = true
        state(RecognitionState.FINISHING)
        scope.launch {
            capture.preparation?.join()
            if (!accepts(capture.ticket)) return@launch
            capture.finishTimeout = scope.launch {
                delay(15_000)
                terminate(capture) { capture.listener.onError(RecognitionError.UNKNOWN, null) }
            }
            try { if (requestStop) capture.engine.stop() } catch (failure: Throwable) {
                terminate(capture) { capture.listener.onError(RecognitionError.UNKNOWN, failure.message) }
            }
        }
    }
    fun restart(ticket: VoiceLease.Ticket, locale: Locale) {
        val capture = active?.takeIf { it.ticket === ticket && !it.cancelled } ?: return
        // Only an already authorized restart can be coalesced while cleanup is in flight.
        if ((capture.ending || capture.finishing) && capture.restartLocale == null) return
        capture.restartLocale = locale
        terminate(capture) {}
    }
    fun cancel(ticket: VoiceLease.Ticket) {
        active?.takeIf { it.ticket === ticket }?.let {
            it.restartLocale = null
            it.cancelled = true
            terminate(it) {}
        }
    }
    private fun terminate(capture: Capture, deliver: () -> Unit) {
        if (active !== capture || capture.ending) return
        capture.ending = true
        capture.finishTimeout?.cancel()
        state(RecognitionState.FINISHING)
        scope.launch {
            // Cancellation is a request, not resource completion. Even a cancelled caller waits here.
            withContext(NonCancellable) {
                capture.preparation?.cancelAndJoin()
                var failure: Throwable? = null
                try { capture.engine.cancelAndJoin() } catch (t: Throwable) { failure = t }
                try {
                    if (failure != null) capture.listener.onError(RecognitionError.UNKNOWN, failure.message)
                    else if (!capture.cancelled) deliver()
                } finally {
                    // Resume while admission is still occupied; no newer capture can inherit this restore.
                    try {
                        state(RecognitionState.IDLE)
                        if (capture.restartLocale == null || failure != null) output.resume()
                    }
                    catch (t: Throwable) {
                        failure = t
                        capture.listener.onError(RecognitionError.UNKNOWN, t.message)
                    } finally { active = null; lease.release(capture.ticket) }
                }
                if (failure == null) capture.restartLocale?.let {
                    start(capture.ticket.owner, capture.engine, it, capture.continuous, capture.listener, capture.admitted)
                }
            }
        }
    }
}
