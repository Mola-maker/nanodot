package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class CareControllerTest {
    private class Rig(val scope:CoroutineScope) {
        var scene:SceneKey?=SceneKey("fixture.app",7,0,3)
        var next:CaptureOutcome?=null
        var captures=0
        var plans=0
        val snapshots=mutableListOf<ScreenSnapshot>()
        var past=emptyList<CareProposal>()
        var planner:suspend (String,ScreenSnapshot,List<CareProposal>)->CarePlanResult={ _,s,history ->
            plans++; past=history
            CarePlanResult.Step(careProposal().copy(snapshotId=s.id,scene=s.scene,targetRef=s.nodes.first().ref,
                expectation=CareExpectation.Focused(s.nodes.first().ref)))
        }
        val controller=CareController(scope,{generation ->
            captures++
            next ?: CaptureOutcome.Success(careScreen(generation=generation,scene=requireNotNull(scene)))
        },{scene},{goal,s,history -> snapshots+=s; planner(goal,s,history)},debounceMs=100)
        fun changed(nodes:List<NodeDto> = listOf(careNode(ref="new",focused=true))) {
            scene=scene!!.copy(version=scene!!.version+1)
            next=CaptureOutcome.Success(careScreen(id="after-${scene!!.version}",scene=scene!!,
                generation=controller.generation,nodes=nodes))
            controller.onSceneChanged()
        }
    }
    @Test fun noEventOrElapsedTimeCanAdvanceStep()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        assertEquals(CareState.AwaitingUser,r.controller.state.value.state)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(r.controller.verifiedSteps.value.isEmpty())
        assertEquals(1,r.captures)
    }
    @Test fun sceneEventImmediatelyClearsBoundsThenDebouncesFreshCapture()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        assertNotNull(r.controller.state.value.target)
        r.changed(listOf(careNode(ref="new")))
        assertNull(r.controller.state.value.target); assertNull(r.controller.state.value.proposal)
        r.controller.onSceneChanged(); r.controller.onSceneChanged()
        advanceTimeBy(99); runCurrent(); assertEquals(1,r.captures)
        advanceTimeBy(1); runCurrent(); assertEquals(2,r.captures)
        assertTrue(r.controller.verifiedSteps.value.isEmpty())
        assertEquals("new",r.controller.state.value.proposal!!.targetRef)
    }
    @Test fun actualFocusEvidenceRecordsOnlyVerifiedStepAndPlansNext()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.changed(); advanceTimeBy(100); runCurrent()
        assertEquals(1,r.controller.verifiedSteps.value.size)
        assertEquals("before",r.controller.verifiedSteps.value.single().proposal.snapshotId)
        assertEquals("after-4",r.controller.verifiedSteps.value.single().afterSnapshotId)
        assertEquals(1,r.past.size)
        assertEquals(2,r.plans)
    }
    @Test fun wrongFocusNeverRecordsStep()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.changed(listOf(careNode(ref="new"),careNode(ref="wrong",viewId="app/ad",focused=true)))
        advanceTimeBy(100); runCurrent()
        assertTrue(r.controller.verifiedSteps.value.isEmpty()); assertEquals(1,r.plans)
    }
    @Test fun pauseClearsTargetAndResumeResamplesRatherThanReusingBounds()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.controller.pause(); assertEquals(CareState.Paused,r.controller.state.value.state)
        assertNull(r.controller.state.value.proposal); assertNull(r.controller.state.value.target)
        r.scene=r.scene!!.copy(version=4)
        r.next=CaptureOutcome.Success(careScreen(id="resume",version=4,generation=r.controller.generation+1,
            nodes=listOf(careNode(ref="resume",bounds=RectPx(5f,160f,150f,190f)))))
        r.controller.resume(); runCurrent()
        assertEquals(2,r.captures); assertEquals("resume",r.controller.state.value.target!!.ref)
        assertTrue(r.controller.verifiedSteps.value.isEmpty())
    }
    @Test fun automaticOperationPausesAndRequiresExplicitResumeAfterStopping()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.controller.onAutomaticOperation(true)
        r.controller.resume(); runCurrent(); assertEquals(CareState.Paused,r.controller.state.value.state)
        r.controller.onAutomaticOperation(false); r.controller.onSceneChanged()
        advanceTimeBy(1000); runCurrent(); assertEquals(1,r.captures)
        r.controller.resume(); runCurrent(); assertEquals(CareState.AwaitingUser,r.controller.state.value.state)
    }
    @Test fun startWhileAutomationActiveDoesNotCapture()=runTest {
        val r=Rig(backgroundScope); r.controller.onAutomaticOperation(true); r.controller.start("Find tea"); runCurrent()
        assertEquals(CareState.Paused,r.controller.state.value.state); assertEquals(0,r.captures)
    }
    @Test fun oldPlannerReplyAfterGoalChangeCannotInjectTarget()=runTest {
        val r=Rig(backgroundScope); val late=CompletableDeferred<CarePlanResult>()
        r.planner={goal,s,_ -> if(goal=="Old") withContext(NonCancellable){late.await()}
            else CarePlanResult.Clarify("Which tea?") }
        r.controller.start("Old"); runCurrent(); r.controller.start("New"); runCurrent()
        late.complete(CarePlanResult.Step(careProposal())); runCurrent()
        assertEquals("New",r.controller.state.value.goal); assertEquals(CareState.Clarifying,r.controller.state.value.state)
        assertNull(r.controller.state.value.pendingInstruction)
        assertNull(r.controller.state.value.target); assertTrue(r.controller.verifiedSteps.value.isEmpty())
        assertTrue(r.snapshots.all(::isClosed))
    }
    @Test fun sceneChangeWhilePlannerRunsRejectsLateReply()=runTest {
        val r=Rig(backgroundScope); val late=CompletableDeferred<CarePlanResult>()
        r.planner={_,_,_->withContext(NonCancellable){late.await()}}
        r.controller.start("Find tea"); runCurrent(); r.scene=r.scene!!.copy(version=4)
        r.controller.onSceneChanged(); late.complete(CarePlanResult.Step(careProposal())); runCurrent()
        assertNull(r.controller.state.value.target); assertTrue(r.controller.verifiedSteps.value.isEmpty())
    }
    @Test fun cancellationRejectsLateSamplerAndClosesOwnership()=runTest {
        val late=CompletableDeferred<CaptureOutcome>(); val s=careScreen()
        val c=CareController(backgroundScope,{withContext(NonCancellable){late.await()}},{s.scene},{_,_,_->
            fail("Late sample cannot plan"); CarePlanResult.Unavailable("unexpected")})
        c.start("Find tea"); runCurrent(); c.cancel(); late.complete(CaptureOutcome.Success(s)); runCurrent()
        assertEquals(CareState.Idle,c.state.value.state); assertTrue(isClosed(s))
    }
    @Test fun pauseRejectsLatePlannerAndClosesOwnership()=runTest {
        val r=Rig(backgroundScope); val late=CompletableDeferred<CarePlanResult>()
        r.planner={_,_,_->withContext(NonCancellable){late.await()}}
        r.controller.start("Find tea"); runCurrent(); r.controller.pause()
        late.complete(CarePlanResult.Step(careProposal())); runCurrent()
        assertEquals(CareState.Paused,r.controller.state.value.state); assertNull(r.controller.state.value.target)
        assertTrue(r.snapshots.all(::isClosed))
    }
    @Test fun secureColdEmptyAndMissingHostRecoverWithoutSuccess()=runTest {
        for(code in listOf("protected","blank_host_unavailable","accessibility_unavailable")) {
            val r=Rig(backgroundScope); r.next=CaptureOutcome.Unavailable(code)
            r.controller.start("Find tea"); runCurrent()
            assertEquals(CareState.Recovering,r.controller.state.value.state)
            assertEquals(code,r.controller.state.value.errorCode); assertTrue(r.controller.verifiedSteps.value.isEmpty())
        }
        val r=Rig(backgroundScope); r.scene=null; r.controller.start("Find tea"); runCurrent()
        assertEquals("host_unavailable",r.controller.state.value.errorCode)
    }
    @Test fun emptyOrWrongGenerationCaptureCannotPlan()=runTest {
        for(s in listOf(careScreen(nodes=emptyList()),careScreen(generation=999))) {
            val r=Rig(backgroundScope); r.next=CaptureOutcome.Success(s); r.controller.start("Find tea"); runCurrent()
            assertEquals(CareState.Recovering,r.controller.state.value.state)
            assertEquals(0,r.plans); assertTrue(isClosed(s))
        }
    }
    @Test fun ambiguousEvidenceRequestsConfirmationWithoutRecording()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.changed(listOf(careNode(ref="a",focused=true),careNode(ref="b")))
        advanceTimeBy(100); runCurrent()
        assertEquals(CareState.Recovering,r.controller.state.value.state)
        assertEquals("needs_user_confirmation",r.controller.state.value.errorCode)
        assertEquals("Use search",r.controller.state.value.pendingInstruction)
        assertNull(r.controller.state.value.target); assertTrue(r.controller.verifiedSteps.value.isEmpty())
    }
    @Test fun invalidProposalAndPlannerFailureRecoverAndCloseSnapshot()=runTest {
        for(throws in listOf(false,true)) {
            val r=Rig(backgroundScope); r.planner={_,_,_-> if(throws) error("private exception")
                else CarePlanResult.Step(careProposal().copy(snapshotId="stale")) }
            r.controller.start("Find tea"); runCurrent()
            assertEquals(CareState.Recovering,r.controller.state.value.state)
            assertNull(r.controller.state.value.proposal); assertTrue(r.snapshots.all(::isClosed))
            assertFalse(r.controller.state.value.errorCode.orEmpty().contains("private"))
        }
    }
    @Test fun goalChangeClearsVerifiedHistory()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.changed(); advanceTimeBy(100); runCurrent(); assertEquals(1,r.controller.verifiedSteps.value.size)
        r.controller.start("Find coffee"); runCurrent(); assertTrue(r.controller.verifiedSteps.value.isEmpty())
    }
    @Test fun explicitConfirmationResamplesAndRecordsUserOriginWithHonestHistory()=runTest {
        val r=Rig(backgroundScope)
        r.planner={_,s,past -> r.past=past; r.plans++
            CarePlanResult.Step(careProposal(CareAction.Copy,CareExpectation.TextEquals("old:field","tea"),"tea")
                .copy(snapshotId=s.id,scene=s.scene,targetRef=s.nodes.single().ref,
                    expectation=CareExpectation.TextEquals(s.nodes.single().ref,"tea"))) }
        r.controller.start("Find tea"); runCurrent(); r.changed(listOf(careNode(ref="new",text="tea")))
        advanceTimeBy(100); runCurrent()
        assertEquals("needs_user_confirmation",r.controller.state.value.errorCode)
        val beforeCount=r.captures
        r.next=CaptureOutcome.Success(careScreen(id="confirmed",scene=r.scene!!,generation=r.controller.generation))
        r.controller.confirmStepCompleted(); runCurrent()
        assertEquals(beforeCount+1,r.captures)
        assertEquals(VerificationOrigin.UserConfirmed,r.controller.verifiedSteps.value.single().origin)
        assertEquals("confirmed",r.controller.verifiedSteps.value.single().afterSnapshotId)
        assertEquals(1,r.past.size)
        assertEquals(3L,r.past.single().scene.version) // Original proposal evidence version stays honest.
    }
    @Test fun copyCanBeExplicitlyConfirmedWithoutInventingASceneChange()=runTest {
        val r=Rig(backgroundScope)
        r.planner={_,s,history -> r.past=history
            CarePlanResult.Step(careProposal(CareAction.Copy,CareExpectation.TextEquals("old:field","tea"),"tea")
                .copy(snapshotId=s.id,scene=s.scene)) }
        r.controller.start("Find tea"); runCurrent()
        assertEquals("needs_user_confirmation",r.controller.state.value.errorCode)
        r.next=CaptureOutcome.Success(careScreen(id="same-scene",generation=r.controller.generation))
        r.controller.confirmStepCompleted(); runCurrent()
        assertEquals(VerificationOrigin.UserConfirmed,r.controller.verifiedSteps.value.single().origin)
        assertEquals("same-scene",r.controller.verifiedSteps.value.single().afterSnapshotId)
        assertEquals(3L,r.controller.verifiedSteps.value.single().afterScene.version)
        assertEquals(1,r.past.size)
    }
    @Test fun copyConfirmationRejectsOriginalSnapshotIdentityWithoutRecordingOrReplanning()=runTest {
        val r=Rig(backgroundScope)
        r.planner={_,s,_-> r.plans++
            CarePlanResult.Step(careProposal(CareAction.Copy,CareExpectation.TextEquals("old:field","tea"),"tea")
                .copy(snapshotId=s.id,scene=s.scene)) }
        r.controller.start("Find tea"); runCurrent()
        assertEquals("needs_user_confirmation",r.controller.state.value.errorCode)
        val reused=careScreen(id="before",generation=r.controller.generation)
        r.next=CaptureOutcome.Success(reused)
        r.controller.confirmStepCompleted(); runCurrent()
        assertTrue("Original snapshot identity cannot be credited as fresh user admission",
            r.controller.verifiedSteps.value.isEmpty())
        assertEquals("Rejected confirmation cannot request another plan",1,r.plans)
        assertEquals(CareState.Recovering,r.controller.state.value.state)
        assertNull(r.controller.state.value.proposal); assertNull(r.controller.state.value.target)
        assertTrue("Rejected capture still closes its owned snapshot",isClosed(reused))
    }
    @Test fun confirmationWithoutPendingQuestionCannotAdvance()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.controller.confirmStepCompleted(); runCurrent()
        assertTrue(r.controller.verifiedSteps.value.isEmpty()); assertEquals(1,r.captures)
    }
    @Test fun confirmationAfterHostChangeCannotRecordStep()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.changed(listOf(careNode(ref="a",focused=true),careNode(ref="b")))
        advanceTimeBy(100); runCurrent()
        r.scene=r.scene!!.copy(packageName="other.app",version=5)
        r.next=CaptureOutcome.Success(careScreen(id="foreign",scene=r.scene!!,generation=r.controller.generation))
        r.controller.confirmStepCompleted(); runCurrent()
        assertTrue(r.controller.verifiedSteps.value.isEmpty())
        assertEquals("scene_changed",r.controller.state.value.errorCode)
    }
    @Test fun reobserveDiscardsPendingTargetButPreservesVerifiedHistory()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent()
        r.changed(); advanceTimeBy(100); runCurrent()
        r.planner={_,_,_->CarePlanResult.Clarify("Which product?")}
        r.next=CaptureOutcome.Success(careScreen(id="reobserved",scene=r.scene!!,generation=r.controller.generation))
        r.controller.reobserve(); assertNull(r.controller.state.value.target); runCurrent()
        assertEquals(CareState.Clarifying,r.controller.state.value.state)
        assertEquals("Which product?",r.controller.state.value.question)
        assertEquals(1,r.controller.verifiedSteps.value.size)
    }
    @Test fun pendingConfirmationTextClearsOnPauseGoalChangeCancelAndEnd()=runTest {
        for(end in listOf<(CareController)->Unit>({it.pause()},{it.start("Different goal")},{it.cancel()},{it.finish()})) {
            val r=Rig(backgroundScope)
            r.planner={_,s,_->CarePlanResult.Step(careProposal(CareAction.Copy,
                CareExpectation.TextEquals("old:field","tea"),"tea").copy(snapshotId=s.id,scene=s.scene))}
            r.controller.start("Find tea"); runCurrent()
            assertEquals("Use search",r.controller.state.value.pendingInstruction)
            end(r.controller)
            assertNull(r.controller.state.value.pendingInstruction)
            assertNull(r.controller.state.value.proposal); assertNull(r.controller.state.value.target)
        }
    }
    @Test fun finishEndsTaskWithoutClaimingAnUnverifiedStep()=runTest {
        val r=Rig(backgroundScope); r.controller.start("Find tea"); runCurrent(); r.controller.finish()
        assertEquals(CareState.Completed,r.controller.state.value.state); assertNull(r.controller.state.value.target)
        assertTrue(r.controller.verifiedSteps.value.isEmpty())
        r.controller.onSceneChanged(); advanceTimeBy(1000); runCurrent(); assertEquals(1,r.captures)
    }
}
private fun isClosed(s:ScreenSnapshot):Boolean = (ScreenSnapshot::class.java.getDeclaredField("closed")
    .apply{isAccessible=true}.get(s) as AtomicBoolean).get()
