package io.github.nanomuse.dot.overlay

import android.animation.ValueAnimator
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Real Canvas and accessibility-overlay checks; no capture, provider, settings or network. */
@RunWith(AndroidJUnit4::class)
class DotCaptureScannerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun progressDrawsAnIncreasingBlueTraceAroundAnUnfilledPerimeter() {
        instrumentation.runOnMainSync {
            val view = DotCaptureScanner(instrumentation.targetContext)
            view.layout(0, 0, 480, 800)
            val frames = listOf(0f, .5f, 1f).map { progress ->
                view.progress = progress
                Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888).also {
                    view.draw(Canvas(it))
                }
            }
            try {
                val counts = frames.map(::countPixels)
                assertTrue("The pending scan has a visible ambient perimeter", counts[0].visible > 0)
                assertEquals("Progress zero must not draw a completed trace", 0, counts[0].strongBlue)
                assertTrue("Half progress draws the blue trace", counts[1].strongBlue > 0)
                assertTrue("Full progress covers substantially more of the perimeter", counts[2].strongBlue > counts[1].strongBlue * 1.5f)
                val fraction = counts[1].strongBlue.toFloat() / counts[2].strongBlue
                assertTrue("Half progress covers approximately half of the perimeter: $fraction", fraction in .4f.. .6f)
                for ((index, bitmap) in frames.withIndex()) {
                    assertEquals("The scanner must leave the host center clear at progress $index", 0, Color.alpha(bitmap.getPixel(240, 400)))
                    for ((name, region) in edgeRegions(bitmap)) {
                        assertTrue("The ambient perimeter reaches the $name display edge at progress $index", hasBluePixel(bitmap, region, 1))
                        if (index == 2) assertTrue("The completed trace reaches the $name display edge", hasBluePixel(bitmap, region, 200))
                    }
                }
            } finally { frames.forEach(Bitmap::recycle) }
        }
    }

    @Test fun scanWindowFactoryLeavesEveryDisplayEdgeUnfittedAndTouchThrough() {
        instrumentation.runOnMainSync {
            val params = captureScanLayoutParams()
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
            assertEquals(Gravity.TOP or Gravity.LEFT, params.gravity)
            assertFlag(params, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertFlag(params, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertFlag(params, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals("System bars must not crop the scan window", 0, params.fitInsetsTypes)
            }
        }
    }

    @Test fun actualWindowCoversTheRealDisplayAndRejectsStaleGenerationCleanup() {
        val service = connectedService()
        lateinit var owner: DotCaptureScanWindow
        instrumentation.runOnMainSync { owner = DotCaptureScanWindow(service) }
        try {
            instrumentation.runOnMainSync { owner.begin(41) }
            awaitMain("The local scanner window must attach and measure") {
                owner.view?.let { it.isAttachedToWindow && it.width > 0 && it.height > 0 } == true
            }
            instrumentation.runOnMainSync {
                val view = requireNotNull(owner.view)
                val display = realDisplayBounds(service.getSystemService(WindowManager::class.java))
                val origin = IntArray(2).also(view::getLocationOnScreen)
                assertEquals("The scanner starts at the actual display left", display.left, origin[0])
                assertEquals("The scanner starts at the actual display top", display.top, origin[1])
                assertEquals("The scanner reaches both horizontal display edges", display.width(), view.width)
                assertEquals("The scanner reaches both vertical display edges", display.height(), view.height)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
                assertFalse(view.isFocusable)
                val params = view.layoutParams as WindowManager.LayoutParams
                assertFlag(params, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertFlag(params, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                owner.finish(40, false)
                owner.cancel(40)
                assertSame("A stale result/cancel must keep the current window", view, owner.view)
                assertNull("A stale result cannot color the current scan as failed", view.outcome)
                owner.cancel(41)
                assertNull("Current cancellation removes the owned window", owner.view)
                assertFalse("Current cancellation detaches the actual view", view.isAttachedToWindow)
                owner.begin(42)
                val current = requireNotNull(owner.view)
                owner.finish(41, false)
                owner.cancel(41)
                assertSame("Old callbacks cannot remove a replacement generation", current, owner.view)
                assertNull(current.outcome)
            }
        } finally { instrumentation.runOnMainSync { owner.cancel() } }
    }

    @Test fun anOutcomeWhileCaptureHidesTheWindowWaitsForAVisibleDrawBeforeRemoval() {
        val service = connectedService()
        for ((index, success) in listOf(true, false).withIndex()) {
            lateinit var owner: DotCaptureScanWindow
            lateinit var view: DotCaptureScanner
            val firstVisibleDraw = AtomicLong(-1)
            val visibleProgress = mutableListOf<Float>()
            var motion = false
            var listenerInstalled = false
            val listener = ViewTreeObserver.OnDrawListener {
                if (view.isAttachedToWindow && view.width > 0 && view.height > 0 &&
                    view.isShown && view.windowVisibility == View.VISIBLE && view.alpha > 0f) {
                    firstVisibleDraw.compareAndSet(-1, SystemClock.uptimeMillis())
                    visibleProgress.add(view.progress)
                }
            }
            instrumentation.runOnMainSync { owner = DotCaptureScanWindow(service) }
            try {
                instrumentation.runOnMainSync {
                    owner.begin(51L + index)
                    view = requireNotNull(owner.view)
                    // Reproduce capture hiding before the newly added window's first traversal.
                    view.alpha = 0f
                    view.viewTreeObserver.addOnDrawListener(listener)
                    listenerInstalled = true
                    motion = ValueAnimator.areAnimatorsEnabled() &&
                        Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
                    owner.finish(51L + index, success)
                    view.invalidate()
                }
                awaitMain("The hidden scan must still attach and measure, success=$success") {
                    owner.view === view && view.isAttachedToWindow && view.width > 0 && view.height > 0
                }
                SystemClock.sleep(350)
                instrumentation.runOnMainSync {
                    assertSame("Hidden capture time cannot consume the outcome's visible lifetime, success=$success", view, owner.view)
                    assertEquals("Hidden draw callbacks do not establish visible readiness", -1L, firstVisibleDraw.get())
                    assertEquals(success, view.outcome)
                    if (motion && success) assertEquals("Motion waits for a visible scan draw", 0f, view.progress, 0f)
                    view.alpha = 1f
                    view.invalidate()
                }
                awaitMain("The restored window must draw visibly, success=$success") { firstVisibleDraw.get() >= 0 }
                SystemClock.sleep(180)
                instrumentation.runOnMainSync {
                    assertSame("The actual visible result must last longer than a single sampled frame, success=$success", view, owner.view)
                    assertTrue(view.isAttachedToWindow && view.isShown && view.alpha > 0f)
                    if (!motion || !success) {
                        assertEquals("Reduced motion and failure still show a completed perimeter", 1f, view.progress, 0f)
                    }
                }
                awaitMain("The finished scanner must eventually remove its own window, success=$success", 15000) {
                    owner.view == null
                }
                instrumentation.runOnMainSync {
                    assertFalse("Outcome removal detaches the real scanner", view.isAttachedToWindow)
                    if (motion && success) assertTrue(
                        "Enabled motion must render intermediate progress after visibility is restored: $visibleProgress",
                        visibleProgress.any { it > 0f && it < 1f },
                    )
                }
                println("CAPTURE_SCAN_NATIVE success=$success motion=$motion visibleDraw=${firstVisibleDraw.get()} progress=$visibleProgress")
            } finally {
                instrumentation.runOnMainSync {
                    if (listenerInstalled && view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
                    owner.cancel()
                }
            }
        }
    }

    private fun connectedService(): MinisAccessibilityService {
        // Preserve the already enabled service when obtaining instrumentation automation.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        var service: MinisAccessibilityService? = null
        awaitMain("Root must enable the real Minis accessibility service before this native scanner gate", 10000) {
            service = MinisAccessibilityService.getInstance()?.takeIf { it.dotOverlay.isConnected }
            service != null
        }
        return requireNotNull(service)
    }

    private fun awaitMain(message: String, timeoutMillis: Long = 5000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) SystemClock.sleep(20)
        }
        assertTrue(message, ready)
    }

    private fun assertFlag(params: WindowManager.LayoutParams, flag: Int) {
        assertTrue("Scanner window must retain flag $flag", params.flags and flag != 0)
    }

    @Suppress("DEPRECATION") // API26-29 need the real display rather than an inset app viewport.
    private fun realDisplayBounds(windows: WindowManager): Rect = if (Build.VERSION.SDK_INT >= 30) {
        windows.maximumWindowMetrics.bounds
    } else {
        Point().also(windows.defaultDisplay::getRealSize).let { Rect(0, 0, it.x, it.y) }
    }

    private data class Pixels(val visible: Int, val strongBlue: Int)

    private fun countPixels(bitmap: Bitmap): Pixels {
        var visible = 0
        var strongBlue = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) > 0) visible++
            if (isBlue(color, 200)) strongBlue++
        }
        return Pixels(visible, strongBlue)
    }

    private fun edgeRegions(bitmap: Bitmap) = listOf(
        "top" to Rect(bitmap.width / 4, 0, bitmap.width * 3 / 4, 24),
        "right" to Rect(bitmap.width - 24, bitmap.height / 4, bitmap.width, bitmap.height * 3 / 4),
        "bottom" to Rect(bitmap.width / 4, bitmap.height - 24, bitmap.width * 3 / 4, bitmap.height),
        "left" to Rect(0, bitmap.height / 4, 24, bitmap.height * 3 / 4),
    )

    private fun hasBluePixel(bitmap: Bitmap, region: Rect, minimumAlpha: Int): Boolean {
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            if (isBlue(bitmap.getPixel(x, y), minimumAlpha)) return true
        }
        return false
    }

    private fun isBlue(color: Int, minimumAlpha: Int) = Color.alpha(color) >= minimumAlpha &&
        abs(Color.red(color) - 45) <= 4 && abs(Color.green(color) - 99) <= 4 && abs(Color.blue(color) - 150) <= 4
}
