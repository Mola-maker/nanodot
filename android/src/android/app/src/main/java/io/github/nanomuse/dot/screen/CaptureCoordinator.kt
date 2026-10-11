package io.github.nanomuse.dot.screen

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.IdentityHashMap
import kotlin.coroutines.resume

/** One in-flight acquisition; cancellation removes the waiter, never transfers its result. */
class CaptureCoordinator {
    private val mutex = Mutex()
    suspend fun <T> serial(block: suspend () -> T): T = mutex.withLock { block() }
}

/** A callback owns its resource until continuation dispatch actually hands it to the caller.
 * The cancellation handler on resume also covers cancellation between resume and dispatch.
 */
internal suspend fun <T : AutoCloseable> awaitOwnedResource(start: ((T) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        val seen = IdentityHashMap<T, Boolean>()
        start { resource ->
            val disposition = synchronized(seen) {
                if (seen.containsKey(resource)) 0
                else { val first = seen.isEmpty(); seen[resource] = true; if (first) 1 else 2 }
            }
            when (disposition) {
                1 -> continuation.resume(resource) { _, value, _ -> value.close() }
                2 -> resource.close()
            }
        }
    }

internal data class SceneFrame(val packageName: String, val windowId: Int, val displayId: Int,
    val bounds: RectPx, val rotation: Int, val insets: RectPx, val imeBounds: RectPx?)

/** Main-thread scene ledger. Window events are clues; observed geometry determines the scene. */
internal class SceneTracker {
    private var frame: SceneFrame? = null
    private var version = 0L
    private var contentDirty = false
    fun contentChanged(windowId: Int, ownedWindowIds: Set<Int>) {
        if (windowId !in ownedWindowIds && windowId == frame?.windowId) contentDirty = true
    }
    fun observe(next: SceneFrame): SceneKey {
        if (frame != next || contentDirty) { version++; frame = next; contentDirty = false }
        return SceneKey(next.packageName, next.windowId, next.displayId, version)
    }
}
