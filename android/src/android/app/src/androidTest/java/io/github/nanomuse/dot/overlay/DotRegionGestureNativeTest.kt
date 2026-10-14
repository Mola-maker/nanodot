package io.github.nanomuse.dot.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotForegroundOperation
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.RectPx
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Native dispatch into the real orb. Uses real host capture, with no provider or automatic Send. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class DotRegionGestureNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val fixture = DotNativeServiceFixture()
    private val inject by lazy {
        UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
    }
    private lateinit var evidence: File
    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        fail(label)
    }
    @Before fun connectNativeService() {
        fixture.connect()
        evidence = File(context.cacheDir, "region-native-${SystemClock.uptimeMillis()}").apply { check(mkdirs()) }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        main { fixture.service().dotOverlay.setEnabled(true) }
        instrumentation.context.startActivity(Intent().setComponent(ComponentName(instrumentation.context,
            DotRegionHostFixture::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var scene: Any? = null
        var since = SystemClock.uptimeMillis()
        await("Public fixture and native scene must settle") {
            val next = main { fixture.service().dotScreen.currentSceneKey() }
            if (next != scene) { scene = next; since = SystemClock.uptimeMillis() }
            next?.packageName == instrumentation.context.packageName &&
                SystemClock.uptimeMillis() - since >= 350 && main { fixture.service().dotOverlay.orbView?.isAttachedToWindow == true }
        }
    }
    @After fun restoreNativeService() { fixture.close() }
    private fun adapter(service: MinisAccessibilityService) = main {
        val field = service.dotInteraction.javaClass.getDeclaredField("adapter\$delegate").apply { isAccessible = true }
        (field.get(service.dotInteraction) as Lazy<*>).value as DotChatAdapter
    }
    private fun orb(service: MinisAccessibilityService) = main {
        val view = requireNotNull(service.dotOverlay.orbView)
        val location = IntArray(2); view.getLocationOnScreen(location)
        Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)
    }
    private fun touch(action: Int, down: Long, point: PointPx) {
        val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,point.x,point.y,0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { assertEquals("Native InputDispatcher accepts each pointer event",true,inject.invoke(automation,event,true,false)) }
        finally { event.recycle() }
    }
    private fun draw(name: String, points: List<PointPx>) {
        val down = SystemClock.uptimeMillis()
        File(evidence,"$name-path.txt").writeText(points.joinToString("\n") { "${it.x},${it.y}" })
        touch(MotionEvent.ACTION_DOWN,down,points.first())
        try {
            points.drop(1).forEach { point -> SystemClock.sleep(16); touch(MotionEvent.ACTION_MOVE,down,point) }
            touch(MotionEvent.ACTION_UP,down,points.last())
        } catch (failure: Throwable) {
            touch(MotionEvent.ACTION_CANCEL,down,points.last()); throw failure
        }
    }
    private fun moveToLeftEdge(service: MinisAccessibilityService, y: Float): Rect {
        val before = orb(service)
        val host = main { requireNotNull(service.dotScreen.hostViewport()) }
        val from = PointPx(before.exactCenterX(),before.exactCenterY())
        val to = PointPx(host.left+host.width*.26f,y)
        draw("open-${SystemClock.uptimeMillis()}",(0..20).map { n ->
            val t=n/20f; PointPx(from.x+(to.x-from.x)*t,from.y+(to.y-from.y)*t)
        })
        var previous: Rect? = null
        var since=SystemClock.uptimeMillis()
        await("Open path must remain movement and settle at the left edge") {
            val now=orb(service)
            if(now != previous) { previous=now; since=SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis()-since>=300 && now.left <= host.left+4 && main {
                service.dotOverlay.panelView == null && service.dotInteraction.foreground.state.value.mode==DotForegroundMode.Idle
            }
        }
        saveScreenshot("after-open-drag")
        assertEquals("Dragging the edge orb must not invoke Android Back and leave the host",
            instrumentation.context.packageName, main { service.dotScreen.currentSceneKey()?.packageName })
        return orb(service)
    }
    private fun saveScreenshot(name: String) {
        val screen=requireNotNull(automation.takeScreenshot())
        try { File(evidence,"$name.png").outputStream().use { assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { screen.recycle() }
    }
    private fun hit(text: String): Rect? {
        fun visit(node: AccessibilityNodeInfo): Rect? {
            if (node.packageName?.toString() == context.packageName &&
                (node.text?.toString() == text || node.contentDescription?.toString() == text)) {
                val bounds=Rect(); node.getBoundsInScreen(bounds)
                if(node.isVisibleToUser && node.isEnabled && !bounds.isEmpty) return bounds
            }
            for(i in 0 until node.childCount) {
                val child=node.getChild(i) ?: continue
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val windows=automation.windows
        try { for(window in windows) {
            val root=window.root ?: continue
            try { visit(root)?.let { return it } } finally { root.recycle() }
        } } finally { windows.forEach { it.recycle() } }
        return null
    }
    private fun tapLabel(text: String) {
        var bounds:Rect?=null
        await("Native foreground action visible: $text") { bounds=hit(text); bounds != null }
        val point=requireNotNull(bounds).let { PointPx(it.exactCenterX(),it.exactCenterY()) }
        val down=SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN,down,point); touch(MotionEvent.ACTION_UP,down,point)
    }
    private fun outline(service: MinisAccessibilityService): android.view.View? = main {
        val field=service.dotOverlay.javaClass.getDeclaredField("selectionOutline").apply { isAccessible=true }
        field.get(service.dotOverlay) as? android.view.View
    }
    private fun selected(service: MinisAccessibilityService, start: Rect, name: String, masked: Boolean) {
        val chat=adapter(service)
        await("Real closed orb path must publish a region preview") { main {
            chat.preview.value?.regionText != null &&
                service.dotInteraction.foreground.state.value.operation != DotForegroundOperation.Capturing
        } }
        val frame=requireNotNull(outline(service))
        main {
            assertTrue(frame.isAttachedToWindow)
            val position=IntArray(2); frame.getLocationOnScreen(position)
            assertArrayEquals("Selection canvas must use screen origin without a second inset",intArrayOf(0,0),position)
            val flags=(frame.layoutParams as android.view.WindowManager.LayoutParams).flags
            assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        }
        val current=orb(service)
        assertEquals("Closed selection restores the DOWN x",start.left,current.left)
        assertEquals("Closed selection restores the DOWN y",start.top,current.top)
        val local=main { requireNotNull(chat.preview.value) }
        val selected= requireNotNull(local.regionText).readText()
        local.file.copyTo(File(evidence,"$name-crop.png"))
        requireNotNull(local.regionText).copyTo(File(evidence,"$name-selected.txt"))
        saveScreenshot(name)
        println("DOT_REGION_NATIVE phase=$name evidence=${evidence.absolutePath} text=$selected")
        assertTrue("Selected LEFT marker: $selected", selected.contains("REGION_INSIDE_LEFT"))
        assertTrue(selected.contains("REGION_INSIDE_RIGHT"))
        assertFalse(selected.contains("REGION_OUTSIDE_RED"))
        assertFalse("API34+ native host window capture must yield actual crop",local.textOnly)
        val bitmap=requireNotNull(BitmapFactory.decodeFile(local.file.path))
        try {
            var red=0; var black=0
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                val pixel=bitmap.getPixel(x,y)
                if(pixel==Color.RED) red++
                if(pixel==Color.BLACK) black++
            }
            assertEquals("Outside red marker cannot survive actual encoded crop",0,red)
            if(masked) assertTrue("Circle's outside corners are actually masked",black>bitmap.width*bitmap.height/20)
            File(evidence,"$name-receipt.txt").writeText("size=${bitmap.width}x${bitmap.height}\nred=$red\nblack=$black\ncapturedAt=${local.capturedAtMs}\n")
        } finally { bitmap.recycle() }
        saveScreenshot(name)
        println("DOT_REGION_NATIVE phase=$name evidence=${evidence.absolutePath}")
    }
    private fun imeVisible(): Boolean {
        val windows = automation.windows
        return try { windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        finally { windows.forEach { it.recycle() } }
    }
    private fun enterNativePrompt(service: MinisAccessibilityService, chat: DotChatAdapter, prompt: String) {
        tapLabel(context.getString(R.string.nm_dot_chat_message))
        var last: Rect? = null
        var since = SystemClock.uptimeMillis()
        await("Actual keyboard and composer must settle before native key input") {
            val bounds = hit(context.getString(R.string.nm_dot_chat_message))
            if (bounds != last) { last = bounds; since = SystemClock.uptimeMillis() }
            imeVisible() && bounds != null && SystemClock.uptimeMillis() - since >= 250
        }
        saveScreenshot("live-keyboard-ready")
        var expected = ""
        // Native key events remain the input route. Acknowledge each short typed span in the
        // actual composer, rather than racing its IME/window transition with a long key burst.
        for (chunk in prompt.chunked(8)) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("input text " + chunk.replace(" ", "%s"))
            ).use { it.readBytes() }
            expected += chunk
            val until = SystemClock.uptimeMillis() + 15_000
            while (main { chat.session?.inputText?.value } != expected && SystemClock.uptimeMillis() < until)
                SystemClock.sleep(30)
            val actual = main { chat.session?.inputText?.value.orEmpty() }
            File(evidence, "live-keyboard-observation.txt").appendText("expected=$expected\nactual=$actual\n")
            if (actual != expected) { saveScreenshot("live-keyboard-failed"); fail("Native composer prefix differs; see retained observation") }
        }
        assertEquals(prompt, main { chat.session!!.inputText.value })
    }

    @Test fun actualRegionComposerAcceptsNativeKeyboardWithoutSending() {
        val app = context.applicationContext as com.openminis.app.MinisApp
        val store = com.openminis.app.ui.chat.ChatViewModelStore
        val prior = main { store.activeSessionId }
        val sessionId = kotlinx.coroutines.runBlocking {
            app.chatRepository.createSession("unknown", "Public keyboard proof", memoryEnabled = false).id
        }
        try {
            main { store.setActiveSession(sessionId) }
            val service = fixture.service()
            val host = main { requireNotNull(service.dotScreen.hostViewport()) }
            val start = moveToLeftEdge(service, (host.top + host.bottom) / 2f)
            val x = start.exactCenterX(); val y = start.exactCenterY()
            val radius = min((host.right - start.width() / 2f - x) / 2f, host.height * .32f)
            val center = x + radius
            draw("keyboard-circle", (0..96).map { n ->
                val angle = Math.PI + n * 2 * Math.PI / 96
                PointPx((center + radius * cos(angle)).toFloat(), (y + radius * sin(angle)).toFloat())
            })
            selected(service, start, "keyboard-preview", true)
            val chat = adapter(service)
            enterNativePrompt(service, chat,
                "Read the selected accessible text. Reply exactly with the two REGION_INSIDE labels separated by a space. Do not describe an image.")
            saveScreenshot("keyboard-complete")
            assertTrue("Input never sends the selected preview", main { chat.preview.value != null })
            assertFalse("No model or Send in keyboard-only proof", main { chat.session!!.isStreaming.value })
        } finally { main { store.setActiveSession(prior) } }
    }

    /** Actual orb/composer route. Host coordinator must explicitly configure and arm this run.
     * No VM send, input setter, reply injection, provider fallback or retry is used here. */
    @Test fun actualCirclePhysicalSendDisplaysRealDeepSeekReplyInTheSameConversation() {
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("regionLiveRun"))
        require(java.util.UUID.fromString(run).toString() == run)
        val app = context.applicationContext as com.openminis.app.MinisApp
        val store = com.openminis.app.ui.chat.ChatViewModelStore
        val prior = main { store.activeSessionId }
        val sessionId = kotlinx.coroutines.runBlocking {
            app.chatRepository.createSession("unknown", "Public region proof", memoryEnabled = false).id
        }
        val phase = File(context.cacheDir, "region-live-$run.json")
        val armed = File(context.cacheDir, "region-live-$run.go")
        try {
            main { store.setActiveSession(sessionId) }
            val service = fixture.service()
            val host = main { requireNotNull(service.dotScreen.hostViewport()) }
            val start = moveToLeftEdge(service, (host.top + host.bottom) / 2f)
            val x = start.exactCenterX(); val y = start.exactCenterY()
            val radius = min((host.right - start.width() / 2f - x) / 2f, host.height * .32f)
            val center = x + radius
            val circle = (0..96).map { n ->
                val angle = Math.PI + n * 2 * Math.PI / 96
                PointPx((center + radius * cos(angle)).toFloat(), (y + radius * sin(angle)).toFloat())
            }
            draw("live-circle", circle)
            selected(service, start, "live-preview", true)
            val chat = adapter(service)
            val originalBinding = main { requireNotNull(chat.binding.value) }
            assertEquals(sessionId, originalBinding.sessionId)
            phase.writeText(org.json.JSONObject().put("phase", "preview_ready").put("run", run)
                .put("sessionId", sessionId).put("evidence", evidence.absolutePath).toString())
            val until = SystemClock.uptimeMillis() + 90_000
            while (!armed.isFile && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
            assertTrue("Host must configure the reviewed bounded text provider and explicitly arm Send", armed.isFile)
            val configuration = org.json.JSONObject(armed.readText())
            main {
                val vm = requireNotNull(chat.businessViewModel)
                val entryId = configuration.getString("entryId")
                assertEquals("Actual canonical VM uses this run's provider entry", entryId, vm.activeEntryId.value)
                val entry = app.providerRepository.config.value.modelEntries.single { it.id == entryId }
                assertEquals(configuration.getString("instanceId"), entry.providerInstanceId)
                assertNull("Direct entry keeps alternate provider fallback unavailable", vm.selectedGroupId.value)
                assertEquals("This bounded public run has one configured provider", 1,
                    app.providerRepository.config.value.instances.size)
            }
            await("Configured canonical text model must be available") { main {
                chat.session?.configured?.value?.isNotBlank() == true && chat.session?.supportsImages() == false
            } }
            val prompt = "Read the selected accessible text. Reply exactly with the two REGION_INSIDE labels separated by a space. Do not describe an image."
            enterNativePrompt(service, chat, prompt)
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            var sendBounds: Rect? = null
            var stableSince = SystemClock.uptimeMillis()
            await("Back hides the actual keyboard and settles physical Send") {
                val now = hit(context.getString(R.string.nm_dot_chat_send))
                if (now != sendBounds) { sendBounds = now; stableSince = SystemClock.uptimeMillis() }
                !imeVisible() && now != null && main { service.dotOverlay.panelView != null } &&
                    SystemClock.uptimeMillis() - stableSince >= 250
            }
            saveScreenshot("live-before-send")
            tapLabel(context.getString(R.string.nm_dot_chat_send))
            phase.writeText(org.json.JSONObject().put("phase", "physical_send").put("run", run)
                .put("sessionId", sessionId).put("evidence", evidence.absolutePath).toString())
            val expected = "REGION_INSIDE_LEFT REGION_INSIDE_RIGHT"
            val replyDeadline = SystemClock.uptimeMillis() + 65_000
            var reply: com.openminis.app.ui.chat.ChatMessage? = null
            while (SystemClock.uptimeMillis() < replyDeadline) {
                reply = main { chat.session?.messages?.value?.lastOrNull { it.role == "assistant" && it.content.trim() == expected } }
                if (reply != null && main { chat.session?.isStreaming?.value == false }) break
                SystemClock.sleep(50)
            }
            assertNotNull("A genuine completed provider reply must reach the same canonical session", reply)
            assertFalse(main { chat.session!!.isStreaming.value })
            assertEquals(originalBinding, main { chat.binding.value })
            val user = main { chat.session!!.messages.value.last { it.role == "user" } }
            assertTrue(user.content.startsWith(prompt))
            assertTrue(user.content.contains("REGION_INSIDE_LEFT"))
            assertTrue(user.content.contains("REGION_INSIDE_RIGHT"))
            assertFalse(user.content.contains("REGION_OUTSIDE_RED"))
            await("Actual completed assistant bubble must render in the foreground") { hit(expected) != null }
            assertNull(main { chat.preview.value })
            saveScreenshot("live-reply")
            File(evidence, "live-chat-receipt.json").writeText(org.json.JSONObject()
                .put("sessionId", sessionId).put("user", user.content).put("reply", requireNotNull(reply).content)
                .put("input", "native_touch_and_shell_keyboard").put("send", "native_touch")
                .put("provider", "host_bounded_real_deepseek").put("source", "selected_accessibility_nodes")
                .put("vision", false).toString())
            phase.writeText(org.json.JSONObject().put("phase", "reply_visible").put("run", run)
                .put("sessionId", sessionId).put("evidence", evidence.absolutePath).toString())
            println("DOT_REGION_LIVE_REPLY evidence=" + evidence.absolutePath)
        } finally {
            armed.delete()
            main { store.setActiveSession(prior) }
        }
    }

    @Test fun actualCircleAndRectangleCreateMaskedPreviewAndStopClearsOwnership() {
        val service=fixture.service()
        val host=main { requireNotNull(service.dotScreen.hostViewport()) }
        val circleStart=moveToLeftEdge(service,(host.top+host.bottom)/2f)
        val x=circleStart.exactCenterX(); val y=circleStart.exactCenterY()
        val radius=min((host.right-circleStart.width()/2f-x)/2f,host.height*.32f)
        assertTrue(radius > 60*context.resources.displayMetrics.density)
        val center=x+radius
        val circle=(0..96).map { n ->
            val angle=Math.PI+n*2*Math.PI/96
            PointPx((center+radius*cos(angle)).toFloat(),(y+radius*sin(angle)).toFloat())
        }
        draw("circle",circle)
        selected(service,circleStart,"circle",true)
        val first=main { requireNotNull(adapter(service).preview.value) }
        tapLabel(context.getString(R.string.nm_dot_region_reselect))
        await("Reselect closes the foreground while deleting both old files") { main { service.dotOverlay.panelView == null } &&
            !first.file.exists() && first.regionText?.exists()==false }
        val rectangleStart=moveToLeftEdge(service,(host.top+host.bottom)/2f-90*context.resources.displayMetrics.density)
        val left=rectangleStart.exactCenterX(); val top=rectangleStart.exactCenterY()
        val right=host.right-rectangleStart.width()/2f
        val bottom=top+180*context.resources.displayMetrics.density
        val corners=listOf(PointPx(left,top),PointPx(right,top),PointPx(right,bottom),PointPx(left,bottom),PointPx(left,top))
        val rectangle=buildList {
            add(corners.first())
            corners.zipWithNext().forEach { (a,b) -> for(n in 1..18) {
                val t=n/18f; add(PointPx(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t))
            } }
        }
        draw("rectangle",rectangle)
        selected(service,rectangleStart,"rectangle",false)
        val local=main { requireNotNull(adapter(service).preview.value) }
        tapLabel(context.getString(R.string.nm_dot_chat_stop))
        await("Stop revokes selected attachments without sending") { main { adapter(service).preview.value==null } &&
            !local.file.exists() && local.regionText?.exists()==false }
        assertNull("Stop removes the owned outline",outline(service))
        saveScreenshot("stopped")
    }
}
