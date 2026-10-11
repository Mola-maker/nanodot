package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareSessionTest {
    private class Rig(scope: CoroutineScope) {
        var scene: SceneKey? = SceneKey("fixture.app",7,0,3)
        var captures = 0
        var clears = 0
        var stops = 0
        val controller = CareController(scope, { gen ->
            captures++
            CaptureOutcome.Success(careScreen(generation=gen, scene=requireNotNull(scene)))
        }, { scene }, { _, s, _ -> CarePlanResult.Step(careProposal().copy(snapshotId=s.id,scene=s.scene)) }, 100)
        val session = CareSession(controller, { clears++ }, { stops++ })
    }
    @Test fun entryShowsGoalWithoutCapturingOrPlanning() = runTest {
        val r=Rig(backgroundScope); r.session.enter(); runCurrent()
        assertTrue(r.session.visible.value); assertTrue(r.session.editingGoal.value)
        assertEquals(0,r.captures)
    }
    @Test fun submittedGoalBindsControllerAndOnlyExactCurrentTargetCanDraw() = runTest {
        val r=Rig(backgroundScope); r.session.enter(); r.session.submitGoal("Find tea"); runCurrent()
        assertEquals(CareState.AwaitingUser,r.controller.state.value.state)
        assertFalse(r.session.editingGoal.value)
        assertNotNull(r.session.focusBounds(r.scene))
        assertNull(r.session.focusBounds(r.scene!!.copy(version=4)))
        assertNull(r.session.focusBounds(null))
    }
    @Test fun sameSceneSelfWindowClueDoesNotClearOrCapture() = runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.session.sceneChanged(r.scene,r.scene); advanceTimeBy(200); runCurrent()
        assertEquals(0,r.clears); assertEquals(1,r.captures)
    }
    @Test fun hostClueClearsBeforeDebounceAndDoesNotCreditGenericTouch() = runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        val before=r.scene; r.scene=r.scene!!.copy(version=4)
        r.session.sceneChanged(before,r.scene)
        assertEquals(1,r.clears); assertNull(r.controller.state.value.target)
        assertEquals(1,r.captures); advanceTimeBy(100); runCurrent()
        assertEquals(2,r.captures); assertTrue(r.controller.verifiedSteps.value.isEmpty())
    }
    @Test fun backPausesAndHidesThenEntryRetainsExplicitResume() = runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.session.pause(hide=true)
        assertEquals(CareState.Paused,r.controller.state.value.state)
        assertFalse(r.session.visible.value); assertEquals(1,r.stops)
        r.session.enter(); runCurrent()
        assertTrue(r.session.visible.value); assertFalse(r.session.editingGoal.value)
        assertEquals(1,r.captures)
        r.session.resume(); runCurrent(); assertEquals(2,r.captures)
    }
    @Test fun changingGoalCancelsPendingWorkAndClearsInstruction() = runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.session.changeGoal()
        assertTrue(r.session.editingGoal.value); assertNull(r.controller.state.value.target)
        assertNull(r.controller.state.value.pendingInstruction); assertEquals(1,r.stops)
    }
    @Test fun operationPausesBeforeActionAndReleaseNeverResumes() = runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        owners.acquire().use {
            assertEquals(CareState.Paused,r.controller.state.value.state)
            assertNull(r.controller.state.value.target)
        }
        runCurrent(); assertEquals(CareState.Paused,r.controller.state.value.state); assertEquals(1,r.captures)
    }
    @Test fun overlappingAndAlreadyRunningOwnersBlockStartUntilAllReleaseAndUserResumes() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners()
        val first=owners.acquire(); val second=owners.acquire(); owners.observe(r.session::automatic)
        r.session.submitGoal("Find tea"); runCurrent(); assertEquals(0,r.captures)
        first.close(); first.close(); r.session.resume(); runCurrent(); assertEquals(0,r.captures)
        second.close(); runCurrent(); assertEquals(0,r.captures)
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
    }
    @Test fun exceptionalOperationReleaseAllowsOnlyExplicitResume() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        r.controller.start("Find tea"); runCurrent()
        runCatching { owners.acquire().use { error("fixture failure") } }
        assertEquals(CareState.Paused,r.controller.state.value.state)
        r.session.resume(); runCurrent(); assertEquals(2,r.captures)
    }

    @Test fun timedOutCallerDoesNotReleaseRunningRemoteWork() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        r.controller.start("Find tea"); runCurrent()
        val busy=kotlinx.coroutines.flow.MutableStateFlow(true)
        try {
            withTimeout(50) {
                owners.acquire().use {
                    owners.retainUntilIdle(backgroundScope,busy)
                    delay(100)
                }
            }
            fail("The remote wait must time out")
        } catch (_: TimeoutCancellationException) { }
        r.session.resume(); runCurrent(); assertEquals("A timed-out caller cannot release its live VM",1,r.captures)
        busy.value=false; runCurrent()
        assertEquals(CareState.Paused,r.controller.state.value.state)
        r.session.resume(); runCurrent(); assertEquals(2,r.captures)
    }
    @Test fun callerCancellationKeepsIndependentLiveOperationPaused() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        r.controller.start("Find tea"); runCurrent()
        val busy=kotlinx.coroutines.flow.MutableStateFlow(true)
        val caller=launch {
            owners.acquire().use { owners.retainUntilIdle(backgroundScope,busy); awaitCancellation() }
        }
        runCurrent(); caller.cancelAndJoin()
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
        busy.value=false; runCurrent(); assertEquals(CareState.Paused,r.controller.state.value.state)
        r.session.resume(); runCurrent(); assertEquals(2,r.captures)
    }
    @Test fun compactConfirmationAndPendingSendRemainBusyAcrossStreamingTransitions() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        r.controller.start("Find tea"); runCurrent()
        val streaming=kotlinx.coroutines.flow.MutableStateFlow(false)
        val compacting=kotlinx.coroutines.flow.MutableStateFlow(false)
        val pending=kotlinx.coroutines.flow.MutableStateFlow(false)
        val prompt=kotlinx.coroutines.flow.MutableStateFlow(true)
        val busy=careOperationActivity(streaming,compacting,pending,prompt)
        owners.retainUntilIdle(backgroundScope,busy); runCurrent()
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
        prompt.value=false; pending.value=true; compacting.value=true; runCurrent()
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
        compacting.value=false; streaming.value=true; pending.value=false; runCurrent()
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
        streaming.value=false; runCurrent()
        assertEquals(CareState.Paused,r.controller.state.value.state)
        r.session.resume(); runCurrent(); assertEquals(2,r.captures)
    }
    @Test fun cancelledLifetimeWatcherReleasesItsOwnLeaseWithoutResumingCare() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        r.controller.start("Find tea"); runCurrent()
        val busy=kotlinx.coroutines.flow.MutableStateFlow(true)
        val job=owners.retainUntilIdle(backgroundScope,busy)
        assertEquals(CareState.Paused,r.controller.state.value.state)
        job.cancel(); runCurrent()
        assertEquals(CareState.Paused,r.controller.state.value.state)
        r.session.resume(); runCurrent(); assertEquals(2,r.captures)
    }
    @Test fun endAndDisconnectClearWindowsAndPendingEvidence() = runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent(); r.session.end()
        assertEquals(CareState.Completed,r.controller.state.value.state)
        assertFalse(r.session.visible.value); assertNull(r.controller.state.value.target)
        r.session.disconnect(); assertEquals(CareState.Idle,r.controller.state.value.state)
        assertEquals(2,r.clears); assertEquals(2,r.stops)
    }
}
