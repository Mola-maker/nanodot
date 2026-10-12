package io.github.nanomuse.dot.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotPreferences
import io.github.nanomuse.dot.screen.ScreenHostFixture
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on an unlocked isolated profile with the real service already granted. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION") // Native node recycling remains compatible with API 26.
class DotForegroundMotionNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val touchTimings = mutableListOf<String>()
    private fun setting(key: String): String? = Settings.Secure.getString(context.contentResolver, key)
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun validateSetting(key: String, value: String?) {
        require(key in setOf("enabled_accessibility_services", "accessibility_enabled"))
        require(value == null || if (key == "accessibility_enabled") value in setOf("0", "1")
            else value.split(':').all { it.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) })
    }
    private fun restore(key: String, value: String?) {
        validateSetting(key, value)
        if (value == null) shell("settings delete secure $key")
        else {
            // UiAutomationConnection uses Runtime.exec(String), not sh -c. Quotes would
            // become literal argv characters; '$' in a nested class is already preserved.
            // Validate every argv value before mutation, including final restoration.
            shell("settings put secure $key $value")
        }
    }
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return
            Thread.sleep(50)
        }
        assertTrue(message, check())
    }
    private fun onMain(check: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = check() }
        return result
    }
    private fun assertInitialForegroundEmpty(service: MinisAccessibilityService) {
        instrumentation.runOnMainSync {
            assertNull("Use an isolated profile without a mounted Dot panel", service.dotOverlay.panelView)
            assertFalse("Use an isolated profile without a visible Care session", service.dotInteraction.careSession.visible.value)
            assertNull("Use an isolated profile without a foreground binding", service.dotInteraction.foreground.state.value.binding)
            assertEquals("Use an isolated profile without an active foreground mode",
                DotForegroundMode.Idle, service.dotInteraction.foreground.state.value.mode)
        }
    }
    private fun bounds(service: MinisAccessibilityService, label: String): Rect? {
        fun visit(node: AccessibilityNodeInfo): Rect? {
            if (node.isVisibleToUser && node.isEnabled &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) return rect
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val windows = service.windows
        try {
            for (window in windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) {
                val root = window.root ?: continue
                try { visit(root)?.let { return it } } finally { root.recycle() }
            }
            return null
        } finally { windows.forEach { it.recycle() } }
    }
    private fun touch(time: Long, action: Int, x: Float, y: Float) {
        val injectedAt = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, injectedAt, action, x, y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue("Global touch accepted", automation.injectInputEvent(event, true)) }
        finally {
            touchTimings.add("action=$action sinceDown=${injectedAt - time}ms " +
                "inject=${SystemClock.uptimeMillis() - injectedAt}ms x=$x y=$y")
            event.recycle()
        }
    }
    private fun near(a: Rect, b: Rect?): Boolean = b != null &&
        kotlin.math.abs(a.left - b.left) <= 3 && kotlin.math.abs(a.top - b.top) <= 3

    /** Read-only failure evidence; never dispatches a touch or changes controller state. */
    private fun releaseDiagnostics(service: MinisAccessibilityService, orb: String,
        dragged: Rect, observed: Rect, releaseReturnedAt: Long): String {
        val samples = mutableListOf<String>()
        fun sample() {
            var native = "missing"
            instrumentation.runOnMainSync {
                service.dotOverlay.orbView?.let { view ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val p = view.layoutParams as? WindowManager.LayoutParams
                    native = "attached=${view.isAttachedToWindow} " +
                        "rect=${Rect(location[0], location[1], location[0] + view.width, location[1] + view.height).toShortString()} " +
                        "layout=${p?.x},${p?.y}"
                }
            }
            samples.add("afterUp=${SystemClock.uptimeMillis() - releaseReturnedAt}ms " +
                "native=($native) ax=${bounds(service, orb)?.toShortString()}")
        }
        // Sample only after the original assertion has already failed. These observations
        // cannot turn an unobserved or completed snap into passing interruption evidence.
        sample()
        repeat(3) { Thread.sleep(64); sample() }
        return "dragged=${dragged.toShortString()} observed=${observed.toShortString()} " +
            "scale=${Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)} " +
            "touches=${touchTimings.joinToString("; ")} samples=${samples.joinToString("; ")}"
    }

    @Test fun nativeDragInterruptsSnapAndPersistsLatestPlacement() {
        // Read originals before UiAutomation connects; never clear app data or credentials.
        val original = listOf("enabled_accessibility_services", "accessibility_enabled").associateWith(::setting)
        // Validate exact raw originals with the restoration validator before UiAutomation
        // connects or any fixture mutation; empty colon entries must fail here, not in finally.
        original.forEach { (key, value) -> validateSetting(key, value) }
        val own = ComponentName(context.packageName, MinisAccessibilityService::class.java.name).flattenToString()
        val services = original["enabled_accessibility_services"].orEmpty().split(':').filter { it.isNotEmpty() }
        assumeTrue("Grant the real accessibility service before running this fixture", own in services)
        assumeTrue("Unlock the isolated profile before running this fixture",
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                context.getSystemService(PowerManager::class.java).isInteractive)
        // Reject an unsuitable live session before settings/overlay changes or host launch.
        MinisAccessibilityService.getInstance()?.let(::assertInitialForegroundEmpty)
        val oldFlags = automation.serviceInfo.flags
        val oldEnabled = DotPreferences(context).showOnAccessibilityConnected()
        val prefs = context.getSharedPreferences(com.openminis.app.ui.settings.PREF_APPEARANCE,
            android.content.Context.MODE_PRIVATE)
        val oldPosition = listOf("dot_orb_x", "dot_orb_y").associateWith { prefs.all[it] }
        assumeTrue("Animated snap requires nonzero animator scale",
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
        var pointer: Long? = null
        var x = 0f
        var y = 0f
        var service: MinisAccessibilityService? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            // onCreate publishes the instance before onServiceConnected initializes this
            // synchronized lazy. Its Compose dispatcher constructor needs Main: never
            // acquire the controller lazy lock from the instrumentation thread.
            if (!onMain { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true }) {
                val others = services.filter { it != own }
                restore("enabled_accessibility_services", others.joinToString(":").ifEmpty { null })
                // Rebinding is framework-driven, and unrelated granted services are retained.
                await("Dead service binding must leave before regrant") {
                    val fd = automation.executeShellCommand("dumpsys accessibility")
                    val dump = ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) }
                    dump.lineSequence().filter { it.contains("Bound services:") || it.contains("Binding services:") }
                        .none { it.contains("MinisAccessibilityService") }
                }
                restore("enabled_accessibility_services", services.joinToString(":"))
                restore("accessibility_enabled", "1")
            }
            await("Real accessibility service did not reconnect") {
                onMain { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true }
            }
            val connected = requireNotNull(MinisAccessibilityService.getInstance())
            assertInitialForegroundEmpty(connected)
            service = connected
            instrumentation.runOnMainSync { connected.dotOverlay.setEnabled(true) }
            instrumentation.context.startActivity(Intent().setComponent(ComponentName(
                instrumentation.context, ScreenHostFixture::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("External fixture must be the capture host") {
                val root = connected.rootInActiveWindow
                root?.let { try { it.packageName?.toString() == instrumentation.context.packageName }
                    finally { it.recycle() } } == true
            }
            val orb = connected.getString(R.string.nm_dot_orb_description)
            await("Visible orb required") { bounds(connected, orb) != null }
            val start = requireNotNull(bounds(connected, orb))
            val host = connected.rootInActiveWindow?.let { node ->
                try { Rect().also { node.getBoundsInScreen(it) } } finally { node.recycle() }
            } ?: error("Native host bounds missing")
            assertTrue("Host must fit meaningful drag", host.width() > start.width() * 4 &&
                host.height() > start.height() * 4)
            fun moveTo(fx: Float, fy: Float) {
                x = host.left + host.width() * fx; y = host.top + host.height() * fy
                touch(requireNotNull(pointer), MotionEvent.ACTION_MOVE, x, y)
                await("Native orb must follow MOVE") {
                    bounds(connected, orb)?.let { kotlin.math.abs(it.exactCenterX() - x) <= 4 &&
                        kotlin.math.abs(it.exactCenterY() - y) <= 4 } == true
                }
            }
            pointer = SystemClock.uptimeMillis(); x = start.exactCenterX(); y = start.exactCenterY()
            touch(requireNotNull(pointer), MotionEvent.ACTION_DOWN, x, y)
            Thread.sleep(20)
            moveTo(.55f, .35f)
            val dragged = requireNotNull(bounds(connected, orb))
            assertFalse("Drag changes native position", near(start, dragged))
            touch(requireNotNull(pointer), MotionEvent.ACTION_UP, x, y); pointer = null
            val releaseReturnedAt = SystemClock.uptimeMillis()
            Thread.sleep(32)
            val snapping = requireNotNull(bounds(connected, orb))
            if (near(dragged, snapping)) {
                fail("Release starts animated snap: " +
                    releaseDiagnostics(connected, orb, dragged, snapping, releaseReturnedAt))
            }
            assertTrue("Interrupt before edge", snapping.left > host.left + 8 && snapping.right < host.right - 8)
            pointer = SystemClock.uptimeMillis(); x = snapping.exactCenterX(); y = snapping.exactCenterY()
            touch(requireNotNull(pointer), MotionEvent.ACTION_DOWN, x, y)
            Thread.sleep(20)
            moveTo(.25f, .65f)
            val held = requireNotNull(bounds(connected, orb))
            // MOVE enters drag before long-press deadline, so this hold cannot admit voice.
            repeat(12) {
                Thread.sleep(100)
                assertTrue("Canceled snap must not move orb against next touch", near(held, bounds(connected, orb)))
            }
            touch(requireNotNull(pointer), MotionEvent.ACTION_UP, x, y); pointer = null
            Thread.sleep(1500)
            val settled = requireNotNull(bounds(connected, orb))
            assertTrue("Orb remains in native host", host.contains(settled))
            assertTrue("Second release snaps left", settled.left < host.left + start.width())
            assertTrue("Second release retains new Y", kotlin.math.abs(settled.top - held.top) <= 4)
            val saved = DotPreferences(context).orbPosition()
            instrumentation.runOnMainSync { connected.dotOverlay.setEnabled(false) }
            instrumentation.runOnMainSync { connected.dotOverlay.setEnabled(true) }
            await("Remount uses latest persisted position") { near(settled, bounds(connected, orb)) }
            Thread.sleep(1200)
            assertTrue("Late old snap cannot override remount", near(settled, bounds(connected, orb)))
            assertEquals("Late old snap cannot overwrite persistence", saved, DotPreferences(context).orbPosition())

        } finally {
            try {
                pointer?.let { touch(it, MotionEvent.ACTION_CANCEL, x, y) }
                service?.let { connected -> instrumentation.runOnMainSync {
                    connected.dotInteraction.close()
                    connected.dotOverlay.setEnabled(false)
                    val edit = prefs.edit()
                    oldPosition.forEach { (key, value) ->
                        if (value == null) edit.remove(key) else edit.putFloat(key, value as Float)
                    }
                    assertTrue("Restore original orb preference entries", edit.commit())
                    connected.dotOverlay.setEnabled(oldEnabled)
                } }
            } finally {
                try { automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
                    assertEquals(oldFlags, automation.serviceInfo.flags)
                } finally {
                    original.forEach { (key, value) -> restore(key, value) }
                    original.forEach { (key, value) -> assertEquals("Restore $key", value, setting(key)) }
                }
            }
        }
    }
}
