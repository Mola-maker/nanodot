package io.github.nanomuse.dot.voice
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureWorkerTest {
    @Test fun cancelBeforeDispatchStillClosesAllocatedRecorder() = runTest {
        val worker = CaptureWorker(); var released = 0
        worker.start(this, {}, { released++ }); worker.cancel(); runCurrent(); worker.awaitStopped()
        assertEquals(1, released)
    }
    @Test fun failedStartStillClosesAndReportsFailure() = runTest {
        val worker = CaptureWorker(); var released = 0
        val independent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> })
        worker.start(independent, { error("recorder start failed") }, { released++ }); runCurrent()
        assertEquals(1, released)
        var failure: Throwable? = null
        try { worker.awaitStopped() } catch (t: Throwable) { failure = t }
        assertEquals("recorder start failed", failure?.message); independent.cancel()
    }
}
