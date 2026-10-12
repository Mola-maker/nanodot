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
class DotForegroundGestureNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
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
    private fun await(message: String, onTimeout: () -> Unit = {}, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return
            Thread.sleep(50)
        }
        val settled = check()
        if (!settled) onTimeout()
        assertTrue(message, settled)
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
    private fun diagnose(service: MinisAccessibilityService, stage: String, hit: Rect,
        orbLabel: String, collapseLabel: String) {
        // Never print binding IDs, conversation content, host text, accounts, or screenshots.
        val out = StringBuilder("DOT_NATIVE_GESTURE stage=$stage hit=$hit")
        out.append(" orbNode=").append(bounds(service, orbLabel))
            .append(" collapseNode=").append(bounds(service, collapseLabel))
        instrumentation.runOnMainSync {
            val state = service.dotInteraction.foreground.state.value
            out.append(" mode=${state.mode} operation=${state.operation} bindingPresent=${state.binding != null}")
                .append(" foregroundConnected=${state.connected} epoch=${state.epoch}")
                .append(" overlayConnected=${service.dotOverlay.isConnected} windows=${service.dotOverlay.windowCount}")
                .append(" careVisible=${service.dotInteraction.careSession.visible.value}")
            fun viewStatus(name: String, view: android.view.View?) {
                out.append(" $name-present=${view != null}")
                view?.let {
                    val xy = IntArray(2); it.getLocationOnScreen(xy)
                    val rect = Rect(xy[0], xy[1], xy[0] + it.width, xy[1] + it.height)
                    val flags = (it.layoutParams as? android.view.WindowManager.LayoutParams)?.flags
                    out.append(" $name-screen=$rect $name-attached=${it.isAttachedToWindow}")
                        .append(" $name-shown=${it.isShown} $name-alpha=${it.alpha} $name-flags=$flags")
                }
            }
            viewStatus("orb", service.dotOverlay.orbView)
            viewStatus("panel", service.dotOverlay.panelView)
            val display = service.dotOverlay.orbView?.display
            val size = android.graphics.Point()
            display?.getRealSize(size)
            val metrics = service.resources.displayMetrics
            out.append(" display=${display?.displayId} rotation=${display?.rotation} realSize=$size")
                .append(" metrics=${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density}")
                .append(" fontScale=${service.resources.configuration.fontScale}")
        }
        out.append(" interactive=${context.getSystemService(PowerManager::class.java).isInteractive}")
            .append(" keyguard=${context.getSystemService(KeyguardManager::class.java).isKeyguardLocked}")
            .append(" doubleTapMs=${ViewConfiguration.getDoubleTapTimeout()} axFlags=${automation.serviceInfo.flags}")
        val windows = service.windows
        try {
            windows.forEach { window ->
                val rect = Rect(); window.getBoundsInScreen(rect)
                out.append(" nativeWindow(type=${window.type},bounds=$rect,active=${window.isActive},focused=${window.isFocused})")
            }
        } finally { windows.forEach { it.recycle() } }
        println(out.toString())
    }
    private fun tap(rect: Rect) {
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                val accepted = automation.injectInputEvent(event, true)
                println("DOT_NATIVE_GESTURE inputAction=$action downTime=$downTime eventTime=${event.eventTime} x=${event.x} y=${event.y} rawX=${event.rawX} rawY=${event.rawY} accepted=$accepted")
                assertTrue("Android must accept the native touch", accepted)
            }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        Thread.sleep(24)
        inject(MotionEvent.ACTION_UP)
    }

    @Test fun realOrbTapCountsMountChatScreenAndCare() {
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
        var service: MinisAccessibilityService? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            if (MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected != true) {
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
                MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true
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
            val collapse = connected.getString(R.string.nm_dot_chat_collapse)
            val interval = (ViewConfiguration.getDoubleTapTimeout() / 4).coerceAtLeast(1).toLong()
            for ((count, mode) in listOf(1 to DotForegroundMode.Chat,
                2 to DotForegroundMode.Screen, 3 to DotForegroundMode.Care)) {
                await("Visible orb required before $count native taps") { bounds(connected, orb) != null }
                val hit = requireNotNull(bounds(connected, orb))
                diagnose(connected, "before-$count-taps", hit, orb, collapse)
                repeat(count) { index -> tap(hit); if (index + 1 < count) Thread.sleep(interval) }
                // Let the platform-configured reducer deadline classify the completed tap sequence.
                Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
                diagnose(connected, "after-$count-tap-arbitration", hit, orb, collapse)
                await("$count native taps must mount $mode and a visible collapse control",
                    onTimeout = { diagnose(connected, "mount-$mode-timeout", hit, orb, collapse) }) {
                    onMain { connected.dotInteraction.foreground.state.value.mode == mode &&
                        if (mode == DotForegroundMode.Care) connected.dotInteraction.careSession.visible.value
                        else connected.dotOverlay.panelView?.isAttachedToWindow == true } &&
                        bounds(connected, collapse) != null
                }
                tap(requireNotNull(bounds(connected, collapse)))
                await("Native collapse must remove the mounted $mode panel",
                    onTimeout = { diagnose(connected, "collapse-$mode-timeout", hit, orb, collapse) }) {
                    onMain { connected.dotOverlay.panelView == null && !connected.dotInteraction.careSession.visible.value } &&
                        bounds(connected, collapse) == null
                }
            }
        } finally {
            try {
                service?.let { connected -> instrumentation.runOnMainSync {
                    connected.dotInteraction.close()
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
