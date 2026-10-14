package io.github.nanomuse.dot.care

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nanomuse.dot.screen.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Root-run native gate: actual test-host UI, NodeReader DTOs and framework events.
 * Does not establish service/controller ownership, full care UI or real-model acceptance.
 */
@RunWith(AndroidJUnit4::class)
class CareVerifierNativeTest {
    @Suppress("DEPRECATION")
    @Test fun wrongButtonAndAdRefreshNeverBecomeFocusOrPageCompletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val tracker = SceneTracker()
        val intent = Intent(instrumentation.context, CareEvidenceHostFixture::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun hostRoot(): AccessibilityNodeInfo? {
            val windows = automation.windows
            try {
                for (window in windows) {
                    if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = window.root ?: continue
                    if (root.packageName?.toString() == instrumentation.context.packageName) return root
                    root.recycle()
                }
                return null
            } finally { windows.forEach { it.recycle() } }
        }
        try {
            val launched = automation.executeAndWaitForEvent({ instrumentation.context.startActivity(intent) },
                { e -> e.packageName?.toString() == instrumentation.context.packageName &&
                    e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }, 5000)
            launched.recycle()
            automation.waitForIdle(500, 5000)
            fun capture(id: String): ScreenSnapshot {
                if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
                val windows = automation.windows
                var root: AccessibilityNodeInfo? = null
                var transferred = false
                try {
                    val host = windows.first { window ->
                        if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) false
                        else window.root?.let { candidate ->
                            if (candidate.packageName?.toString() == instrumentation.context.packageName) {
                                root = candidate; true
                            } else { candidate.recycle(); false }
                        } ?: false
                    }
                    val hostRoot = requireNotNull(root)
                    val rect = Rect().also(hostRoot::getBoundsInScreen)
                    val bounds = RectPx(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat())
                    val displayId = if (Build.VERSION.SDK_INT >= 30) host.displayId else 0
                    val display = requireNotNull(instrumentation.context.getSystemService(
                        android.hardware.display.DisplayManager::class.java).getDisplay(displayId))
                    val size = android.graphics.Point().also(display::getRealSize)
                    val insets = RectPx(bounds.left,bounds.top,size.x-bounds.right,size.y-bounds.bottom)
                    val ime = windows.firstOrNull { window ->
                        window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                            (Build.VERSION.SDK_INT < 30 || window.displayId == displayId)
                    }?.let { window ->
                        val r = Rect().also(window::getBoundsInScreen)
                        RectPx(r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat())
                    }
                    val scene = tracker.observe(SceneFrame(instrumentation.context.packageName,
                        host.id,displayId,bounds,display.rotation,insets,ime))
                    transferred = true
                    val nodes = NodeReader.read(hostRoot,id)
                    return ScreenSnapshot(id,1,System.currentTimeMillis(),scene,bounds,display.rotation,insets,ime,
                        nodes,null,ScreenTransform(bounds,bounds.width.toInt(),bounds.height.toInt()))
                } finally {
                    if (!transferred) root?.recycle()
                    windows.forEach { it.recycle() }
                }
            }

            fun click(label: String) {
                val event = automation.executeAndWaitForEvent({
                    val root = requireNotNull(hostRoot())
                    try {
                        val candidates = root.findAccessibilityNodeInfosByText(label)
                        try { assertTrue(requireNotNull(candidates.singleOrNull()).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                        finally { candidates.forEach { it.recycle() } }
                    } finally { root.recycle() }
                }, { e -> e.packageName?.toString() == instrumentation.context.packageName &&
                    (e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }, 5000)
                try { tracker.contentChanged(event.windowId, emptySet()) } finally { event.recycle() }
                automation.waitForIdle(500, 5000)
            }
            val before = capture("native-before")
            try {
                val target = before.nodes.single { it.description == "CARE_TARGET" }
                assertFalse(target.focused)
                val focused = CareProposal(before.id,before.scene,target.ref,CareAction.Focus,"Focus the target query",null,
                    CareExpectation.Focused(target.ref),0.9f)
                val page = focused.copy(action=CareAction.Open, expectation=CareExpectation.PageContains("Delivery"))
                val verifier = CareVerifier(before)
                click("CARE_WRONG_BUTTON")
                capture("native-wrong").use { wrong ->
                    assertTrue(wrong.nodes.any { it.description == "CARE_WRONG" && it.focused })
                    assertEquals(VerificationResult.NotYet,verifier.evaluate(focused,wrong))
                }
                click("CARE_AD_REFRESH")
                capture("native-ad").use { ad ->
                    assertTrue(ad.nodes.any { it.description == "CARE_AD" && it.text == "Delivery" })
                    assertEquals(VerificationResult.NotYet,verifier.evaluate(focused,ad))
                    assertEquals(VerificationResult.NeedsUserConfirmation,verifier.evaluate(page,ad))
                }
                click("CARE_RIGHT_BUTTON")
                capture("native-right").use { right ->
                    assertTrue(right.nodes.any { it.description == "CARE_TARGET" && it.focused })
                    assertEquals(VerificationResult.Confirmed,verifier.evaluate(focused,right))
                }
            } finally { before.close() }
        } finally {
            // External test-APK Activity: close through its native test control, preserving settings.
            hostRoot()?.let { root ->
                try {
                    val close = root.findAccessibilityNodeInfosByText("CARE_CLOSE")
                    try { close.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    finally { close.forEach { it.recycle() } }
                } finally { root.recycle() }
            }
            automation.waitForIdle(500, 5000)
        }
    }
}
