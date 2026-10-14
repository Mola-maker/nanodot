package io.github.nanomuse.dot.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Captures actual Compose output. Screenshots require human review. */
@RunWith(AndroidJUnit4::class)
class DotTokenGalleryRenderTest {
    @Test
    fun captureLightDarkAndDynamicType() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir(null), "dot-token-gallery").apply { mkdirs() }
        // Prevent stale rendering views from a prior interrupted fixture run.
        output.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
        for (dark in listOf(false, true)) {
            for (scale in listOf(1f, 1.5f, 2f)) {
                for (page in listOf("actions", "inputs", "dialog", "sheet")) {
                    DotTokenGalleryFixtureActivity.requestedFontScale = scale
                    val intent = Intent(context, DotTokenGalleryFixtureActivity::class.java)
                        .putExtra("dark", dark).putExtra("scale", scale).putExtra("page", page)
                    ActivityScenario.launch<DotTokenGalleryFixtureActivity>(intent).use { scenario ->
                        scenario.onActivity { activity ->
                            assertTrue("Native window font scale must match requested rendering",
                                activity.resources.configuration.fontScale == scale)
                        }
                        instrumentation.waitForIdleSync()
                        // Wait for dialog/sheet entrance animations to settle before capture.
                        Thread.sleep(700)
                        val prefix = "${if (dark) "dark" else "light"}-${(scale * 100).toInt()}-$page"
                        capture(output, "$prefix.png")
                        if (page == "actions" || page == "inputs") {
                            // Long dynamic-type content is intentionally scrollable, not clipped.
                            scenario.onActivity { activity -> activity.showBottom() }
                            Thread.sleep(250)
                            capture(output, "$prefix-bottom.png")
                        }
                    }
                }
            }
        }
        assertTrue("Expected all 36 rendering views", output.listFiles()!!.count { it.extension == "png" } == 36)
    }

    private fun capture(output: File, name: String) {
        val screenshot = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Screenshot unavailable" }
        File(output, name).outputStream().use { stream ->
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        screenshot.recycle()
    }

}
