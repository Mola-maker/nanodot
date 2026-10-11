package io.github.nanomuse.dot.voice

import com.openminis.app.speech.*
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedCaptureTest {
    private class Engine : CaptureEngine {
        lateinit var listener: SpeechRecognitionEngine.Listener
        var starts = 0; var stops = 0; var closes = 0; var live = false
        var continuous: Boolean? = null
        var closeGate: CompletableDeferred<Unit>? = null
        var startFailure = false; var stopFailure = false; var closeFailure = false
        override fun start(locale: Locale, continuous: Boolean?, listener: SpeechRecognitionEngine.Listener) {
            this.listener = listener; this.continuous = continuous; starts++; live = true
            if (startFailure) error("start failed")
        }
        override fun stop() { stops++; if (stopFailure) error("stop failed") }
        override suspend fun cancelAndJoin() {
            closes++; closeGate?.await(); live = false
            if (closeFailure) error("cleanup failed")
        }
    }
    private class Output : CaptureOutput {
        var pauses = 0; var resumes = 0
        var gate: CompletableDeferred<Unit>? = null; var failure = false
        override suspend fun pause() { pauses++; gate?.await(); if (failure) error("TTS stop failed") }
        override fun resume() { resumes++ }
    }
    private class Listener : SpeechRecognitionEngine.Listener {
        val text = mutableListOf<String>(); val errors = mutableListOf<RecognitionError>()
        override fun onPartial(text: String) { this.text.add(text) }
        override fun onFinal(text: String) { this.text.add(text) }
        override fun onError(error: RecognitionError, message: String?) { errors.add(error) }
    }
    @Test fun leaseExcludesOtherAndDuplicateOwner() {
        val lease = VoiceLease(); assertTrue(lease.acquire("main")); assertFalse(lease.acquire("dot"))
        assertFalse(lease.acquire("main")); lease.release("dot"); assertFalse(lease.acquire("dot"))
        lease.release("main"); assertTrue(lease.acquire("dot"))
    }
    @Test fun oldTicketCannotReleaseReopenedSameOwner() {
        val lease = VoiceLease(); val a = lease.acquireTicket("dot")!!; lease.release(a)
        val b = lease.acquireTicket("dot")!!; lease.release(a)
        assertTrue(lease.owns(b)); assertFalse(lease.owns(a)); assertNull(lease.acquireTicket("main"))
    }
    @Test fun cancelHoldsLeaseUntilRecorderReallyCloses() = runTest {
        val output = Output(); val core = OwnedCapture(this, VoiceLease(), output); val e = Engine()
        val a = core.start("dot", e, Locale.CHINESE, false, Listener())!!; runCurrent()
        assertTrue(e.live); e.closeGate = CompletableDeferred(); core.cancel(a); runCurrent()
        assertTrue(e.live); assertNull(core.start("main", Engine(), Locale.US, null, Listener()))
        assertEquals(0, output.resumes); e.closeGate!!.complete(Unit); runCurrent()
        assertFalse(e.live); assertEquals(1, e.closes); assertEquals(1, output.resumes)
        assertNotNull(core.start("main", Engine(), Locale.US, null, Listener()))
        core.current("main")?.let(core::cancel); runCurrent()
    }
    @Test fun staleCallbacksAndTerminalDoNotTouchNewOwner() = runTest {
        val output = Output(); val core = OwnedCapture(this, VoiceLease(), output); val a = Engine()
        val old = core.start("dot", a, Locale.US, false, Listener())!!; runCurrent(); core.cancel(old); runCurrent()
        val b = Engine(); val l = Listener(); val current = core.start("dot", b, Locale.US, false, l)!!; runCurrent()
        a.listener.onPartial("old"); a.listener.onFinal("old"); a.listener.onError(RecognitionError.NETWORK)
        a.listener.onFinishing()
        core.cancel(old); core.stop(old); runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertTrue(b.live); assertEquals(0, b.closes); assertTrue(l.text.isEmpty()); assertTrue(l.errors.isEmpty())
        assertEquals(1, output.resumes); core.cancel(current); runCurrent()
    }
    @Test fun stopAndCancelUsePinnedEngine() = runTest {
        val core = OwnedCapture(this, VoiceLease(), Output()); val a = Engine(); val b = Engine()
        val ticket = core.start("main", a, Locale.US, null, Listener())!!; runCurrent()
        assertNull(core.start("dot", b, Locale.US, false, Listener())); core.stop(ticket); runCurrent()
        assertEquals(1, a.stops); assertEquals(0, b.stops); core.cancel(ticket); runCurrent()
        assertEquals(1, a.closes); assertEquals(0, b.closes)
    }
    @Test fun waitsForTtsBeforeOpeningMic() = runTest {
        val output = Output().also { it.gate = CompletableDeferred() }; val e = Engine()
        val core = OwnedCapture(this, VoiceLease(), output); val a = core.start("dot", e, Locale.US, false, Listener())!!
        runCurrent(); assertEquals(0, e.starts); output.gate!!.complete(Unit); runCurrent(); assertEquals(1, e.starts)
        assertEquals(false, e.continuous); core.cancel(a); runCurrent()
    }
    @Test fun ttsFailureDoesNotOpenMicAndReleasesLease() = runTest {
        val output = Output().also { it.failure = true }; val e = Engine(); val l = Listener(); val lease = VoiceLease()
        val core = OwnedCapture(this, lease, output); core.start("dot", e, Locale.US, false, l); runCurrent()
        assertEquals(0, e.starts); assertEquals(listOf(RecognitionError.UNKNOWN), l.errors); assertTrue(lease.acquire("main"))
    }
    @Test fun startStopAndCleanupFailuresAreObservableAndRelease() = runTest {
        for (kind in listOf("start", "stop", "close")) {
            val lease = VoiceLease(); val core = OwnedCapture(this, lease, Output()); val e = Engine().also {
                it.startFailure = kind == "start"; it.stopFailure = kind == "stop"; it.closeFailure = kind == "close"
            }; val l = Listener(); val t = core.start("dot", e, Locale.US, false, l)!!; runCurrent()
            if (kind == "stop") core.stop(t) else core.cancel(t)
            runCurrent(); assertFalse(e.live); assertTrue(l.errors.isNotEmpty()); assertTrue(lease.acquire("main"))
        }
    }
    @Test fun terminalIsOnceAndMainPolicyIsUnspecified() = runTest {
        val core = OwnedCapture(this, VoiceLease(), Output()); val e = Engine(); val l = Listener()
        core.start("main", e, Locale.US, null, l); runCurrent(); assertNull(e.continuous)
        e.listener.onFinal("editable"); e.listener.onFinal("duplicate"); runCurrent()
        assertEquals(listOf("editable"), l.text); assertEquals(1, e.closes)
    }
    @Test fun localeRestartWaitsForCleanupAndCancelledRestartCannotRearm() = runTest {
        val core = OwnedCapture(this, VoiceLease(), Output()); val e = Engine()
        val ticket = core.start("main", e, Locale.US, null, Listener())!!; runCurrent()
        e.closeGate = CompletableDeferred(); core.restart(ticket, Locale.CHINESE); runCurrent()
        assertEquals(1, e.closes); assertEquals(1, e.starts)
        core.cancel(ticket); e.closeGate!!.complete(Unit); runCurrent()
        assertEquals(1, e.starts); assertNull(core.current("main"))
    }
    @Test fun localeRestartPinsEngineAndDropsOldListener() = runTest {
        val core = OwnedCapture(this, VoiceLease(), Output()); val e = Engine(); val l = Listener()
        val ticket = core.start("main", e, Locale.US, null, l)!!; runCurrent(); val old = e.listener
        core.restart(ticket, Locale.CHINESE); runCurrent()
        assertEquals(2, e.starts); old.onFinal("stale"); runCurrent(); assertTrue(l.text.isEmpty())
        assertTrue(e.live); core.current("main")?.let(core::cancel); runCurrent()
    }
    @Test fun missingFinalHasBoundedFinishAndClosesCapture() = runTest {
        val core = OwnedCapture(this, VoiceLease(), Output()); val e = Engine(); val l = Listener()
        val ticket = core.start("main", e, Locale.US, null, l)!!; runCurrent(); core.stop(ticket); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertFalse(e.live); assertEquals(1, e.closes); assertEquals(listOf(RecognitionError.UNKNOWN), l.errors)
    }
    @Test fun cancelDuringFinalCleanupSuppressesQueuedFinal() = runTest {
        val core = OwnedCapture(this, VoiceLease(), Output()); val e = Engine(); val l = Listener()
        val ticket = core.start("dot", e, Locale.US, false, l)!!; runCurrent()
        e.closeGate = CompletableDeferred(); e.listener.onFinal("obsolete"); runCurrent(); core.cancel(ticket)
        e.closeGate!!.complete(Unit); runCurrent(); assertTrue(l.text.isEmpty())
    }
    @Test fun restartDoesNotResumeTtsBetweenCaptures() = runTest {
        val output = Output(); val core = OwnedCapture(this, VoiceLease(), output); val e = Engine()
        val ticket = core.start("main", e, Locale.US, null, Listener())!!; runCurrent()
        core.restart(ticket, Locale.CHINESE); runCurrent(); assertEquals(0, output.resumes)
        core.current("main")?.let(core::cancel); runCurrent(); assertEquals(1, output.resumes)
    }
}
