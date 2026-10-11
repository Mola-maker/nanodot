package io.github.nanomuse.dot.care

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Executes the production composable with a real Recomposer and frame clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class CareInstructionRevealTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test fun idleFirstAndNextInstructionResetActualAnimationAndWaitForMeasurement() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(EmptyApplier(), recomposer)
        var current by mutableStateOf<String?>(null)
        var measured by mutableStateOf(false)
        var observed = -1f
        var time = 0L
        fun frame(ms: Long = 16) {
            Snapshot.sendApplyNotifications(); runCurrent()
            time += ms * 1_000_000; clock.sendFrame(time); runCurrent()
        }
        try {
            composition.setContent { observed = rememberCareInstructionReveal(current, true, measured) }
            frame(); assertEquals(1f, observed, 0f)
            current = "first"; frame(); assertEquals("First proposal starts hidden", 0f, observed, 0f)
            frame(1200); assertEquals("Unmeasured proposal remains hidden", 0f, observed, 0f)
            measured = true; frame(); frame(); frame(600)
            assertEquals(0f, careCueProgress((observed * 980).toLong(), true).bubble, 0f)
            frame(200); assertEquals(0f, careCueProgress((observed * 980).toLong(), true).bubble, .01f)
            frame(180); frame(); assertEquals(1f, observed, .01f)
            current = "next"; measured = false; frame()
            assertEquals("Next proposal owns a fresh animation", 0f, observed, 0f)
            measured = true; frame(); frame(); frame(980); frame()
            assertEquals(1f, observed, .01f)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun reducedMotionIsVisibleBeforeMeasurementAndHasNoFrameTimingGate() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(EmptyApplier(), recomposer)
        var current by mutableStateOf<String?>(null)
        var observed = -1f
        try {
            composition.setContent { observed = rememberCareInstructionReveal(current, false, false) }
            runCurrent(); assertEquals(1f, observed, 0f)
            current = "first"; Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(16_000_000); runCurrent()
            assertEquals(1f, observed, 0f)
            current = "next"; Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(32_000_000); runCurrent()
            assertEquals(1f, observed, 0f)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }
}
