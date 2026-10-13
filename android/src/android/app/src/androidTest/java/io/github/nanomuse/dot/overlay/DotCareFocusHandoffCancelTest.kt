package io.github.nanomuse.dot.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.StreamingDelta
import io.github.nanomuse.dot.*
import io.github.nanomuse.dot.care.CareOverlayFixture
import io.github.nanomuse.dot.chat.*
import io.github.nanomuse.dot.gesture.DotIntent
import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.*
import io.github.nanomuse.dot.selection.RegionSelection
import io.github.nanomuse.dot.selection.SelectionOrigin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Native cancellation boundary only. No goal, Send, model, successful crop or gesture claim.
 * The sampler is a fail-fast counter; both cards, window focus and the Stop input are real.
 * Expected RED on R5: old Care Stop hides only Care and leaves the opening ticket admitted.
 */
@RunWith(AndroidJUnit4::class)
class DotCareFocusHandoffCancelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val nativeService = DotNativeServiceFixture()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    // Same native input entry used by DotRegionVisionNativeTest on the coordinator's QA guest.
    // Disable animation waiting so the two-second focus timeout cannot be spent inside injection.
    private val nativeInject by lazy {
        UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
    }
    @Before fun connect() { nativeService.connect() }
    @After fun restoreService() { nativeService.close() }

    private class LocalSession : DotChatSession {
        override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        override val inputText = MutableStateFlow("unsent cancellation draft")
        override val isStreaming = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val configured = MutableStateFlow<String?>("inert cancellation fixture")
        override val deltas = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
        override val consumedAttachments = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun input(text: String) { inputText.value = text }
        override fun stage(file: File): DotOwnedAttachment? = error("No Send permitted")
        override fun remove(id: String) = Unit
        override fun send(text: String) = error("No provider permitted")
        override fun cancel() { isStreaming.value = false }
        override fun retry() = error("No provider permitted")
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(16)
        }
        fail(message)
    }
    private data class NativeCard(val id: Int, val bounds: Rect, val focused: Boolean, val stop: Rect?)
    private data class HostObservation(val scene: SceneKey, val bounds: Rect)

    @Suppress("DEPRECATION")
    private fun hasVisibleMarker(root: AccessibilityNodeInfo, marker: String): Boolean {
        val matches = root.findAccessibilityNodeInfosByText(marker)
        return try { matches.any { it.isVisibleToUser &&
            (it.text?.toString() == marker || it.contentDescription?.toString() == marker) } }
        finally { matches.forEach { it.recycle() } }
    }

    /** Package alone can still name the preceding test Activity while this launch is pending. */
    @Suppress("DEPRECATION")
    private fun fixtureHost(service: MinisAccessibilityService): HostObservation? {
        automation.clearCache()
        val scene = main { service.dotScreen.currentSceneKey() } ?: return null
        if (scene.packageName != instrumentation.context.packageName) return null
        val windows = automation.windows
        return try {
            val window = windows.singleOrNull { it.id == scene.windowId && it.isFocused &&
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
            val root = window.root ?: return null
            try {
                if (root.windowId != scene.windowId || root.packageName?.toString() != scene.packageName ||
                    !hasVisibleMarker(root, "CARE_TOUCH_TARGET") ||
                    !hasVisibleMarker(root, "CARE_TOUCH_PENDING")) return null
                HostObservation(scene, Rect().also(window::getBoundsInScreen))
            } finally { root.recycle() }
        } finally { windows.forEach { it.recycle() } }
    }

    /** Public-fixture metadata only; never dump arbitrary node text or screenshot content. */
    @Suppress("DEPRECATION")
    private fun setupWindows(directory: File, phase: String, stopLabel: String) {
        automation.clearCache()
        val windows = automation.windows
        val rows = JSONArray()
        try {
            windows.forEach { window ->
                val stops = JSONArray()
                window.root?.let { root ->
                    try {
                        val matches = root.findAccessibilityNodeInfosByText(stopLabel)
                        try { matches.filter { it.isVisibleToUser && it.isEnabled &&
                            (it.text?.toString() == stopLabel || it.contentDescription?.toString() == stopLabel)
                        }.forEach { stops.put(Rect().apply { it.getBoundsInScreen(this) }.toShortString()) } }
                        finally { matches.forEach { it.recycle() } }
                    } finally { root.recycle() }
                }
                rows.put(JSONObject().put("id", window.id).put("type", window.type)
                    .put("title", window.title?.toString()).put("focused", window.isFocused)
                    .put("bounds", Rect().also(window::getBoundsInScreen).toShortString())
                    .put("stopLabelCount", stops.length()).put("stopLabelBounds", stops))
            }
        } finally { windows.forEach { it.recycle() } }
        File(directory, "$phase-windows.json").writeText(JSONObject().put("phase", phase)
            .put("wallClockMs", System.currentTimeMillis()).put("uptimeMs", SystemClock.uptimeMillis())
            .put("windows", rows).toString())
    }

    /** Select only the original Care window; never use the first globally matching Stop. */
    @Suppress("DEPRECATION")
    private fun careCard(label: String, id: Int? = null): NativeCard? {
        automation.clearCache()
        val windows = automation.windows
        fun stopBounds(node: AccessibilityNodeInfo): Rect? {
            if (node.isVisibleToUser && node.isEnabled &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                var action = AccessibilityNodeInfo.obtain(node)
                try {
                    while (!action.isClickable) {
                        val parent = action.parent ?: break
                        action.recycle(); action = parent
                    }
                    if (action.isVisibleToUser && action.isEnabled && action.isClickable) {
                        return Rect().apply { action.getBoundsInScreen(this) }.takeUnless { it.isEmpty }
                    }
                } finally { action.recycle() }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { stopBounds(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        return try {
            val window = windows.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                it.title?.toString() == "Nanodot Care card" && (id == null || it.id == id) } ?: return null
            val bounds = Rect().also(window::getBoundsInScreen)
            val stop = window.root?.let { root -> try { stopBounds(root) } finally { root.recycle() } }
            NativeCard(window.id, bounds, window.isFocused, stop)
        } finally { windows.forEach { it.recycle() } }
    }
    private fun input(action: Int, downAt: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertEquals("Physical input was rejected", true, nativeInject.invoke(automation, event, true, false)) }
        finally { event.recycle() }
    }
    private fun dragHeader(card: NativeCard, host: RectPx, density: Float) {
        // The left part of the status/header is the existing draggable Box, away from Stop/menu.
        val x = card.bounds.left + 24f * density
        val from = card.bounds.top + 24f * density
        val to = host.top + 24f * density
        val downAt = SystemClock.uptimeMillis()
        input(MotionEvent.ACTION_DOWN, downAt, x, from)
        for (n in 1..12) {
            SystemClock.sleep(16)
            input(MotionEvent.ACTION_MOVE, downAt, x, from + (to - from) * n / 12f)
        }
        input(MotionEvent.ACTION_UP, downAt, x, to)
    }
    private fun tap(x: Float, y: Float) {
        val downAt = SystemClock.uptimeMillis()
        input(MotionEvent.ACTION_DOWN, downAt, x, y)
        SystemClock.sleep(48)
        input(MotionEvent.ACTION_UP, downAt, x, y)
    }
    private fun bounds(view: View): Rect {
        val position = IntArray(2); view.getLocationOnScreen(position)
        return Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }
    // Read-only test inspection; no product hook, reflection write or callback invocation.
    private fun opening(owner: DotInteractionCoordinator): Job =
        owner.javaClass.getDeclaredField("opening").apply { isAccessible = true }.get(owner) as Job
    private fun outline(service: MinisAccessibilityService): View? =
        service.dotOverlay.javaClass.getDeclaredField("selectionOutline").apply { isAccessible = true }
            .get(service.dotOverlay) as? View
    private fun screenshot(directory: File, name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun oldCareStopCancelsNativeFocusWaitAndCannotHideReenteredCare() {
        val service = nativeService.service()
        val savedFlags = automation.serviceInfo.flags
        val previousSession = main { ChatViewModelStore.activeSessionId }
        val evidence = File(instrumentation.targetContext.cacheDir,
            "care-focus-cancel-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val session = LocalSession()
        var captureCalls = 0
        var nodeCalls = 0
        val adapter = DotChatAdapter({ it }, { session }, { error("No capture may reach staging") })
        var owner: DotInteractionCoordinator? = null
        val stopLabel = service.getString(R.string.nm_dot_chat_stop)
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = savedFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.context.startActivity(Intent(instrumentation.context, CareOverlayFixture::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var previousHost: HostObservation? = null
            try {
                await("Exact Care fixture must own the same focused host window in two observations") {
                    val observed = fixtureHost(service)
                    val stable = observed != null && observed == previousHost
                    previousHost = observed
                    stable
                }
            } catch (failure: AssertionError) {
                setupWindows(evidence, "host-admission-failed", stopLabel)
                throw failure
            }
            val current = main {
                service.dotInteraction.close(); service.dotOverlay.setEnabled(true); service.dotOverlay.setLocked(false)
                // Process-local, inert binding avoids a database resolve suspension before panel creation.
                ChatViewModelStore.setActiveSession("__new__focus-cancel-${UUID.randomUUID()}")
                DotInteractionCoordinator(service, adapter,
                    screenCapture = { _, _ -> captureCalls++; CaptureOutcome.Unavailable("unexpected_capture") },
                    nodeCapture = { nodeCalls++; CaptureOutcome.Unavailable("unexpected_nodes") }).also {
                    owner = it; it.connect(); it.handle(DotIntent.Triple)
                }
            }
            try {
                await("The genuine Care card must be focused and expose enabled Stop") {
                    careCard(stopLabel)?.let { it.focused && it.stop != null } == true
                }
            } catch (failure: AssertionError) {
                setupWindows(evidence, "care-admission-failed", stopLabel)
                throw failure
            }
            val original = requireNotNull(careCard(stopLabel))
            val host = main { requireNotNull(service.dotScreen.hostViewport()) }
            dragHeader(original, host, service.resources.displayMetrics.density)
            await("Drag must expose the old Care header above the future bottom Screen panel") {
                careCard(stopLabel, original.id)?.let { it.focused && it.bounds.top < host.top + host.height * .25f } == true
            }
            screenshot(evidence, "care-before-selection")

            val handoffStarted = SystemClock.uptimeMillis()
            val waiting = main {
                val viewport = requireNotNull(service.dotScreen.hostViewport())
                val key = requireNotNull(service.dotScreen.currentSceneKey())
                val left = viewport.left + viewport.width * .25f
                val top = viewport.top + viewport.height * .40f
                val right = viewport.left + viewport.width * .65f
                val bottom = viewport.top + viewport.height * .60f
                val region = RegionSelection(listOf(PointPx(left, top), PointPx(right, top),
                    PointPx(right, bottom), PointPx(left, bottom), PointPx(left, top)),
                    RectPx(left, top, right, bottom), 3000f)
                current.selectRegion(region, SelectionOrigin(key, viewport))
                val panel = requireNotNull(service.dotOverlay.panelView)
                assertFalse("Must intercept the new panel before it receives native focus", panel.hasWindowFocus())
                val params = panel.layoutParams as WindowManager.LayoutParams
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                service.getSystemService(WindowManager::class.java).updateViewLayout(panel, params)
                assertEquals(DotForegroundMode.Screen, current.foreground.state.value.mode)
                assertEquals(DotForegroundOperation.Capturing, current.foreground.state.value.operation)
                assertTrue(current.careSession.visible.value)
                assertEquals(0, captureCalls); assertEquals(0, nodeCalls)
                // A real owned decoration proves Stop removes an existing outline, not only null.
                service.dotOverlay.showSelectionOutline(region)
                Triple(current.foreground.ticket(), panel, opening(current))
            }
            val (ticket, panel, oldJob) = waiting
            // Allow actual window traversal while NOFOCUS remains set. Do not waitForIdle here:
            // the product timeout is two seconds, and timeout must never masquerade as Stop.
            SystemClock.sleep(96)
            val old = requireNotNull(careCard(stopLabel, original.id))
            val stop = requireNotNull(old.stop)
            val panelBounds = main { bounds(panel) }
            val x = stop.exactCenterX(); val y = stop.exactCenterY()
            assertTrue("Original Care must still own native focus", old.focused)
            assertFalse("Old Care Stop is occluded by the new Screen card; do not tap another Stop",
                panelBounds.contains(x.toInt(), y.toInt()))
            assertTrue("Old Care Stop must remain inside its original window", old.bounds.contains(x.toInt(), y.toInt()))
            main {
                assertSame(panel, service.dotOverlay.panelView)
                assertTrue(panel.isAttachedToWindow)
                assertFalse(panel.hasWindowFocus())
                assertTrue((panel.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertTrue("The opening coroutine must actually still be waiting", oldJob.isActive && !oldJob.isCompleted)
                assertTrue(current.foreground.accepts(ticket))
                assertTrue(outline(service)?.isAttachedToWindow == true)
                assertNull(adapter.preview.value)
                assertEquals(0, captureCalls); assertEquals(0, nodeCalls)
            }
            screenshot(evidence, "focus-wait-old-care-stop")
            assertTrue("Fixture setup consumed the focus-wait deadline; this is not cancellation evidence",
                SystemClock.uptimeMillis() - handoffStarted < 1_500)
            tap(x, y)
            await("Physical old Care Stop must revoke the opening ticket, before any capture") {
                main { !current.foreground.accepts(ticket) }
            }
            val completedBeforeReenter = main {
                assertFalse(current.careSession.visible.value)
                assertEquals(DotForegroundOperation.Idle, current.foreground.state.value.operation)
                assertNull(adapter.preview.value); assertNull(outline(service))
                assertEquals(0, captureCalls); assertEquals(0, nodeCalls)
                assertEquals("unsent cancellation draft", session.inputText.value)
                oldJob.isCompleted.also { current.handle(DotIntent.Triple) }
            }
            await("Reentered Care must remain visible after the old coroutine has completed") {
                main { oldJob.isCompleted && current.foreground.state.value.mode == DotForegroundMode.Care &&
                    current.careSession.visible.value } && careCard(stopLabel)?.focused == true
            }
            // Cross the original deadline while checking continuously; a late hide must fail.
            while (SystemClock.uptimeMillis() < handoffStarted + 2_250) {
                main {
                    assertTrue(current.careSession.visible.value)
                    assertEquals(DotForegroundMode.Care, current.foreground.state.value.mode)
                    assertNull(adapter.preview.value); assertNull(outline(service))
                    assertEquals(0, captureCalls); assertEquals(0, nodeCalls)
                }
                SystemClock.sleep(16)
            }
            screenshot(evidence, "care-reentered-after-old-job")
            File(evidence, "receipt.json").writeText(JSONObject().put("physicalOldCareStop", true)
                .put("careWindowIdBeforeStop", original.id).put("stopBounds", stop.toShortString())
                .put("screenBoundsAtStop", panelBounds.toShortString()).put("nativeNoFocus", true)
                .put("captureCalls", captureCalls).put("nodeCalls", nodeCalls)
                .put("oldJobCompletedBeforeReenter", completedBeforeReenter)
                .put("oldJobCompleted", oldJob.isCompleted).put("reenteredCareVisible", true)
                .put("previewAbsent", true).put("outlineRemoved", true)
                .put("providerRequests", 0).put("evidence", evidence.absolutePath).toString())
        } finally {
            main {
                owner?.disconnect()
                ChatViewModelStore.setActiveSession(previousSession)
                service.dotInteraction.disconnect(); service.dotInteraction.connect()
            }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            automation.serviceInfo = automation.serviceInfo.apply { flags = savedFlags }
        }
    }
}
