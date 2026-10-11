package io.github.nanomuse.dot.screen

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureCoordinatorTest {
    private class Resource : AutoCloseable { var closes = 0; override fun close() { closes++ } }
    @Test fun lateCallbackReleased() = runTest {
        var callback: ((Resource) -> Unit)? = null
        val job = launch { awaitOwnedResource<Resource> { callback = it } }
        runCurrent(); job.cancelAndJoin()
        val late = Resource(); callback!!(late); callback!!(late)
        assertEquals(1, late.closes)
    }
    @Test fun deliveredBeforeDispatchCancellationReleased() = runTest {
        var callback: ((Resource) -> Unit)? = null
        val job = launch { awaitOwnedResource<Resource> { callback = it }.close() }
        runCurrent(); val resource = Resource(); callback!!(resource); job.cancelAndJoin()
        assertEquals(1, resource.closes)
    }
    @Test fun duplicateCallbackReleasedAndNewRequestDoesNotReceiveOldResource() = runTest {
        val callbacks = mutableListOf<(Resource) -> Unit>()
        val first = launch { awaitOwnedResource<Resource> { callbacks.add(it) }.close() }
        runCurrent(); first.cancelAndJoin()
        val next = async { awaitOwnedResource<Resource> { callbacks.add(it) } }
        runCurrent()
        val old = Resource(); callbacks[0](old)
        assertFalse(next.isCompleted); assertEquals(1, old.closes)
        val current = Resource(); callbacks[1](current)
        assertSame(current, next.await()); current.close(); assertEquals(1, current.closes)
    }
    @Test fun capturesAreSerialAndCancelledWaiterReleasesQueue() = runTest {
        val coordinator = CaptureCoordinator()
        val events = mutableListOf<String>()
        val first = launch { coordinator.serial { events += "first-start"; awaitCancellation() } }
        runCurrent()
        val next = async { coordinator.serial { events += "next-start"; 42 } }
        runCurrent()
        assertEquals(listOf("first-start"),events)
        first.cancelAndJoin()
        assertEquals(42,next.await())
        assertEquals(listOf("first-start","next-start"),events)
    }
    @Test fun timeoutReleasesLateResourceAndNextCaptureRemainsFresh() = runTest {
        val coordinator = CaptureCoordinator()
        var callback: ((Resource) -> Unit)? = null
        val timed = async { withTimeoutOrNull(10) { coordinator.serial { awaitOwnedResource<Resource> { callback = it } } } }
        runCurrent(); advanceTimeBy(10); runCurrent()
        assertNull(timed.await())
        val late = Resource(); callback!!(late); assertEquals(1,late.closes)
        val fresh = Resource()
        assertSame(fresh,coordinator.serial { awaitOwnedResource<Resource> { it(fresh) } })
        fresh.close(); assertEquals(1,fresh.closes)
    }
    @Test fun hostImeChangesScene() {
        val tracker = SceneTracker()
        val frame = SceneFrame("host", 9, 0, RectPx(0f, 24f, 540f, 924f), 0, RectPx(0f,24f,0f,36f), null)
        assertEquals(1, tracker.observe(frame).version)
        tracker.contentChanged(99, setOf(99))
        assertEquals(1, tracker.observe(frame).version)
        val ime = frame.copy(imeBounds=RectPx(0f,600f,540f,924f), bounds=RectPx(0f,24f,540f,600f))
        assertEquals(2, tracker.observe(ime).version)
        tracker.contentChanged(9, setOf(99))
        assertEquals(3, tracker.observe(ime).version)
    }
}
