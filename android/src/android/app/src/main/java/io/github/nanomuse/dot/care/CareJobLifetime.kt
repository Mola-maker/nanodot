package io.github.nanomuse.dot.care

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Actual VM work lifetime, separate from promptly cancelled UI busy indicators.
 * Acquire before dispatch; release only at Job completion (after children and cleanup),
 * never at cancellation/isActive=false. Completion can run on IO, so counts are serialized.
 * Explicit leases cover a pending user decision and hand off before being closed.
 */
class CareJobLifetime {
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private var count = 0
    fun acquire(): AutoCloseable = synchronized(this) {
        count++
        mutableBusy.value = true
        var closed = false
        AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    count--
                    mutableBusy.value = count > 0
                }
            }
        }
    }
    fun launch(scope: CoroutineScope, context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit): Job {
        val lease = acquire()
        try {
            return scope.launch(context,start,block).also { job ->
                job.invokeOnCompletion { lease.close() }
            }
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
    }
}
