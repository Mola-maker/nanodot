package io.github.nanomuse.dot.screen

import android.app.UiAutomation
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.gesture.PointPx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Before
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenRepositoryTest {
    companion object {
        private fun overlayConnectedOnMain(): Boolean {
            var connected = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                connected = MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true
            }
            return connected
        }
        @JvmStatic @AfterClass fun releaseFrameworkGrant() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { String(it.readBytes()) }
            instrumentation.runOnMainSync { LegacyProjectionService.stop(instrumentation.targetContext) }
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
            val deadline = SystemClock.uptimeMillis()+10000
            var dump = shell("dumpsys accessibility")
            while ((overlayConnectedOnMain() ||
                dump.contains("io.github.nanodot.app/com.openminis.app.accessibility.MinisAccessibilityService")) && SystemClock.uptimeMillis()<deadline) {
                Thread.sleep(100); dump = shell("dumpsys accessibility")
            }
            java.io.File(instrumentation.targetContext.cacheDir,"t3-teardown-accessibility.txt").writeText(dump)
            assertFalse(dump,dump.contains("io.github.nanodot.app/com.openminis.app.accessibility.MinisAccessibilityService"))
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private lateinit var service: MinisAccessibilityService
    @Before fun bindThroughFramework() {
        shell("input keyevent 224"); shell("input keyevent 82")
        shell("cmd statusbar collapse")
        if (!overlayConnectedOnMain()) {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
            waitUntil { !shell("dumpsys accessibility").contains("Binding services:{{io.github.nanodot.app/") }
            shell("settings put secure enabled_accessibility_services io.github.nanodot.app/com.openminis.app.accessibility.MinisAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
        }
        waitUntil { overlayConnectedOnMain() }
        instrumentation.runOnMainSync {
            service = requireNotNull(MinisAccessibilityService.getInstance())
            LegacyProjectionService.stop(instrumentation.targetContext)
        }
        waitUntil { LegacyProjectionService.capture == null }
        host()
    }
    @Test fun externalHostOriginPasswordAndSnapshotOwnership() = runBlocking<Unit> {
        val snapshot = capture()
        try {
            assertEquals("io.github.nanodot.app.test", snapshot.scene.packageName)
            assertTrue(snapshot.nodes.any { it.text == "HOST_ORIGIN_T3_91E7" })
            val password = snapshot.nodes.first { it.password }
            assertNull(password.text); assertNull(password.description)
            assertFalse(snapshot.nodes.any { it.text?.contains("T3_PRIVATE_PASSWORD") == true })
            if (Build.VERSION.SDK_INT < 30) assertNull(snapshot.image)
            else verifyMarker(snapshot)
            saveSnapshot("origin",snapshot)
            println("T3_ORIGIN api=${Build.VERSION.SDK_INT} scene=${snapshot.scene} bounds=${snapshot.bounds} image=${snapshot.image?.width}x${snapshot.image?.height}")
        } finally { snapshot.close(); snapshot.close() }
        assertTrue(snapshot.image?.isRecycled ?: true)
    }
    @Test fun actualHostImeChangesSceneAndOwnedOrbDoesNot() = runBlocking<Unit> {
        instrumentation.runOnMainSync { service.dotOverlay.setEnabled(false) }
        Thread.sleep(300)
        val before = capture(); val version = before.scene.version; before.close()
        instrumentation.runOnMainSync { service.dotOverlay.setEnabled(true) }
        Thread.sleep(500)
        val orb = capture(); assertEquals(version, orb.scene.version); orb.close()
        val edit = automation.rootInActiveWindow.findAccessibilityNodeInfosByText("HOST_IME_T3").first()
        val editBounds = android.graphics.Rect()
        try { edit.getBoundsInScreen(editBounds) } finally { edit.recycle() }
        shell("input tap ${editBounds.centerX()} ${editBounds.centerY()}")
        waitUntil { service.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        val ime = capture()
        try {
            assertNotNull(ime.imeBounds); assertTrue(ime.scene.version > version)
            println("T3_IME beforeVersion=$version after=${ime.scene} bounds=${ime.bounds} insets=${ime.insets} ime=${ime.imeBounds}")
            java.io.File(instrumentation.targetContext.cacheDir,"t3-ime-native.txt").writeText(shell("dumpsys window windows"))
            ime.image?.let { image -> java.io.File(instrumentation.targetContext.cacheDir,"t3-ime-image.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            } }
        } finally { ime.close() }
        shell("input keyevent 4")
    }
    @Test fun legacyExplicitGrantPixelsSecureAndForegroundStop() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT >= 30) return@runBlocking
        grantLegacyProjection()
        host()
        val pixels = capture()
        try { verifyMarker(pixels); println("T3_PROJECTION origin=${pixels.scene} pixels=${pixels.image!!.width}x${pixels.image.height}") }
        finally { pixels.close() }
        shell("settings put system accelerometer_rotation 0")
        try {
            for (rotation in 0..3) {
                shell("settings put system user_rotation $rotation"); Thread.sleep(800)
                val rotated = capture()
                try {
                    assertEquals(rotation,rotated.rotation); verifyMarker(rotated)
                    println("T3_PROJECTION_ROTATION r=$rotation scene=${rotated.scene} bounds=${rotated.bounds} insets=${rotated.insets} root=${rotated.nodes.first().bounds}")
                    java.io.File(instrumentation.targetContext.cacheDir,"t3-projection-rotation-$rotation-native.txt").writeText(shell("dumpsys window windows"))
                } finally { rotated.close() }
            }
        } finally { shell("settings put system user_rotation 0"); Thread.sleep(800) }
        stopLegacyFromNotification()
        host()
        val fallback = capture(); assertNull(fallback.image); fallback.close()
    }
    private fun grantLegacyProjection() {
        assertNull(LegacyProjectionService.capture)
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startActivity(android.content.Intent(instrumentation.targetContext,
                LegacyProjectionPermissionActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val allow = instrumentation.targetContext.getString(com.openminis.app.R.string.nm_dot_capture_allow)
        waitUntil { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(allow)?.isNotEmpty() == true }
        clickText(allow)
        waitUntil { automation.rootInActiveWindow?.packageName?.toString() == "com.android.systemui" }
        clickText("Start now")
        waitUntil { LegacyProjectionService.capture != null }
    }
    @Test fun legacyProtectedWindowEvidenceAndForegroundStop() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT >= 30) return@runBlocking
        grantLegacyProjection()
        shell("settings put system accelerometer_rotation 0")
        try {
            for (rotation in 0..1) {
                shell("settings put system user_rotation $rotation"); Thread.sleep(800)
                host()
                capture().use { verifyMarker(it) }
                host(true)
                val publicRoot = requireNotNull(service.rootInActiveWindow)
                val content = publicRoot.findAccessibilityNodeInfosByViewId("android:id/content")
                try {
                    java.io.File(instrumentation.targetContext.cacheDir,"t3-secure-$rotation-content-query.txt").writeText(content.joinToString("\n") { node ->
                        val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                        "viewId=${node.viewIdResourceName} bounds=$bounds"
                    })
                } finally { content.forEach { it.recycle() }; publicRoot.recycle() }
                val nodes = (service.dotScreen.captureNodes(12) as CaptureOutcome.Success).snapshot
                nodes.use { java.io.File(instrumentation.targetContext.cacheDir,"t3-secure-$rotation-nodes.txt").writeText(it.nodes.joinToString("\n")) }
                val secure = service.dotScreen.capture(CapturePurpose.Care, 12)
                java.io.File(instrumentation.targetContext.cacheDir,"t3-secure-$rotation-native.txt").writeText(shell("dumpsys window windows"))
                if (secure is CaptureOutcome.Success) secure.snapshot.use { snapshot ->
                    java.io.File(instrumentation.targetContext.cacheDir,"t3-secure-$rotation-image.png").outputStream().use {
                        snapshot.image!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    }
                }
                println("T3_PROJECTION_SECURE r=$rotation $secure")
                assertEquals(CaptureOutcome.Unavailable("blank_host_unavailable"), secure)
            }
        } finally { shell("settings put system user_rotation 0"); Thread.sleep(800) }
        stopLegacyFromNotification()
        host()
        val fallback = capture(); assertNull(fallback.image); fallback.close()
    }
    private fun stopLegacyFromNotification() {
        shell("cmd statusbar expand-notifications")
        waitUntil { automation.rootInActiveWindow?.packageName?.toString() == "com.android.systemui" }
        val title = instrumentation.targetContext.getString(com.openminis.app.R.string.nm_dot_capture_title)
        val root = requireNotNull(automation.rootInActiveWindow)
        val nodes = NodeReader.read(root,"notification")
        val titlePath = nodes.first { it.text == title }.ref.split('.')
        val expand = nodes.filter { it.viewId == "android:id/expand_button" && it.clickable }.maxBy { candidate ->
            titlePath.zip(candidate.ref.split('.')).takeWhile { it.first == it.second }.size
        }
        val bounds = expand.bounds
        println("T3_NOTIFICATION_EXPAND $bounds ref=${expand.ref}")
        shell("input tap ${((bounds.left+bounds.right)/2).toInt()} ${((bounds.top+bounds.bottom)/2).toInt()}")
        Thread.sleep(300)
        val notificationRoot = requireNotNull(automation.rootInActiveWindow)
        val expandedNodes = NodeReader.read(notificationRoot,"notification")
        java.io.File(instrumentation.targetContext.cacheDir,"t3-stop-notification-nodes.txt").writeText(expandedNodes.joinToString("\n"))
        val stop = instrumentation.targetContext.getString(com.openminis.app.R.string.nm_dot_capture_stop)
        val action = expandedNodes.first { it.text == stop && it.clickable && it.enabled }
        val actionBounds = action.bounds
        println("T3_NOTIFICATION_STOP $actionBounds ref=${action.ref}")
        shell("input tap ${((actionBounds.left+actionBounds.right)/2).toInt()} ${((actionBounds.top+actionBounds.bottom)/2).toInt()}")
        waitUntil { LegacyProjectionService.capture == null }
        shell("cmd statusbar collapse")
    }
    private fun clickText(text: String) {
        val root = requireNotNull(automation.rootInActiveWindow)
        val matches = root.findAccessibilityNodeInfosByText(text)
        try {
            val node = matches.firstOrNull { it.isClickable && it.isEnabled } ?: matches.first()
            if (!node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                val parent = node.parent
                try { assertTrue(parent!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) }
                finally { parent?.recycle() }
            }
        } finally { matches.forEach { it.recycle() }; root.recycle() }
    }
    @Test fun cancelledRequestCannotSupplyLaterHost() = runBlocking<Unit> {
        val request = launch { service.dotScreen.capture(CapturePurpose.Care, 81) }
        yield(); request.cancel(); request.join()
        host(marker = "HOST_NEXT_T3_4CB2")
        Thread.sleep(400)
        val next = service.dotScreen.capture(CapturePurpose.Care, 82) as CaptureOutcome.Success
        try {
            assertEquals(82L,next.snapshot.generation)
            assertTrue(next.snapshot.nodes.any { it.text == "HOST_NEXT_T3_4CB2" })
            assertFalse(next.snapshot.nodes.any { it.text == "HOST_ORIGIN_T3_91E7" })
            if (next.snapshot.image != null) verifyMarker(next.snapshot,"HOST_NEXT_T3_4CB2",Color.GREEN)
            saveSnapshot("cancel-next",next.snapshot)
        } finally { next.snapshot.close() }
    }
    @Test fun ourMainPackageActivityRemainsAValidHost() = runBlocking<Unit> {
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startActivity(android.content.Intent().setClassName(instrumentation.targetContext,
                "io.github.nanomuse.dot.ui.DotTokenGalleryFixtureActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        waitUntil { activePackage() == "io.github.nanodot.app" }
        val snapshot = capture()
        try { assertEquals("io.github.nanodot.app",snapshot.scene.packageName); assertTrue(snapshot.nodes.isNotEmpty()) }
        finally { snapshot.close() }
    }
    @Test fun actualRotationsUseCurrentHostCoordinates() = runBlocking<Unit> {
        shell("settings put system accelerometer_rotation 0")
        try {
            for (rotation in 0..3) {
                shell("settings put system user_rotation $rotation")
                Thread.sleep(800)
                val snapshot = capture()
                try {
                    assertEquals(rotation,snapshot.rotation)
                    assertTrue(snapshot.nodes.any { it.text == "HOST_ORIGIN_T3_91E7" })
                    if (snapshot.image != null) verifyMarker(snapshot)
                    saveSnapshot("rotation-$rotation",snapshot)
                    println("T3_ROTATION r=$rotation bounds=${snapshot.bounds} insets=${snapshot.insets} root=${snapshot.nodes.first().bounds} image=${snapshot.image?.width}x${snapshot.image?.height}")
                    java.io.File(instrumentation.targetContext.cacheDir,"t3-rotation-$rotation-native.txt").writeText(shell("dumpsys window windows"))
                } finally { snapshot.close() }
            }
        } finally { shell("settings put system user_rotation 0"); Thread.sleep(800) }
    }
    @Test fun differentDisplaySizeUsesFreshCoordinates() = runBlocking<Unit> {
        shell("wm size 720x1280")
        Thread.sleep(800)
        try {
            val snapshot = capture()
            try {
                assertTrue(snapshot.bounds.right >= 720f || snapshot.bounds.bottom >= 1280f)
                if (snapshot.image != null) verifyMarker(snapshot)
                saveSnapshot("resize",snapshot)
                println("T3_DISPLAY_SIZE ${snapshot.bounds}")
            } finally { snapshot.close() }
        } finally { shell("wm size reset"); Thread.sleep(800) }
    }
    @Test fun explicitNodesFallbackReReadsHostAfterImageFailure() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT >= 30) {
            host(true)
            assertTrue(service.dotScreen.capture(CapturePurpose.Care, 91) is CaptureOutcome.Unavailable)
        }
        host(marker = "HOST_FALLBACK_T3_64DE")
        val fallback = service.dotScreen.captureNodes(92) as CaptureOutcome.Success
        try {
            assertNull(fallback.snapshot.image)
            assertEquals(92L,fallback.snapshot.generation)
            assertTrue(fallback.snapshot.nodes.any { it.text == "HOST_FALLBACK_T3_64DE" })
            assertFalse(fallback.snapshot.nodes.any { it.text == "HOST_ORIGIN_T3_91E7" })
        } finally { fallback.snapshot.close() }
    }
    @Test fun displayCaptureHidesAndRestoresOwnedOrb() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT < 30) return@runBlocking
        instrumentation.runOnMainSync { service.dotOverlay.setEnabled(true) }
        waitUntil { service.windows.any(service.dotOverlay::ownsWindow) }
        val orbBounds = android.graphics.Rect()
        service.windows.first(service.dotOverlay::ownsWindow).getBoundsInScreen(orbBounds)
        val center = PointPx(orbBounds.exactCenterX(),orbBounds.exactCenterY())
        val host = capture()
        Thread.sleep(400)
        val display = (service.dotScreen.capture(CapturePurpose.Chat,31) as CaptureOutcome.Success).snapshot
        try {
            val a = host.transform.screenToImage(center)
            val b = display.transform.screenToImage(center)
            assertEquals(host.image!!.getPixel(a.x.toInt(),a.y.toInt()), display.image!!.getPixel(b.x.toInt(),b.y.toInt()))
            assertEquals(0f,display.bounds.left,0f); assertEquals(0f,display.bounds.top,0f)
            instrumentation.runOnMainSync { assertEquals(1f,service.dotOverlay.orbView!!.alpha,0f) }
            saveSnapshot("care-orb",host); saveSnapshot("chat-display",display)
            println("T3_DISPLAY_ORB_RESTORED center=$center host=${host.bounds} display=${display.bounds}")
        } finally { host.close(); display.close() }
    }
    @Test fun actualMultiwindowHostRemainsOriginWhenSupported() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue("Native Recents Split fixture requires modern VM",Build.VERSION.SDK_INT >= 34)
        try {
            shell("wm size 1200x800"); shell("wm density 160")
            shell("am start -W -a android.settings.SETTINGS")
            host()
            shell("input keyevent 187"); Thread.sleep(700)
            fun uiNodes(label: String): List<NodeDto> {
                val root = requireNotNull(automation.rootInActiveWindow)
                val nodes = NodeReader.read(root,"split-$label")
                java.io.File(instrumentation.targetContext.cacheDir,"t3-split-$label-ui.txt").writeText(nodes.joinToString("\n"))
                return nodes
            }
            fun tap(node: NodeDto) {
                val b=node.bounds
                shell("input tap ${((b.left+b.right)/2).toInt()} ${((b.top+b.bottom)/2).toInt()}")
                Thread.sleep(600)
            }
            tap(uiNodes("recents").first { it.viewId == "com.google.android.apps.nexuslauncher:id/action_split" && it.clickable })
            waitUntil { uiNodes("choose").any { it.viewId == "com.google.android.apps.nexuslauncher:id/split_instructions_text" } }
            Thread.sleep(500)
            tap(uiNodes("choose").first { it.viewId == "com.google.android.apps.nexuslauncher:id/task" && it.description == "Settings" })
            waitUntil {
                val current=service.windows
                try { current.any { window ->
                    val root=window.root; val bounds=android.graphics.Rect(); window.getBoundsInScreen(bounds)
                    try { root?.packageName?.toString() == "io.github.nanodot.app.test" && bounds.width()<1200 }
                    finally { root?.recycle() }
                } } finally { current.forEach { it.recycle() } }
            }
            val windows = service.windows
            try {
                val selected = windows.first { window ->
                    val root=window.root
                    try { root?.packageName?.toString() == "io.github.nanodot.app.test" } finally { root?.recycle() }
                }
                val bounds=android.graphics.Rect(); selected.getBoundsInScreen(bounds)
                println("T3_MULTIWINDOW_AX_WINDOW id=${selected.id} bounds=$bounds")
                assertTrue("Actual pane must occupy part of display: $bounds",bounds.width()<1200)
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            } finally { windows.forEach { it.recycle() } }
            waitUntil {
                val current=service.windows
                try { current.any { window ->
                    val root=window.root
                    try { window.isActive && root?.packageName?.toString() == "io.github.nanodot.app.test" }
                    finally { root?.recycle() }
                } } finally { current.forEach { it.recycle() } }
            }
            val dump=shell("dumpsys window windows")
            assertTrue(dump,dump.contains("mWindowingMode=multi-window"))
            java.io.File(instrumentation.targetContext.cacheDir,"t3-multiwindow-before-native.txt").writeText(dump)
            val snapshot=capture()
            try {
                assertEquals("io.github.nanodot.app.test",snapshot.scene.packageName)
                assertTrue(snapshot.bounds.width<1200)
                assertTrue("Cropped host has nonzero origin",snapshot.bounds.left>0)
                assertTrue(snapshot.nodes.any { it.text == "HOST_ORIGIN_T3_91E7" })
                verifyMarker(snapshot)
                for (point in listOf(PointPx(snapshot.bounds.left,snapshot.bounds.top),PointPx(snapshot.bounds.right,snapshot.bounds.top),
                    PointPx(snapshot.bounds.left,snapshot.bounds.bottom),PointPx(snapshot.bounds.right,snapshot.bounds.bottom))) {
                    val inverse=snapshot.transform.imageToScreen(snapshot.transform.screenToImage(point))
                    assertEquals(point.x,inverse.x,1f); assertEquals(point.y,inverse.y,1f)
                }
                saveSnapshot("multiwindow",snapshot)
                val edit=snapshot.nodes.first { it.description == "HOST_IME_T3" }
                val center=PointPx((edit.bounds.left+edit.bounds.right)/2,(edit.bounds.top+edit.bounds.bottom)/2)
                val touch=snapshot.transform.imageToScreen(snapshot.transform.screenToImage(center))
                shell("input tap ${touch.x.toInt()} ${touch.y.toInt()}")
                waitUntil { val current=service.windows
                    try { current.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                    finally { current.forEach { it.recycle() } } }
                val touched=service.dotScreen.captureNodes(118)
                assertTrue(touched is CaptureOutcome.Success)
                (touched as CaptureOutcome.Success).snapshot.use {
                    assertTrue(it.nodes.any { node -> node.description == "HOST_IME_T3" && node.focused })
                    assertTrue(it.scene.version>snapshot.scene.version)
                    saveSnapshot("multiwindow-touch",it)
                    println("T3_MULTIWINDOW_TOUCH touch=$touch scene=${it.scene} ime=${it.imeBounds}")
                }
                shell("input keyevent 4")
                println("T3_MULTIWINDOW scene=${snapshot.scene} bounds=${snapshot.bounds} insets=${snapshot.insets} root=${snapshot.nodes.first().bounds} image=${snapshot.image!!.width}x${snapshot.image.height}")
            } finally { snapshot.close() }
        } finally {
            shell("am force-stop io.github.nanodot.app.test")
            shell("input keyevent 3")
            shell("wm size reset"); shell("wm density reset")
            Thread.sleep(500)
        }
    }
    @Test fun modernRateLimitHasDistinctCodeAndFreshNodeFallback() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT < 30) return@runBlocking
        capture().close()
        val limited = service.dotScreen.capture(CapturePurpose.Care, 61)
        assertEquals(CaptureOutcome.Unavailable("rate_limited"),limited)
        val fallback = service.dotScreen.captureNodes(62) as CaptureOutcome.Success
        try { assertNull(fallback.snapshot.image); assertEquals(62L,fallback.snapshot.generation) }
        finally { fallback.snapshot.close() }
        println("T3_RATE_LIMIT $limited")
    }
    @Test fun actualBitmapAndHardwareBufferCloseOnceAfterCancelledAdapter() = runBlocking<Unit> {
        class Pixels : AutoCloseable {
            val bitmap = android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
            val buffer = android.hardware.HardwareBuffer.create(8,8,android.hardware.HardwareBuffer.RGBA_8888,1,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            var closes = 0
            override fun close() { closes++; buffer.close(); bitmap.recycle() }
        }
        var callback: ((Pixels) -> Unit)? = null
        val waiting = launch { awaitOwnedResource<Pixels> { callback = it }.close() }
        yield(); waiting.cancel(); waiting.join()
        val late = Pixels(); callback!!(late); callback!!(late)
        assertEquals(1,late.closes); assertTrue(late.bitmap.isRecycled)
        try { late.buffer.width; fail("hardware buffer remained open") } catch (_: IllegalStateException) { }
        println("T3_LATE_NATIVE_RESOURCE closes=${late.closes} bitmapRecycled=${late.bitmap.isRecycled}")
    }
    @Test fun sleepingDeviceRefused() = runBlocking<Unit> {
        shell("input keyevent 223"); Thread.sleep(400)
        try { assertEquals(CaptureOutcome.Unavailable("locked"), service.dotScreen.capture(CapturePurpose.Care, 10)) }
        finally { shell("input keyevent 224"); shell("input keyevent 82") }
    }
    @Test fun modernProtectedHostRefusedWithoutOldImage() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT < 30) return@runBlocking
        capture().close(); host(true)
        val result = service.dotScreen.capture(CapturePurpose.Care, 11)
        java.io.File(instrumentation.targetContext.cacheDir,"t3-modern-secure-native.txt").writeText(shell("dumpsys window windows"))
        if (Build.VERSION.SDK_INT >= 34) assertEquals(CaptureOutcome.Unavailable("protected"),result)
        else assertEquals(CaptureOutcome.Unavailable("blank_host_unavailable"),result)
        println("T3_PROTECTED $result")
    }
    private fun verifyMarker(snapshot: ScreenSnapshot, marker: String = "HOST_ORIGIN_T3_91E7", color: Int = Color.MAGENTA) {
        val node = snapshot.nodes.first { it.text == marker }
        val point = snapshot.transform.screenToImage(PointPx(node.bounds.right - 8f, node.bounds.bottom - 8f))
        assertEquals(color, snapshot.image!!.getPixel(point.x.toInt(), point.y.toInt()))
    }
    private fun saveSnapshot(label: String, snapshot: ScreenSnapshot) {
        val cache = instrumentation.targetContext.cacheDir
        java.io.File(cache,"t3-$label-nodes.txt").writeText(snapshot.nodes.joinToString("\n"))
        java.io.File(cache,"t3-$label-native.txt").writeText(shell("dumpsys window windows"))
        snapshot.image?.let { image -> java.io.File(cache,"t3-$label-image.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        } }
    }
    private suspend fun capture(): ScreenSnapshot {
        Thread.sleep(400)
        val outcome = service.dotScreen.capture(CapturePurpose.Care, 1)
        assertTrue("fresh capture unavailable: $outcome",outcome is CaptureOutcome.Success)
        return (outcome as CaptureOutcome.Success).snapshot
    }
    private fun host(secure: Boolean = false, marker: String = "HOST_ORIGIN_T3_91E7") {
        shell("am start -W -n io.github.nanodot.app.test/io.github.nanomuse.dot.screen.ScreenHostFixture --ez secure $secure --ez green ${marker == "HOST_NEXT_T3_4CB2"} --es marker $marker -f 0x10008000")
        waitUntil { activePackage() == "io.github.nanodot.app.test" }
        Thread.sleep(500)
    }
    private fun activePackage(): String? {
        val root = service.rootInActiveWindow ?: return null
        return try { root.packageName?.toString() } finally { root.recycle() }
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis()+10000
        while (!condition() && SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
        assertTrue("framework condition did not arrive", condition())
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { String(it.readBytes()) }
}
