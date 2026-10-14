package io.github.nanomuse.dot.voice

/** Retains resources even after an engine has stopped exposing its active detector. */
class CaptureResources {
    private class Resource(val cancel: () -> Unit, val join: suspend () -> Unit)
    private val resources = mutableListOf<Resource>()
    @Synchronized fun track(cancel: () -> Unit, join: suspend () -> Unit) { resources.add(Resource(cancel, join)) }
    fun cancel() {
        var failure: Throwable? = null
        for (resource in synchronized(this) { resources.toList() }) {
            try { resource.cancel() } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw it }
    }
    suspend fun closeAndJoin() {
        val snapshot = synchronized(this) { resources.toList() }
        var failure: Throwable? = null
        for (r in snapshot) try { r.cancel() } catch (t: Throwable) { failure = t }
        for (r in snapshot) try { r.join() } catch (t: Throwable) { failure = t }
        synchronized(this) { resources.removeAll(snapshot.toSet()) }
        failure?.let { throw it }
    }
}
