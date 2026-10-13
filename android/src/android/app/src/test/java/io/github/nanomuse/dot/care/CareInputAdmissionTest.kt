package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareInputAdmissionTest {
    @Test fun inputDismissalAndSceneChangesCannotCaptureOrPlanBeforeAdmission() = runTest {
        val inputSettled = CompletableDeferred<Unit>()
        var scene = SceneKey("fixture.app", 7, 0, 3)
        var captures = 0
        var plans = 0
        var plannedScene: SceneKey? = null
        val controller = CareController(backgroundScope, { generation ->
            captures++
            CaptureOutcome.Success(careScreen(generation = generation, scene = scene))
        }, { scene }, { _, snapshot, _ ->
            plans++
            plannedScene = snapshot.scene
            CarePlanResult.Step(careProposal().copy(scene = snapshot.scene))
        }, debounceMs = 100)
        controller.start("Find tea") { inputSettled.await() }
        runCurrent()
        assertEquals(CareState.Observing, controller.state.value.state)
        assertEquals("The editor/IME handoff must precede the first screenshot", 0, captures)
        assertEquals(0, plans)
        advanceTimeBy(50)
        scene = scene.copy(version = 4)
        controller.onSceneChanged()
        advanceTimeBy(150)
        runCurrent()
        assertEquals("A scene event must not bypass pending input admission", 0, captures)
        assertEquals(0, plans)
        inputSettled.complete(Unit)
        runCurrent()
        assertEquals(0, captures)
        assertEquals(0, plans)
        advanceTimeBy(99)
        runCurrent()
        assertEquals("Scene debounce still follows successful input admission", 0, captures)
        assertEquals(0, plans)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, captures)
        assertEquals(1, plans)
        assertEquals(scene, plannedScene)
        assertEquals(CareState.AwaitingUser, controller.state.value.state)
        assertTrue(controller.verifiedSteps.value.isEmpty())
    }

    @Test fun stopDuringInputAdmissionCannotCaptureAfterTheInputSettles() = runTest {
        val inputSettled = CompletableDeferred<Unit>()
        val scene = SceneKey("fixture.app", 7, 0, 3)
        var captures = 0
        val controller = CareController(backgroundScope, { generation ->
            captures++
            CaptureOutcome.Success(careScreen(generation = generation, scene = scene))
        }, { scene }, { _, _, _ ->
            fail("Stop during input admission must prevent the provider call")
            CarePlanResult.Unavailable("unexpected")
        })
        controller.start("Find tea") { inputSettled.await() }
        runCurrent()
        assertEquals(0, captures)
        controller.pause()
        inputSettled.complete(Unit)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, captures)
        assertEquals(CareState.Paused, controller.state.value.state)
        assertNull(controller.state.value.target)
        assertTrue(controller.verifiedSteps.value.isEmpty())
    }

    @Test fun sceneRestartsShareOneInputAdmissionDeadline() = runTest {
        val inputSettled = CompletableDeferred<Unit>()
        var scene = SceneKey("fixture.app", 7, 0, 3)
        var admissions = 0
        var captures = 0
        val controller = CareController(backgroundScope, { generation ->
            captures++
            CaptureOutcome.Success(careScreen(generation = generation, scene = scene))
        }, { scene }, { _, _, _ -> CarePlanResult.Unavailable("unexpected") })
        controller.start("Find tea") { admissions++; inputSettled.await() }
        runCurrent()
        repeat(4) {
            advanceTimeBy(1000)
            scene = scene.copy(version = scene.version + 1)
            controller.onSceneChanged()
            runCurrent()
        }
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, admissions)
        assertEquals(0, captures)
        assertEquals(CareState.Recovering, controller.state.value.state)
        assertEquals("care_timeout", controller.state.value.errorCode)
        // A further automatic event cannot renew a failed admission deadline.
        scene = scene.copy(version = scene.version + 1)
        controller.onSceneChanged()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, admissions)
        assertEquals("care_timeout", controller.state.value.errorCode)
        inputSettled.complete(Unit)
        runCurrent()
        assertEquals(0, captures)
    }

    @Test fun explicitResumeStillWaitsForThePendingInputToSettle() = runTest {
        val inputSettled = CompletableDeferred<Unit>()
        val scene = SceneKey("fixture.app", 7, 0, 3)
        var admissions = 0
        var cancelledAdmissions = 0
        var captures = 0
        val controller = CareController(backgroundScope, { generation ->
            captures++
            CaptureOutcome.Success(careScreen(generation = generation, scene = scene))
        }, { scene }, { _, snapshot, _ -> CarePlanResult.Step(careProposal().copy(scene = snapshot.scene)) })
        controller.start("Find tea") {
            admissions++
            try { inputSettled.await() } finally { cancelledAdmissions++ }
        }
        runCurrent()
        controller.pause()
        runCurrent()
        assertEquals(1, cancelledAdmissions)
        assertEquals(0, captures)
        controller.resume()
        runCurrent()
        assertEquals(2, admissions)
        assertEquals(0, captures)
        assertEquals(CareState.Observing, controller.state.value.state)
        inputSettled.complete(Unit)
        runCurrent()
        assertEquals(1, captures)
        assertEquals(CareState.AwaitingUser, controller.state.value.state)
        assertTrue(controller.verifiedSteps.value.isEmpty())
    }


    @Test fun continuousSceneEventsCannotPostponeInputTimeoutRecovery() = runTest {
        val inputSettled = CompletableDeferred<Unit>()
        var scene = SceneKey("fixture.app", 7, 0, 3)
        var admissions = 0
        var captures = 0
        val controller = CareController(backgroundScope, { generation ->
            captures++
            CaptureOutcome.Success(careScreen(generation = generation, scene = scene))
        }, { scene }, { _, _, _ -> CarePlanResult.Unavailable("unexpected") })
        controller.start("Find tea") { admissions++; inputSettled.await() }
        runCurrent()
        // Faster than the 150ms scene debounce, including an event at the deadline.
        repeat(100) {
            advanceTimeBy(50)
            scene = scene.copy(version = scene.version + 1)
            controller.onSceneChanged()
            runCurrent()
        }
        assertEquals(1, admissions)
        assertEquals(0, captures)
        assertEquals(CareState.Recovering, controller.state.value.state)
        assertEquals("care_timeout", controller.state.value.errorCode)
    }

}
