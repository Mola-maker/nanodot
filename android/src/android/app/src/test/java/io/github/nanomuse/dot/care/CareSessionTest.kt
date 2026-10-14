package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareSessionTest {
    @Test fun observedStepOutcomePrecedesNextInstructionSoCurrentTargetStaysAtHistoryEnd() = runTest {
        var scene = SceneKey("fixture.app",7,0,3)
        var sample = 0
        val controller = CareController(backgroundScope, { generation ->
            sample++
            CaptureOutcome.Success(careScreen(id="sample:$sample",scene=scene,generation=generation,
                nodes=listOf(careNode(ref="field:$sample",focused=sample>1))))
        }, { scene }, { _, snapshot, verified ->
            CarePlanResult.Step(careProposal().copy(snapshotId=snapshot.id,scene=scene,
                targetRef=snapshot.nodes.single().ref,expectation=CareExpectation.Focused(snapshot.nodes.single().ref),
                instruction=if(verified.isEmpty()) "Touch Search" else "Enter tea"))
        }, 100)
        val session=CareSession(controller, {}, {})
        session.submitGoal("Find tea"); runCurrent(); session.observeConversation()
        assertFalse(session.conversation.rows.any { it.verified })
        scene=scene.copy(version=4); controller.onSceneChanged(); advanceTimeBy(100); runCurrent()
        session.observeConversation()
        assertEquals(1,controller.verifiedSteps.value.size)
        assertEquals(listOf("Find tea","Touch Search","Touch Search","Enter tea"),session.conversation.rows.map { it.text })
        assertTrue(session.conversation.rows[2].verified)
        assertEquals(session.instructionKey(),session.conversation.rows.last().id)
        session.observeConversation(); assertEquals(4,session.conversation.rows.size)
    }
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
        val vm=CareJobLifetime(); val exit=CompletableDeferred<Unit>()
        vm.launch(backgroundScope) { exit.await() }
        try {
            withTimeout(50) {
                owners.acquire().use {
                    owners.retainUntilIdle(backgroundScope,vm.busy)
                    delay(100)
                }
            }
            fail("The remote wait must time out")
        } catch (_: TimeoutCancellationException) { }
        r.session.resume(); runCurrent(); assertEquals("A timed-out caller cannot release its live VM",1,r.captures)
        exit.complete(Unit); runCurrent()
        assertEquals(CareState.Paused,r.controller.state.value.state)
        r.session.resume(); runCurrent(); assertEquals(2,r.captures)
    }
    @Test fun callerCancellationKeepsIndependentLiveOperationPaused() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        r.controller.start("Find tea"); runCurrent()
        val vm=CareJobLifetime(); val exit=CompletableDeferred<Unit>()
        vm.launch(backgroundScope) { exit.await() }
        val caller=launch {
            owners.acquire().use { owners.retainUntilIdle(backgroundScope,vm.busy); awaitCancellation() }
        }
        runCurrent(); caller.cancelAndJoin()
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
        exit.complete(Unit); runCurrent(); assertEquals(CareState.Paused,r.controller.state.value.state)
        r.session.resume(); runCurrent(); assertEquals(2,r.captures)
    }
    @Test fun compactConfirmationAndPendingSendRemainBusyAcrossStreamingTransitions() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        r.controller.start("Find tea"); runCurrent()
        val vm=CareJobLifetime(); val prompt=vm.acquire()
        val compactExit=CompletableDeferred<Unit>(); val streamExit=CompletableDeferred<Unit>()
        owners.retainUntilIdle(backgroundScope,vm.busy); runCurrent()
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
        vm.launch(backgroundScope) {
            vm.launch(backgroundScope) { compactExit.await() }.join()
            vm.launch(backgroundScope) { streamExit.await() }
        }
        prompt.close(); runCurrent()
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
        compactExit.complete(Unit); runCurrent()
        r.session.resume(); runCurrent(); assertEquals(1,r.captures)
        streamExit.complete(Unit); runCurrent()
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
    @Test fun cancelledVmOwnerWithIdleUiCannotResumeCareUntilCleanupCompletes() = runTest {
        val r=Rig(backgroundScope); val owners=CareOperationOwners(); owners.observe(r.session::automatic)
        r.controller.start("Find tea"); runCurrent()
        val vm=CareJobLifetime(); val cleanup=CompletableDeferred<Unit>()
        val uiStreaming=kotlinx.coroutines.flow.MutableStateFlow(true)
        val vmJob=vm.launch(backgroundScope) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        owners.acquire().use { owners.retainUntilIdle(backgroundScope,vm.busy) }
        try {
            runCurrent(); vmJob.cancel(); uiStreaming.value=false; runCurrent()
            assertFalse(uiStreaming.value); assertFalse(vmJob.isActive); assertFalse(vmJob.isCompleted)
            r.session.resume(); runCurrent(); assertEquals("Cancellation is not VM completion",1,r.captures)
            cleanup.complete(Unit); runCurrent(); assertTrue(vmJob.isCompleted)
            assertEquals(CareState.Paused,r.controller.state.value.state)
            r.session.resume(); runCurrent(); assertEquals(2,r.captures)
        } finally { cleanup.complete(Unit) }
    }
    @Test fun endAndDisconnectClearWindowsAndPendingEvidence() = runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent(); r.session.end()
        assertEquals(CareState.Completed,r.controller.state.value.state)
        assertFalse(r.session.visible.value); assertNull(r.controller.state.value.target)
        r.session.disconnect(); assertEquals(CareState.Idle,r.controller.state.value.state)
        assertEquals(2,r.clears); assertEquals(2,r.stops)
    }
}
