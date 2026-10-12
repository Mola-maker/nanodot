package io.github.nanomuse.dot.overlay

import android.app.UiAutomation
import android.os.SystemClock
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openminis.app.R
import android.view.WindowManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.nanomuse.dot.DotPreferences
import io.github.nanomuse.dot.gesture.DotIntent
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DotOverlayLifecycleTest {
    @Before fun restoreRealServiceAfterInstrumentationStartsTargetProcess() {
        // am instrument stops the existing target process. Rebind the granted service through
        // the real framework rather than manually calling onServiceConnected in a test.
        shell("input keyevent 224")
        shell("input keyevent 82")
        if (MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected != true) {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure enabled_accessibility_services io.github.nanodot.app/com.openminis.app.accessibility.MinisAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
        }
        connectedService()
    }

    private fun connectedService(): MinisAccessibilityService {
        val until = SystemClock.uptimeMillis() + 10000
        while (MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected != true &&
            SystemClock.uptimeMillis() < until) Thread.sleep(100)
        return requireNotNull(MinisAccessibilityService.getInstance())
    }

    private fun shell(command: String) {
        val fd = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    @Suppress("DEPRECATION") // AccessibilityEvent.obtain/recycle keep this regression compatible with API26.
    @Test fun realSleepAndLateAccessibilityEventCannotResurrectOrb() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        instrumentation.runOnMainSync { service.dotOverlay.setEnabled(true) }
        try {
            shell("input keyevent 223")
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                assertFalse(service.getSystemService(PowerManager::class.java).isInteractive)
                assertEquals(0, service.dotOverlay.windowCount)
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                service.onAccessibilityEvent(event)
                event.recycle()
                assertEquals("late service event must not restore a sleeping window", 0, service.dotOverlay.windowCount)
            }
        } finally {
            shell("input keyevent 224")
            shell("input keyevent 82")
            Thread.sleep(500)
        }
        instrumentation.runOnMainSync {
            service.dotOverlay.refreshLockState()
            assertTrue(requireNotNull(service.dotOverlay.orbView).isAttachedToWindow)
        }
    }

    @Test fun realOrbExposesEquivalentAccessibilityActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        instrumentation.runOnMainSync { service.dotOverlay.setEnabled(true); service.dotOverlay.setLocked(false) }
        instrumentation.waitForIdleSync()
        val expected = setOf(R.string.nm_dot_open_conversation, R.string.nm_dot_view_screen,
            R.string.nm_dot_start_care).map(service::getString).toSet()
        val labels = mutableSetOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            node.actionList.mapNotNullTo(labels) { it.label?.toString() }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        val end = SystemClock.uptimeMillis() + 5000
        while (!labels.containsAll(expected) && SystemClock.uptimeMillis() < end) {
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .mapNotNull { it.root }.forEach(::visit)
            if (!labels.containsAll(expected)) Thread.sleep(100)
        }
        assertTrue("real accessibility action labels: $labels", labels.containsAll(expected))
    }

    @Test fun pendingTapIsCancelledByLockRotationAndDisconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        val intents = mutableListOf<DotIntent>()
        lateinit var controller: DotOverlayController
        instrumentation.runOnMainSync {
            service.dotOverlay.disconnect()
            controller = DotOverlayController(service) { intents.add(it) }
            controller.connect()
            controller.setEnabled(true)
            controller.setLocked(false)
        }
        try {
            for (interruption in 0..2) {
                instrumentation.runOnMainSync {
                    val view = requireNotNull(controller.orbView)
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
                    val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, 100f, 100f, 0)
                    view.dispatchTouchEvent(down); view.dispatchTouchEvent(up)
                    down.recycle(); up.recycle()
                    when (interruption) {
                        0 -> { controller.setLocked(true); controller.setLocked(false) }
                        1 -> controller.onDisplayChanged()
                        else -> { controller.disconnect(); controller.connect() }
                    }
                }
                Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
                instrumentation.runOnMainSync { assertTrue("no capture/care intent after interruption", intents.isEmpty()) }
            }
        } finally {
            instrumentation.runOnMainSync { controller.disconnect(); service.dotOverlay.connect() }
        }
    }

    @Test fun appearanceChoicePersistsAndUpdatesConnectedWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        val preferences = DotPreferences(service)
        try {
            instrumentation.runOnMainSync { preferences.setShowOnAccessibilityConnected(false) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(0, service.dotOverlay.windowCount)
                service.dotOverlay.disconnect(); service.dotOverlay.connect()
                assertEquals(0, service.dotOverlay.windowCount)
                assertFalse(DotPreferences(service).showOnAccessibilityConnected())
            }
        } finally {
            instrumentation.runOnMainSync { preferences.setShowOnAccessibilityConnected(true) }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun serviceForegroundReconnectRejectsOldCallbacksAndRebuildsCareOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        instrumentation.runOnMainSync {
            val coordinator = service.dotInteraction
            val before = coordinator.foreground.ticket()
            val care = coordinator.careSession
            coordinator.disconnect()
            coordinator.disconnect()
            assertFalse(coordinator.foreground.accepts(before))
            assertFalse(coordinator.foreground.state.value.connected)
            assertNull(coordinator.foreground.state.value.binding)
            coordinator.connect()
            assertTrue(coordinator.foreground.accepts(coordinator.foreground.ticket()))
            assertFalse(coordinator.foreground.accepts(before))
            assertNotSame("Care must not retain the cancelled coroutine scope", care, coordinator.careSession)
            assertSame(coordinator.foreground.state, service.dotOverlay.foregroundState)
        }
    }

    private fun awaitAttached(controller: DotOverlayController) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val until = SystemClock.uptimeMillis() + 5000
        var attached = false
        while (!attached && SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync { attached = controller.orbView?.isAttachedToWindow == true }
            if (!attached) Thread.sleep(16)
        }
        assertTrue("new real window must attach after its scheduled traversal", attached)
    }

    @Test fun realAccessibilityWindowFollowsLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        val controller = service.dotOverlay
        instrumentation.runOnMainSync {
            assertTrue("real onServiceConnected required", controller.isConnected)
            controller.setEnabled(true)
            controller.setLocked(false)
            controller.connect()
            controller.connect()
        }
        awaitAttached(controller)
        instrumentation.runOnMainSync {
            val first = requireNotNull(controller.orbView)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                (first.layoutParams as WindowManager.LayoutParams).type)
            assertEquals(1, controller.windowCount)
            val owner = requireNotNull(controller.orbOwner)
            controller.setLocked(true)
            assertFalse(first.isAttachedToWindow)
            assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
            assertEquals(0, controller.windowCount)
            controller.setEnabled(false)
            controller.setLocked(false)
            assertEquals(0, controller.windowCount)
            controller.setEnabled(true)
        }
        awaitAttached(controller)
        instrumentation.runOnMainSync {
            val beforeRotation = requireNotNull(controller.orbView)
            assertTrue(beforeRotation.isAttachedToWindow)
            controller.onDisplayChanged()
            assertFalse(beforeRotation.isAttachedToWindow)
            assertEquals(1, controller.windowCount)
        }
        awaitAttached(controller)
        instrumentation.runOnMainSync {
            controller.disconnect()
            controller.disconnect()
            assertEquals(0, controller.windowCount)
            controller.connect()
            assertEquals(1, controller.windowCount)
        }
        awaitAttached(controller)
    }
}
