package io.github.nanomuse.dot.voice

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A stop failure still waits for controlled synthesis/playback cleanup before being reported. */
object CapturePause {
    suspend fun await(job: Job?, stop: () -> Unit) {
        var failure: Throwable? = null
        try { stop() } catch (t: Throwable) { failure = t }
        withContext(NonCancellable) { job?.join() }
        failure?.let { throw it }
    }
}
