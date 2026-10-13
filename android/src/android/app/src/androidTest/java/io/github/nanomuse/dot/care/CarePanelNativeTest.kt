package io.github.nanomuse.dot.care

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.screen.*
import io.github.nanomuse.dot.ui.DotTokenGalleryFixtureActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real production CarePanel with a local validated proposal; no decorative focus window or provider. */
@RunWith(AndroidJUnit4::class)
class CarePanelNativeTest {
    private data class Owner(val component: String, val windowId: Int?, val attached: Boolean,
        val bounds: Rect, val panel: RectF?)
    private data class Observation(val ownerPresent: Boolean, val completeTextVisible: Boolean, val diagnostic: String)

    @Suppress("DEPRECATION")
    private fun owner(activity: DotTokenGalleryFixtureActivity, panel: RectF?): Owner {
        val decor=activity.window.decorView
        val origin=IntArray(2); decor.getLocationOnScreen(origin)
        val info=decor.createAccessibilityNodeInfo()
        val id=try { info?.windowId?.takeIf { it>=0 } } finally { info?.recycle() }
        return Owner(activity.componentName.flattenToString(),id,decor.isAttachedToWindow,
            Rect(origin[0],origin[1],origin[0]+decor.width,origin[1]+decor.height),panel)
    }

    /** Read the Activity's actual window; an accessibility overlay may be the active root. */
    @Suppress("DEPRECATION")
    private fun observe(automation: UiAutomation, owner: Owner, instruction: String): Observation {
        if (android.os.Build.VERSION.SDK_INT>=33) automation.clearCache()
        val output=StringBuilder("uptime=${SystemClock.uptimeMillis()} owner=$owner\n")
        automation.rootInActiveWindow?.let { active ->
            try { output.append("ACTIVE id=${active.windowId} package=${active.packageName} class=${active.className}\n") }
            finally { active.recycle() }
        }
        var present=false
        var complete=false
        fun visit(node: AccessibilityNodeInfo, depth: Int, window: Rect) {
            val bounds=Rect(); node.getBoundsInScreen(bounds)
            val text=node.text?.toString()
            val measured=owner.panel
            val contained=measured!=null && bounds.left>=measured.left-1 && bounds.top>=measured.top-1 &&
                bounds.right<=measured.right+1 && bounds.bottom<=measured.bottom+1
            val exact=text==instruction
            output.append("  ".repeat(depth)).append("NODE window=${node.windowId} class=${node.className} ")
                .append("text=${text?.replace("\n","\\n")} description=${node.contentDescription?.toString()?.replace("\n","\\n")} ")
                .append("visible=${node.isVisibleToUser} bounds=$bounds exact=$exact panelContains=$contained\n")
            if (exact && node.windowId==owner.windowId && node.isVisibleToUser && !bounds.isEmpty &&
                contained && window.contains(bounds)) complete=true
            for (i in 0 until node.childCount) {
                val child=node.getChild(i) ?: continue
                try { visit(child,depth+1,window) } finally { child.recycle() }
            }
        }
        val windows=automation.windows
        try {
            for (window in windows) {
                val bounds=Rect(); window.getBoundsInScreen(bounds)
                output.append("WINDOW id=${window.id} type=${window.type} active=${window.isActive} ")
                    .append("focused=${window.isFocused} title=${window.title} bounds=$bounds\n")
                if (window.id!=owner.windowId) continue
                val root=window.root ?: continue
                try {
                    present=owner.attached && !owner.bounds.isEmpty
                    visit(root,0,bounds)
                } finally { root.recycle() }
            }
        } finally { windows.forEach { it.recycle() } }
        return Observation(present,complete,output.toString())
    }

