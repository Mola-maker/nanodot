package io.github.nanomuse.dot.selection

import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionCoordinatorTest {
    private val host = RectPx(10f, 20f, 410f, 620f)
    private val key = SceneKey("fixture.host", 7, 0, 1)
    private val region = RegionSelection(listOf(PointPx(20f,30f), PointPx(120f,30f),
        PointPx(120f,130f), PointPx(20f,130f)), RectPx(20f,30f,120f,130f), 10000f)
    private fun shot(scene: SceneKey = key, generation: Long = 9) = ScreenSnapshot("fixture",generation,42,
        scene,host,0,RectPx(0f,0f,0f,0f),null,emptyList(),null,ScreenTransform(host,400,600))

    @Test fun sameSceneTransfersOnlyTheDerivedEvidence() = runTest {
        var captures = 0
        var crops = 0
        val derived = shot()
        val coordinator = SelectionCoordinator({ key }, { generation ->
            captures++; assertEquals(9L,generation); CaptureOutcome.Success(shot())
        }, { source, selection ->
            crops++; assertEquals("fixture",source.id); assertEquals(region,selection); derived
        })
        val result = coordinator.capture(region,SelectionOrigin(key,host),9) { true }
        assertTrue(result is CaptureOutcome.Success)
        assertSame(derived,(result as CaptureOutcome.Success).snapshot)
        assertEquals(1,captures); assertEquals(1,crops)
    }

    @Test fun sceneChangedDuringGestureCannotCaptureTheNextPage() = runTest {
        val coordinator = SelectionCoordinator({ key.copy(version=2) }, { error("Must not acquire next page") },
            { _, _ -> error("Must not crop") })
        assertEquals(CaptureOutcome.Unavailable("scene_changed"),
            coordinator.capture(region,SelectionOrigin(key,host),9) { true })
    }

    @Test fun stopWhileCaptureIsHeldCannotCropOrPublishLateResult() = runTest {
        val pending = CompletableDeferred<CaptureOutcome>()
        var current = true
        val coordinator = SelectionCoordinator({ key }, { pending.await() }, { _, _ -> error("Late crop") })
        val work = async { coordinator.capture(region,SelectionOrigin(key,host),9) { current } }
        runCurrent(); current=false; pending.complete(CaptureOutcome.Success(shot())); runCurrent()
        assertEquals(CaptureOutcome.Unavailable("selection_cancelled"),work.await())
    }

    @Test fun sceneOrGenerationMismatchRejectsAcquiredPixels() = runTest {
        for (source in listOf(shot(key.copy(version=2)),shot(generation=10))) {
            val coordinator = SelectionCoordinator({ key }, { CaptureOutcome.Success(source) },
                { _, _ -> error("Mismatched evidence cannot be cropped") })
            assertEquals(CaptureOutcome.Unavailable("scene_changed"),
                coordinator.capture(region,SelectionOrigin(key,host),9) { true })
        }
    }

    @Test fun outsideHostPathIsNotSilentlyReinterpretedAsARegion() = runTest {
        val coordinator = SelectionCoordinator({ key }, { error("Outside host must not capture") },
            { _, _ -> error("No crop") })
        assertEquals(CaptureOutcome.Unavailable("selection_outside_host"),coordinator.capture(region,
            SelectionOrigin(key,RectPx(50f,50f,410f,620f)),9) { true })
    }
}
