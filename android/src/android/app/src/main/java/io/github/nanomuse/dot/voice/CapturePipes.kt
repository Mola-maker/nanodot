package io.github.nanomuse.dot.voice

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/** One pipe owns all three close attempts, even when a stream close fails. */
class CapturePipe<R : Closeable>(val read: R, private val write: Closeable, val stream: OutputStream) {
    private val closing = AtomicBoolean()
    private val completion = CompletableDeferred<Throwable?>()

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        var failure: Throwable? = null
        for (resource in listOf(stream, write, read)) {
            try { resource.close() } catch (t: Throwable) { failure = t }
        }
        completion.complete(failure)
    }

    suspend fun awaitClosed() { completion.await()?.let { throw it } }
}

/** The IO sink can retire A after main installs B; only A's identity may be removed. */
class CapturePipes<R : Closeable> {
    private var current: CapturePipe<R>? = null
    private val owned = mutableListOf<CapturePipe<R>>()
    val read: R? get() = synchronized(this) { current?.read }

    fun install(pipe: CapturePipe<R>) {
        val previous = synchronized(this) { val old = current; owned.add(pipe); current = pipe; old }
        previous?.close()
    }

    fun write(bytes: ByteArray, length: Int) {
        val pipe = synchronized(this) { current } ?: return
        try { pipe.stream.write(bytes, 0, length) }
        catch (_: Throwable) { retire(pipe) }
    }

    private fun retire(pipe: CapturePipe<R>) {
        synchronized(this) { if (current === pipe) current = null }
        pipe.close()
    }

    fun retireCurrent() {
        val pipe = synchronized(this) { val old = current; current = null; old }
        pipe?.close()
    }

    /** A concurrent close is still in flight until its completion, not merely its request. */
    suspend fun closeAndJoin() {
        val snapshot = synchronized(this) { current = null; owned.toList() }
        snapshot.forEach { it.close() }
        var failure: Throwable? = null
        for (pipe in snapshot) try { pipe.awaitClosed() } catch (t: Throwable) { failure = t }
        synchronized(this) { owned.removeAll(snapshot.toSet()) }
        failure?.let { throw it }
    }
}
