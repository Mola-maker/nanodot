package io.github.nanomuse.dot.screen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import io.github.nanomuse.dot.overlay.DotOverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Service-scoped, read-only scene sampler. No capture is performed at construction or connection. */
class ScreenRepository(private val service: AccessibilityService, private val overlays: DotOverlayController) {
    private val coordinator = CaptureCoordinator()
    private val tracker = SceneTracker()
    private val displays = service.getSystemService(DisplayManager::class.java)
    private var connected = false
    fun connect() { connected = true }
    fun disconnect() { connected = false; LegacyProjectionService.stop(service) }
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Window-list changes are observed from current bounds, never package-wide invalidation.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            tracker.contentChanged(event.windowId, ownedWindowIds())
        }
        // Observe IME/display/content changes now, so async results cannot outlive a changed scene.
        if (connected && !locked()) readScene()?.use { tracker.observe(it.frame) }
    }

    suspend fun capture(purpose: CapturePurpose, generation: Long): CaptureOutcome = sample(purpose,generation,true)

    /** Explicit fresh text fallback; never reuses nodes from a failed pixel attempt. */
    suspend fun captureNodes(generation: Long): CaptureOutcome = sample(CapturePurpose.Care,generation,false)

    private suspend fun sample(purpose: CapturePurpose, generation: Long, pixelsRequested: Boolean): CaptureOutcome = coordinator.serial {
        var handoff: ScreenSnapshot? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                if (!connected) return@withContext CaptureOutcome.Unavailable("accessibility_unavailable")
                if (locked()) return@withContext CaptureOutcome.Unavailable("locked")
                try {
                    withTimeout(4000) {
                        overlays.withHiddenForCapture {
                            val before = readScene() ?: return@withHiddenForCapture CaptureOutcome.Unavailable("host_unavailable")
                            before.use {
                            val key = tracker.observe(before.frame)
                            val id = UUID.randomUUID().toString()
                            val root = before.window.root ?: return@withHiddenForCapture CaptureOutcome.Unavailable("nodes_unavailable")
                            if (root.windowId != before.frame.windowId) {
                                root.recycle()
                                return@withHiddenForCapture CaptureOutcome.Unavailable("nodes_unavailable")
                            }
                            val nodes = NodeReader.read(root, id)
                            if (nodes.firstOrNull()?.bounds != before.frame.bounds) {
                                return@withHiddenForCapture CaptureOutcome.Unavailable("scene_changed")
                            }
                            var pixels: Bitmap? = null
                            try {
                                val windowCapture = pixelsRequested && Build.VERSION.SDK_INT >= 34 && purpose == CapturePurpose.Care
                                val captureBounds = if (windowCapture) before.frame.bounds else before.displayBounds
                                if (pixelsRequested && Build.VERSION.SDK_INT >= 30) {
                                    val raw = accessibilityCapture(before.frame, windowCapture)
                                    raw.use {
                                        if (it.error != null) return@withHiddenForCapture CaptureOutcome.Unavailable(it.error)
                                        pixels = it.copyBitmap()
                                    }
                                    if (pixels == null) return@withHiddenForCapture CaptureOutcome.Unavailable("pixels_unavailable")
                                } else if (pixelsRequested) {
                                    val projection = LegacyProjectionService.capture
                                    if (projection != null) pixels = projection.capture(before.displayBounds)
                                }
                                val sceneMatches = readScene()?.use { after ->
                                    tracker.observe(after.frame) == key && after.displayBounds == before.displayBounds
                                } == true
                                if (!connected || locked()) return@withHiddenForCapture CaptureOutcome.Unavailable("locked_or_disconnected")
                                if (!sceneMatches) {
                                    return@withHiddenForCapture CaptureOutcome.Unavailable("scene_changed")
                                }
                                val image = pixels
                                if (image != null) {
                                    // Refuse stale/letterboxed/cropped buffers instead of inventing a coordinate origin.
                                    if (image.width != captureBounds.width.toInt() || image.height != captureBounds.height.toInt()) {
                                        return@withHiddenForCapture CaptureOutcome.Unavailable("geometry_changed")
                                    }
                                    if (isBlankHost(image, ScreenTransform(captureBounds,image.width,image.height), before.frame.bounds,nodes)) {
                                        return@withHiddenForCapture CaptureOutcome.Unavailable("blank_host_unavailable")
                                    }
                                }
                                val snapshot = ScreenSnapshot(id,generation,System.currentTimeMillis(),key,captureBounds,
                                    before.frame.rotation,before.frame.insets,before.frame.imeBounds,nodes,image,
                                    ScreenTransform(captureBounds,image?.width ?: captureBounds.width.toInt(),image?.height ?: captureBounds.height.toInt()))
                                handoff = snapshot; pixels = null
                                CaptureOutcome.Success(snapshot)
                            } finally { pixels?.recycle() }
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) { handoff?.close(); CaptureOutcome.Unavailable("timeout") }
                catch (_: SecurityException) { CaptureOutcome.Unavailable("permission_denied") }
                catch (error: CaptureFailure) { CaptureOutcome.Unavailable(error.code) }
            }
        } catch (cancelled: CancellationException) { handoff?.close(); throw cancelled }
    }

    private fun locked() = service.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
        !service.getSystemService(PowerManager::class.java).isInteractive
    @Suppress("DEPRECATION")
    private fun ownedWindowIds(): Set<Int> {
        val windows = service.windows.orEmpty()
        return try { windows.filter(overlays::ownsWindow).map { it.id }.toSet() }
        finally { windows.forEach { it.recycle() } }
    }
    private class Observed(val frame: SceneFrame, val window: AccessibilityWindowInfo, val displayBounds: RectPx,
        private val windows: List<AccessibilityWindowInfo>) : AutoCloseable {
        @Suppress("DEPRECATION")
        override fun close() { windows.forEach { it.recycle() } }
    }
    @Suppress("DEPRECATION")
    private fun readScene(): Observed? {
        val windows = service.windows.orEmpty()
        var handedOff = false
        try {
        val host = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !overlays.ownsWindow(it) }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }.thenByDescending { it.isFocused }.thenByDescending { it.layer })
            .firstOrNull() ?: return null
        val root = host.root ?: return null
        val rootRect = Rect()
        val rootWindowId = root.windowId
        val packageName = try { root.getBoundsInScreen(rootRect); root.packageName?.toString() }
            finally { root.recycle() } ?: return null
        val bounds = hostFrameBounds(host.id,rootWindowId,rootRect.toPx()) ?: return null
        val displayId = if (Build.VERSION.SDK_INT >= 30) host.displayId else 0
        val display = displays.getDisplay(displayId) ?: return null
        val size = Point(); display.getRealSize(size)
        if (size.x <= 0 || size.y <= 0) return null
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
            (Build.VERSION.SDK_INT < 30 || it.displayId == displayId) }?.let { Rect().apply { it.getBoundsInScreen(this) }.toPx() }
        val frame = SceneFrame(packageName,host.id,displayId,bounds,display.rotation,
            RectPx(bounds.left,bounds.top,size.x-bounds.right,size.y-bounds.bottom),ime)
        val observed = Observed(frame,host,RectPx(0f,0f,size.x.toFloat(),size.y.toFloat()),windows)
        handedOff = true
        return observed
        } finally { if (!handedOff) windows.forEach { it.recycle() } }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun accessibilityCapture(scene: SceneFrame, window: Boolean): RawShot = awaitOwnedResource { deliver ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) { deliver(RawShot(result.hardwareBuffer,result.colorSpace,null)) }
            override fun onFailure(errorCode: Int) { deliver(RawShot(null,null,when (errorCode) {
                2 -> "permission_denied"; 3 -> "rate_limited"; 4 -> "display_unavailable"
                5 -> "window_unavailable"; 6 -> "protected"; else -> "capture_failed"
            })) }
        }
        if (window && Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(scene.windowId,service.mainExecutor,callback)
        else service.takeScreenshot(scene.displayId,service.mainExecutor,callback)
    }

    /** Display capture on old APIs masks secure content; an all-black host is unusable.
     * This deliberately also refuses a genuinely black page, because there is no public secure flag.
     */
    private fun isBlankHost(image: Bitmap, transform: ScreenTransform, host: RectPx, nodes: List<NodeDto>): Boolean {
        val content = nodes.firstOrNull { it.viewId == "android:id/content" }?.bounds ?: host
        val region = RectPx(maxOf(host.left,content.left),maxOf(host.top,content.top),
            minOf(host.right,content.right),minOf(host.bottom,content.bottom))
        if (listOf(region.left,region.top,region.right,region.bottom).any { !it.isFinite() } || region.width <= 0 || region.height <= 0) {
            throw CaptureFailure("geometry_changed")
        }
        val first = transform.screenToImage(io.github.nanomuse.dot.gesture.PointPx(region.left,region.top))
        val last = transform.screenToImage(io.github.nanomuse.dot.gesture.PointPx(region.right,region.bottom))
        if (first.x < 0 || first.y < 0 || last.x > image.width || last.y > image.height) throw CaptureFailure("geometry_changed")
        val left = kotlin.math.ceil(first.x).toInt(); val top = kotlin.math.ceil(first.y).toInt()
        val right = kotlin.math.floor(last.x).toInt(); val bottom = kotlin.math.floor(last.y).toInt()
        if (right <= left || bottom <= top) throw CaptureFailure("geometry_changed")
        val row = IntArray(right-left)
        for (y in top until bottom) {
            image.getPixels(row,0,row.size,left,y,row.size,1)
            if (row.any { it and 0x00ffffff != 0 }) return false
        }
        return true
    }
}
internal class CaptureFailure(val code: String) : Exception(code)
@androidx.annotation.RequiresApi(30)
private class RawShot(private val buffer: HardwareBuffer?, private val colorSpace: android.graphics.ColorSpace?, val error: String?) : AutoCloseable {
    private val closed = AtomicBoolean()
    fun copyBitmap(): Bitmap? {
        val hardware = buffer?.let { Bitmap.wrapHardwareBuffer(it,colorSpace) } ?: return null
        return try { hardware.copy(Bitmap.Config.ARGB_8888,false) } finally { hardware.recycle() }
    }
    override fun close() { if (closed.compareAndSet(false,true)) buffer?.close() }
}
