package io.github.nanomuse.dot.voice

import kotlinx.coroutines.*

/** A recorder allocated before dispatch is released even if cancellation wins the dispatch race. */
class CaptureWorker {
    private var job: Job? = null
    @Volatile private var failure: Throwable? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, run: () -> Unit, close: () -> Unit) {
        check(job == null) { "Capture worker is single use" }
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            try { if (isActive) run() }
            catch (t: Throwable) { failure = t }
            finally {
                try { close() } catch (t: Throwable) { failure = t }
            }
        }
    }
    fun cancel() { job?.cancel() }
    suspend fun awaitStopped() { job?.join(); failure?.let { throw it } }
}
