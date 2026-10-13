package io.github.nanomuse.dot.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nanomuse.dot.DotColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/** Real Android Canvas pixels from the production view; not proof of live window/input timing. */
@RunWith(AndroidJUnit4::class)
class DotCrystalTrailRenderTest {
    @Test fun sparseCrystalFramesFadeOnTheExistingGestureClock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val width = (420 * density).roundToInt()
            val height = (180 * density).roundToInt()
            val baseline = 90 * density
            val view = DotTrailView(context).apply { layout(0, 0, width, height) }
            assertFalse(view.isFocusable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
            val frames = linkedMapOf<String, Bitmap>()
            try {
                for ((label, now) in listOf("before-lag" to 79L, "live" to 280L,
                    "fading" to 400L, "expired" to 440L)) {
                    val trail = CrystalTrail()
                    for (index in 0..20) if (index * 10L <= now) {
                        trail.add((30 + index * 18) * density, baseline, index * 10L, true)
                    }
                    view.points = trail.visible(now)
                    frames[label] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        view.draw(Canvas(it))
                    }
                }
                assertEquals("No early tail before the existing 80 ms lag", 0L, alphaMass(frames.getValue("before-lag")))
                val live = frames.getValue("live")
                val fading = frames.getValue("fading")
                assertTrue("Historical crystals add visible height beyond the narrow line", offAxisInk(live, baseline, density) > 0)
                assertTrue("Late frames retain only a faint remainder", alphaMass(fading) in 1L until alphaMass(live))
                assertEquals("The last input expires at 200 + 240 ms", 0L, alphaMass(frames.getValue("expired")))
                val output = File(context.cacheDir, "crystal-trail-reference").apply { mkdirs() }
                for ((name, bitmap) in frames) save(bitmap, File(output, "$name-transparent.png"))
                for ((name, background) in listOf("light" to DotColors.LightBackground.toArgb(),
                    "dark" to DotColors.DarkBackground.toArgb())) {
                    for ((stage, bitmap) in listOf("live" to live, "fading" to fading)) {
                        val composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(composite).apply { drawColor(background); drawBitmap(bitmap, 0f, 0f, null) }
                            save(composite, File(output, "$name-$stage.png"))
                        } finally { composite.recycle() }
                    }
                }
                println("CRYSTAL_TRAIL_RENDER evidence=crystal-trail-reference lagBlank=true " +
                    "liveAlpha=${alphaMass(live)} fadingAlpha=${alphaMass(fading)} expiredBlank=true " +
                    "liveWindowTiming=not-tested")
            } finally { frames.values.forEach(Bitmap::recycle) }
        }
    }

    private fun alphaMass(bitmap: Bitmap): Long {
        val row = IntArray(bitmap.width)
        var mass = 0L
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            mass += row.sumOf { Color.alpha(it).toLong() }
        }
        return mass
    }

    private fun offAxisInk(bitmap: Bitmap, baseline: Float, density: Float): Int {
        var count = 0
        // The old line's maximum radius is under 6 dp at the admitted sample ages.
        // A crystal silhouette should add ink beyond 7 dp without filling the host.
        val upper = (baseline - 7 * density).toInt()
        for (y in 0 until upper.coerceAtLeast(0)) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 2) count++
        }
        assertEquals("Far outside the local trail remains transparent", 0, Color.alpha(bitmap.getPixel(bitmap.width / 2, 0)))
        return count
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
