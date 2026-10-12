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
import android.view.ViewConfiguration
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
    private var latestFailureGeometry = "no mounted geometry observed"
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
        try {
            // The public API still synchronizes window transactions on DOWN/UP. Queue
            // target dispatch without another wait; measure rather than assume its cost.
            assertTrue("Global touch accepted", automation.injectInputEvent(event, false))
        }
        finally {
            touchTimings.add("action=$action sinceDown=${injectedAt - time}ms " +
                "inject=${SystemClock.uptimeMillis() - injectedAt}ms x=$x y=$y")
            event.recycle()
        }
    }
    private fun near(a: Rect, b: Rect?): Boolean = b != null &&
        kotlin.math.abs(a.left - b.left) <= 3 && kotlin.math.abs(a.top - b.top) <= 3

    private fun nativeBounds(service: MinisAccessibilityService): Rect? {
        var rect: Rect? = null
        instrumentation.runOnMainSync {
            service.dotOverlay.orbView?.takeIf { it.isAttachedToWindow }?.let { view ->
                val xy = IntArray(2)
                view.getLocationOnScreen(xy)
                rect = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
            }
        }
        return rect
    }

    private fun awaitHostReady(service: MinisAccessibilityService) {
        var previous: Pair<Int, Rect>? = null
        var unchangedSince = SystemClock.uptimeMillis()
        await("External test host native application window must settle before orb input") {
            var observed: Pair<Int, Rect>? = null
            val windows = service.windows
            try {
                for (window in windows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive || !window.isFocused) continue
                    val root = window.root ?: continue
                    try {
                        if (root.packageName?.toString() == instrumentation.context.packageName) {
                            val frame = Rect(); window.getBoundsInScreen(frame)
                            if (!frame.isEmpty) observed = window.id to frame
                        }
                    } finally { root.recycle() }
                }
            } finally { windows.forEach { it.recycle() } }
            val now = SystemClock.uptimeMillis()
            if (observed == null || observed != previous) {
                touchTimings.add("hostGeometry previous=$previous current=$observed")
                previous = observed; unchangedSince = now
            }
            observed != null && now - unchangedSince >= ViewConfiguration.getDoubleTapTimeout()
        }
    }

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
        try { exerciseNativeDrag() }
        catch (failure: Throwable) {
            // Covers failures before setup and in cleanup, as well as body assertions.
            val details = "DOT_NATIVE_MOTION failure=${failure.javaClass.simpleName} " +
                "touches=${touchTimings.joinToString("; ")} geometry=$latestFailureGeometry"
            println(details)
            // Preserve the original failure, and expose evidence in instrumentation's
            // stack trace even when System.out is routed only to device logcat.
            failure.addSuppressed(AssertionError(details))
            throw failure
        }
    }

    private fun exerciseNativeDrag() {
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
            // An active root can appear during Activity entry. Match the gesture fixture's
            // native active/focused window readiness before creating the first DOWN time.
            awaitHostReady(connected)
            val orb = connected.getString(R.string.nm_dot_orb_description)
            await("Visible orb required") { bounds(connected, orb) != null }
            val start = requireNotNull(bounds(connected, orb))
            val host = connected.rootInActiveWindow?.let { node ->
                try { Rect().also { node.getBoundsInScreen(it) } } finally { node.recycle() }
            } ?: error("Native host bounds missing")
            assertTrue("Host must fit meaningful drag", host.width() > start.width() * 4 &&
                host.height() > start.height() * 4)
            var pointerOffsetX = 0f
            var pointerOffsetY = 0f
            fun moveTo(fx: Float, fy: Float) {
                assertTrue("MOVE must precede long-press suppression",
                    SystemClock.uptimeMillis() - requireNotNull(pointer) < ViewConfiguration.getLongPressTimeout())
                x = host.left + host.width() * fx; y = host.top + host.height() * fy
                touch(requireNotNull(pointer), MotionEvent.ACTION_MOVE, x, y)
                await("Native orb must follow MOVE") {
                    bounds(connected, orb)?.let { kotlin.math.abs(it.exactCenterX() - (x + pointerOffsetX)) <= 4 &&
                        kotlin.math.abs(it.exactCenterY() - (y + pointerOffsetY)) <= 4 } == true
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
            // AX bounds may describe an earlier frame. Aim at the actual native hitbox,
            // and require that this fresh observation still represents unfinished motion.
            val hitbox = requireNotNull(nativeBounds(connected))
            touchTimings.add("secondHitbox=${hitbox.toShortString()} axSnap=${snapping.toShortString()}")
            assertTrue("Native interruption hitbox remains before edge",
                hitbox.left > host.left + 8 && hitbox.right < host.right - 8)
            pointer = SystemClock.uptimeMillis(); x = hitbox.exactCenterX(); y = hitbox.exactCenterY()
            touch(requireNotNull(pointer), MotionEvent.ACTION_DOWN, x, y)
            Thread.sleep(20)
            val afterDown = requireNotNull(nativeBounds(connected))
            touchTimings.add("afterSecondDown=${afterDown.toShortString()} " +
                "sinceDown=${SystemClock.uptimeMillis() - requireNotNull(pointer)}ms")
            // A valid DOWN can land away from center as the target moves. Production
            // retains that finger offset; MOVE must preserve it instead of recentering.
            pointerOffsetX = afterDown.exactCenterX() - x
            pointerOffsetY = afterDown.exactCenterY() - y
            assertTrue("DOWN interrupts before native edge arrival",
                afterDown.left > host.left + 8 && afterDown.right < host.right - 8)
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

        } catch (failure: Throwable) {
            service?.let { connected ->
                latestFailureGeometry = runCatching {
                    val orb = connected.getString(R.string.nm_dot_orb_description)
                    val observed = bounds(connected, orb) ?: Rect()
                    releaseDiagnostics(connected, orb, observed, observed, SystemClock.uptimeMillis())
                }.getOrElse { "diagnosticUnavailable=${it.javaClass.simpleName}" }
                println("DOT_NATIVE_MOTION beforeCleanup=$latestFailureGeometry")
            }
            throw failure
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
