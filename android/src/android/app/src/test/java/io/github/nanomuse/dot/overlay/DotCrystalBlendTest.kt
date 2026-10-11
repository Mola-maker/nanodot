package io.github.nanomuse.dot.overlay

import androidx.compose.ui.graphics.BlendMode
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Executes the production layer/draw recipe; backend implements the specified Porter-Duff math. */
class DotCrystalBlendTest {
    private data class Pixel(val r: Double, val g: Double, val b: Double, val a: Double) {
        fun weighted(weight: Float) = Pixel(r * weight, g * weight, b * weight, a * weight)
        fun plus(other: Pixel) = Pixel(r + other.r, g + other.g, b + other.b, a + other.a)
        fun over(other: Pixel) = plus(other.weighted((1 - a).toFloat()))
        companion object {
            val Clear = Pixel(0.0, 0.0, 0.0, 0.0)
            fun argb(value: Int): Pixel {
                val alpha = (value ushr 24) / 255.0
                return Pixel((value shr 16 and 255) / 255.0 * alpha,
                    (value shr 8 and 255) / 255.0 * alpha, (value and 255) / 255.0 * alpha, alpha)
            }
        }
    }
    private class PixelCanvas(val frames: List<Pixel>, background: Pixel = Pixel.Clear) : CrystalBlendCanvas {
        var pixel = background
        override fun isolatedLayer(draw: () -> Unit) {
            val parent = pixel
            pixel = Pixel.Clear
            draw()
            pixel = pixel.over(parent)
        }
        override fun frame(index: Int, weight: Float, mode: BlendMode) {
            val source = frames[index].weighted(weight)
            pixel = if (mode == BlendMode.Plus) source.plus(pixel) else source.over(pixel)
        }
    }
    private fun assertPixel(expected: Pixel, actual: Pixel, tolerance: Double = 0.000001) {
        assertEquals("alpha", expected.a, actual.a, tolerance)
        assertEquals("premultiplied red", expected.r, actual.r, tolerance)
        assertEquals("premultiplied green", expected.g, actual.g, tolerance)
        assertEquals("premultiplied blue", expected.b, actual.b, tolerance)
    }
    @Test fun identicalTranslucentFramesKeepRgbaAcrossTheWholeBlend() {
        val source = Pixel.argb(0x804080C0.toInt())
        for (fraction in listOf(0f, .25f, .5f, .75f, 1f)) {
            val canvas = PixelCanvas(List(16) { source })
            CrystalBlend.draw(0, fraction, canvas)
            assertPixel(source, canvas.pixel)
        }
    }
    @Test fun blendIsIsolatedFromLightAndDarkHostPixels() {
        val source = Pixel.argb(0x804080C0.toInt())
        for (background in listOf(0xFFF4F8FF.toInt(), 0xFF101D30.toInt())) {
            val host = Pixel.argb(background)
            for (fraction in listOf(0f, .25f, .5f, .75f, 1f)) {
                val canvas = PixelCanvas(List(16) { source }, host)
                CrystalBlend.draw(0, fraction, canvas)
                assertPixel(source.over(host), canvas.pixel)
            }
        }
    }
    @Test fun everyOriginalAtlasBoundaryApproachesNextFrameAloneIncludingWrap() {
        // The Android compile bootclasspath omits java.desktop, but this JVM test runs on JDK21.
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val atlas = imageIo.getMethod("read", File::class.java)
            .invoke(null, File("src/main/res/drawable-nodpi/nm_crystal_orb_atlas.png"))
        val width = atlas.javaClass.getMethod("getWidth").invoke(atlas) as Int
        val height = atlas.javaClass.getMethod("getHeight").invoke(atlas) as Int
        val getRgb = atlas.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        assertEquals(1254, width)
        assertEquals(1254, height)
        // Sample the same fixed crop as the native 48dp target, without saving altered pixels.
        for (y in 0 until 48) for (x in 0 until 48) {
            val frames = (0..15).map { index ->
                val rect = CrystalFrames.rect(width, height, index)
                val insetX = (rect.right - rect.left) / 10
                val insetY = (rect.bottom - rect.top) / 10
                val px = rect.left + insetX + x * (rect.right - rect.left - 2 * insetX) / 48
                val py = rect.top + insetY + y * (rect.bottom - rect.top - 2 * insetY) / 48
                Pixel.argb(getRgb.invoke(atlas, px, py) as Int)
            }
            for (index in 0..15) {
                val next = frames[(index + 1) % 16]
                val left = PixelCanvas(frames)
                CrystalBlend.draw(index, .99999f, left)
                assertPixel(next, left.pixel, .00002)
                val endpoint = PixelCanvas(frames)
                CrystalBlend.draw(index, 1f, endpoint)
                assertPixel(next, endpoint.pixel)
            }
        }
    }
}
