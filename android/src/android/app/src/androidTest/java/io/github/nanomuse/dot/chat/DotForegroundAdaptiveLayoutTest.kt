package io.github.nanomuse.dot.chat

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect as NativeRect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotForegroundOperation
import io.github.nanomuse.dot.DotForegroundState
import io.github.nanomuse.dot.ui.DotForegroundStatus
import io.github.nanomuse.dot.ui.DotTokenGalleryFixtureActivity
import io.github.nanomuse.dot.ui.DotTokens
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production chrome at constrained widths; no service, provider, model or window injection. */
@RunWith(AndroidJUnit4::class)
class DotForegroundAdaptiveLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test fun toolbarTargetsAndCompleteMessageFitAtThreeWidthsAndTwoFontScales() {
        try {
            for (scale in listOf(1f, 2f)) {
                DotTokenGalleryFixtureActivity.requestedFontScale = scale
                ActivityScenario.launch<DotTokenGalleryFixtureActivity>(
                    Intent(context, DotTokenGalleryFixtureActivity::class.java)
                ).use { scenario ->
                    for (width in listOf(240, 320, 400)) checkCase(scenario, width, 320, scale, "width")
                }
            }
        } finally { DotTokenGalleryFixtureActivity.requestedFontScale = 1f }
    }

    @Test fun landscapeActivityKeepsTargetsAndMessageInsideThreeWidthsAtDoubleFont() {
        DotTokenGalleryFixtureActivity.requestedFontScale = 2f
        try {
            ActivityScenario.launch<DotTokenGalleryFixtureActivity>(
                Intent(context, DotTokenGalleryFixtureActivity::class.java)
            ).use { scenario ->
                var original = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                scenario.onActivity { original = it.requestedOrientation }
                try {
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    await("Fixture Activity must actually enter landscape") {
                        var landscape = false
                        scenario.onActivity {
                            landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                                it.window.decorView.width > it.window.decorView.height
                        }
                        landscape
                    }
                    for (width in listOf(240, 320, 400)) checkCase(scenario, width, 240, 2f, "landscape")
                } finally {
                    scenario.onActivity { it.requestedOrientation = original }
                }
            }
        } finally { DotTokenGalleryFixtureActivity.requestedFontScale = 1f }
    }

    private fun checkCase(scenario: ActivityScenario<DotTokenGalleryFixtureActivity>, width: Int,
        height: Int, scale: Float, mode: String) {
        val label = "$mode-${width}dp-font-$scale"
        val measured = ConcurrentHashMap<String, Rect>()
        val textLayout = AtomicReference<TextLayoutResult>()
        val stopped = AtomicInteger()
        val collapsed = AtomicInteger()
        var density = 0f
        var stop = ""
        var collapse = ""
        var more = ""
        scenario.onActivity { activity ->
            density = activity.resources.displayMetrics.density
            assertEquals("Real context font scale $label", scale, activity.resources.configuration.fontScale, 0f)
            if (mode == "landscape") assertEquals(Configuration.ORIENTATION_LANDSCAPE,
                activity.resources.configuration.orientation)
            stop = activity.getString(R.string.nm_dot_chat_stop)
            collapse = activity.getString(R.string.nm_dot_chat_collapse)
            more = activity.getString(R.string.nm_more)
            activity.setContent { MinisTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                    key(label) {
                        Box(Modifier.size(width.dp, height.dp).onGloballyPositioned {
                            measured["panel"] = it.boundsInWindow()
                            val origin = it.localToScreen(Offset.Zero)
                            measured["screen"] = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                        }) {
                            DotForegroundChat(onCollapse = { collapsed.incrementAndGet() }, capsule = {
                                DotForegroundStatus(DotForegroundState(connected = true,
                                    mode = DotForegroundMode.Chat, operation = DotForegroundOperation.Replying),
                                    { stopped.incrementAndGet() })
                            }, history = {
                                LazyColumn(Modifier.fillMaxSize().onGloballyPositioned {
                                    measured["history"] = it.boundsInWindow()
                                }, verticalArrangement = Arrangement.Bottom) {
                                    item { DotMessageBubble(false, Modifier.onGloballyPositioned {
                                        measured["bubble"] = it.boundsInWindow()
                                    }) {
                                        Text("Next page", fontSize = DotTokens.Type.Care,
                                            lineHeight = DotTokens.Type.Care * 1.45f,
                                            onTextLayout = { textLayout.set(it) },
                                            modifier = Modifier.onGloballyPositioned {
                                                measured["text"] = it.boundsInWindow()
                                                val origin = it.localToWindow(Offset.Zero)
                                                measured["text_full"] = Rect(origin.x, origin.y,
                                                    origin.x + it.size.width, origin.y + it.size.height)
                                            })
                                    } }
                                }
                            }, controls = {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    DotDraftField("Draft", {}, activity.getString(R.string.nm_dot_chat_message),
                                        Modifier.weight(1f).onGloballyPositioned { measured["input"] = it.boundsInWindow() })
                                    IconButton({}, Modifier.size(DotTokens.TouchTarget).onGloballyPositioned {
                                        measured["send"] = it.boundsInWindow()
                                    }) { Icon(Icons.AutoMirrored.Filled.Send, activity.getString(R.string.nm_dot_chat_send)) }
                                }
                            }, menu = { dismiss ->
                                DropdownMenuItem(text = { Text("Fixture action") }, onClick = dismiss,
                                    modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
                            })
                        }
                    }
                }
            } }
        }
        await("Production content must lay out $label") { measured.size == 8 && textLayout.get() != null }
        instrumentation.waitForIdleSync()
        val evidence = File(context.getExternalFilesDir(null), "foreground-adaptive").apply { mkdirs() }
        var actions = emptyMap<String, NativeRect>()
        await("Three accessible production actions $label") {
            actions = actionBounds(listOf(stop, collapse, more))
            actions.size == 3
        }
        val record = "$label density=$density rects=$measured actions=$actions\n"
        File(evidence, "$label.txt").writeText(record)
        automation.takeScreenshot()?.let { bitmap ->
            try { File(evidence, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        val panel = measured.getValue("panel")
        val screen = measured.getValue("screen")
        assertEquals("Actual available width $record", width * density, panel.width, .5f)
        assertEquals("Actual available height $record", height * density, panel.height, .5f)
        val minimum = (48 * density).roundToInt()
        actions.forEach { (name, bounds) ->
            assertTrue("48dp action $name $record", bounds.width() >= minimum && bounds.height() >= minimum)
            assertTrue("Action entirely inside panel $name $record", bounds.left >= screen.left - .5f &&
                bounds.top >= screen.top - .5f && bounds.right <= screen.right + .5f && bounds.bottom <= screen.bottom + .5f)
        }
        val actionRects = actions.values.toList()
        for (i in actionRects.indices) for (j in i + 1 until actionRects.size) {
            assertFalse("Independent action hit bounds $record", NativeRect.intersects(actionRects[i], actionRects[j]))
        }
        for (name in listOf("history", "input", "send", "bubble", "text")) {
            val bounds = measured.getValue(name)
            assertTrue("$name inside panel $record", contains(panel, bounds))
        }
        val history = measured.getValue("history")
        val input = measured.getValue("input")
        assertTrue("Usable transcript $record", history.height >= 48 * density - .5f)
        assertTrue("Usable input height and width $record", input.height >= 48 * density - .5f && input.width >= 48 * density - .5f)
        assertTrue("Transcript does not overlap input $record", history.bottom <= input.top + .5f)
        assertTrue("Complete message inside transcript $record", contains(history, measured.getValue("text_full")))
        val visible = measured.getValue("text")
        val full = measured.getValue("text_full")
        assertEquals("Whole message width $record", full.width, visible.width, .5f)
        assertEquals("Whole message height $record", full.height, visible.height, .5f)
        assertFalse("Text layout must not clip internally $record", textLayout.get().hasVisualOverflow)
        assertTrue("Production Care text is measured $record", full.height >= 18 * scale * density - .5f)
        tap(actions.getValue(stop)); await("Stop callback $label") { stopped.get() == 1 }
        tap(actions.getValue(collapse)); await("Collapse callback $label") { collapsed.get() == 1 }
        tap(actions.getValue(more)); await("More reveals actual menu $label") { hasText("Fixture action") }
        assertEquals("Only one Stop action $label", 1, stopped.get())
        assertEquals("Only one Collapse action $label", 1, collapsed.get())
        File(evidence, "$label.txt").appendText("physicalStop=1 physicalCollapse=1 menuObserved=true\n")
        println("FOREGROUND_ADAPTIVE $record")
    }

    private fun contains(outer: Rect, inner: Rect) = inner.left >= outer.left - .5f &&
        inner.top >= outer.top - .5f && inner.right <= outer.right + .5f && inner.bottom <= outer.bottom + .5f

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(30)
        }
        fail(label)
    }

    @Suppress("DEPRECATION")
    private fun visitNodes(visit: (AccessibilityNodeInfo) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return
        fun walk(node: AccessibilityNodeInfo) {
            visit(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { walk(child) } finally { child.recycle() }
            }
        }
        try { if (root.packageName == context.packageName) walk(root) } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun actionBounds(labels: List<String>): Map<String, NativeRect> {
        val matches = labels.associateWith { mutableSetOf<NativeRect>() }
        visitNodes { node ->
            val label = node.contentDescription?.toString()
            if (label in labels && node.isVisibleToUser) {
                var action = AccessibilityNodeInfo.obtain(node)
                try {
                    while (!action.isClickable) {
                        val parent = action.parent ?: break
                        action.recycle(); action = parent
                    }
                    if (action.windowId == node.windowId && action.isEnabled && action.isVisibleToUser &&
                        action.isClickable && action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                        matches.getValue(requireNotNull(label)).add(NativeRect().also(action::getBoundsInScreen))
                    }
                } finally { action.recycle() }
            }
        }
        return matches.mapNotNull { (label, bounds) -> bounds.singleOrNull()?.let { label to it } }.toMap()
    }

    private fun hasText(text: String): Boolean {
        var found = false
        visitNodes { if (it.isVisibleToUser && it.text?.toString() == text) found = true }
        return found
    }

    private fun tap(bounds: NativeRect) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue("Physical touch injected", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }
}
