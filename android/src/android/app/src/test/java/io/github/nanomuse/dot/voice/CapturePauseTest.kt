package io.github.nanomuse.dot.voice
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapturePauseTest {
    @Test fun capturePauseWaitsForCancelledSynthesisCleanup() = runTest {
        val release = CompletableDeferred<Unit>(); var cleaned = false
        val synthesis = launch { try { awaitCancellation() } finally {
            withContext(NonCancellable) { release.await(); cleaned = true }
        } }; runCurrent()
        val pause = launch { CapturePause.await(synthesis) { synthesis.cancel() } }; runCurrent()
        val premature = pause.isCompleted; val releasedBeforeGate = cleaned
        release.complete(Unit); runCurrent()
        assertFalse(premature); assertFalse(releasedBeforeGate); assertTrue(cleaned); assertTrue(pause.isCompleted)
    }
    @Test fun stopFailureStillWaitsForSynthesisCleanupAndIsReported() = runTest {
        val release = CompletableDeferred<Unit>(); var cleaned = false; var failure: Throwable? = null
        val synthesis = launch { try { awaitCancellation() } finally {
            withContext(NonCancellable) { release.await(); cleaned = true }
        } }; runCurrent()
        val pause = launch { try { CapturePause.await(synthesis) {
            synthesis.cancel(); error("system stop refused")
        } } catch (t: Throwable) { failure = t } }; runCurrent()
        val premature = pause.isCompleted; release.complete(Unit); runCurrent()
        assertFalse(premature); assertTrue(cleaned); assertEquals("system stop refused", failure?.message)
    }
}
