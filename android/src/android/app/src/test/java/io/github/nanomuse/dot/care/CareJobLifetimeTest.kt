package io.github.nanomuse.dot.care

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareJobLifetimeTest {
    @Test fun admissionPrecedesDispatchAndCancelledLazyJobReleases() = runTest {
        val owner=CareJobLifetime(); var acted=false
        val job=owner.launch(backgroundScope,start=CoroutineStart.LAZY) { acted=true }
        assertTrue(owner.busy.value); assertFalse(acted)
        job.cancel(); runCurrent()
        assertTrue(job.isCompleted); assertFalse(owner.busy.value); assertFalse(acted)
    }
    @Test fun cancelledJobRemainsOwnedThroughNonCancellableCleanup() = runTest {
        val owner=CareJobLifetime(); val cleanup=CompletableDeferred<Unit>()
        val job=owner.launch(backgroundScope) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        try {
            runCurrent(); job.cancel(); runCurrent()
            assertFalse(job.isActive); assertFalse(job.isCompleted); assertTrue(owner.busy.value)
            cleanup.complete(Unit); runCurrent()
            assertTrue(job.isCompleted); assertFalse(owner.busy.value)
        } finally { cleanup.complete(Unit) }
    }
    @Test fun setupRemainsOwnedUntilStructuredStreamChildCompletes() = runTest {
        val owner=CareJobLifetime(); val streamExit=CompletableDeferred<Unit>()
        val setup=owner.launch(backgroundScope) { launch { streamExit.await() } }
        runCurrent(); assertFalse(setup.isCompleted); assertTrue(owner.busy.value)
        streamExit.complete(Unit); runCurrent()
        assertTrue(setup.isCompleted); assertFalse(owner.busy.value)
    }
    @Test fun compactCompletionHandsOffToIndependentContinuationAndStream() = runTest {
        val owner=CareJobLifetime(); val compactExit=CompletableDeferred<Unit>()
        val streamExit=CompletableDeferred<Unit>(); val seen=mutableListOf<Boolean>()
        val collector=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.busy.collect { seen+=it } }
        val prompt=owner.acquire()
        owner.launch(backgroundScope) {
            val compact=owner.launch(backgroundScope) { compactExit.await() }
            compact.join()
            owner.launch(backgroundScope) { streamExit.await() }
        }
        prompt.close(); runCurrent(); compactExit.complete(Unit); runCurrent()
        assertEquals(listOf(false,true),seen); assertTrue(owner.busy.value)
        streamExit.complete(Unit); runCurrent(); assertEquals(listOf(false,true,false),seen)
        collector.cancel()
    }
    @Test fun cancellationOfContinuationDoesNotReleaseStillUnwindingCompact() = runTest {
        val owner=CareJobLifetime(); val cleanup=CompletableDeferred<Unit>()
        lateinit var compact: Job
        val continuation=owner.launch(backgroundScope) {
            compact=owner.launch(backgroundScope) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            }
            compact.join()
        }
        try {
            runCurrent(); continuation.cancel(); compact.cancel(); runCurrent()
            assertTrue(continuation.isCompleted); assertFalse(compact.isCompleted); assertTrue(owner.busy.value)
            cleanup.complete(Unit); runCurrent(); assertFalse(owner.busy.value)
        } finally { cleanup.complete(Unit) }
    }
    @Test fun failedOwnerAndOverlappingJobReleaseExactlyOnce() = runTest {
        val owner=CareJobLifetime(); val exit=CompletableDeferred<Unit>()
        val failures=mutableListOf<Throwable>()
        val supervisor=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler)+
            CoroutineExceptionHandler { _,failure -> failures+=failure })
        try {
            val failed=owner.launch(supervisor) { error("owned failure") }
            owner.launch(supervisor) { exit.await() }
            val pending=owner.acquire(); runCurrent(); pending.close(); pending.close()
            assertTrue(failed.isCompleted); assertEquals("owned failure",failures.single().message)
            assertTrue(owner.busy.value)
            exit.complete(Unit); runCurrent(); assertFalse(owner.busy.value)
        } finally { supervisor.cancel() }
    }

    @Test fun interceptedNonStreamPromptRetainsItsDetachedVmAnnouncement() = runTest {
        val owner=CareJobLifetime(); val announcementExit=CompletableDeferred<Unit>()
        var uiStreaming=false
        val intercepted=owner.launch(backgroundScope) {
            assertFalse(uiStreaming)
            owner.launch(backgroundScope) { announcementExit.await() }
        }
        runCurrent(); assertTrue(intercepted.isCompleted); assertFalse(uiStreaming)
        assertTrue("Early-returning prompt still owns its VM announcement",owner.busy.value)
        announcementExit.complete(Unit); runCurrent(); assertFalse(owner.busy.value)
    }
    @Test fun alreadyCancelledScopeCannotLeakAdmission() = runTest {
        val owner=CareJobLifetime(); val scope=CoroutineScope(Job().apply { cancel() }+StandardTestDispatcher(testScheduler))
        val job=owner.launch(scope) { fail("cancelled scope cannot act") }
        runCurrent(); assertTrue(job.isCompleted); assertFalse(owner.busy.value)
    }
}
