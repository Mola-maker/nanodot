package io.github.nanomuse.dot.care

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import com.openminis.app.data.model.*
import com.openminis.app.provider.LLMProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Root-only native gate. Production controller, Compose card, window owner and focus layer;
 * injected inert planner; actual host nodes and touches. No network or persistent data. */
@RunWith(AndroidJUnit4::class)
class CareOverlayTest {
    private lateinit var session: CareSession
    private lateinit var window: CareOverlayWindow
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    @Suppress("DEPRECATION")
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result+=node
            for(i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        val windows=automation.windows
        try { windows.forEach { it.root?.let(::visit) } } finally { windows.forEach { it.recycle() } }
        return result
    }
    @Suppress("DEPRECATION")
    private fun withNodes(block: (List<AccessibilityNodeInfo>)->Unit) {
        if(android.os.Build.VERSION.SDK_INT>=33) automation.clearCache()
        val all=nodes(); try { block(all) } finally { all.forEach { it.recycle() } }
    }
    private fun tap(x: Float,y: Float) {
        val t=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,x,y,0)
            try { assertTrue(automation.injectInputEvent(e,true)) } finally { e.recycle() }
        }
    }
    private fun click(label: String) {
        withNodes { all ->
            val node=all.first { it.text?.toString()==label && it.isVisibleToUser }
            var clickable=AccessibilityNodeInfo.obtain(node)
            try {
                while(!clickable.isClickable) {
                    val parent=requireNotNull(clickable.parent); clickable.recycle(); clickable=parent
                }
                assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            } finally { clickable.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun scrollCardToStart() {
        repeat(12) { withNodes { all -> all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } }
        instrumentation.waitForIdleSync()
    }
    private fun assertReachable(label: String) {
        scrollCardToStart()
        var found=false
        repeat(12) {
            withNodes { all ->
                if(all.any { it.text?.toString()==label && it.isVisibleToUser }) found=true
                if(!found) all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            automation.waitForIdle(100,2000)
        }
        assertTrue("Control must remain reachable at this font scale/orientation: $label",found)
    }
    private fun closeOwnedFixture(service: MinisAccessibilityService) {
        var owned=false
        instrumentation.runOnMainSync { owned=service.dotScreen.currentSceneKey()?.packageName==instrumentation.context.packageName }
        if(owned) automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }
    private fun awaitMain(description: String, condition: () -> Boolean) {
        val deadline=SystemClock.uptimeMillis()+5000
        var ready=false
        while(!ready && SystemClock.uptimeMillis()<deadline) {
            instrumentation.runOnMainSync { ready=condition() }
            if(!ready) SystemClock.sleep(50)
        }
        assertTrue(description,ready)
    }
    @Test fun compactTargetCareRetainsTranscriptWhileAdjustedDraggedAndExpanded() {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val flags = automation.serviceInfo.flags
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var viewport: RectPx? = null
        fun card(): android.view.View = CareOverlayWindow::class.java.getDeclaredField("card").let {
            it.isAccessible = true; it.get(window) as android.view.View
        }
        fun controlBounds(label: String): Rect? {
            var result: Rect? = null
            withNodes { all -> all.firstOrNull { it.text?.toString() == label && it.isVisibleToUser }?.let { node ->
                var action = AccessibilityNodeInfo.obtain(node)
                try {
                    while (!action.isClickable) {
                        val parent = action.parent ?: break
                        action.recycle(); action = parent
                    }
                    val bounds = Rect(); action.getBoundsInScreen(bounds)
                    var clipped = false
                    instrumentation.runOnMainSync {
                        val view = card()
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        val cardBounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                        val host = service.dotScreen.hostViewport()
                        clipped = bounds.intersect(cardBounds) && host != null && bounds.intersect(
                            Rect(host.left.toInt(), host.top.toInt(), host.right.toInt(), host.bottom.toInt()))
                    }
                    if (action.isClickable && action.isVisibleToUser && action.isEnabled && clipped &&
                        bounds.height() >= (48 * service.resources.displayMetrics.density).toInt()) result = bounds
                } finally { action.recycle() }
            } }
            return result
        }
        fun reach(label: String): Rect {
            repeat(20) {
                controlBounds(label)?.let { return it }
                withNodes { all ->
                    // Compact status and adjustment share the LOWER 48dp controls viewport;
                    // scrolling the independent transcript cannot reveal these buttons.
                    all.filter { it.isScrollable }.maxByOrNull { node ->
                        Rect().also(node::getBoundsInScreen).top
                    }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                automation.waitForIdle(100, 2000)
            }
            error("48dp Care control unreachable: $label")
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.context.startActivity(Intent(instrumentation.context, CareOverlayFixture::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitMain("Actual external host must be current") { service.dotScreen.currentSceneKey()?.packageName == instrumentation.context.packageName }
            instrumentation.runOnMainSync {
                service.dotInteraction.close()
                val controller = CareController(scope, { generation ->
                    // Controlled wide target is delivered through the real Care controller,
                    // validator and session, never through ordinary Chat's injected target hook.
                    val scene = requireNotNull(service.dotScreen.currentSceneKey())
                    val host = requireNotNull(service.dotScreen.hostViewport())
                    val node = NodeDto("wide-target", host, "Wide fixture", null, null, "container", false, true, false, true, false)
                    CaptureOutcome.Success(ScreenSnapshot("wide-fixture", generation, 1, scene, host, 0,
                        RectPx(0f, 0f, 0f, 0f), null, listOf(node), null,
                        ScreenTransform(host, host.width.toInt(), host.height.toInt())))
                }, { service.dotScreen.currentSceneKey() }, { _, snapshot, _ ->
                    CarePlanResult.Step(CareProposal(snapshot.id, snapshot.scene, "wide-target", CareAction.Focus,
                        "Touch the fixture target", null, CareExpectation.Focused("wide-target"), .9f))
                })
                session = CareSession(controller, { if (::window.isInitialized) window.clearFocus() }, {})
                window = CareOverlayWindow(service, scope, session, { viewport ?: service.dotScreen.hostViewport() }, { service.dotScreen.currentSceneKey() })
                session.enter(); session.submitGoal("Keep this Care goal")
            }
            awaitMain("Real Care owner must reach compact target collision") {
                session.controller.state.value.state == CareState.AwaitingUser && card().isAttachedToWindow && card().height > 1
            }
            automation.waitForIdle(300, 3000)
            val retained = card()
            val history = session.conversation.state.value
            val generation = session.controller.generation
            val before = (retained.layoutParams as WindowManager.LayoutParams).y
            val adjust = reach(service.getString(com.openminis.app.R.string.nm_dot_foreground_adjust))
            tap(adjust.exactCenterX(), adjust.exactCenterY())
            awaitMain("Actual named Care adjustment moves its same window") { (card().layoutParams as WindowManager.LayoutParams).y > before }
            val adjusted = (retained.layoutParams as WindowManager.LayoutParams).y
            instrumentation.runOnMainSync { window.refreshGeometry(); assertEquals(adjusted, (retained.layoutParams as WindowManager.LayoutParams).y) }
            val drag = reach(service.getString(com.openminis.app.R.string.nm_dot_foreground_adjust))
            val down = SystemClock.uptimeMillis()
            for ((action, y) in listOf(MotionEvent.ACTION_DOWN to drag.exactCenterY(),
                MotionEvent.ACTION_MOVE to (drag.exactCenterY() - 80f),
                MotionEvent.ACTION_MOVE to (drag.exactCenterY() - 220f), MotionEvent.ACTION_UP to (drag.exactCenterY() - 220f))) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, drag.exactCenterX(), y, 0)
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            awaitMain("Actual drag changes Care placement") { (card().layoutParams as WindowManager.LayoutParams).y < adjusted }
            instrumentation.runOnMainSync {
                val host = requireNotNull(service.dotScreen.hostViewport())
                val p = retained.layoutParams as WindowManager.LayoutParams
                assertTrue(p.x >= host.left && p.y >= host.top && p.x + p.width <= host.right + 1 && p.y + p.height <= host.bottom + 1)
                assertSame(retained, card()); assertEquals(history, session.conversation.state.value)
                assertEquals(generation, session.controller.generation); assertEquals("Keep this Care goal", session.controller.state.value.goal)
                window.refreshGeometry()
            }
            val height = retained.height
            val expand = reach(service.getString(com.openminis.app.R.string.nm_dot_foreground_expand))
            tap(expand.exactCenterX(), expand.exactCenterY())
            awaitMain("Expand lifts compact cap without replacing Care") { card().height > height }
            instrumentation.runOnMainSync {
                assertSame(retained, card()); assertEquals(history, session.conversation.state.value)
                assertEquals(generation, session.controller.generation)
                session.changeGoal()
            }
            awaitMain("Same Care composition must show the goal editor") { session.editingGoal.value }
            var typed = false
            repeat(20) {
                if (!typed) withNodes { all ->
                    all.firstOrNull { it.isEditable && it.isVisibleToUser }?.let { editor ->
                        typed = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "retained Care draft")
                        })
                    }
                    if (!typed) all.filter { it.isScrollable }.maxByOrNull { Rect().also(it::getBoundsInScreen).top }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                automation.waitForIdle(100, 2000)
            }
            assertTrue("Actual editable goal accepts an unsent draft", typed)
            instrumentation.runOnMainSync {
                val host = requireNotNull(service.dotScreen.hostViewport())
                viewport = host.copy(bottom = minOf(host.bottom, host.top + 200f * service.resources.displayMetrics.density))
                window.refreshGeometry()
                assertSame(retained, card())
            }
            automation.waitForIdle(300, 3000)
            withNodes { all -> assertTrue("Relayout must retain the unsent goal in the same editor", all.any {
                it.isEditable && it.text?.toString() == "retained Care draft"
            }) }
        } finally {
            instrumentation.runOnMainSync { if (::window.isInitialized) window.close(); if (::session.isInitialized) session.disconnect(); scope.cancel() }
            closeOwnedFixture(service)
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }
    @Test fun fontScaleTwoKeepsAllControlsReachable() {
        val service=requireNotNull(MinisAccessibilityService.getInstance())
        val previous=android.provider.Settings.System.getString(service.contentResolver,"font_scale")
        require(previous==null || previous.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            shell("settings put system font_scale 2.0")
            awaitMain("Actual service configuration must reach font scale 2.0") { service.resources.configuration.fontScale==2f }
            oneTouchThroughTargetUserCopyBackPauseAndAccessibleControls()
        } finally {
            shell(if(previous==null) "settings delete system font_scale" else "settings put system font_scale $previous")
        }
    }
    @Test fun animatorScaleZeroKeepsOneStaticNonfocusedOutline() {
        val service=requireNotNull(MinisAccessibilityService.getInstance())
        val previous=android.provider.Settings.Global.getString(service.contentResolver,"animator_duration_scale")
        require(previous==null || previous.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        var layer: CareFocusLayer?=null
        val originalFlags=automation.serviceInfo.flags
        try {
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            shell("settings put global animator_duration_scale 0")
            instrumentation.context.startActivity(Intent(instrumentation.context,CareOverlayFixture::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitMain("Disposable reduced-motion host must be the real current scene") {
                service.dotScreen.currentSceneKey()?.packageName==instrumentation.context.packageName
            }
            automation.waitForIdle(300,5000)
            val rect=Rect()
            withNodes { all -> all.first { it.contentDescription?.toString()=="CARE_TOUCH_TARGET" }.getBoundsInScreen(rect) }
            val bounds=RectPx(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat())
            instrumentation.runOnMainSync {
                assertEquals(0f,android.provider.Settings.Global.getFloat(service.contentResolver,"animator_duration_scale"),0f)
                layer=CareFocusLayer(service)
                layer!!.show(bounds)
                val first=layer!!.view
                layer!!.show(bounds)
                assertSame(first,layer!!.view)
                assertFalse(first!!.hasFocus())
            }
            awaitMain("Static focus attachment") { layer!!.view?.isAttachedToWindow==true }
            automation.waitForIdle(100,5000)
            val first=requireNotNull(automation.takeScreenshot())
            try {
                SystemClock.sleep(300)
                val second=requireNotNull(automation.takeScreenshot())
                try {
                    val a=IntArray(rect.width()*rect.height()); val b=IntArray(a.size)
                    first.getPixels(a,0,rect.width(),rect.left,rect.top,rect.width(),rect.height())
                    second.getPixels(b,0,rect.width(),rect.left,rect.top,rect.width(),rect.height())
                    assertArrayEquals("Reduced motion must remain static",a,b)
                } finally { second.recycle() }
            } finally { first.recycle() }
        } finally {
            try {
                instrumentation.runOnMainSync { layer?.clear() }
            } finally {
                try {
                    shell(if(previous==null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $previous")
                    closeOwnedFixture(service)
                } finally {
                    automation.serviceInfo=automation.serviceInfo.apply { flags=originalFlags }
                }
            }
        }
    }
    @Test fun oneTouchThroughTargetUserCopyBackPauseAndAccessibleControls() {
        val service=requireNotNull(MinisAccessibilityService.getInstance()) { "Root must enable the real accessibility service first" }
        val originalFlags=automation.serviceInfo.flags
        var originalClip: android.content.ClipData?=null
        var clipboardSaved=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var target: RectPx?=null
        try {
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.context.startActivity(Intent(instrumentation.context,CareOverlayFixture::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitMain("Disposable external host must be the real current scene") {
                service.dotScreen.currentSceneKey()?.packageName==instrumentation.context.packageName
            }
            automation.waitForIdle(500,5000)
            instrumentation.runOnMainSync {
                val controller=CareController(scope, { gen -> service.dotScreen.captureNodes(gen) },
                    { service.dotScreen.currentSceneKey() }, { _,snapshot,_ ->
                        val node=snapshot.nodes.single { it.description=="CARE_TOUCH_TARGET" }
                        target=node.bounds
                        CarePlanner(FixtureProvider(),"Which control can you see?").next("Touch fixture",snapshot,emptyList())
                    })
                session=CareSession(controller,{ if(::window.isInitialized) window.clearFocus() },{})
                window=CareOverlayWindow(service,scope,session,{service.dotScreen.hostViewport()},{service.dotScreen.currentSceneKey()})
                session.enter(); session.submitGoal("Touch fixture")
            }
            awaitMain("Care proposal and actual focus window must be ready on Main") {
                session.controller.state.value.state==CareState.AwaitingUser && window.focus.view?.let {
                    it.isAttachedToWindow && it.isShown && it.windowVisibility==android.view.View.VISIBLE && it.alpha>0f
                }==true
            }
            automation.waitForIdle(500,5000)
            instrumentation.runOnMainSync {
                assertEquals(CareState.AwaitingUser,session.controller.state.value.state)
                assertEquals(target,window.focus.bounds)
                val params=window.focus.view!!.layoutParams as WindowManager.LayoutParams
                assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,params.type)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,window.focus.view!!.importantForAccessibility)
            }
            val reachable = listOf(com.openminis.app.R.string.nm_care_copy,
                com.openminis.app.R.string.nm_care_repeat,com.openminis.app.R.string.nm_care_pause,
                com.openminis.app.R.string.nm_care_reobserve,com.openminis.app.R.string.nm_care_end,
                com.openminis.app.R.string.nm_care_confirm,com.openminis.app.R.string.nm_care_change_goal)
            for (label in reachable) assertReachable(service.getString(label))
            scrollCardToStart()
            instrumentation.runOnMainSync {
                originalClip=service.getSystemService(ClipboardManager::class.java).primaryClip
                clipboardSaved=true
            }
            val copy=service.getString(com.openminis.app.R.string.nm_care_copy)
            click(copy)
            instrumentation.runOnMainSync {
                assertEquals("fixture copy",service.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
                assertTrue(session.controller.verifiedSteps.value.isEmpty())
            }
            val bounds=requireNotNull(target); tap((bounds.left+bounds.right)/2,(bounds.top+bounds.bottom)/2)
            automation.waitForIdle(300,5000)
            withNodes { all -> assertTrue(all.any { it.text?.toString()=="CARE_TOUCH_RECEIVED" }) }
            // Compose semantics: announced status plus visible controls. Run this same method under
            // root's 1.0/1.5/2.0 font-scale + landscape matrix; scroll the measured card when needed.
            withNodes { all ->
                if(android.os.Build.VERSION.SDK_INT>=30) assertTrue(all.any { it.stateDescription?.isNotBlank()==true })
                assertTrue(all.any { it.text?.toString()==service.getString(com.openminis.app.R.string.nm_care_awaiting) })
            }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            automation.waitForIdle(300,5000)
            instrumentation.runOnMainSync {
                assertEquals(CareState.Paused,session.controller.state.value.state)
                assertNull(window.focus.bounds)
            }
        } finally {
            try {
            instrumentation.runOnMainSync {
                if(clipboardSaved) {
                    val clipboard=service.getSystemService(ClipboardManager::class.java)
                    originalClip?.let(clipboard::setPrimaryClip) ?: run {
                        if(android.os.Build.VERSION.SDK_INT>=28) clipboard.clearPrimaryClip()
                        else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("",""))
                    }
                }
                if(::window.isInitialized) window.close()
                if(::session.isInitialized) session.disconnect()
                scope.cancel()
            }
            closeOwnedFixture(service)
            } finally {
                automation.serviceInfo=automation.serviceInfo.apply { flags=originalFlags }
            }
        }
    }
}

/** Public provider contract, inert test transport; the real CarePlanner validates its reply. */
private class FixtureProvider: LLMProvider {
    override val name="care-overlay-fixture"
    override var model=LLMModel("fixture","Fixture","test",contextWindow=32768,maxOutputTokens=1024)
    override suspend fun sendMessageClamped(messages: List<LLMMessage>,systemPrompt: String?,maxTokens: Int,
        temperature: Double?,imageParts: List<LLMMessage.ImagePart>,tools: List<AgentToolDefinition>,thinkingLevel: ThinkingLevel): LLMResponse {
        check(tools.isEmpty() && imageParts.isEmpty())
        val request=Json.parseToJsonElement(messages.single().content).jsonObject
        val node=request.getValue("nodes").jsonArray.single { it.jsonObject["description"]?.jsonPrimitive?.content=="CARE_TOUCH_TARGET" }.jsonObject
        val reply=buildJsonObject {
            put("type","Step"); put("snapshotId",request.getValue("snapshotId")); put("scene",request.getValue("scene"))
            put("targetRef",node.getValue("ref")); put("action","Copy"); put("instruction","Touch the fixture target")
            put("copyText","fixture copy"); put("confidence",0.9)
            put("expectation",buildJsonObject { put("type","PageContains"); put("text","CARE_TOUCH_RECEIVED") })
        }
        return LLMResponse(reply.toString(),"stop",null)
    }
    override fun streamMessageClamped(messages: List<LLMMessage>,systemPrompt: String?,maxTokens: Int,
        temperature: Double?,imageParts: List<LLMMessage.ImagePart>,tools: List<AgentToolDefinition>,thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> =
        error("Care does not stream or run an agent loop")
}
