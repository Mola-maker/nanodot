package io.github.nanomuse.dot.chat

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect as NativeRect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
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
    private val sceneSequence = AtomicInteger()
    private val evidenceRun by lazy {
        File(context.getExternalFilesDir(null), "foreground-adaptive/${UUID.randomUUID()}").apply {
            assertTrue("Create unique adaptive evidence directory", mkdirs())
        }
    }

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
        val scene = sceneSequence.incrementAndGet()
        val markerCode = mutableStateOf(scene * 4)
        var displaySize = Point()
        var orientation = Configuration.ORIENTATION_UNDEFINED
        var density = 0f
        var stop = ""
        var collapse = ""
        var more = ""
        scenario.onActivity { activity ->
            density = activity.resources.displayMetrics.density
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealSize(displaySize)
            orientation = activity.resources.configuration.orientation
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
                    Column(Modifier.align(Alignment.BottomStart)) {
                        Text(label, fontSize = 12.sp, maxLines = 1)
                        Canvas(Modifier.size(64.dp, 16.dp).onGloballyPositioned {
                            val origin = it.localToScreen(Offset.Zero)
                            measured["marker"] = Rect(origin.x, origin.y,
                                origin.x + it.size.width, origin.y + it.size.height)
                        }) {
                            val colors = markerColors(markerCode.value)
                            for (cell in colors.indices) {
                                drawRect(Color(colors[cell]),
                                    topLeft = Offset((cell % 4) * size.width / 4, (cell / 4) * size.height / 2),
                                    size = Size(size.width / 4, size.height / 2))
                            }
                        }
                    }
                }
            } }
        }
        await("Production content must lay out $label") { measured.size == 9 && textLayout.get() != null }
        instrumentation.waitForIdleSync()
        val evidence = evidenceRun
        var actions = emptyMap<String, NativeRect>()
        await("Three accessible production actions $label") {
            actions = actionBounds(listOf(stop, collapse, more))
            actions.size == 3
        }
        val record = "$label density=$density rects=$measured actions=$actions\n"
        File(evidence, "$label.txt").writeText(record)
        val marker = measured.getValue("marker")
        val screen = measured.getValue("screen")
        assertFalse("Evidence marker must not change or cover production panel $label", marker.overlaps(screen))
        val frame = FrameSpec(displaySize.x, displaySize.y, orientation, marker, markerColors(markerCode.value))
        captureEvidence(File(evidence, "$label.png"), frame, measured,
            listOf(stop, collapse, more)) { !hasText("Fixture action") }
        val panel = measured.getValue("panel")
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
        scenario.onActivity { markerCode.value = scene * 4 + 1 }
        captureEvidence(File(evidence, "$label-menu.png"),
            frame.copy(colors = markerColors(scene * 4 + 1)), measured,
            listOf(stop, collapse, more)) { hasText("Fixture action") }
        var menuAction: NativeRect? = null
        await("Actual accessible menu item $label") {
            menuAction = textActionBounds("Fixture action")
            menuAction != null
        }
        tap(requireNotNull(menuAction))
        await("Scene handoff must not retain Fixture action $label") { !hasText("Fixture action") }
        scenario.onActivity { markerCode.value = scene * 4 + 2 }
        captureEvidence(File(evidence, "$label-dismissed.png"),
            frame.copy(colors = markerColors(scene * 4 + 2)), measured,
            listOf(stop, collapse, more)) { !hasText("Fixture action") }
        File(evidence, "$label.txt").appendText(
            "physicalStop=1 physicalCollapse=1 menuObserved=true physicalMenuDismiss=1 " +
                "scene=$scene orientation=$orientation screenshot=${displaySize.x}x${displaySize.y} " +
                "currentFrames=2 pngRoundTripVerified=true\n")
        println("FOREGROUND_ADAPTIVE $record")
    }

    private data class FrameSpec(val width: Int, val height: Int, val orientation: Int,
        val marker: Rect, val colors: List<Int>)

    // Two asymmetric anchors and six scene/phase bits. The marker is outside production chrome.
    private fun markerColors(code: Int): List<Int> = listOf(0xffe4005a.toInt(), 0xff00a86b.toInt()) +
        (0 until 6).map { if (code and (1 shl it) != 0) 0xff000000.toInt() else 0xffffffff.toInt() }

    private fun currentFrame(bitmap: Bitmap?, expected: FrameSpec): Boolean {
        if (bitmap == null || bitmap.width != expected.width || bitmap.height != expected.height) return false
        if (expected.orientation == Configuration.ORIENTATION_LANDSCAPE && bitmap.width <= bitmap.height) return false
        if (expected.orientation == Configuration.ORIENTATION_PORTRAIT && bitmap.width >= bitmap.height) return false
        val marker = expected.marker
        if (expected.colors.size != 8 || marker.width < 16 || marker.height < 8 ||
            marker.left < 0 || marker.top < 0 || marker.right > bitmap.width || marker.bottom > bitmap.height) return false
        // Scan cell interiors, not just centers: a still-rotating/scaled marker must not pass.
        for (cell in expected.colors.indices) {
            val left = (marker.left + (cell % 4) * marker.width / 4).roundToInt() + 1
            val right = (marker.left + (cell % 4 + 1) * marker.width / 4).roundToInt() - 1
            val top = (marker.top + (cell / 4) * marker.height / 2).roundToInt() + 1
            val bottom = (marker.top + (cell / 4 + 1) * marker.height / 2).roundToInt() - 1
            for (y in top until bottom) for (x in left until right) {
                val actual = bitmap.getPixel(x, y)
                val want = expected.colors[cell]
                for (shift in 0..24 step 8) {
                    if (kotlin.math.abs(((actual ushr shift) and 255) - ((want ushr shift) and 255)) > 3) return false
                }
            }
        }
        return true
    }

    private fun captureEvidence(file: File, expected: FrameSpec,
        measured: ConcurrentHashMap<String, Rect>, labels: List<String>, sceneReady: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var previousLayout: Map<String, Rect>? = null
        var previousActions: Map<String, NativeRect>? = null
        var consecutive = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val bitmap = automation.takeScreenshot()
            assertNotNull("Required screenshot ${file.name}", bitmap)
            val captured = requireNotNull(bitmap)
            try {
                val layout = measured.toMap()
                val actions = actionBounds(labels)
                val valid = currentFrame(captured, expected) && layout.size == 9 &&
                    actions.size == labels.size && sceneReady()
                if (valid) {
                    consecutive = if (layout == previousLayout && actions == previousActions) consecutive + 1 else 1
                    previousLayout = layout
                    previousActions = actions
                    if (consecutive >= 2) {
                        writeVerifiedPng(captured, expected, file)
                        return
                    }
                } else {
                    consecutive = 0
                    previousLayout = null
                    previousActions = null
                }
            } finally { captured.recycle() }
            SystemClock.sleep(30)
        }
        fail("Two current, stable compositor frames required ${file.name} expected=$expected")
    }

    private fun writeVerifiedPng(bitmap: Bitmap, expected: FrameSpec, file: File,
        encode: (Bitmap, OutputStream) -> Boolean = { image, stream ->
            image.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }) {
        val pending = File(file.parentFile, "${file.name}.pending")
        var saved = false
        try {
            assertTrue("Only current frames may become evidence ${file.name}", currentFrame(bitmap, expected))
            assertTrue("PNG compression must succeed ${file.name}", pending.outputStream().use { encode(bitmap, it) })
            assertTrue("PNG must not be empty ${file.name}", pending.length() > 0)
            val decoded = BitmapFactory.decodeFile(pending.absolutePath)
            assertNotNull("PNG must decode ${file.name}", decoded)
            try {
                assertTrue("Decoded PNG must retain current frame ${file.name}", currentFrame(decoded, expected))
                assertTrue("Lossless PNG must retain every captured pixel ${file.name}",
                    requireNotNull(decoded).sameAs(bitmap))
            } finally { decoded?.recycle() }
            assertTrue("Commit verified PNG ${file.name}", pending.renameTo(file))
            saved = true
        } finally {
            pending.delete()
            if (!saved) file.delete()
        }
    }

    @Test fun evidenceOracleRejectsPreviousSceneRotationNullAndBrokenPng() {
        // Hand-painted literal pattern, independent of markerColors and Compose rendering.
        val colors = listOf(0xffe4005a.toInt(), 0xff00a86b.toInt(), 0xff000000.toInt(),
            0xffffffff.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt())
        val expected = FrameSpec(96, 64, Configuration.ORIENTATION_LANDSCAPE, Rect(8f, 8f, 72f, 24f), colors)
        val current = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val previous = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val altered = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val rotated = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        val shifted = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val file = File(evidenceRun, "oracle.png")
        try {
            current.eraseColor(0xffffffff.toInt())
            for (cell in colors.indices) {
                for (y in 8 + (cell / 4) * 8 until 16 + (cell / 4) * 8) {
                    for (x in 8 + (cell % 4) * 16 until 24 + (cell % 4) * 16) current.setPixel(x, y, colors[cell])
                }
            }
            assertTrue("Independent current frame accepted", currentFrame(current, expected))
            previous.eraseColor(0xffffffff.toInt())
            shifted.eraseColor(0xffffffff.toInt())
            for (y in 0 until 64) for (x in 0 until 96) {
                previous.setPixel(x, y, current.getPixel(x, y))
                altered.setPixel(x, y, current.getPixel(x, y))
                rotated.setPixel(63 - y, x, current.getPixel(x, y))
                if (x < 94) shifted.setPixel(x + 2, y, current.getPixel(x, y))
            }
            // Previous phase differs in one scene bit, even with identical dimensions/bounds.
            for (y in 16 until 24) for (x in 8 until 24) previous.setPixel(x, y, 0xffffffff.toInt())
            // Same dimensions and marker, but one production-region pixel changed outside the marker.
            altered.setPixel(40, 40, 0xff000000.toInt())
            assertTrue("Outside-marker mutation still has the current scene marker", currentFrame(altered, expected))
            assertFalse("Previous scene rejected", currentFrame(previous, expected))
            assertFalse("Rotated screenshot rejected", currentFrame(rotated, expected))
            assertFalse("Orientation mismatch rejected", currentFrame(current,
                expected.copy(orientation = Configuration.ORIENTATION_PORTRAIT)))
            assertFalse("Transformed marker rejected", currentFrame(shifted, expected))
            assertFalse("Null capture rejected", currentFrame(null, expected))
            writeVerifiedPng(current, expected, file)
            assertTrue("Verified PNG retained", file.isFile && file.length() > 0)
            expectEvidenceFailure("Stale pixels cannot be saved") { writeVerifiedPng(previous, expected, file) }
            expectEvidenceFailure("False encoder result rejected") {
                writeVerifiedPng(current, expected, file) { _, _ -> false }
            }
            expectEvidenceFailure("Empty encoder output rejected") {
                writeVerifiedPng(current, expected, file) { _, _ -> true }
            }
            expectEvidenceFailure("Undecodable output rejected") {
                writeVerifiedPng(current, expected, file) { _, stream -> stream.write(byteArrayOf(1, 2, 3)); true }
            }
            expectEvidenceFailure("PNG of previous scene rejected after decode") {
                writeVerifiedPng(current, expected, file) { _, stream ->
                    previous.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
            val alteredPng = ByteArrayOutputStream().use { stream ->
                assertTrue("Outside-marker mutation encodes a valid PNG",
                    altered.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
            val alteredDecoded = requireNotNull(BitmapFactory.decodeByteArray(alteredPng, 0, alteredPng.size))
            try {
                assertTrue("Altered PNG retains dimensions and marker", currentFrame(alteredDecoded, expected))
                assertEquals("Altered PNG retains the outside-marker mutation", 0xff000000.toInt(),
                    alteredDecoded.getPixel(40, 40))
            } finally { alteredDecoded.recycle() }
            expectEvidenceFailure("Valid PNG with changed pixels outside the marker rejected") {
                writeVerifiedPng(current, expected, file) { _, stream ->
                    stream.write(alteredPng)
                    true
                }
            }
            assertFalse("Rejected encoders leave no evidence", file.exists())
            File(evidenceRun, "oracle.txt").writeText(
                "independentCurrent=true previousRejected=true rotationRejected=true " +
                    "transformedRejected=true nullRejected=true compressionRejected=true " +
                    "emptyRejected=true decodeRejected=true decodedPreviousRejected=true outsideMarkerMutationRejected=true\n")
        } finally {
            listOf(current, previous, altered, rotated, shifted).forEach { it.recycle() }
            file.delete()
        }
    }

    private fun expectEvidenceFailure(label: String, action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: AssertionError) { rejected = true }
        assertTrue(label, rejected)
    }

    @Suppress("DEPRECATION")
    private fun textActionBounds(text: String): NativeRect? {
        val bounds = mutableSetOf<NativeRect>()
        visitNodes { node ->
            if (node.isVisibleToUser && node.text?.toString() == text) {
                var action = AccessibilityNodeInfo.obtain(node)
                try {
                    while (!action.isClickable) {
                        val parent = action.parent ?: break
                        action.recycle(); action = parent
                    }
                    if (action.windowId == node.windowId && action.isEnabled && action.isVisibleToUser &&
                        action.isClickable && action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                        bounds.add(NativeRect().also(action::getBoundsInScreen))
                    }
                } finally { action.recycle() }
            }
        }
        return bounds.singleOrNull()
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
