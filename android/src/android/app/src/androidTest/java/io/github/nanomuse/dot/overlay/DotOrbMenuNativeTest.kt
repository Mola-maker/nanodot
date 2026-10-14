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
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotForegroundOperation
import io.github.nanomuse.dot.DotPreferences
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.gesture.DotIntent
import io.github.nanomuse.dot.voice.DotVoiceController
import io.github.nanomuse.dot.voice.VoiceSelection
import io.github.nanomuse.dot.voice.VoiceStartGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.roundToInt

/** Real native touch/windows and canonical coordinator. Permission launch is controlled only in the voice case. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class DotOrbMenuNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val inject by lazy {
        // Same framework TestApi used by the existing native drag fixture. No policy override.
        UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
    }
    private data class Hit(val bounds: Rect, val window: Rect, val windowId: Int)
    private fun <T> main(block: () -> T): T {
        var value: Any? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        val evidence = main {
            val service = MinisAccessibilityService.getInstance()
            val overlay = service?.dotOverlay
            val view = overlay?.orbMenuView
            val params = view?.layoutParams as? WindowManager.LayoutParams
            val location = IntArray(2).also { view?.getLocationOnScreen(it) }
            "stage=$message connected=${overlay?.isConnected} menuAttached=${view?.isAttachedToWindow} " +
                "menu=${params?.x},${params?.y},${params?.width},${params?.height} " +
                "planned=${overlay?.orbMenuBounds} actual=${location.toList()},${view?.width},${view?.height} " +
                "hostId=${service?.dotScreen?.hostWindowId()} host=${service?.dotScreen?.hostViewport()} " +
                "mode=${service?.dotInteraction?.foreground?.state?.value?.mode} " +
                "operation=${service?.dotInteraction?.foreground?.state?.value?.operation}"
        }
        java.io.File(context.cacheDir, "orb-menu-native-timeout.txt").writeText(evidence)
        println("DOT_ORB_MENU_TIMEOUT $evidence")
        runCatching { retainMenuTimeout(message) }.onFailure {
            println("DOT_ORB_MENU_DIAGNOSTIC_FAILED ${it.javaClass.simpleName}: ${it.message}")
        }
        fail(message)
    }
    /** Failure-only diagnostics for the actual menu window; never traverse other apps' trees. */
    private fun retainMenuTimeout(message: String) {
        data class Menu(val id: Int?, val bounds: Rect)
        val menu=main {
            val view=MinisAccessibilityService.getInstance()?.dotOverlay?.orbMenuView ?: return@main null
            val location=IntArray(2); view.getLocationOnScreen(location)
            Menu(windowId(view),Rect(location[0],location[1],location[0]+view.width,location[1]+view.height))
        } ?: return
        val directory=java.io.File(context.cacheDir,"orb-menu-native-timeout-${SystemClock.uptimeMillis()}").apply { mkdirs() }
        val labels=listOf(R.string.nm_dot_open_conversation,R.string.nm_dot_view_screen,
            R.string.nm_dot_start_care,R.string.nm_dot_voice_start).map(context::getString)
        fun quoted(value: CharSequence?): String = value?.toString()?.replace("\\","\\\\")
            ?.replace("\n","\\n")?.replace("\r","\\r")?.replace("\t","\\t")?.let { "\"$it\"" } ?: "null"
        fun dump(name: String) {
            val out=StringBuilder("stage=$message expectedMenuId=${menu.id} actualMenuBounds=${menu.bounds}\n")
            val windows=automation.windows
            try {
                val selected=windows.filter { window ->
                    val bounds=Rect(); window.getBoundsInScreen(bounds)
                    if (menu.id!=null) window.id==menu.id else
                        window.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && bounds==menu.bounds &&
                            window.title?.toString()=="Nanodot Dot actions"
                }
                out.append("matchingMenuWindows=${selected.size}\n")
                selected.forEach { window ->
                    val bounds=Rect(); window.getBoundsInScreen(bounds)
                    out.append("WINDOW id=${window.id} type=${window.type} title=${quoted(window.title)} bounds=$bounds " +
                        "active=${window.isActive} focused=${window.isFocused} layer=${window.layer}\n")
                    fun visit(node: AccessibilityNodeInfo, depth: Int) {
                        val rect=Rect(); node.getBoundsInScreen(rect)
                        val actions=node.actionList.joinToString { "${it.id}:${quoted(it.label)}" }
                        out.append("  ".repeat(depth)).append("NODE windowId=${node.windowId} class=${quoted(node.className)} " +
                            "id=${quoted(node.viewIdResourceName)} text=${quoted(node.text)} description=${quoted(node.contentDescription)} " +
                            "visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable} " +
                            "longClickable=${node.isLongClickable} focusable=${node.isFocusable} focused=${node.isFocused} " +
                            "accessibilityFocused=${node.isAccessibilityFocused} selected=${node.isSelected} " +
                            "bounds=$rect children=${node.childCount} actions=[$actions]\n")
                        for (i in 0 until node.childCount) {
                            val child=node.getChild(i) ?: continue
                            try { visit(child,depth+1) } finally { child.recycle() }
                        }
                    }
                    val root=window.root
                    if (root==null) out.append("ROOT null\n")
                    else try { visit(root,0) } finally { root.recycle() }
                }
            } finally { windows.forEach { it.recycle() } }
            // Same strict production-facing locator; loose lookup is diagnostic only.
            if (menu.id!=null) labels.forEach { label ->
                out.append("HIT label=${quoted(label)} strict=${nativeHit(label,menu.id,true)} " +
                    "labelOnly=${nativeHit(label,menu.id,false)}\n")
            }
            java.io.File(directory,"$name.txt").writeText(out.toString())
            println("DOT_ORB_MENU_AX_DUMP phase=$name path=${java.io.File(directory,"$name.txt")}")
        }
        dump("cached-tree")
        if (android.os.Build.VERSION.SDK_INT>=33) {
            val cleared=automation.clearCache()
            println("DOT_ORB_MENU_AX_CACHE cleared=$cleared windowId=${menu.id}")
            dump("fresh-tree")
        }
        automation.takeScreenshot()?.let { screen ->
            try {
                val crop=Rect(menu.bounds)
                if (crop.intersect(0,0,screen.width,screen.height) && !crop.isEmpty) {
                    val pixels=android.graphics.Bitmap.createBitmap(screen,crop.left,crop.top,crop.width(),crop.height())
                    try { java.io.File(directory,"menu-native.png").outputStream().use {
                        pixels.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    } } finally { if (pixels !== screen) pixels.recycle() }
                    java.io.File(directory,"screenshot-bounds.txt").writeText("actualMenu=${menu.bounds} capturedMenu=$crop display=${screen.width}x${screen.height}\n")
                }
            } finally { screen.recycle() }
        }
        println("DOT_ORB_MENU_DIAGNOSTICS directory=$directory")
    }
    private fun setting(name: String) = Settings.Secure.getString(context.contentResolver, name)
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun restore(name: String, value: String?) {
        require(name in setOf("enabled_accessibility_services", "accessibility_enabled"))
        require(value == null || if (name == "accessibility_enabled") value in setOf("0", "1")
            else value.split(':').all { it.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) })
        shell(if (value == null) "settings delete secure $name" else "settings put secure $name $value")
    }
    private fun withHost(block: (MinisAccessibilityService) -> Unit) {
        val original = listOf("enabled_accessibility_services", "accessibility_enabled").associateWith(::setting)
        original.forEach { (name, value) ->
            require(value == null || if (name == "accessibility_enabled") value in setOf("0", "1")
                else value.split(':').all { it.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) })
        }
        val own = ComponentName(context.packageName, MinisAccessibilityService::class.java.name).flattenToString()
        val services = original.getValue("enabled_accessibility_services").orEmpty().split(':').filter(String::isNotEmpty)
        assumeTrue("Grant the real service on the isolated guest first", own in services)
        assumeTrue("Unlock the isolated guest first", !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            context.getSystemService(PowerManager::class.java).isInteractive)
        val oldEnabled = DotPreferences(context).showOnAccessibilityConnected()
        val oldFlags = automation.serviceInfo.flags
        var connected: MinisAccessibilityService? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            if (!main { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true }) {
                restore("enabled_accessibility_services", services.filter { it != own }.joinToString(":").ifEmpty { null })
                await("Old service must unbind") { main { MinisAccessibilityService.getInstance() == null } }
                restore("enabled_accessibility_services", services.joinToString(":"))
                restore("accessibility_enabled", "1")
            }
            await("Real service must connect") { main { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true } }
            val service = requireNotNull(MinisAccessibilityService.getInstance()); connected = service
            main {
                assertNull("Use an isolated scene without a mounted conversation", service.dotOverlay.panelView)
                assertFalse(service.dotInteraction.careSession.visible.value)
                service.dotOverlay.setEnabled(true)
            }
            instrumentation.context.startActivity(Intent().setComponent(ComponentName(instrumentation.context,
                DotOrbMenuHostFixture::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var previous: Hit? = null
            var since = SystemClock.uptimeMillis()
            await("Fixture root/window and host must settle before native input") {
                val hit = nativeHit("ORB_HOST_TARGET")
                val ready = hit != null && main {
                    val display = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                    service.dotScreen.hostWindowId() == hit.windowId && hit.window == display
                }
                val now = SystemClock.uptimeMillis()
                if (!ready || hit != previous) { previous = hit; since = now }
                ready && now - since >= 300
            }
            block(service)
        } finally {
            try { connected?.let { service -> main {
                service.dotInteraction.close()
                service.dotOverlay.setVoicePermissionWindow(false)
                service.dotOverlay.refreshLockState()
                if (!service.dotOverlay.isConnected) service.dotOverlay.connect()
                service.dotOverlay.setEnabled(oldEnabled)
            } } } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
                original.forEach { (name, value) -> restore(name, value) }
                original.forEach { (name, value) -> assertEquals("Restore $name", value, setting(name)) }
            }
        }
    }
    private fun nativeHit(label: String, windowId: Int? = null, clickable: Boolean = false): Hit? {
        fun visit(node: AccessibilityNodeInfo): Rect? {
            // Menu labels must belong to the real actionable Button, not an unclickable
            // description child beside an unlabeled clickable parent (observed in r8).
            val buttonAction = node.isClickable && node.className?.toString() == "android.widget.Button" &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (node.isVisibleToUser && node.isEnabled && (!clickable || buttonAction) &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) return bounds
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val windows = automation.windows
        try { windows.forEach { window ->
            if (windowId != null && window.id != windowId) return@forEach
            val root = window.root ?: return@forEach
            try { visit(root)?.let {
                val bounds = Rect(); window.getBoundsInScreen(bounds)
                if (bounds.contains(it)) return Hit(it, bounds, window.id)
            } } finally { root.recycle() }
        } } finally { windows.forEach { it.recycle() } }
        return null
    }
    private fun windowId(view: android.view.View?): Int? = view?.createAccessibilityNodeInfo()?.let {
        try { it.windowId.takeIf { id -> id >= 0 } } finally { it.recycle() }
    }
    private fun settledHit(label: String, menu: MinisAccessibilityService? = null, ownWindowId: Int? = null): Hit {
        var previous: Hit? = null
        var since = SystemClock.uptimeMillis()
        var result: Hit? = null
        await("Native action and its owning window must settle: $label") {
            val id = menu?.let { main { windowId(it.dotOverlay.orbMenuView) } } ?: ownWindowId
            val current = if (menu != null && id == null) null else nativeHit(label, id, clickable = menu != null)
            val now = SystemClock.uptimeMillis()
            if (current == null || previous != current) { previous = current; since = now }
            if (current != null && now - since >= 240) result = current
            result != null
        }
        return requireNotNull(result)
    }
    private fun touch(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertEquals("InputDispatcher must accept native touch", true, inject.invoke(automation, event, true, false)) }
        finally { event.recycle() }
    }
    private fun tap(hit: Hit, x: Float = hit.bounds.exactCenterX(), y: Float = hit.bounds.exactCenterY()) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        touch(MotionEvent.ACTION_UP, down, x, y)
    }
    private fun back() {
        val now = SystemClock.uptimeMillis()
        assertEquals(true, inject.invoke(automation, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true, false))
        assertEquals(true, inject.invoke(automation, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true, false))
    }
    private fun hold(service: MinisAccessibilityService) {
        val care = main { service.dotInteraction.careSession.visible.value }
        var orbId: Int? = null
        await("Orb must attach before its geometry is sampled") {
            orbId = main { windowId(service.dotOverlay.orbView) }
            orbId != null
        }
        val orbHit = settledHit(service.getString(if (care) R.string.nm_care_awaiting else R.string.nm_dot_orb_description),
            ownWindowId = requireNotNull(orbId))
        val before = main { service.dotInteraction.foreground.state.value }
        val orb = main {
            val view = requireNotNull(service.dotOverlay.orbView)
            assertEquals(windowId(view), orbHit.windowId)
            val position = IntArray(2); view.getLocationOnScreen(position)
            Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
        }
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, orb.exactCenterX(), orb.exactCenterY())
        try {
            await("Holding the real orb must mount actions before release") { main { service.dotOverlay.orbMenuView != null } }
            main { assertEquals("Opening actions is presentation only", before, service.dotInteraction.foreground.state.value) }
        } finally { touch(MotionEvent.ACTION_UP, down, orb.exactCenterX(), orb.exactCenterY()) }
        val released = SystemClock.uptimeMillis()
        await("Release must not leak a chat/tap action") {
            main { assertEquals(before, service.dotInteraction.foreground.state.value) }
            SystemClock.uptimeMillis() - released > ViewConfiguration.getDoubleTapTimeout()
        }
        val hit = settledHit(service.getString(R.string.nm_dot_open_conversation), service)
        val density = context.resources.displayMetrics.density
        val minimum = kotlin.math.ceil(48 * density).toInt()
        val all = listOf(R.string.nm_dot_open_conversation, R.string.nm_dot_view_screen,
            R.string.nm_dot_start_care, R.string.nm_dot_voice_start).map { id ->
            var found: Hit? = null
            await("Every bubble must expose its full touch action") {
                found = nativeHit(service.getString(id), hit.windowId, clickable = true)
                found?.let { it.bounds.width() >= minimum && it.bounds.height() >= minimum } == true
            }
            requireNotNull(found)
        }
        all.forEachIndexed { index, first -> all.drop(index + 1).forEach { second ->
            assertFalse("Bubble targets must not overlap", Rect.intersects(first.bounds, second.bounds))
        } }
        main {
            val view = requireNotNull(service.dotOverlay.orbMenuView)
            val p = view.layoutParams as WindowManager.LayoutParams
            assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            val planned = requireNotNull(service.dotOverlay.orbMenuBounds)
            val expected = Rect(planned.left.roundToInt(), planned.top.roundToInt(),
                planned.left.roundToInt() + planned.width.roundToInt(),
                planned.top.roundToInt() + planned.height.roundToInt())
            val position = IntArray(2); view.getLocationOnScreen(position)
            val actual = Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
            assertEquals("Native menu must use planned screen coordinates without extra system insets", expected, actual)
            assertEquals("Accessibility window must agree with native menu geometry", actual, hit.window)
            val windows = service.windows
            try {
                for ((name, owned) in listOf("orb" to service.dotOverlay.orbView,
                    "panel" to service.dotOverlay.panelView, "menu" to view)) {
                    if (owned == null) continue
                    val id = requireNotNull(windowId(owned)) { "$name must be attached to its actual window" }
                    assertTrue("Controller must own the mounted $name window $id",
                        windows.any { it.id == id && service.dotOverlay.ownsWindow(it) })
                }
            }
            finally { windows.forEach { it.recycle() } }
        }
        println("DOT_ORB_MENU held-and-released menuWindow=${hit.windowId} bounds=${hit.window}")
    }

    @Test fun realLongPressRoutesChatScreenCareAndVoicePermissionWithoutSending() = withHost { service ->
        for ((label, mode) in listOf(R.string.nm_dot_open_conversation to DotForegroundMode.Chat,
            R.string.nm_dot_view_screen to DotForegroundMode.Screen, R.string.nm_dot_start_care to DotForegroundMode.Care)) {
            hold(service)
            tap(settledHit(service.getString(label), service))
            await("Selected $mode must use the real coordinator") { main {
                service.dotOverlay.orbMenuView == null && service.dotInteraction.foreground.state.value.mode == mode &&
                    if (mode == DotForegroundMode.Care) service.dotInteraction.careSession.visible.value
                    else service.dotOverlay.panelView != null && service.dotInteraction.foreground.state.value.operation != DotForegroundOperation.Capturing
            } }
            if (mode == DotForegroundMode.Care) {
                val ticket = main { service.dotInteraction.foreground.ticket() }
                hold(service)
                back()
                await("Care Back closes actions before leaving guidance") { main { service.dotOverlay.orbMenuView == null } }
                main { assertTrue(service.dotInteraction.careSession.visible.value); assertEquals(ticket, service.dotInteraction.foreground.ticket()) }
            }
            main { service.dotInteraction.close() }
            println("DOT_ORB_MENU selected=$mode closed=true")
        }
        // Retain the actual canonical adapter. Only the platform permission/audio launch is controlled;
        // this test does not claim an actual permission dialog, ASR or microphone pass.
        val adapter = main {
            val field = service.dotInteraction.javaClass.getDeclaredField("adapter\$delegate").apply { isAccessible = true }
            (field.get(service.dotInteraction) as Lazy<*>).value as DotChatAdapter
        }
        val originalVoice = adapter.voice
        val gate = VoiceStartGate()
        var launches = 0
        var request: VoiceStartGate.Request? = null
        val voice = DotVoiceController(adapter, gate, { VoiceSelection("system", Locale.US, false) },
            { next, _ -> launches++; request = next }, service.dotOverlay::setVoicePermissionWindow)
        try {
            main { adapter.voice = voice }
            hold(service)
            tap(settledHit(service.getString(R.string.nm_dot_voice_start), service))
            await("Voice bubble must enter the existing permission gate") { main { voice.ui.value.phase == DotVoiceController.Phase.PERMISSION } }
            main {
                assertEquals(1, launches)
                val pending = requireNotNull(request)
                assertSame(pending, gate.find(pending.id))
                assertNotNull(adapter.binding.value)
                assertEquals(DotForegroundMode.Chat, service.dotInteraction.foreground.state.value.mode)
                assertFalse(requireNotNull(adapter.session).isStreaming.value)
                assertNull(service.dotOverlay.orbMenuView)
                val binding = adapter.binding.value
                service.dotInteraction.handle(DotIntent.Voice)
                assertEquals(binding, adapter.binding.value)
                assertNull(gate.find(pending.id))
                gate.visible(pending, true)
                assertFalse(gate.begin(pending, true))
                assertEquals(1, launches)
            }
        } finally { main { voice.cancel(); adapter.voice = originalVoice; service.dotInteraction.close() } }
    }

    @Test fun outsideTouchPassesThroughAndMenuKeepsExistingIme() = withHost { service ->
        hold(service)
        val menu = requireNotNull(main { service.dotOverlay.orbMenuView }).let { main {
            val p = it.layoutParams as WindowManager.LayoutParams; Rect(p.x, p.y, p.x + p.width, p.y + p.height)
        } }
        val target = settledHit("ORB_HOST_TARGET")
        val choices = listOf(target.bounds.left + 8f, target.bounds.right - 8f)
        val x = choices.first { !menu.contains(it.toInt(), target.bounds.centerY()) }
        tap(target, x)
        await("Outside action must reach the host once and dismiss the menu") {
            nativeHit("ORB_HOST_TOUCHES_1") != null && main { service.dotOverlay.orbMenuView == null }
        }
        tap(settledHit("ORB_HOST_IME"))
        fun ime(): Boolean {
            val windows = automation.windows
            return try { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            finally { windows.forEach { it.recycle() } }
        }
        await("Host keyboard must really be visible") { ime() }
        hold(service)
        assertTrue("Non-focusable menu must retain the host IME", ime())
        main { service.dotOverlay.dismissOrbMenu() }
        back()
        await("Restore the host keyboard") { !ime() }
    }

    @Test fun ownerCaptureLockStopAndBackRevokeTransientMenu() = withHost { service ->
        for (interruption in 0..4) {
            hold(service)
            val old = main { service.dotOverlay.orbMenuView }
            main {
                when (interruption) {
                    0 -> { service.dotOverlay.setLocked(true); service.dotOverlay.setLocked(false) }
                    1 -> { service.dotOverlay.setEnabled(false); service.dotOverlay.setEnabled(true) }
                    2 -> service.dotOverlay.onDisplayChanged()
                    3 -> { service.dotOverlay.setVoicePermissionWindow(true); service.dotOverlay.setVoicePermissionWindow(false) }
                    else -> { service.dotOverlay.disconnect(); service.dotOverlay.connect() }
                }
                assertNull(service.dotOverlay.orbMenuView)
                assertFalse(requireNotNull(old).isAttachedToWindow)
            }
        }
        for (hide in listOf(false, true)) {
            hold(service)
            runBlocking { withContext(Dispatchers.Main) { service.dotOverlay.withHiddenForCapture(hide) {
                assertNull(service.dotOverlay.orbMenuView)
                service.dotOverlay.onIntent(DotIntent.LongPress)
                assertNull("Capture cannot recreate menu windows", service.dotOverlay.orbMenuView)
            } } }
            main { assertNull(service.dotOverlay.orbMenuView) }
        }
        main { service.dotOverlay.onIntent(DotIntent.Single) }
        await("Canonical chat must mount") { main { service.dotOverlay.panelView != null } }
        val ticket = main { service.dotInteraction.foreground.ticket() }
        hold(service)
        back()
        await("Back must close actions before the mounted chat") { main { service.dotOverlay.orbMenuView == null } }
        main { assertNotNull(service.dotOverlay.panelView); assertEquals(ticket, service.dotInteraction.foreground.ticket()) }
        hold(service)
        tap(settledHit(service.getString(R.string.nm_dot_chat_stop)))
        await("Visible Stop must close menu and revoke old admission immediately") { main {
            service.dotOverlay.orbMenuView == null && !service.dotInteraction.foreground.accepts(ticket) && service.dotOverlay.panelView != null
        } }
    }

    /** Controlled event times exercise overdue delivery; this is View/Window fixture proof, not global input. */
    @Test fun overdueMoveBeforeTimerDispatchMovesRealOrbWithoutOpeningMenu() = withHost { service ->
        var orbId: Int? = null
        await("Fixture orb must attach before controlled event dispatch") {
            orbId = main { windowId(service.dotOverlay.orbView) }
            orbId != null
        }
        settledHit(service.getString(R.string.nm_dot_orb_description), ownWindowId = requireNotNull(orbId))
        val view = main { requireNotNull(service.dotOverlay.orbView) }
        fun bounds(): Rect = main {
            val location = IntArray(2); view.getLocationOnScreen(location)
            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        for (lateBy in listOf(0L, 1L)) {
            val before = bounds()
            val targetX = main { requireNotNull(service.dotScreen.hostViewport()).let { (it.left + it.right) / 2 } }
            val dx = (targetX - before.exactCenterX()).coerceIn(-96 * context.resources.displayMetrics.density,
                96 * context.resources.displayMetrics.density)
            assertTrue("Fixture needs room beyond touch slop", kotlin.math.abs(dx) > ViewConfiguration.get(context).scaledTouchSlop)
            val at = SystemClock.uptimeMillis()
            val downAt = at - ViewConfiguration.getLongPressTimeout() - lateBy
            fun dispatch(action: Int, time: Long, screenX: Float) {
                val location = IntArray(2); view.getLocationOnScreen(location)
                val event = MotionEvent.obtain(downAt, time, action, screenX, before.exactCenterY(), 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                    // Keep raw coordinates in screen space, as WindowManager does for real input.
                    offsetLocation(-location[0].toFloat(), -location[1].toFloat())
                }
                try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            try {
                main {
                    // One Main task means the overdue scheduled Tick cannot run between these events.
                    dispatch(MotionEvent.ACTION_DOWN, downAt, before.exactCenterX())
                    dispatch(MotionEvent.ACTION_MOVE, at, before.exactCenterX() + dx)
                    assertNull("Fresh movement must not mount a long-press menu", service.dotOverlay.orbMenuView)
                }
                await("Real orb window must follow overdue MOVE before any committed hold") {
                    val actual = bounds()
                    kotlin.math.abs(actual.exactCenterX() - before.exactCenterX() - dx) <= 1 &&
                        actual.top == before.top && main { service.dotOverlay.orbMenuView == null }
                }
                println("DOT_ORB_MENU overdue-move-fixture lateBy=$lateBy before=$before actual=${bounds()}")
            } finally {
                main { dispatch(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis(), before.exactCenterX() + dx) }
                await("Cancel restores the fixture orb placement") { bounds() == before }
            }
        }
    }
}
