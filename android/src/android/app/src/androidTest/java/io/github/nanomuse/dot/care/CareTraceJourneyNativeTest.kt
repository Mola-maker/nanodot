package io.github.nanomuse.dot.care

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.DotColors
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotForegroundOperation
import io.github.nanomuse.dot.DotForegroundState
import io.github.nanomuse.dot.overlay.DotNativeServiceFixture
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.screen.SceneKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Public controlled host and local planner; real Care owners, windows, input and display pixels.
 * Catches a static-only border, missing/stuck cue, Repeat planning, animation crediting a step,
 * or Stop allowing old frames/results to return. Not provider or third-party-app acceptance.
 * The coordinator may record the same run; every saved frame originates in takeScreenshot.
 */
@RunWith(AndroidJUnit4::class)
class CareTraceJourneyNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val nativeService = DotNativeServiceFixture()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private lateinit var session: CareSession
    private lateinit var window: CareOverlayWindow
    private lateinit var evidence: File
    private var frameNumber = 0
    private var lastAdmission = ""
    private val instruction = "Touch the fixture target — 点击目标"

    @Before fun connect() = nativeService.connect()
    @After fun restoreService() = nativeService.close()

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(message: String, timeout: Long = 5000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail(message)
    }
    private fun RectPx.rect() = Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

    @Suppress("DEPRECATION")
    private fun visibleBounds(label: String, inside: Rect? = null): Rect? {
        automation.clearCache()
        fun visit(node: AccessibilityNodeInfo): Rect? {
            try {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && !bounds.isEmpty &&
                    (node.text?.toString() == label || node.contentDescription?.toString() == label) &&
                    (inside == null || inside.contains(bounds))) return bounds
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { visit(it) }?.let { return it }
                }
                return null
            } finally { node.recycle() }
        }
        val windows = automation.windows
        return try { windows.firstNotNullOfOrNull { it.root?.let(::visit) } }
        finally { windows.forEach { it.recycle() } }
    }
    private val nativeInject by lazy {
        UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
    }
    private fun tap(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Touch dispatch must not wait out the animation",
                nativeInject.invoke(automation, event, true, false) as Boolean) }
            finally { event.recycle() }
        }
    }
    private fun click(resource: Int) {
        val label = nativeService.service().getString(resource)
        var bounds: Rect? = null
        await("Visible Care control must be reachable: $label") {
            bounds = cardBounds()?.let { visibleBounds(label, it) }; bounds != null
        }
        tap(requireNotNull(bounds))
    }
    private fun cardBounds(): Rect? = main {
        val field = CareOverlayWindow::class.java.getDeclaredField("card").apply { isAccessible = true }
        val card = field.get(window) as? View ?: return@main null
        if (!card.isAttachedToWindow || card.width <= 0 || card.height <= 0) return@main null
        val location = IntArray(2); card.getLocationOnScreen(location)
        Rect(location[0], location[1], location[0] + card.width, location[1] + card.height)
    }
    private data class Geometry(val target: Rect, val cue: Rect, val card: Rect, val host: Rect)
    private fun layoutHost(): RectPx? = CareOverlayWindow::class.java.getDeclaredMethod("layoutViewport")
        .apply { isAccessible = true }.invoke(window) as? RectPx
    private fun geometry(): Geometry? {
        val measured = main {
            val target = window.focus.bounds ?: return@main null
            val host = layoutHost() ?: return@main null
            assertTrue("Fixture target must be wholly inside the actual safe layout host",
                host.rect().contains(target.rect()))
            // Read measured placement only; timing/rendering assertions use display pixels.
            val field = CareFocusLayer::class.java.getDeclaredField("cueBounds").apply { isAccessible = true }
            val cue = field.get(window.focus) as? RectPx ?: return@main null
            Triple(target.rect(), cue.rect(), host.rect())
        } ?: return null
        return Geometry(measured.first, measured.second, cardBounds() ?: return null, measured.third).also {
            assertTrue("Cue remains inside current host", it.host.contains(it.cue))
            assertFalse("Cue avoids target", Rect.intersects(it.cue, it.target))
            assertFalse("Cue avoids complete chat card", Rect.intersects(it.cue, it.card))
        }
    }
    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    /** Read-only diagnostics: distinguish controller admission, cue measurement and node bounds. */
    private fun admissionDiagnostic(predicate: String): String = main {
        val service = nativeService.service()
        if (!::session.isInitialized || !::window.isInitialized) return@main "owner_not_created $predicate"
        val state = session.controller.state.value
        val focus = window.focus
        val playback = field(focus, "playback") as CareInstructionPlayback
        fun viewDescription(view: View?): String {
            if (view == null) return "null"
            val location = IntArray(2); view.getLocationOnScreen(location)
            return "attached=${view.isAttachedToWindow},shown=${view.isShown},layoutRequested=${view.isLayoutRequested}," +
                "bounds=${location.toList()},${view.width}x${view.height},measured=${view.measuredWidth}x${view.measuredHeight}"
        }
        val layoutHost = layoutHost()
        buildString {
            append("predicate=$predicate state=${state.state} error=${state.errorCode} visible=${session.visible.value} editing=${session.editingGoal.value}")
            append(" generation=${session.controller.generation} scene=${service.dotScreen.currentSceneKey()} proposalScene=${state.proposal?.scene}")
            append(" host=${service.dotScreen.hostViewport()} layoutHost=$layoutHost target=${focus.bounds} cue=${field(focus, "cueBounds")}")
            append(" instructionId=${field(window, "instructionId")} playbackIdentity=${playback.identity} cueStarted=${playback.startedAt}")
            append(" hasText=${field(focus, "instructionText") != null} hasLayout=${field(focus, "instructionLayout") != null}")
            append(" card=[${viewDescription(field(window, "card") as? View)}] focusView=[${viewDescription(focus.view)}]")
            append(" density=${service.resources.displayMetrics.density} fontScale=${service.resources.configuration.fontScale}")
        }
    }
    private fun recordAdmission(predicate: String) {
        val row = admissionDiagnostic(predicate)
        if (row == lastAdmission) return
        lastAdmission = row
        File(evidence, "admission.txt").appendText("uptime=${SystemClock.uptimeMillis()} $row\n")
    }
    @Suppress("DEPRECATION")
    private fun saveFailureWindows() {
        automation.clearCache()
        val rows = StringBuilder()
        val windows = automation.windows
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            try {
                val bounds = Rect().also(node::getBoundsInScreen)
                // Never dump arbitrary app text. Fixed fixture labels identify the relevant nodes.
                val value = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                val marker = when {
                    value == instruction -> "exact_instruction"
                    value.contains(instruction) -> "contains_instruction"
                    value in setOf("CARE_TOUCH_TARGET", "CARE_TOUCH_PENDING", "CARE_TOUCH_RECEIVED") -> value
                    description == "CARE_TOUCH_TARGET" -> "target_description"
                    else -> "other"
                }
                rows.append("depth=$depth bounds=$bounds visible=${node.isVisibleToUser} class=${node.className} " +
                    "marker=$marker textLength=${value.length} descriptionLength=${description.length}\n")
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
            } finally { node.recycle() }
        }
        try {
            for (native in windows) {
                val bounds = Rect().also(native::getBoundsInScreen)
                rows.append("window=${native.id} type=${native.type} focused=${native.isFocused} bounds=$bounds\n")
                native.root?.let { root ->
                    if (root.packageName?.toString() in setOf(instrumentation.context.packageName, instrumentation.targetContext.packageName))
                        visit(root, 0)
                    else root.recycle()
                }
            }
        } finally { windows.forEach { it.recycle() } }
        File(evidence, "failure-windows.txt").writeText(rows.toString())
    }
    private fun save(label: String, bitmap: Bitmap) {
        val name = "%03d-%s.png".format(frameNumber++, label)
        File(evidence, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun near(a: Int, b: Int, tolerance: Int = 12) =
        abs(Color.red(a) - Color.red(b)) <= tolerance &&
            abs(Color.green(a) - Color.green(b)) <= tolerance &&
            abs(Color.blue(a) - Color.blue(b)) <= tolerance
    private data class Frame(val at: Long, val border: BooleanArray, val cueInk: Int, val textInk: Int,
        val crop: Bitmap) : AutoCloseable {
        val outlined get() = border.count { it }
        override fun close() = crop.recycle()
    }
    private fun saveDisplay(label: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { save(label, bitmap) } finally { bitmap.recycle() }
    }
    private data class Arc(val start: Int, val end: Int)
    private fun clockwiseDistance(from: Int, to: Int, size: Int) = (to - from + size) % size
    private fun singleArc(border: BooleanArray): Arc? {
        val starts = border.indices.filter { border[it] && !border[(it + border.size - 1) % border.size] }
        val ends = border.indices.filter { border[it] && !border[(it + 1) % border.size] }
        return if (starts.size == 1 && ends.size == 1) Arc(starts.single(), ends.single()) else null
    }
    /** Straight perimeter runs clockwise; no production Path, Timeline or placement helper. */
    private fun borderSamples(target: Rect, density: Float): List<Pair<Int, Int>> {
        val inset = (1.5f * density).roundToInt()
        val corner = (12f * density).roundToInt()
        return buildList {
            for (x in target.left + corner until target.right - corner step 3) add(x to target.top + inset)
            for (y in target.top + corner until target.bottom - corner step 3) add(target.right - inset to y)
            for (x in target.right - corner downTo target.left + corner step 3) add(x to target.bottom - inset)
            for (y in target.bottom - corner downTo target.top + corner step 3) add(target.left + inset to y)
        }
    }
    private fun capture(g: Geometry, baseline: Bitmap, samples: List<Pair<Int, Int>>, blue: Int, text: Int): Frame {
        val at = SystemClock.uptimeMillis()
        val shot = requireNotNull(automation.takeScreenshot()) { "Actual display screenshot is required" }
        try {
            assertEquals(baseline.width, shot.width); assertEquals(baseline.height, shot.height)
            assertTrue(Rect(0, 0, shot.width, shot.height).contains(g.cue))
            val border = samples.map { (x, y) -> near(shot.getPixel(x, y), blue) }.toBooleanArray()
            var ink = 0; var glyphs = 0
            for (y in g.cue.top until g.cue.bottom step 2) for (x in g.cue.left until g.cue.right step 2) {
                val current = shot.getPixel(x, y)
                if (!near(current, baseline.getPixel(x, y), 8)) {
                    ink++
                    if (near(current, text)) glyphs++
                }
            }
            val crop = Rect(g.target).apply { union(g.cue); inset(-4, -4); intersect(0, 0, shot.width, shot.height) }
            return Frame(at, border, ink, glyphs,
                Bitmap.createBitmap(shot, crop.left, crop.top, crop.width(), crop.height()))
        } finally { shot.recycle() }
    }
    private fun retain(label: String, frame: Frame, origin: Long) {
        save(label, frame.crop)
        val arc = singleArc(frame.border)
        File(evidence, "frames.tsv").appendText("$label\t${frame.at}\t${frame.at-origin}\t${frame.outlined}\t${frame.border.size}\t${frame.cueInk}\t${frame.textInk}\t${arc?.start}\t${arc?.end}\n")
    }
    private fun awaitFrame(label: String, g: Geometry, baseline: Bitmap, samples: List<Pair<Int, Int>>,
        blue: Int, text: Int, origin: Long, timeout: Long = 5000, condition: (Frame) -> Boolean): Frame {
        val deadline = SystemClock.uptimeMillis() + timeout
        var diagnostic = "none"
        while (SystemClock.uptimeMillis() < deadline) {
            val frame = capture(g, baseline, samples, blue, text)
            if (condition(frame)) return frame
            diagnostic = "outline=${frame.outlined}/${frame.border.size} cue=${frame.cueInk} text=${frame.textInk}"
            if (SystemClock.uptimeMillis() + 50 >= deadline) retain("timeout-$label", frame, origin)
            frame.close(); SystemClock.sleep(20)
        }
        throw AssertionError("$label: $diagnostic")
    }
    private fun assertNoProgress(plans: Int) = main {
        assertEquals("Decoration/Repeat never makes another planner request", 1, plans)
        assertTrue("Decoration never verifies the step", session.controller.verifiedSteps.value.isEmpty())
        assertEquals(CareState.AwaitingUser, session.controller.state.value.state)
    }

    @Test fun actualRepeatTracesTargetRevealsCueRetainsChatAndStopRejectsLateWork() {
        val service = nativeService.service()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val originalFlags = automation.serviceInfo.flags
        val late = CompletableDeferred<Unit>()
        val lateProduced = CompletableDeferred<Unit>()
        var listener: ((MinisAccessibilityService.RecordedEvent) -> Unit)? = null
        var baseline: Bitmap? = null
        var plans = 0
        var passed = false
        var primaryFailure: Throwable? = null
        evidence = File(service.cacheDir, "care-trace-journey-${UUID.randomUUID()}").apply { check(mkdirs()) }
        File(evidence, "frames.tsv").writeText("# Actual display screenshots; controlled host/local planner, no provider.\nlabel\tuptimeMs\telapsedMs\tborderSamples\ttotalSamples\tcueInk\ttextInk\tclockwiseStart\tclockwiseEnd\n")
        try {
            assertTrue("Trace gate requires enabled motion; reduced motion has its own native gate",
                Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.context.startActivity(Intent(instrumentation.context, CareOverlayFixture::class.java)
                .putExtra(CareOverlayFixture.EXTRA_SAFE_TARGET, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var previous: Pair<SceneKey, Rect>? = null
            var unchanged = SystemClock.uptimeMillis()
            await("Public host, exact target and pending marker must settle before admission") {
                val target = visibleBounds("CARE_TOUCH_TARGET")
                val scene = main { service.dotScreen.currentSceneKey() }
                val current = if (scene != null && scene.packageName == instrumentation.context.packageName && target != null &&
                    visibleBounds("CARE_TOUCH_PENDING") != null) scene to target else null
                if (current != previous) { previous = current; unchanged = SystemClock.uptimeMillis() }
                current != null && SystemClock.uptimeMillis() - unchanged >= 250
            }
            val hostBaseline = requireNotNull(automation.takeScreenshot()).also { baseline = it; save("host-before-care", it) }
            main {
                val controller = CareController(scope, { service.dotScreen.captureNodes(it) },
                    { service.dotScreen.currentSceneKey() }, { _, snapshot, history ->
                        plans++
                        val target = snapshot.nodes.single { it.description == "CARE_TOUCH_TARGET" }
                        val proposal = CareProposal(snapshot.id, snapshot.scene, target.ref, CareAction.Open,
                            instruction, null, CareExpectation.PageContains("CARE_TOUCH_RECEIVED"), 0.95f)
                        val result = CarePlanResult.Step(proposal)
                        if (history.isNotEmpty()) withContext(NonCancellable) {
                            late.await(); lateProduced.complete(Unit); result
                        } else result
                    })
                session = CareSession(controller, { if (::window.isInitialized) window.clearFocus() }, {})
                window = CareOverlayWindow(service, scope, session, { service.dotScreen.hostViewport() },
                    { service.dotScreen.currentSceneKey() }, MutableStateFlow(DotForegroundState(connected = true,
                        mode = DotForegroundMode.Care, operation = DotForegroundOperation.CareWaiting)))
                val safeHost = requireNotNull(layoutHost()).rect()
                val settledTarget = requireNotNull(previous).second
                File(evidence, "admission.txt").appendText("fixtureSafeHost=$safeHost fixtureTarget=$settledTarget\n")
                assertTrue("Inset-aware fixture target must be inside layoutHost before Care admission",
                    safeHost.contains(settledTarget))
                var previousScene = service.dotScreen.currentSceneKey()
                listener = { event ->
                    scope.launch(Dispatchers.Main.immediate) {
                        val next = service.dotScreen.currentSceneKey()
                        val before = previousScene; previousScene = next
                        if (before != next) File(evidence, "scene-events.txt").appendText(
                            "uptime=${SystemClock.uptimeMillis()} type=${event.type} package=${event.packageName} class=${event.className} before=$before after=$next\n")
                        session.sceneChanged(before, next); window.refreshGeometry()
                    }
                }
                service.addEventListener(requireNotNull(listener))
                session.enter(); session.submitGoal("Touch the public fixture target")
            }
            var measured: Geometry? = null
            await("Real owner must mount a safe cue and retain the complete bottom instruction") {
                val awaiting = main { session.controller.state.value.state == CareState.AwaitingUser }
                measured = if (awaiting) geometry() else null
                val instructionBounds = measured?.card?.let { visibleBounds(instruction, it) }
                recordAdmission("awaiting=$awaiting measured=$measured instructionBounds=$instructionBounds")
                awaiting && measured != null && instructionBounds != null
            }
            val g = requireNotNull(measured)
            val density = service.resources.displayMetrics.density
            val samples = borderSamples(g.target, density)
            assertTrue("Fixture target supplies independent edge positions", samples.size > 40)
            val dark = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val blue = (if (dark) DotColors.DarkCare else DotColors.LightCare).toArgb()
            val text = (if (dark) DotColors.DarkText else DotColors.LightText).toArgb()
            val firstOrigin = SystemClock.uptimeMillis()
            awaitFrame("initial-visible", g, hostBaseline, samples, blue, text, firstOrigin) { it.cueInk > 20 }
                .use { retain("initial-visible", it, firstOrigin) }
            awaitFrame("initial-cue-expired", g, hostBaseline, samples, blue, text, firstOrigin, 9000) {
                it.cueInk == 0 && it.outlined >= samples.size * 0.97
            }.use { retain("initial-expired", it, firstOrigin) }
            assertNoProgress(main { plans })
            assertNotNull("Full instruction semantics remain visible after cue expires", visibleBounds(instruction, g.card))
            saveDisplay("initial-expired-with-bottom-transcript")

            click(R.string.nm_more)
            var repeatBounds: Rect? = null
            await("Actual Repeat menu item must be visible before timing") {
                repeatBounds = cardBounds()?.let { visibleBounds(service.getString(R.string.nm_care_repeat), it) }
                repeatBounds != null
            }
            val replayAt = SystemClock.uptimeMillis()
            tap(requireNotNull(repeatBounds))
            // Save after the burst: PNG compression must not consume the 600ms trace.
            val burst = mutableListOf<Frame>()
            try {
                while (SystemClock.uptimeMillis() - replayAt < 1500) {
                    burst += capture(g, hostBaseline, samples, blue, text)
                    SystemClock.sleep(12)
                }
                burst.forEachIndexed { index, frame -> retain("repeat-%02d".format(index), frame, replayAt) }
                val partial = burst.filter { it.outlined > samples.size * 0.05 && it.outlined < samples.size * 0.95 }
                assertTrue("partial_trace_not_observed: at least two partial display frames required; a complete rectangle is insufficient", partial.size >= 2)
                assertTrue("Outline grows along the target edge before closing",
                    partial.last().outlined - partial.first().outlined > samples.size * 0.15)
                val arcs = partial.map { frame ->
                    requireNotNull(singleArc(frame.border)) {
                        "Trace must be one connected perimeter segment, not unrelated flashing edges"
                    }
                }
                val anchor = arcs.first().start // Observed origin, independent of production Path start.
                val tolerance = 2 // At most two boundary samples may move through antialiasing.
                arcs.forEach { arc ->
                    val offset = clockwiseDistance(anchor, arc.start, samples.size)
                    assertTrue("The trace keeps its original start while its clockwise front advances",
                        minOf(offset, samples.size - offset) <= tolerance)
                }
                val fronts = arcs.map { clockwiseDistance(anchor, it.end, samples.size) }
                fronts.zipWithNext().forEach { (before, after) ->
                    assertTrue("Previously traced perimeter does not retreat", after + tolerance >= before)
                }
                assertTrue("Clockwise front advances; counterclockwise expansion is not accepted",
                    fronts.last() - fronts.first() > samples.size * 0.15)
                assertTrue("Trace reaches complete outline", burst.any { it.outlined >= samples.size * 0.97 })
                val glyphFrames = burst.filter { it.textInk > 0 }
                assertTrue("Display contains separate instruction glyphs", glyphFrames.size >= 2)
                assertTrue("Later display frames reveal more glyphs", glyphFrames.last().textInk > glyphFrames.first().textInk + 20)
            } finally { burst.forEach(Frame::close) }
            awaitFrame("readable-hold", g, hostBaseline, samples, blue, text, replayAt) {
                it.at - replayAt >= 1900 && it.textInk > 20 && it.outlined >= samples.size * 0.97
            }.use { retain("full-hold", it, replayAt) }
            awaitFrame("replay-expired", g, hostBaseline, samples, blue, text, replayAt, 7000) {
                it.cueInk == 0 && it.outlined >= samples.size * 0.97
            }.use { retain("replay-expired", it, replayAt) }
            assertNoProgress(main { plans })
            assertNotNull("Full bottom instruction semantics remain visible after replay", visibleBounds(instruction, g.card))
            saveDisplay("replay-expired-with-bottom-transcript")
            assertNotNull("Animation never touches the host target", visibleBounds("CARE_TOUCH_PENDING"))
            main { window.refreshGeometry() }
            capture(g, hostBaseline, samples, blue, text).use {
                assertEquals("Layout refresh cannot restart retired cue", 0, it.cueInk)
            }

            click(R.string.nm_more); click(R.string.nm_care_repeat)
            awaitFrame("active-before-stop", g, hostBaseline, samples, blue, text, replayAt, 1500) { it.cueInk > 20 }
                .use { retain("active-before-stop", it, replayAt) }
            click(R.string.nm_dot_chat_stop)
            await("Actual Stop hides owner and removes focus") {
                main { !session.visible.value && window.focus.view == null && session.controller.state.value.state == CareState.Paused }
            }
            repeat(3) {
                capture(g, hostBaseline, samples, blue, text).use { frame ->
                    assertEquals(0, frame.outlined); assertEquals(0, frame.cueInk)
                    retain("stopped-$it", frame, replayAt)
                }
                SystemClock.sleep(100)
            }

            main { session.enter() }
            click(R.string.nm_care_resume)
            await("Explicit Resume restores this owner") { main { plans == 2 && session.controller.state.value.state == CareState.AwaitingUser } }
            main { assertTrue(session.controller.verifiedSteps.value.isEmpty()) }
            tap(requireNotNull(visibleBounds("CARE_TOUCH_TARGET")))
            await("Only native target input changes public host") { visibleBounds("CARE_TOUCH_RECEIVED") != null }
            await("Generic page text truthfully requires explicit user confirmation") {
                main { session.controller.state.value.errorCode == "needs_user_confirmation" }
            }
            main { assertTrue(session.controller.verifiedSteps.value.isEmpty()); assertEquals(2, plans) }
            click(R.string.nm_care_confirm)
            await("User confirmation admits next locally held planning request") { main { plans == 3 } }
            main { assertEquals(VerificationOrigin.UserConfirmed, session.controller.verifiedSteps.value.single().origin) }
            click(R.string.nm_dot_chat_stop)
            main { late.complete(Unit) }
            await("Held local planner must actually produce its late result after Stop") { lateProduced.isCompleted }
            await("Late planner completion cannot reopen stopped owner") {
                main { session.controller.state.value.state == CareState.Paused && !session.visible.value && window.focus.view == null }
            }
            SystemClock.sleep(350)
            main { assertEquals(3, plans); assertEquals(CareState.Paused, session.controller.state.value.state); assertNull(window.focus.view) }
            saveDisplay("late-result-stays-stopped")
            passed = true
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { recordAdmission("failure") }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { saveFailureWindows() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { saveDisplay("failure-before-cleanup") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) { cleanupFailures += failure }
            }
            cleanup { listener?.let(service::removeEventListener) }
            cleanup { main { if (::session.isInitialized) session.disconnect() } }
            cleanup { main { if (::window.isInitialized) window.close() } }
            cleanup { main { late.complete(Unit); scope.cancel() } }
            cleanup { baseline?.recycle() }
            cleanup {
                if (main { service.dotScreen.currentSceneKey()?.packageName == instrumentation.context.packageName })
                    assertTrue("Close disposable public host",
                        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            }
            cleanup { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
            val ownerCleanupPassed = cleanupFailures.isEmpty()
            cleanup {
                File(evidence, "result.txt").writeText("assertionsPassed=$passed\nownerCleanupPassed=$ownerCleanupPassed\nproof=actual_display_local_planner_fixture\nplannerCalls=${main { plans }}\nlateProduced=${lateProduced.isCompleted}\n")
                println("CARE_TRACE_JOURNEY assertionsPassed=$passed ownerCleanupPassed=$ownerCleanupPassed evidence=${evidence.absolutePath}")
            }
            if (cleanupFailures.isNotEmpty()) {
                val failure = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(failure::addSuppressed)
                primaryFailure?.addSuppressed(failure) ?: throw failure
            }
        }
    }
}
