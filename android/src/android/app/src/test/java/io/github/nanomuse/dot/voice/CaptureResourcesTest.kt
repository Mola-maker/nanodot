package io.github.nanomuse.dot.voice
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
class CaptureResourcesTest {
    @Test fun cancellationFailureStillRequestsEveryResource() {
        val resources = CaptureResources(); var secondClosed = false
        resources.track({ error("first stop failed") }, {}); resources.track({ secondClosed = true }, {})
        runCatching { resources.cancel() }; assertTrue(secondClosed)
    }
    @Test fun joinFailureStillWaitsEveryResource() = runBlocking {
        val resources = CaptureResources(); var secondJoined = false
        resources.track({}, { error("first join failed") }); resources.track({}, { secondJoined = true })
        val error = runCatching { resources.closeAndJoin() }.exceptionOrNull()
        assertEquals("first join failed", error?.message); assertTrue(secondJoined)
    }
}
