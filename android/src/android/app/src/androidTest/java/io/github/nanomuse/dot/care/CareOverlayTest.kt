package io.github.nanomuse.dot.care

import android.content.ClipboardManager
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
import org.junit.Rule
import org.junit.runner.RunWith

/** Root-only native gate. Production controller, Compose card, window owner and focus layer;
 * injected inert planner; actual host nodes and touches. No network or persistent data. */
@RunWith(AndroidJUnit4::class)
class CareOverlayTest {
    private lateinit var session: CareSession
    private lateinit var window: CareOverlayWindow
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @get:Rule val native = CareNativeHarness()
    private val automation get()=native.automation
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
    @Test fun fontScaleTwoKeepsAllControlsReachable() {
        val service=native.service
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
        val service=native.service
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
            native.awaitHost()
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
                } finally {
                    automation.serviceInfo=automation.serviceInfo.apply { flags=originalFlags }
                }
            }
        }
    }
    @Test fun oneTouchThroughTargetUserCopyBackPauseAndAccessibleControls() {
        val service=native.service
        val originalFlags=automation.serviceInfo.flags
        native.backupClipboard() // A durable host acknowledgement is required before any Copy.
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var target: RectPx?=null
        try {
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            native.awaitHost()
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
            val copy=service.getString(com.openminis.app.R.string.nm_care_copy)
            native.stage("copy_about_to_execute")
            click(copy)
            instrumentation.runOnMainSync {
                assertTrue("Copy must equal the public fixture text",
                    service.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()=="fixture copy")
                assertTrue(session.controller.verifiedSteps.value.isEmpty())
            }
            native.stage("copy_asserted")
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
                if(::window.isInitialized) window.close()
                if(::session.isInitialized) session.disconnect()
                scope.cancel()
            }
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
