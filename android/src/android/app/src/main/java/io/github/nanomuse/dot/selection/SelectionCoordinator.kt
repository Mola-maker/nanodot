package io.github.nanomuse.dot.selection

import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class SelectionOrigin(val scene: SceneKey, val viewport: RectPx)

/** Acquisition only; the existing foreground job owns cancellation and the canonical binding. */
class SelectionCoordinator(
    private val scene: () -> SceneKey?,
    private val capture: suspend (Long) -> CaptureOutcome,
    private val crop: suspend (ScreenSnapshot, RegionSelection) -> ScreenSnapshot,
) {
    suspend fun capture(selection: RegionSelection, origin: SelectionOrigin, generation: Long,
        admitted: () -> Boolean): CaptureOutcome {
        if (!admitted()) return CaptureOutcome.Unavailable("selection_cancelled")
        if (scene() != origin.scene) return CaptureOutcome.Unavailable("scene_changed")
        val box=selection.bounds
        val host=origin.viewport
        if (box.left < host.left || box.top < host.top || box.right > host.right || box.bottom > host.bottom)
            return CaptureOutcome.Unavailable("selection_outside_host")
        val result=capture(generation)
        if (result !is CaptureOutcome.Success) return result
        result.snapshot.use { source ->
            coroutineContext.ensureActive()
            if (!admitted()) return CaptureOutcome.Unavailable("selection_cancelled")
            if (scene() != origin.scene || source.scene != origin.scene || source.generation != generation)
                return CaptureOutcome.Unavailable("scene_changed")
            // Only the finite CPU crop handoff is protected. Otherwise cancellation while its
            // Default dispatcher returns can discard an allocated bitmap before we own/close it.
            // NonCancellable retains our dispatcher, so no cancellable dispatch occurs on return.
            val cropped=withContext(NonCancellable) { crop(source,selection) }
            try {
                coroutineContext.ensureActive()
                if (!admitted()) { cropped.close(); return CaptureOutcome.Unavailable("selection_cancelled") }
                if (scene() != origin.scene) { cropped.close(); return CaptureOutcome.Unavailable("scene_changed") }
                return CaptureOutcome.Success(cropped)
            } catch (failure: Throwable) { cropped.close(); throw failure }
        }
    }
}
