package io.github.nanomuse.dot.chat

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.ui.DotTokenGalleryFixtureActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.io.File

/** Catches an opaque empty transcript, or translucent bubbles whose actual glyphs disappear. */
@RunWith(AndroidJUnit4::class)
class DotForegroundGlassRenderTest {
    @Test fun hostContrastSurvivesEmptyHistoryWhileBubbleTextRemainsReadable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        try {
            for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) {
                DotTokenGalleryFixtureActivity.requestedFontScale = scale
                val visible = mutableStateOf(false)
                val measured = ConcurrentHashMap<String, Rect>()
                var density = 1f
                ActivityScenario.launch<DotTokenGalleryFixtureActivity>(
                    Intent(context, DotTokenGalleryFixtureActivity::class.java).putExtra("dark", dark)
                ).use { scenario ->
                    scenario.onActivity { activity ->
                        density = activity.resources.displayMetrics.density
                        activity.setContent {
                            MinisTheme(darkTheme = dark) {
                                Box(Modifier.size(320.dp, 440.dp).onGloballyPositioned {
                                    val origin = it.localToScreen(Offset.Zero)
                                    measured["host"] = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                                }) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawRect(Color.White)
                                        var x = 0f
                                        while (x < size.width) {
                                            drawRect(Color.Black, Offset(x, 232.dp.toPx()), Size(24.dp.toPx(), 16.dp.toPx()))
                                            x += 48.dp.toPx()
                                        }
                                    }
                                    Text("Host reading stays readable", color = Color.Black,
                                        modifier = Modifier.padding(top = 128.dp, start = 12.dp))
                                    if (visible.value) DotForegroundChat(
                                        status = "Current instruction", onCollapse = {}, history = {
                                            DotMessageBubble(false) {
                                                Text("Touch Search", modifier = Modifier.onGloballyPositioned {
                                                    val origin = it.localToScreen(Offset.Zero)
                                                    measured["glyph"] = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                                                })
                                            }
                                        }, controls = {
                                            Button({}, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                                Text("Start")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    fun emptyContrast(frame: Bitmap): Double {
                        val host = measured["host"] ?: return 0.0
                        // Centers of host stripes in the empty middle of the history, away from text/chrome.
                        val white = luminance(frame.getPixel((host.left + 84 * density).toInt(), (host.top + 240 * density).toInt()))
                        val black = luminance(frame.getPixel((host.left + 108 * density).toInt(), (host.top + 240 * density).toInt()))
                        return kotlin.math.abs(white - black)
                    }
                    val evidence = File(context.getExternalFilesDir(null), "foreground-glass").apply { mkdirs() }
                    fun save(frame: Bitmap, phase: String) {
                        File(evidence, "$phase-dark-$dark-scale-$scale.png").outputStream().use {
                            frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        File(evidence, "$phase-dark-$dark-scale-$scale.txt").writeText("density=$density bounds=$measured contrast=${emptyContrast(frame)} screen=${frame.width}x${frame.height}")
                    }
                    // Main-thread idle alone can precede the compositor's first actual frame.
                    var baseline = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    val deadline = SystemClock.uptimeMillis() + 5000
                    while (emptyContrast(baseline) < .8 && SystemClock.uptimeMillis() < deadline) {
                        baseline.recycle()
                        SystemClock.sleep(60)
                        baseline = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    }
                    save(baseline, "baseline")
                    assertTrue("Fixture has actual white/black host pixels: ${emptyContrast(baseline)} bounds=$measured", emptyContrast(baseline) >= .8)
                    scenario.onActivity { visible.value = true }
                    instrumentation.waitForIdleSync()
                    val renderDeadline = SystemClock.uptimeMillis() + 5000
                    while (!measured.containsKey("glyph") && SystemClock.uptimeMillis() < renderDeadline) SystemClock.sleep(20)
                    SystemClock.sleep(120)
                    val rendered = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    save(rendered, "rendered")
                    try {
                        val retained = emptyContrast(rendered) / emptyContrast(baseline)
                        assertTrue("Host contrast retained dark=$dark scale=$scale: $retained", retained >= .55)
                        val glyph = requireNotNull(measured["glyph"])
                        val background = luminance(rendered.getPixel(glyph.left.toInt(), glyph.top.toInt()))
                        var readable = 0
                        for (y in glyph.top.toInt() until glyph.bottom.toInt() step 2) {
                            for (x in glyph.left.toInt() until glyph.right.toInt() step 2) {
                                val pixel = luminance(rendered.getPixel(x, y))
                                if ((maxOf(background, pixel) + .05) / (minOf(background, pixel) + .05) >= 4.5) readable++
                            }
                        }
                        assertTrue("Native readable bubble glyphs dark=$dark scale=$scale: $readable", readable >= 8)
                        println("FOREGROUND_GLASS dark=$dark scale=$scale hostContrast=$retained readableGlyphs=$readable")
                    } finally {
                        baseline.recycle()
                        rendered.recycle()
                    }
                }
            }
        } finally { DotTokenGalleryFixtureActivity.requestedFontScale = 1f }
    }

    private fun luminance(pixel: Int): Double {
        fun channel(value: Int): Double {
            val x = value / 255.0
            return if (x <= .04045) x / 12.92 else Math.pow((x + .055) / 1.055, 2.4)
        }
        return .2126 * channel(android.graphics.Color.red(pixel)) +
            .7152 * channel(android.graphics.Color.green(pixel)) +
            .0722 * channel(android.graphics.Color.blue(pixel))
    }
}
