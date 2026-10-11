package io.github.nanomuse.dot.screen

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

data class RectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}
data class NodeDto(val ref: String, val bounds: RectPx, val text: String?, val description: String?,
    val viewId: String?, val role: String, val focused: Boolean, val clickable: Boolean,
    val editable: Boolean, val enabled: Boolean, val password: Boolean)
data class SceneKey(val packageName: String, val windowId: Int, val displayId: Int, val version: Long)
enum class CapturePurpose { Chat, Care, Selection }
sealed interface CaptureOutcome {
    data class Success(val snapshot: ScreenSnapshot) : CaptureOutcome
    data class Unavailable(val code: String) : CaptureOutcome
}
/** Ownership transfers to the receiver; close the snapshot when its attachment is discarded.
 * Insets stores host edge distances from the display: left/top/right/bottom margins in pixels,
 * including IME and multiwindow space. It is not a geometric rectangle or universal WindowInsets;
 * never apply RectPx.width/height to this field.
 */
class ScreenSnapshot(val id: String, val generation: Long, val capturedAtMs: Long, val scene: SceneKey,
    val bounds: RectPx, val rotation: Int, val insets: RectPx, val imeBounds: RectPx?,
    nodes: List<NodeDto>, val image: Bitmap?, val transform: ScreenTransform) : AutoCloseable {
    val nodes = nodes.toList()
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) image?.recycle() }
}
