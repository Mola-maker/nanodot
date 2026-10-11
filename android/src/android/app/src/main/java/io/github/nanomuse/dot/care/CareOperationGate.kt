package io.github.nanomuse.dot.care

import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/** Real operator admission: worker callers wait for Main to revoke Care before acting.
 * Main callers execute inline, so no blocking wait is ever posted back onto the same thread.
 * Owners survive accessibility reconnect; a new Care owner receives current admission first.
 */
object CareOperationGate {
    private val owners = CareOperationOwners()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun <T> onMain(block: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else runBlocking(Dispatchers.Main.immediate) { block() }
    fun observe(listener: ((Boolean) -> Unit)?) = onMain { owners.observe(listener) }
    /** Retain through the VM lifetime even when the remote RPC wait times out. */
    fun retainUntilIdle(busy: Flow<Boolean>) { onMain { owners.retainUntilIdle(scope,busy) } }
    fun acquire(): AutoCloseable {
        val lease = onMain { owners.acquire() }
        return AutoCloseable { onMain { lease.close() } }
    }
}