    private fun retain(automation: UiAutomation, directory: File, label: String, observed: Observation) {
        File(directory,"$label-windows.txt").writeText(observed.diagnostic)
        val bitmap=requireNotNull(automation.takeScreenshot()) { "Actual display screenshot required for $label" }
        try {
            File(directory,"$label.png").outputStream().use {
                assertTrue("Retain actual display frame $label",bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
            }
        } finally { bitmap.recycle() }
        println("CARE_PANEL_NATIVE frame=$label path=${directory.absolutePath} ownerPresent=${observed.ownerPresent} completeTextVisible=${observed.completeTextVisible}")
    }

    @Suppress("DEPRECATION")
    @Test fun fullCurrentInstructionIsVisibleWithoutAMeasuredDecorativeCue() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val instruction="Tap Search — 搜索"
        val scene=SceneKey("care-panel-fixture",1,0,1)
        val host=RectPx(0f,0f,1080f,2400f)
        val target=NodeDto("search",RectPx(100f,800f,700f,950f),"Search",null,null,"EditText",
            false,true,true,true,false)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val panel=AtomicReference<RectF?>()
        val oldFlags=automation.serviceInfo.flags
        // One run directory per invocation: a failing rerun cannot inherit prior success frames.
        val directory=File(context.cacheDir,"care-panel-native/run-${android.os.Process.myPid()}-${SystemClock.uptimeMillis()}")
        check(directory.mkdirs()) { "Create fresh native evidence directory: $directory" }
        File(directory,"run.txt").writeText("Actual display screenshots and Activity-owned native window trees; local fixture, no provider.\n")
        lateinit var session: CareSession
        var created=false
        try {
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            ActivityScenario.launch<DotTokenGalleryFixtureActivity>(Intent(context,DotTokenGalleryFixtureActivity::class.java)).use { scenario ->
                fun observation(): Observation {
                    lateinit var current: Owner
                    scenario.onActivity { current=owner(it,panel.get()) }
                    return observe(automation,current,instruction)
                }
                try {
                    scenario.onActivity { activity ->
                        val controller=CareController(scope,{ generation ->
                            CaptureOutcome.Success(ScreenSnapshot("local:$generation",generation,SystemClock.uptimeMillis(),scene,
                                host,0,RectPx(0f,0f,0f,0f),null,listOf(target),null,ScreenTransform(host,1080,2400)))
                        },{scene},{ _,snapshot,_ ->
                            CarePlanResult.Step(CareProposal(snapshot.id,scene,target.ref,CareAction.Focus,instruction,null,
                                CareExpectation.Focused(target.ref),0.95f))
                        },debounceMs=0)
                        session=CareSession(controller,{},{}); created=true
                        session.enter()
                        activity.setContent { MinisTheme {
                            Box(Modifier.size(340.dp,480.dp).onGloballyPositioned {
                                val bounds=it.boundsInWindow()
                                val origin=IntArray(2); activity.window.decorView.getLocationOnScreen(origin)
                                panel.set(RectF(bounds.left+origin[0],bounds.top+origin[1],
                                    bounds.right+origin[0],bounds.bottom+origin[1]))
                            }) {
                                CarePanel(session,{}, {})
                            }
                        } }
                    }
                    val ownerDeadline=SystemClock.uptimeMillis()+5000
                    var before=observation()
                    while ((!before.ownerPresent || panel.get()==null) && SystemClock.uptimeMillis()<ownerDeadline) {
                        SystemClock.sleep(40); before=observation()
                    }
                    retain(automation,directory,"01-before-goal",before)
                    assertTrue("The exact fixture Activity window and laid-out panel must be observable: ${before.diagnostic}",
                        before.ownerPresent && panel.get()?.isEmpty==false)
                    scenario.onActivity {
                        session.submitGoal("Find search")
                        assertEquals(CareState.AwaitingUser,session.controller.state.value.state)
                        session.observeConversation()
                    }
                    val deadline=SystemClock.uptimeMillis()+5000
                    var observed=observation()
                    retain(automation,directory,"02-during",observed)
                    while (!observed.completeTextVisible && SystemClock.uptimeMillis()<deadline) {
                        SystemClock.sleep(40); observed=observation()
                    }
                    retain(automation,directory,if(observed.completeTextVisible) "03-visible" else "03-timeout",observed)
                    assertTrue("The actual CarePanel must expose the complete current instruction without a focus/cue measurement: ${observed.diagnostic}",
                        observed.ownerPresent && observed.completeTextVisible)
                    scenario.onActivity {
                        assertEquals(instruction,session.conversation.rows.last().text)
                        assertTrue(session.controller.verifiedSteps.value.isEmpty())
                        assertEquals(CareState.AwaitingUser,session.controller.state.value.state)
                    }
                } catch (failure: Throwable) {
                    try { retain(automation,directory,"failure",observation()) }
                    catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
                    throw failure
                } finally { scenario.onActivity { if (created) session.disconnect(); scope.cancel() } }
            }
        } finally {
            scope.cancel()
            automation.serviceInfo=automation.serviceInfo.apply { flags=oldFlags }
            assertEquals("Restore native observation flags",oldFlags,automation.serviceInfo.flags)
        }
    }
}
