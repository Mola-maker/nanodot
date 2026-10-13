package io.github.nanomuse.dot.chat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect as NativeRect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.StreamingDelta
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotForegroundOperation
import io.github.nanomuse.dot.DotForegroundState
import io.github.nanomuse.dot.ui.DotTokenGalleryFixtureActivity
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real DotChatPanel, text layout, native swipe and PNGs; the local session never calls a provider. */
@RunWith(AndroidJUnit4::class)
class DotChatTranscriptFollowNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val replyId = "transcript-fixture-reply"

    // Catches a missing follow on same-count deltas/finalization or on a smaller host viewport.
    @Test fun stableReplyGrowthAndFinalizationKeepLastLineInsideTranscript() {
        val session = LocalSession(listOf(
            ChatMessage("selection", "user", "Selected text context"),
            ChatMessage(replyId, "assistant", "Starting reply", isStreaming = true),
        ))
        val observations = mutableListOf<Observation>()
        mount(session, width = 320, height = 280, scale = 1f).use { mounted ->
            settle(mounted)
            assertFalse("The short starting conversation fits without scrolling", read(mounted).forward)
            val initialReplySize = read(mounted).replySize
            capture(mounted, "growth-before")
            val grown = (1..12).joinToString("\n") { "Reply line $it" } + "\nLATEST STREAM LINE"
            instrumentation.runOnMainSync {
                session.deltas.value = mapOf(replyId to StreamingDelta(grown, emptyList(), false))
            }
            await("The production reply must lay out the grown delta") {
                read(mounted).replySize > initialReplySize
            }
            settle(mounted)
            observations += capture(mounted, "growth-streaming")
            instrumentation.runOnMainSync {
                session.messages.value = session.messages.value.map {
                    if (it.id == replyId) it.copy(content = "$grown\nLATEST FINAL LINE", isStreaming = false) else it
                }
                session.deltas.value = emptyMap()
                session.isStreaming.value = false
            }
            settle(mounted)
            observations += capture(mounted, "growth-finalized")
            instrumentation.runOnMainSync { mounted.height.value = 200 }
            await("The host-equivalent resize must reduce the real transcript viewport") {
                read(mounted).viewportEnd < observations.last().viewportEnd
            }
            settle(mounted)
            observations += capture(mounted, "growth-short-host")
            assertEquals("Delta/finalization retain message ids and count", listOf("selection", replyId),
                session.messages.value.map { it.id })
        }
        observations.forEach { observed ->
            assertTrue("Latest reply including its last line and bottom inset must be inside the transcript: $observed",
                observed.replySize > 0 && observed.replyBottom <= observed.viewportEnd + 1)
            assertFalse("Following the reply must leave no newer content below the viewport: $observed", observed.forward)
        }
    }

    // Catches interrupted older reading or a failure to resume follow after physically returning to the end.
    @Test fun physicalScrollAwayKeepsReadingAnchorDuringReplyGrowth() {
        val session = LocalSession((0..19).map {
            ChatMessage("older-$it", if (it % 2 == 0) "user" else "assistant", "Older message $it\nRetained reading line")
        } + ChatMessage(replyId, "assistant", "Starting reply", isStreaming = true))
        mount(session, width = 240, height = 320, scale = 2f).use { mounted ->
            settle(mounted)
            // Setup only: the behavior under test starts with a real touch away from this end position.
            instrumentation.runOnMainSync {
                mounted.scope.launch {
                    val state = requireNotNull(mounted.list.get())
                    state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                }
            }
            await("Fixture starts at the newest message") { !read(mounted).forward }
            settle(mounted)
            capture(mounted, "reading-at-end")
            repeat(3) {
                swipeToOlder(nativeTranscriptBounds(mounted))
                settle(mounted)
            }
            val before = capture(mounted, "reading-scrolled-away")
            assertTrue("Native swipes must move the reading position away from the end: $before", before.forward)
            assertEquals("The active reply must be outside the reading viewport", 0, before.replySize)
            instrumentation.runOnMainSync {
                session.deltas.value = mapOf(replyId to StreamingDelta(
                    (1..14).joinToString("\n") { "New reply line $it" }, emptyList(), false))
            }
            settle(mounted)
            val grown = capture(mounted, "reading-delta-grown")
            instrumentation.runOnMainSync {
                session.messages.value = session.messages.value.map {
                    if (it.id == replyId) it.copy(content = session.deltas.value.getValue(replyId).content,
                        isStreaming = false) else it
                }
                session.deltas.value = emptyMap()
                session.isStreaming.value = false
            }
            settle(mounted)
            val final = capture(mounted, "reading-finalized")
            for (after in listOf(grown, final)) {
                assertEquals("Older reading item stays selected: $before -> $after", before.firstIndex, after.firstIndex)
                assertEquals("Older reading offset stays fixed: $before -> $after", before.firstOffset, after.firstOffset)
                assertTrue("Reading remains away from the latest reply: $after", after.forward)
            }
            val messageIds = session.messages.value.map { it.id }
            var latestSwipes = 0
            while (read(mounted).forward && latestSwipes < 24) {
                swipeToLatest(nativeTranscriptBounds(mounted))
                settle(mounted)
                latestSwipes++
            }
            val returned = capture(mounted, "reading-returned-to-end")
            assertTrue("Returning from older history requires a physical swipe", latestSwipes > 0)
            assertFalse("Bounded native swipes must reach the newest end: $returned", returned.forward)
            val resumedText = session.messages.value.last().content + "\n" +
                (15..22).joinToString("\n") { "Resumed reply line $it" } + "\nLATEST RESUMED STREAM LINE"
            instrumentation.runOnMainSync {
                session.messages.value = session.messages.value.map {
                    if (it.id == replyId) it.copy(isStreaming = true) else it
                }
                session.isStreaming.value = true
                session.deltas.value = mapOf(replyId to StreamingDelta(resumedText, emptyList(), false))
            }
            await("The stable-id resumed delta must grow the real reply layout") {
                read(mounted).replySize > returned.replySize
            }
            settle(mounted)
            val resumedStreaming = capture(mounted, "reading-resumed-streaming")
            instrumentation.runOnMainSync {
                session.messages.value = session.messages.value.map {
                    if (it.id == replyId) it.copy(content = "$resumedText\nLATEST RESUMED FINAL LINE",
                        isStreaming = false) else it
                }
                session.deltas.value = emptyMap()
                session.isStreaming.value = false
            }
            await("Resumed final text must grow the real reply layout") {
                read(mounted).replySize > resumedStreaming.replySize
            }
            settle(mounted)
            val resumedFinal = capture(mounted, "reading-resumed-finalized")
            assertEquals("Resumed delta/finalization retain every message id and count", messageIds,
                session.messages.value.map { it.id })
            for (after in listOf(returned, resumedStreaming, resumedFinal)) {
                assertTrue("Physical return must resume following the last line inside the transcript: $after",
                    after.replySize > 0 && after.replyBottom <= after.viewportEnd + 1)
                assertFalse("Resumed following must leave no newer content below the viewport: $after", after.forward)
            }
        }
    }

    private class LocalSession(initial: List<ChatMessage>) : DotChatSession {
        override val messages = MutableStateFlow(initial)
        override val inputText = MutableStateFlow("")
        override val isStreaming = MutableStateFlow(true)
        override val error = MutableStateFlow<String?>(null)
        override val configured = MutableStateFlow<String?>("local-transcript-fixture")
        override val deltas = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
        override val consumedAttachments = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun input(text: String) { inputText.value = text }
        override fun stage(file: File): DotOwnedAttachment? = kotlin.error("Unexpected fixture attachment")
        override fun remove(id: String) = Unit
        override fun send(text: String) = kotlin.error("This layout fixture must not send")
        override fun cancel() = Unit
        override fun retry() = kotlin.error("This layout fixture must not retry")
    }

    private class Mounted(val scenario: ActivityScenario<DotTokenGalleryFixtureActivity>,
        val adapter: DotChatAdapter, val height: androidx.compose.runtime.MutableState<Int>,
        val list: AtomicReference<LazyListState>, val panel: AtomicReference<Rect>,
        val scope: CoroutineScope) : AutoCloseable {
        override fun close() {
            scope.cancel()
            scenario.close()
            DotTokenGalleryFixtureActivity.requestedFontScale = 1f
        }
    }

    private fun mount(session: LocalSession, width: Int, height: Int, scale: Float): Mounted {
        DotTokenGalleryFixtureActivity.requestedFontScale = scale
        val adapter = DotChatAdapter({ it }, { session }, { error("This fixture never captures") })
        adapter.bind("local-transcript-fixture")
        val mounted = Mounted(ActivityScenario.launch<DotTokenGalleryFixtureActivity>(Intent(context, DotTokenGalleryFixtureActivity::class.java)),
            adapter, mutableStateOf(height), AtomicReference(), AtomicReference(),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        try {
            mounted.scenario.onActivity { activity ->
                assertEquals("Actual Android font scale", scale, activity.resources.configuration.fontScale, 0f)
                activity.setContent { MinisTheme {
                    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.size(width.dp, mounted.height.value.dp).onGloballyPositioned {
                            val origin = it.localToScreen(Offset.Zero)
                            mounted.panel.set(Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height))
                        }) {
                            DotChatPanel(adapter, {}, {}, foreground = DotForegroundState(connected = true,
                                mode = DotForegroundMode.Chat, operation = DotForegroundOperation.Replying),
                                onTranscriptState = { mounted.list.set(it) })
                        }
                    }
                } }
            }
            await("Actual DotChatPanel must finish its first layout") {
                var laidOut = false
                instrumentation.runOnMainSync {
                    laidOut = mounted.panel.get() != null &&
                        mounted.list.get()?.layoutInfo?.totalItemsCount == session.messages.value.size + 1
                }
                laidOut
            }
            settle(mounted)
            return mounted
        } catch (failure: Throwable) {
            mounted.close()
            throw failure
        }
    }

    private data class Observation(val firstIndex: Int, val firstOffset: Int, val viewportEnd: Int,
        val replyBottom: Int, val replySize: Int, val forward: Boolean, val scrolling: Boolean)

    private fun read(mounted: Mounted): Observation {
        var observed: Observation? = null
        instrumentation.runOnMainSync {
            val state = requireNotNull(mounted.list.get())
            val layout = state.layoutInfo
            val reply = layout.visibleItemsInfo.singleOrNull { it.key == replyId }
            observed = Observation(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
                layout.viewportEndOffset, reply?.let { it.offset + it.size } ?: Int.MAX_VALUE,
                reply?.size ?: 0, state.canScrollForward, state.isScrollInProgress)
        }
        return requireNotNull(observed)
    }

    private fun settle(mounted: Mounted) {
        instrumentation.waitForIdleSync()
        var previous: Observation? = null
        var unchangedSince = SystemClock.uptimeMillis()
        await("The native transcript must settle without an active scroll") {
            val current = read(mounted)
            val now = SystemClock.uptimeMillis()
            if (current != previous || current.scrolling) unchangedSince = now
            previous = current
            !current.scrolling && now - unchangedSince >= 300
        }
    }

    private fun capture(mounted: Mounted, name: String): Observation {
        val observed = read(mounted)
        val evidence = File(context.getExternalFilesDir(null), "transcript-follow").apply { check(mkdirs() || isDirectory) }
        val tree = StringBuilder()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = NativeRect().also(node::getBoundsInScreen)
            tree.append(" ".repeat(depth)).append("text=${node.text} visible=${node.isVisibleToUser} scrollable=${node.isScrollable} bounds=$bounds\n")
            for (index in 0 until node.childCount) node.getChild(index)?.let {
                try { walk(it, depth + 1) } finally { it.recycle() }
            }
        }
        awaitNativeAppWindow("evidence $name").let {
            try { walk(it, 0) } finally { it.recycle() }
        }
        File(evidence, "$name.txt").writeText("nativeFixture=true providerCalls=0 panel=${mounted.panel.get()}\n$observed\n$tree")
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Required native PNG is unavailable: $name" }
        try {
            File(evidence, "$name.png").outputStream().use {
                assertTrue("Required PNG must encode: $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        println("TRANSCRIPT_FOLLOW $name $observed evidence=${evidence.absolutePath}")
        return observed
    }

    private fun nativeTranscriptBounds(mounted: Mounted): NativeRect {
        val matches = mutableListOf<NativeRect>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isScrollable && node.isVisibleToUser) {
                val bounds = NativeRect().also(node::getBoundsInScreen)
                val panel = requireNotNull(mounted.panel.get())
                if (bounds.width() > 0 && bounds.height() > 0 && bounds.left >= panel.left - 1 &&
                    bounds.top >= panel.top - 1 && bounds.right <= panel.right + 1 && bounds.bottom <= panel.bottom + 1) {
                    matches += bounds
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let {
                try { walk(it) } finally { it.recycle() }
            }
        }
        awaitNativeAppWindow("physical transcript swipe").let {
            try { walk(it) } finally { it.recycle() }
        }
        // A BasicTextField can also expose scroll semantics; the transcript is the tall native viewport.
        val transcript = requireNotNull(matches.maxByOrNull { it.height() }) { "Actual native transcript is missing" }
        assertTrue("Native transcript has a usable swipe viewport", transcript.height() > 0)
        return transcript
    }

    private fun awaitNativeAppWindow(purpose: String): AccessibilityNodeInfo {
        var root: AccessibilityNodeInfo? = null
        await("Visible native Activity window for ${context.packageName} must become ready for $purpose") {
            if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
            val candidate = automation.rootInActiveWindow
            if (candidate != null && candidate.packageName?.toString() == context.packageName &&
                candidate.isVisibleToUser) {
                root = candidate
                true
            } else {
                candidate?.recycle()
                false
            }
        }
        return requireNotNull(root)
    }

    private fun swipeToOlder(bounds: NativeRect) = swipe(bounds, .20f, .80f)

    private fun swipeToLatest(bounds: NativeRect) = swipe(bounds, .80f, .20f)

    private fun swipe(bounds: NativeRect, fromFraction: Float, toFraction: Float) {
        val down = SystemClock.uptimeMillis()
        val from = bounds.top + bounds.height() * fromFraction
        val to = bounds.top + bounds.height() * toFraction
        fun event(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Physical transcript swipe injected", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, from)
        try {
            repeat(12) { index ->
                SystemClock.sleep(25)
                event(MotionEvent.ACTION_MOVE, from + (to - from) * (index + 1) / 12)
            }
            // Hold at the endpoint so a fling cannot change the saved reading anchor later.
            SystemClock.sleep(180)
            event(MotionEvent.ACTION_MOVE, to)
        } finally { event(MotionEvent.ACTION_UP, to) }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail(label)
    }
}
