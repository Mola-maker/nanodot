package io.github.nanomuse.dot.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.StreamingDelta
import io.github.nanomuse.dot.DotForegroundOperation
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotForegroundTicket
import io.github.nanomuse.dot.DotInteractionCoordinator
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.chat.DotChatSession
import io.github.nanomuse.dot.chat.DotOwnedAttachment
import io.github.nanomuse.dot.chat.SessionBinding
import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.CaptureOutcome
import io.github.nanomuse.dot.screen.CapturePurpose
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.screen.ScreenSnapshot
import io.github.nanomuse.dot.screen.ScreenTransform
import io.github.nanomuse.dot.selection.RegionSelection
import io.github.nanomuse.dot.selection.SelectionOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled-coordinator native fixture, not an orb-gesture or real-provider journey.
 * Real service/windows/scene tracking and InputDispatcher Stop stay intact; only the sampler
 * is held. Breaks caught: cancelled capture published/leaked; old region applied after host
 * replacement; old portrait region applied after a real display rotation. No Send or sockets.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class DotRegionLifecycleNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val nativeService = DotNativeServiceFixture()
    private val inject by lazy {
        UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
    }
    private lateinit var evidence: File

    private class LocalSession : DotChatSession {
        override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        override val inputText = MutableStateFlow("region lifecycle draft")
        override val isStreaming = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val configured = MutableStateFlow<String?>("inert fixture")
        override val deltas = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
        override val consumedAttachments = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun input(text: String) { inputText.value = text }
        override fun stage(file: File): DotOwnedAttachment? = error("No Send in this fixture")
        override fun remove(id: String) = Unit
        override fun send(text: String) = error("No provider calls")
        override fun cancel() { isStreaming.value = false }
        override fun retry() = error("No provider calls")
    }

    private fun <T> main(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(32)
        }
        fail(message)
    }

    @Before fun connectActualServiceAndHost() {
        nativeService.connect()
        evidence = File(context.cacheDir, "region-lifecycle-${SystemClock.uptimeMillis()}").apply { check(mkdirs()) }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        main { nativeService.service().dotOverlay.setEnabled(true) }
        instrumentation.context.startActivity(Intent(instrumentation.context, DotRegionHostFixture::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        settledFixture()
    }

    @After fun restoreActualService() { nativeService.close() }

    private fun settledFixture() {
        var previous: Any? = null
        var since = SystemClock.uptimeMillis()
        await("Public host scene must settle before a controlled region request") {
            val next = main { nativeService.service().dotScreen.currentSceneKey() }
            if (next != previous) { previous = next; since = SystemClock.uptimeMillis() }
            next?.packageName == instrumentation.context.packageName && SystemClock.uptimeMillis() - since >= 350
        }
    }

    private inner class HeldRegion(val service: MinisAccessibilityService) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delivered: ScreenSnapshot? = null
        var stages = 0
        val session = LocalSession()
        val adapter = DotChatAdapter({ it }, { session }, { stages++; error("Rejected capture must never stage") })
        val origin = main {
            SelectionOrigin(requireNotNull(service.dotScreen.currentSceneKey()), requireNotNull(service.dotScreen.hostViewport()))
        }
        val shape: RegionSelection
        val owner: DotInteractionCoordinator
        lateinit var requestTicket: DotForegroundTicket
        lateinit var requestBinding: SessionBinding

        init {
            val host = origin.viewport
            val side = 60f * context.resources.displayMetrics.density
            assertTrue("Fixture must fit a 60dp public region", host.width > side && host.height > side)
            val left = (host.left + host.right - side) / 2f
            val top = (host.top + host.bottom - side) / 2f
            shape = RegionSelection(listOf(PointPx(left, top), PointPx(left + side, top),
                PointPx(left + side, top + side), PointPx(left, top + side)),
                RectPx(left, top, left + side, top + side), 3600f)
            owner = main {
                DotInteractionCoordinator(service, adapter, screenCapture = { purpose, generation ->
                    check(purpose == CapturePurpose.Selection)
                    entered.complete(Unit)
                    // Model a late callback that still owns its successful bitmap. Production
                    // selection admission, not this fixture, must reject and close the handoff.
                    withContext(NonCancellable) {
                        release.await()
                        val width = host.width.toInt(); val height = host.height.toInt()
                        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val snapshot = ScreenSnapshot("held-public-region", generation, 42, origin.scene,
                            host, 0, RectPx(0f, 0f, 0f, 0f), null, emptyList(), image,
                            ScreenTransform(host, width, height))
                        delivered = snapshot
                        CaptureOutcome.Success(snapshot)
                    }
                }, nodeCapture = { error("A late Success must not start fallback") })
            }
        }

        fun start() {
            main { owner.connect(); owner.selectRegion(shape, origin) }
            await("Region capture must actually enter its held acquisition") { main { entered.isCompleted } }
            await("Real panel and Capturing operation must remain visible") { main {
                owner.foreground.state.value.operation == DotForegroundOperation.Capturing &&
                    service.dotOverlay.panelView?.let { it.isAttachedToWindow && it.isShown && it.alpha > 0f } == true
            } }
            main {
                requestTicket = owner.foreground.ticket()
                requestBinding = requireNotNull(adapter.binding.value)
            }
            trace("entered")
        }

        fun trace(phase: String, stop: Rect? = null) {
            val row = main {
                val overlay = service.dotOverlay
                "uptime=${SystemClock.uptimeMillis()} phase=$phase proof=controlled_coordinator_native_fixture " +
                    "origin=${origin.scene} current=${service.dotScreen.currentSceneKey()} " +
                    "requestTicket=${if (::requestTicket.isInitialized) requestTicket else null} state=${owner.foreground.state.value} " +
                    "requestBinding=${if (::requestBinding.isInitialized) requestBinding else null} binding=${adapter.binding.value} " +
                    "panelIdentity=${overlay.panelView?.let { System.identityHashCode(it) }} panelRect=${overlay.panelView?.let(::actualBounds)} " +
                    "panelHost=${field(overlay, "panelHostWindowId")} pending=${field(overlay, "geometryPending")} " +
                    "panelMotionActive=${(field(overlay, "panelMotion") as? Job)?.isActive} stop=$stop " +
                    "captureError=${adapter.captureError.value} stages=$stages preview=${adapter.preview.value != null} " +
                    "lateBitmapRecycled=${delivered?.image?.isRecycled}\n"
            }
            File(evidence, "lifecycle-trace.txt").appendText(row)
            println("DOT_REGION_LIFECYCLE_TRACE $row")
        }

        fun requireOldRequestRevoked(phase: String, collapsed: Boolean) {
            trace("$phase-before-revocation-check")
            await("The exact captured ticket and binding must be revoked before releasing old pixels") { main {
                !owner.foreground.accepts(requestTicket) && !adapter.accepts(requestBinding) &&
                    owner.foreground.state.value.operation == DotForegroundOperation.Idle &&
                    (!collapsed || (adapter.binding.value == null && service.dotOverlay.panelView == null &&
                        owner.foreground.state.value.mode == DotForegroundMode.Idle))
            } }
            trace("$phase-revoked")
        }

        fun releaseAndRequireClosed(name: String) {
            trace("$name-pre-release")
            main { release.complete(Unit) }
            await("Late region snapshot must be released without publication") { main {
                delivered?.image?.isRecycled == true &&
                    owner.foreground.state.value.operation != DotForegroundOperation.Capturing
            } }
            trace("$name-post-delivery")
            main {
                assertFalse("Late delivery cannot restore the revoked ticket", owner.foreground.accepts(requestTicket))
                assertFalse("Late delivery cannot restore the old binding", adapter.accepts(requestBinding))
                assertNull("Old region must not become an attachment", adapter.preview.value)
                assertEquals("No rejected pixels/text may be staged", 0, stages)
                assertEquals("Rejecting capture preserves the canonical draft", "region lifecycle draft", session.inputText.value)
                assertFalse("A revoked request must not publish a new capture error", adapter.captureError.value)
            }
            val receipt = main {
                "proof=controlled_coordinator_native_fixture\nphase=$name\norigin=${origin.scene}\n" +
                    "current=${service.dotScreen.currentSceneKey()}\nlateBitmapRecycled=${delivered?.image?.isRecycled}\n" +
                    "stages=$stages\npreview=${adapter.preview.value}\n"
            }
            File(evidence, "$name-receipt.txt").writeText(receipt)
            screenshot(name)
            println("DOT_REGION_LIFECYCLE phase=$name proof=controlled_coordinator_native_fixture evidence=${evidence.absolutePath}")
        }

        fun close() {
            try {
                trace("cleanup-before-release")
                main { release.complete(Unit); owner.disconnect() }
                if (entered.isCompleted) await("Fixture cleanup must finish the owned late callback") {
                    main { delivered?.image?.isRecycled == true }
                }
            } finally {
                // Restore the service's production CareOperationGate observer after the injected owner.
                main { service.dotInteraction.disconnect(); service.dotInteraction.connect() }
            }
        }
    }

    private fun screenshot(name: String) {
        val image = requireNotNull(automation.takeScreenshot())
        val file = File(evidence, "$name.png")
        try { file.outputStream().use { assertTrue("Required screenshot encoding", image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
        assertTrue("Required screenshot must have bytes", file.length() > 0)
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
        try { assertTrue(decoded.width > 0 && decoded.height > 0) } finally { decoded.recycle() }
    }

    private fun touch(action: Int, down: Long, point: PointPx) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { assertEquals("InputDispatcher must accept the actual Stop touch", true, inject.invoke(automation, event, true, false)) }
        finally { event.recycle() }
    }

    private fun field(overlay: DotOverlayController, name: String): Any? =
        overlay.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(overlay)

    private fun actualBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun settledStopTarget(held: HeldRegion, panel: View): Rect {
        var previous: Pair<Rect, Rect>? = null
        var stableFrames = 0
        var target: Rect? = null
        await("Actual 48dp Stop and native panel bounds must agree across three settled frames") {
            val frame = AtomicBoolean(false)
            main { panel.postOnAnimation { frame.set(true) } }
            await("The actual panel must render its next animation frame") { frame.get() }
            val candidate = stopTarget(held.service)
            val bounds = main {
                val overlay = held.service.dotOverlay
                assertSame("Stop panel must still belong to this adapter", held.adapter, field(overlay, "panelAdapter"))
                assertSame("Stop status must still belong to this foreground owner", held.owner.foreground.state, field(overlay, "panelForeground"))
                assertTrue("Stop must be sampled while this exact capture remains admitted", held.owner.foreground.accepts(held.requestTicket))
                assertTrue("Stop must retain the exact captured binding", held.adapter.accepts(held.requestBinding))
                val settled = overlay.panelView === panel && panel.isAttachedToWindow && panel.isShown &&
                    panel.width > 0 && panel.height > 0 && (field(overlay, "panelRequestedHeight") as Int) > 0 &&
                    field(overlay, "panelGeometry") != null && field(overlay, "geometryPending") == false &&
                    (field(overlay, "panelMotion") as? Job)?.isActive != true
                if (settled) actualBounds(panel) else null
            }
            val sample = if (candidate != null && bounds != null && bounds.contains(candidate)) bounds to candidate else null
            stableFrames = if (sample != null && sample == previous) stableFrames + 1 else 0
            previous = sample
            target = candidate
            stableFrames >= 3
        }
        return requireNotNull(target)
    }

    private fun stopTarget(service: MinisAccessibilityService): Rect? {
        automation.clearCache()
        val label = service.getString(R.string.nm_dot_chat_stop)
        val minimum = 48f * service.resources.displayMetrics.density
        fun visit(node: AccessibilityNodeInfo): Rect? {
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser && node.isEnabled &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                var action = AccessibilityNodeInfo.obtain(node)
                try {
                    while (!action.isClickable) {
                        val parent = action.parent ?: break
                        action.recycle(); action = parent
                    }
                    val bounds = Rect(); action.getBoundsInScreen(bounds)
                    if (action.isVisibleToUser && action.isEnabled && action.isClickable &&
                        bounds.width() >= minimum && bounds.height() >= minimum) return bounds
                } finally { action.recycle() }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val windows = automation.windows
        try {
            for (window in windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) {
                val root = window.root ?: continue
                try { visit(root)?.let { return it } } finally { root.recycle() }
            }
        } finally { windows.forEach { it.recycle() } }
        return null
    }

    @Test fun physicalStopDuringRegionCaptureClosesLateSuccess() {
        val held = HeldRegion(nativeService.service())
        try {
            held.start()
            val panel = main { requireNotNull(held.service.dotOverlay.panelView) }
            val target = settledStopTarget(held, panel)
            held.trace("stop-pre-down", target)
            assertEquals("Physical touch must use the current settled native Stop", target, stopTarget(held.service))
            val point = target.let { PointPx(it.exactCenterX(), it.exactCenterY()) }
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, down, point); touch(MotionEvent.ACTION_UP, down, point)
            held.trace("stop-post-up", target)
            held.requireOldRequestRevoked("physical-stop", collapsed = false)
            main {
                assertSame("Stop retains the mounted conversation", panel, held.service.dotOverlay.panelView)
                assertNotNull("Stop rebinds the retained conversation", held.adapter.binding.value)
                assertNotEquals("Stop replaces the old binding generation", held.requestBinding, held.adapter.binding.value)
            }
            held.releaseAndRequireClosed("stop-during-capture")
        } finally { held.close() }
    }

    @Test fun hostReplacementDuringRegionCaptureRejectsOldCoordinates() {
        val held = HeldRegion(nativeService.service())
        var hostChanged = false
        try {
            held.start()
            instrumentation.context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hostChanged = true
            await("A different real application host must replace the original region host") { main {
                val scene = held.service.dotScreen.currentSceneKey()
                scene != null && scene != held.origin.scene && scene.packageName != held.origin.scene.packageName
            } }
            held.requireOldRequestRevoked("host-replaced", collapsed = true)
            held.releaseAndRequireClosed("host-replaced")
        } finally {
            try { held.close() } finally {
                if (hostChanged) {
                    automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    instrumentation.context.startActivity(Intent(instrumentation.context, DotRegionHostFixture::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    settledFixture()
                }
            }
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun restoreRotationSetting(key: String, value: String?) {
        require(key in setOf(Settings.System.ACCELEROMETER_ROTATION, Settings.System.USER_ROTATION))
        require(value == null || value in setOf("0", "1", "2", "3"))
        if (value == null) shell("settings delete system $key") else shell("settings put system $key $value")
        assertEquals("Restore exact rotation setting $key", value, Settings.System.getString(context.contentResolver, key))
    }

    @Test fun actualDisplayRotationDuringRegionCaptureRejectsPortraitCoordinates() {
        val held = HeldRegion(nativeService.service())
        val display = held.service.getSystemService(DisplayManager::class.java).getDisplay(held.origin.scene.displayId)
        val originalRotation = requireNotNull(display).rotation
        val settings = listOf(Settings.System.ACCELEROMETER_ROTATION, Settings.System.USER_ROTATION)
            .associateWith { Settings.System.getString(context.contentResolver, it) }
        try {
            held.start()
            assertTrue("UiAutomation accepts a real different display rotation", automation.setRotation((originalRotation + 1) % 4))
            await("Actual native rotation and scene identity must both change before late delivery") { main {
                val current = held.service.dotScreen.currentSceneKey()
                display.rotation != originalRotation && current != null && current != held.origin.scene
            } }
            held.requireOldRequestRevoked("rotated-during-capture", collapsed = true)
            held.releaseAndRequireClosed("rotated-during-capture")
        } finally {
            try { held.close() } finally {
                assertTrue("Restore the original display rotation", automation.setRotation(originalRotation))
                settings.forEach { (key, value) -> restoreRotationSetting(key, value) }
                await("Native display returns to its original rotation") { display.rotation == originalRotation }
                settledFixture()
            }
        }
    }
}
