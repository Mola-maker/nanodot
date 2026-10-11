package io.github.nanomuse.dot.screen

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** API26–29 only. One explicit grant, one foreground owner, no retained last image.
 * Every request drains old frames and accepts only an image newer than that request.
 */
class LegacyProjectionCapture internal constructor(private val projection: MediaProjection, private val density: Int) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var size: Pair<Int,Int>? = null
    private var pending: Pending? = null
    private val closed = AtomicBoolean()
    private data class Pending(val sinceNs: Long, val deliver: (Frame) -> Unit)
    private class Frame(val bitmap: Bitmap?, val error: String? = null) : AutoCloseable {
        private val released = AtomicBoolean()
        override fun close() { if (released.compareAndSet(false,true)) bitmap?.recycle() }
    }
    suspend fun capture(bounds: RectPx): Bitmap {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed.get()) throw CaptureFailure("projection_revoked")
        prepare(bounds.width.toInt(),bounds.height.toInt())
        reader?.acquireLatestImage()?.close()
        val requestAt = System.nanoTime()
        var request: Pending? = null
        try {
            val frame = awaitOwnedResource<Frame> { deliver ->
                check(pending == null)
                request = Pending(requestAt,deliver); pending = request
            }
            frame.use {
                it.error?.let { code -> throw CaptureFailure(code) }
                return it.bitmap?.copy(Bitmap.Config.ARGB_8888,false) ?: throw CaptureFailure("pixels_unavailable")
            }
        } finally { if (pending === request) pending = null }
    }
    private fun prepare(width: Int, height: Int) {
        if (size == width to height) return
        require(width > 0 && height > 0)
        display?.release(); display = null
        reader?.close()
        val next = ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
        reader = next; size = width to height
        next.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
            image?.use {
                val request = pending
                if (request != null && !closed.get() && it.timestamp >= request.sinceNs) {
                    pending = null
                    val frame = try { Frame(toBitmap(it)) } catch (_: RuntimeException) { Frame(null,"pixels_unavailable") }
                    request.deliver(frame)
                }
            }
        },handler)
        try {
            display = projection.createVirtualDisplay("Nanodot explicit screen capture",width,height,density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,next.surface,null,handler)
        } catch (error: RuntimeException) { close(); throw error }
    }
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]; val buffer = plane.buffer
        val colors = IntArray(image.width * image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val offset = y * plane.rowStride + x * plane.pixelStride
            val r = buffer.get(offset).toInt() and 255; val g = buffer.get(offset+1).toInt() and 255
            val b = buffer.get(offset+2).toInt() and 255; val a = buffer.get(offset+3).toInt() and 255
            colors[y*image.width+x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(colors,image.width,image.height,Bitmap.Config.ARGB_8888)
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        val waiting = pending; pending = null
        waiting?.deliver?.invoke(Frame(null,"projection_revoked"))
        display?.release(); display = null
        reader?.close(); reader = null
        projection.stop()
    }
}
