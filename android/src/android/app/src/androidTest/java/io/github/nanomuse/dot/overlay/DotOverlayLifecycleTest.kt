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

    @Test fun visibleStopRefreshesAdmissionAndContinuesProjection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        val stream = kotlinx.coroutines.flow.MutableStateFlow(false)
        var stoppedTransitions = 0
        val session = object : io.github.nanomuse.dot.chat.DotChatSession {
            override val messages = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.openminis.app.ui.chat.ChatMessage>())
            override val inputText = kotlinx.coroutines.flow.MutableStateFlow("")
            override val isStreaming = stream
            override val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
            override val configured = kotlinx.coroutines.flow.MutableStateFlow<String?>("local fixture")
            override val deltas = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, com.openminis.app.ui.chat.StreamingDelta>())
            override val consumedAttachments = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, String>())
            override fun input(text: String) { inputText.value = text }
            override fun stage(file: java.io.File): io.github.nanomuse.dot.chat.DotOwnedAttachment? = null
            override fun remove(id: String) = Unit
            override fun send(text: String) { stream.value = true }
            override fun cancel() { if (stream.value) stoppedTransitions++; stream.value = false }
            override fun retry() = Unit
        }
        val adapter = io.github.nanomuse.dot.chat.DotChatAdapter({ it }, { session }, { error("No capture") })
        val coordinator = io.github.nanomuse.dot.DotInteractionCoordinator(service, adapter)
        fun awaitState(check: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < until) {
                var done = false
                instrumentation.runOnMainSync { done = check() }
                if (done) return
                instrumentation.waitForIdleSync()
            }
            fail("Foreground transition did not settle")
        }
        val owner = coordinator.foreground
        try {
            instrumentation.runOnMainSync {
                coordinator.connect()
                service.dotOverlay.setEnabled(true)
                coordinator.handle(io.github.nanomuse.dot.gesture.DotIntent.Single)
            }
            awaitState { service.dotOverlay.panelView != null && adapter.binding.value != null }
            lateinit var before: io.github.nanomuse.dot.DotForegroundTicket
            lateinit var oldBinding: io.github.nanomuse.dot.chat.SessionBinding
            instrumentation.runOnMainSync {
                oldBinding = requireNotNull(adapter.binding.value)
                before = owner.ticket()
                assertTrue(adapter.send(oldBinding, "first local reply"))
            }
            awaitState { owner.state.value.operation == io.github.nanomuse.dot.DotForegroundOperation.Replying }
            val retainedView = service.dotOverlay.panelView
            val retainedList = service.dotOverlay.panelTranscriptState
            val host = requireNotNull(service.dotScreen.hostViewport(service.dotOverlay.panelHostWindowId))
            instrumentation.runOnMainSync {
                service.dotOverlay.setForegroundTarget(io.github.nanomuse.dot.screen.RectPx(host.left, host.bottom - 120f, host.right, host.bottom))
                service.dotOverlay.onDisplayChanged()
            }
            Thread.sleep(400)
            instrumentation.runOnMainSync {
                assertSame(retainedView, service.dotOverlay.panelView)
                assertSame(retainedList, service.dotOverlay.panelTranscriptState)
                assertEquals(oldBinding, adapter.binding.value)
                assertEquals(before, owner.ticket())
                assertTrue(stream.value)
                service.dotOverlay.setForegroundTarget(null)
            }
            val label = service.getString(com.openminis.app.R.string.nm_dot_chat_stop)
            var clicked = false
            val diagnostics = java.io.File(service.cacheDir, "dot-stop-native-tree.txt")
            val until = SystemClock.uptimeMillis() + 5000
            while (!clicked && SystemClock.uptimeMillis() < until) {
                val dump = StringBuilder("Stop label=$label\n")
                fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    dump.append("  ".repeat(depth)).append("text=${node.text} desc=${node.contentDescription} bounds=$bounds clickable=${node.isClickable} visible=${node.isVisibleToUser} enabled=${node.isEnabled} actions=${node.actionList}\n")
                    if (node.isVisibleToUser && node.isEnabled &&
                        (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            dump.append("CLICK matched node succeeded\n")
                            return true
                        }
                        var ancestor = node.parent
                        while (ancestor != null) {
                            val current = ancestor
                            val next = current.parent
                            val accepted = current.isVisibleToUser && current.isEnabled && current.isClickable &&
                                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            dump.append("CLICK ancestor clickable=${current.isClickable} accepted=$accepted\n")
                            current.recycle()
                            if (accepted) { next?.recycle(); return true }
                            ancestor = next
                        }
                    }
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { if (visit(child, depth + 1)) return true }
                        finally { child.recycle() }
                    }
                    return false
                }
                val windows = service.windows
                try { windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.forEach { window ->
                    dump.append("WINDOW id=${window.id} type=${window.type}\n")
                    val root = window.root
                    try { if (!clicked && root != null) clicked = visit(root, 0) }
                    finally { root?.recycle() }
                } } finally { windows.forEach { it.recycle() } }
                diagnostics.writeText(dump.toString())
                instrumentation.waitForIdleSync()
            }
            assertTrue("Visible Stop must invoke the mounted control", clicked)
            awaitState { owner.state.value.operation == io.github.nanomuse.dot.DotForegroundOperation.Idle && owner.state.value.binding != oldBinding }
            instrumentation.runOnMainSync {
                assertFalse(owner.accepts(before))
                assertEquals("The active reply must transition to stopped", 1, stoppedTransitions)
                assertFalse(stream.value)
                assertNotNull(service.dotOverlay.panelView)
                val fresh = requireNotNull(adapter.binding.value)
                assertEquals(fresh, owner.state.value.binding)
                assertTrue(adapter.send(fresh, "next local reply"))
            }
            awaitState { owner.state.value.operation == io.github.nanomuse.dot.DotForegroundOperation.Replying }
        } finally {
            instrumentation.runOnMainSync {
                coordinator.disconnect()
                service.dotInteraction.disconnect()
                service.dotInteraction.connect()
            }
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
