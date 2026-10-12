package io.github.nanomuse.dot.overlay

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.StreamingDelta
import io.github.nanomuse.dot.*
import io.github.nanomuse.dot.care.CareOverlayFixture
import io.github.nanomuse.dot.chat.*
import io.github.nanomuse.dot.gesture.DotIntent
import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Root executes against real accessibility windows. Only the asynchronous sampler is held;
 * admission, actual mounted status/Stop and attachment disposal are production code. No sockets. */
@RunWith(AndroidJUnit4::class)
class DotForegroundReviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private class LocalSession : DotChatSession {
        override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        override val inputText = MutableStateFlow("capture draft")
        override val isStreaming = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val configured = MutableStateFlow<String?>("inert fixture")
        override val deltas = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
        override val consumedAttachments = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun input(text: String) { inputText.value = text }
        override fun stage(file: java.io.File): DotOwnedAttachment? = error("No Send in this fixture")
        override fun remove(id: String) = Unit
        override fun send(text: String) = error("No model calls")
        override fun cancel() { isStreaming.value = false }
        override fun retry() = error("No model calls")
    }
    private fun awaitMain(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(32)
        }
        fail(message)
    }
    @Suppress("DEPRECATION")
    private fun visibleControl(service: MinisAccessibilityService, label: String, click: Boolean = false): Boolean {
        automation.clearCache()
        val native = automation.windows
        var found = false
        fun visit(node: AccessibilityNodeInfo) {
            if (found) return
            if (node.text?.toString() == label && node.isVisibleToUser && node.isEnabled) {
                var action = AccessibilityNodeInfo.obtain(node)
                try {
                    while (!action.isClickable) {
                        val parent = action.parent ?: break
                        action.recycle(); action = parent
                    }
                    val bounds = Rect(); action.getBoundsInScreen(bounds)
                    if (action.isVisibleToUser && action.isEnabled && action.isClickable &&
                        bounds.height() >= (48 * service.resources.displayMetrics.density).toInt()) {
                        found = !click || action.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } finally { action.recycle() }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { try { visit(it) } finally { it.recycle() } }
        }
        try { native.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.forEach { window ->
            window.root?.let { try { visit(it) } finally { it.recycle() } }
        } } finally { native.forEach { it.recycle() } }
        return found
    }
    @Suppress("DEPRECATION")
    private fun visibleLabel(label: String): Boolean {
        automation.clearCache()
        val windows = automation.windows
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.toString() == label) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let {
                try { if (visit(it)) return true } finally { it.recycle() }
            }
            return false
        }
        return try { windows.any { window -> window.root?.let { try { visit(it) } finally { it.recycle() } } == true } }
            finally { windows.forEach { it.recycle() } }
    }
    private fun withHost(block: (MinisAccessibilityService) -> Unit) {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val flags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.context.startActivity(Intent(instrumentation.context, CareOverlayFixture::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitMain("External fixture host must be current") { service.dotScreen.currentSceneKey()?.packageName == instrumentation.context.packageName }
            instrumentation.runOnMainSync { service.dotInteraction.close(); service.dotOverlay.setEnabled(true); service.dotOverlay.setLocked(false) }
            block(service)
        } finally {
            instrumentation.runOnMainSync { service.dotInteraction.disconnect(); service.dotInteraction.connect() }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }
    @Test fun heldScreenCaptureKeepsActualStopReachableAndClosesLateSuccess() = withHost { service ->
        val entered = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        val image = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val session = LocalSession()
        var stages = 0
        val adapter = DotChatAdapter({ it }, { session }, { stages++; error("Stopped capture must never stage") })
        lateinit var owner: DotInteractionCoordinator
        var cleanup: () -> Unit = {}
        try {
            instrumentation.runOnMainSync {
                owner = DotInteractionCoordinator(service, adapter,
                    screenCapture = { _, generation ->
                        entered.complete(generation)
                        withContext(NonCancellable) {
                            release.await()
                            val bounds = RectPx(0f, 0f, 48f, 48f)
                            val shot = ScreenSnapshot("held", generation, 1, SceneKey("fixture.host", 1, 0, 1), bounds,
                                0, RectPx(0f, 0f, 0f, 0f), null, emptyList(), image, ScreenTransform(bounds, 48, 48))
                            delivered.complete(Unit)
                            CaptureOutcome.Success(shot)
                        }
                    }, nodeCapture = { error("Successful held pixel capture needs no fallback") })
                cleanup = owner::disconnect
                owner.connect(); owner.handle(DotIntent.Double)
            }
            awaitMain("Capture must suspend") { entered.isCompleted }
            awaitMain("Actual capture status host remains visible during suspension") {
                owner.foreground.state.value.operation == DotForegroundOperation.Capturing &&
                    service.dotOverlay.panelView?.let { it.isAttachedToWindow && it.isShown && it.alpha > 0f && it.width > 0 && it.height > 0 } == true
            }
            automation.waitForIdle(200, 3000)
            lateinit var before: DotForegroundTicket
            lateinit var old: SessionBinding
            val view = service.dotOverlay.panelView
            instrumentation.runOnMainSync { before = owner.foreground.ticket(); old = requireNotNull(adapter.binding.value) }
            assertTrue("48dp enabled native Stop must be reachable during capture", visibleControl(service, service.getString(com.openminis.app.R.string.nm_dot_chat_stop), true))
            awaitMain("Stop invalidates capture admission") { !owner.foreground.accepts(before) && owner.foreground.state.value.operation == DotForegroundOperation.Idle }
            instrumentation.runOnMainSync {
                assertSame(view, service.dotOverlay.panelView)
                assertNotEquals(old, adapter.binding.value)
                assertEquals("capture draft", session.inputText.value)
                release.complete(Unit)
            }
            awaitMain("Late successful capture is closed") { delivered.isCompleted && image.isRecycled }
            instrumentation.runOnMainSync { assertNull(adapter.preview.value); assertFalse(adapter.captureError.value); assertEquals(0, stages) }
        } finally {
            instrumentation.runOnMainSync { release.complete(Unit); cleanup() }
            if (!entered.isCompleted && !image.isRecycled) image.recycle()
        }
    }
    @Test fun hostWindowPixelsExcludeMountedStatusWithoutHidingItsControls() = withHost { service ->
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34)
        val adapter = DotChatAdapter({ it }, { LocalSession() }, { error("No attachment in this pixel fixture") })
        lateinit var owner: DotInteractionCoordinator
        var cleanup: () -> Unit = {}
        val result = CompletableDeferred<CaptureOutcome>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            instrumentation.runOnMainSync {
                owner = DotInteractionCoordinator(service, adapter); cleanup = owner::disconnect
                owner.connect(); owner.handle(DotIntent.Single)
            }
            awaitMain("Actual status panel must be visible before a real window shot") {
                service.dotOverlay.panelView?.let { it.isAttachedToWindow && it.isShown && it.alpha > 0f && it.height > 1 } == true
            }
            val view = requireNotNull(service.dotOverlay.panelView)
            val location = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
            val display = requireNotNull(automation.takeScreenshot())
            var marked: android.graphics.Point? = null
            try {
                // Prove a visibly drawn text/edge pixel exists over the fixture's empty white
                // lower host. Near-white glass alone cannot distinguish exclusion from inclusion.
                find@ for (y in location[1] + view.height / 2 until location[1] + view.height) {
                    for (x in location[0] until location[0] + view.width) {
                        val color = display.getPixel(x, y)
                        if (android.graphics.Color.red(color) < 180 || android.graphics.Color.green(color) < 180 ||
                            android.graphics.Color.blue(color) < 180) {
                            marked = android.graphics.Point(x, y); break@find
                        }
                    }
                }
                assertNotNull("Display screenshot must first prove a contrasting panel pixel", marked)
            } finally { display.recycle() }
            instrumentation.runOnMainSync {
                scope.launch { result.complete(service.dotScreen.capture(CapturePurpose.Chat, requireNotNull(adapter.binding.value).generation)) }
            }
            awaitMain("Real host window screenshot must finish") { result.isCompleted }
            val outcome = runBlocking { result.await() }
            assertTrue("Expected real window pixels, got $outcome", outcome is CaptureOutcome.Success)
            (outcome as CaptureOutcome.Success).snapshot.use { shot ->
                assertNotNull(shot.image)
                assertFalse(shot.nodes.any { it.text == service.getString(com.openminis.app.R.string.nm_dot_chat_stop) })
                // The exact display coordinate visibly contained our contrasting card pixel;
                // window capture must reveal its white host beneath it instead.
                val marker = requireNotNull(marked)
                val x = (marker.x - shot.bounds.left).toInt()
                val y = (marker.y - shot.bounds.top).toInt()
                val color = requireNotNull(shot.image).getPixel(x, y)
                assertTrue(android.graphics.Color.red(color) >= 250 && android.graphics.Color.green(color) >= 250 && android.graphics.Color.blue(color) >= 250)
            }
            instrumentation.runOnMainSync { assertSame(view, service.dotOverlay.panelView); assertTrue(view.isShown && view.alpha > 0f) }
        } finally { instrumentation.runOnMainSync { cleanup(); scope.cancel() } }
    }
    @Test fun failedScreenCaptureDoesNotContaminateHealthyCareStatus() = withHost { service ->
        val session = LocalSession()
        val adapter = DotChatAdapter({ it }, { session }, { error("Unavailable capture has no image") })
        lateinit var owner: DotInteractionCoordinator
        var cleanup: () -> Unit = {}
        try {
            instrumentation.runOnMainSync {
                owner = DotInteractionCoordinator(service, adapter,
                    screenCapture = { _, _ -> CaptureOutcome.Unavailable("fixture_unavailable") },
                    nodeCapture = { CaptureOutcome.Unavailable("fixture_unavailable") })
                cleanup = owner::disconnect
                owner.connect(); owner.handle(DotIntent.Double)
            }
            awaitMain("Failed capture must project Error") { adapter.captureError.value && owner.foreground.state.value.operation == DotForegroundOperation.Error }
            instrumentation.runOnMainSync { owner.handle(DotIntent.Triple) }
            awaitMain("New healthy Care owner must project Idle") { owner.foreground.state.value.mode == DotForegroundMode.Care && owner.foreground.state.value.operation == DotForegroundOperation.Idle }
            automation.waitForIdle(300, 3000)
            assertEquals(com.openminis.app.R.string.nm_status_waiting, io.github.nanomuse.dot.ui.foregroundStatusResource(owner.foreground.state.value))
            assertTrue("Mounted Care status must describe its healthy owner", visibleLabel(service.getString(com.openminis.app.R.string.nm_status_waiting)))
            assertTrue("Care's own visible Stop remains reachable", visibleControl(service, service.getString(com.openminis.app.R.string.nm_dot_chat_stop)))
        } finally { instrumentation.runOnMainSync { cleanup() } }
    }
}
