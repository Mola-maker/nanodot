package io.github.nanomuse.dot.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ChatViewModelStore
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotForegroundOperation
import io.github.nanomuse.dot.care.CareState
import io.github.nanomuse.dot.care.CareSession
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.chat.DotPreview
import io.github.nanomuse.dot.gesture.PointPx
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Real touch, real host pixels, canonical messages. Only the explicitly armed method sends.
 * Catches missing crops, answer-bearing node substitution, wrong conversation and lost Stop ownership.
 * Provider/wire verification belongs to the coordinator's independent bounded relay receipt.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class DotRegionVisionNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as MinisApp
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val fixture = DotNativeServiceFixture()
    private val service get() = fixture.service()
    private val suppliedRun = InstrumentationRegistry.getArguments().getString("regionVisionRun")
    private val run = suppliedRun ?: UUID.randomUUID().toString()
    private lateinit var evidence: File
    private lateinit var sessionId: String
    private var previousSession: String? = null
    private var initiated = 0
    private var armedConfiguration: JSONObject? = null
    private data class OwnedCareGoal(val owner: CareSession, val userBubbleId: String, val goal: String,
        val runId: String, val canonicalSessionId: String)
    private var ownedCareGoal: OwnedCareGoal? = null
    private val phaseFile get() = File(context.cacheDir, "region-vision-$run.json")
    private val goFile get() = File(context.cacheDir, "region-vision-$run.go")
    private val inject by lazy {
        UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
    }
    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(label: String, limit: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + limit
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        screenshot("failure-${SystemClock.uptimeMillis()}")
        fail(label)
    }
    private fun chat(): DotChatAdapter = main {
        val field = service.dotInteraction.javaClass.getDeclaredField("adapter\$delegate").apply { isAccessible = true }
        (field.get(service.dotInteraction) as Lazy<*>).value as DotChatAdapter
    }

    @Before fun connectRasterHostAndCanonicalSession() {
        require(UUID.fromString(run).toString() == run)
        fixture.connect()
        main {
            assertTrue("Use an isolated scene without an unfinished Care goal",
                service.dotInteraction.careSession.controller.state.value.state in setOf(CareState.Idle, CareState.Completed))
        }
        evidence = File(context.cacheDir, "region-vision-evidence-$run").apply { check(mkdirs()) }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        main { service.dotOverlay.setEnabled(true) }
        instrumentation.context.startActivity(Intent().setComponent(ComponentName(instrumentation.context,
            DotVisionHostFixture::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Raster host and real orb must be visible") {
            main { service.dotScreen.currentSceneKey()?.packageName == instrumentation.context.packageName &&
                service.dotOverlay.orbView?.isAttachedToWindow == true } && hostContent() != null
        }
        previousSession = main { ChatViewModelStore.activeSessionId }
        sessionId = runBlocking { app.chatRepository.createSession("unknown", "Public pixel conversation", memoryEnabled = false).id }
        // Controlled session setup only. All goal/message text and Send actions below use native input.
        main { ChatViewModelStore.setActiveSession(sessionId); chat().bind(sessionId) }
        File(evidence, "ground-truth.json").writeText(JSONObject()
            .put("rasterOnly", true).put("ordinaryControl", "Inspect card")
            .put("circle", JSONArray(listOf("blue triangle", "orange circle", "green square")))
            .put("followup", "blue triangle")
            .put("rectangle", JSONObject().put("ALPHA", 17).put("BETA", 42))
            .put("irregular", JSONArray(listOf("blue triangle", "orange circle")))
            .put("excluded", "magenta diamond").toString())
        assertHostHasNoVisualAnswerSemantics()
    }

    @After fun restoreOwnedNativeScene() {
        try {
            if (::sessionId.isInitialized) main {
                // Keep the paused journey receipt, then release only this run's submitted goal.
                // A replacement goal (including identical text) has a different user bubble ID.
                ownedCareGoal?.let { owned ->
                    val current = service.dotInteraction.careSession
                    if (owned.runId == run && owned.canonicalSessionId == sessionId &&
                        ChatViewModelStore.activeSessionId == sessionId && current === owned.owner &&
                        current.controller.state.value.goal == owned.goal &&
                        current.conversation.rows.lastOrNull { it.user }?.id == owned.userBubbleId) {
                        // Reset this CareSession only; this does not disconnect the Android service
                        // and does not manufacture a Completed/verified user step.
                        current.disconnect()
                    }
                }
                ChatViewModelStore.setActiveSession(previousSession)
            }
        }
        finally { fixture.close() }
    }

    /** No provider, no Send: actual Care entry plus three crop shapes and physical cleanup. */
    @Test fun localCareSelectionHasRasterOnlyEvidenceAndStopCleansUp() = guarded {
        enterCare()
        val first = select("circle", Shape.Circle)
        select("rectangle-existing-panel", Shape.Rectangle)
        assertFalse("Replacing a local preview must release its old crop", first.file.exists())
        assertFalse("Replacing a local preview must release its old nodes", requireNotNull(first.regionText).exists())
        assertTrue(main { service.dotInteraction.careSession.controller.state.value.state in setOf(CareState.Idle, CareState.Completed) })
        reselect()
        select("rectangle", Shape.Rectangle)
        reselect()
        select("irregular", Shape.Irregular)
        stopAndCheck(emptyList())
        assertEquals(0, initiated)
        record("local_complete")
    }

    /** One real node-grounded Care step, then four bounded vision/chat requests; never retries. */
    @Test fun armedCareRegionVisionConversation() = guarded {
        require(suppliedRun != null) { "Explicit regionVisionRun UUID is required for the live method" }
        enterCare()
        record("configure_ready")
        await("Host must configure and explicitly arm this new five-request run", 90_000) { goFile.isFile }
        armedConfiguration = JSONObject(goFile.readText()).also {
            assertEquals(setOf("run", "entryId", "instanceId", "maxRequests"), it.keys().asSequence().toSet())
            assertEquals(run, it.getString("run")); assertEquals(5, it.getInt("maxRequests"))
            it.toString().let { safe -> File(evidence, "armed-metadata.json").writeText(safe) }
        }
        requireConfiguredVision()
        val goal = "Help me tap Inspect card."
        typeNative(context.getString(R.string.nm_care_goal), goal, "care") { editorValue(context.getString(R.string.nm_care_goal)) }
        hideKeyboard(context.getString(R.string.nm_care_start))
        screenshot("care-before-send")
        val submittedCare = main { service.dotInteraction.careSession }
        val priorGoalRows = main { submittedCare.conversation.rows.filter { it.user }.map { it.id }.toSet() }
        initiated++
        tapLabel(context.getString(R.string.nm_care_start))
        await("Physical Care Send must admit this run's own goal", 5_000) {
            main {
                val row = submittedCare.conversation.rows.lastOrNull { it.user }
                if (row != null && row.id !in priorGoalRows && row.text == goal &&
                    submittedCare.controller.state.value.goal == goal) {
                    ownedCareGoal = OwnedCareGoal(submittedCare, row.id, goal, run, sessionId)
                    true
                } else false
            }
        }
        record("care_physical_send")
        await("Genuine Care planner must publish a current instruction without retry", 40_000) {
            main {
                val state = service.dotInteraction.careSession.controller.state.value
                assertNull("Care request failed; no retry is authorized", state.errorCode)
                state.state in setOf(CareState.Guiding, CareState.AwaitingUser) && state.proposal != null && state.target != null
            }
        }
        val care = main { service.dotInteraction.careSession.controller.state.value }
        val careTarget = requireNotNull(care.target)
        val careProposal = requireNotNull(care.proposal)
        assertEquals("Inspect card", careTarget.text)
        assertNotNull(main { service.dotInteraction.careSession.focusBounds(service.dotScreen.currentSceneKey()) })
        assertTrue(main { service.dotInteraction.careSession.controller.verifiedSteps.value.isEmpty() })
        screenshot("care-guiding")
        File(evidence, "care-receipt.json").writeText(JSONObject().put("goal", goal)
            .put("instruction", careProposal.instruction).put("target", careTarget.text)
            .put("snapshotId", careProposal.snapshotId).put("state", care.state.name)
            .put("source", "accessibility_control").put("imageUnderstanding", false).put("verifiedSteps", 0).toString())
        record("care_guiding")

        select("circle", Shape.Circle)
        assertCarePausedForRegionChat()
        record("circle_preview")
        val first = sendAndObserve("circle", "Read the attached image and list each colored symbol using color and shape. Use no tools.", true)
        observePairs("circle", first.content, "blue triangle", "orange circle", "green square")
        val followup = sendAndObserve("followup", "Using the image from my previous message tell me which symbol is leftmost. Use no tools.", false)
        observePairs("followup", followup.content, "blue triangle")
        select("rectangle-reselect", Shape.Rectangle)
        reselect()
        select("rectangle", Shape.Rectangle)
        val table = sendAndObserve("rectangle", "Read the new attached image and transcribe the table rows with their values. Use no tools.", true)
        observePairs("rectangle", table.content, "alpha 17", "beta 42")
        select("irregular", Shape.Irregular)
        val irregular = sendAndObserve("irregular", "Describe the visible colored symbols in this newly selected image. Use no tools.", true)
        observePairs("irregular", irregular.content, "blue triangle", "orange circle")
        val historicalMedia = main { chat().session!!.messages.value.filter { it.role == "user" }.flatMap { it.imageUris }.map { File(requireNotNull(it.path)) } }
        assertEquals(3, historicalMedia.size)
        select("stop-pending", Shape.Circle)
        stopAndCheck(historicalMedia)
        assertCarePausedForRegionChat()
        val userRows = main { chat().session!!.messages.value.count { it.role == "user" } }
        assertEquals(4, userRows); assertEquals(5, initiated)
        File(evidence, "complete-receipt.json").writeText(JSONObject().put("run", run).put("sessionId", sessionId)
            .put("requestCountInitiated", initiated).put("careState", "Paused").put("foregroundMode", "Screen")
            .put("canonicalUserRows", userRows).put("historicalMediaPreserved", historicalMedia.all { it.isFile })
            .put("stopClearedPreview", main { chat().preview.value == null }).put("stopClearedOutline", outline() == null)
            .put("evidence", evidence.absolutePath).toString())
        record("complete")
    }

    private fun guarded(body: () -> Unit) {
        try { body() } catch (failure: Throwable) {
            record("failed", JSONObject().put("failureClass", failure.javaClass.simpleName))
            throw failure
        } finally { goFile.delete() }
    }
    private fun record(phase: String, extra: JSONObject = JSONObject()) {
        val json = JSONObject().put("run", run).put("phase", phase).put("sessionId", sessionId)
            .put("evidence", evidence.absolutePath).put("maxRequests", 5).put("requestIndex", initiated)
            .put("wallClockMs", System.currentTimeMillis()).put("uptimeMs", SystemClock.uptimeMillis())
        extra.keys().forEach { json.put(it, extra.get(it)) }
        File(evidence, "events.jsonl").appendText(json.toString() + "\n")
        val staged = File(phaseFile.path + ".next").apply { writeText(json.toString()) }
        check(staged.renameTo(phaseFile))
        println("DOT_REGION_VISION phase=$phase requestIndex=$initiated evidence=${evidence.absolutePath}")
    }
    private fun requireConfiguredVision() = main {
        val configured = requireNotNull(armedConfiguration)
        val vm = requireNotNull(chat().businessViewModel)
        assertEquals(configured.getString("entryId"), vm.activeEntryId.value)
        assertEquals(configured.getString("entryId"), app.providerRepository.lastUsedVisibleEntry()?.id)
        val entry = app.providerRepository.config.value.modelEntries.single { it.id == vm.activeEntryId.value }
        assertEquals(configured.getString("instanceId"), entry.providerInstanceId)
        assertEquals(1, app.providerRepository.config.value.instances.size)
        assertNull("No group fallback", vm.selectedGroupId.value)
        assertTrue("Current explicit image capability", chat().session!!.supportsImages())
    }
    private fun enterCare() {
        val content = requireNotNull(hostContent())
        moveOrb(content.top + content.height() * .43f)
        val position = orb().let { PointPx(it.exactCenterX(), it.exactCenterY()) }
        repeat(3) { tap(position); if (it < 2) SystemClock.sleep(60) }
        await("Actual triple tap must show the Care goal editor") {
            main { service.dotInteraction.foreground.state.value.mode == DotForegroundMode.Care &&
                service.dotInteraction.careSession.visible.value && service.dotInteraction.careSession.editingGoal.value } &&
                hit(context.getString(R.string.nm_care_goal)) != null
        }
        screenshot("care-entry")
    }
    private fun assertCarePausedForRegionChat() = main {
        assertEquals(CareState.Paused, service.dotInteraction.careSession.controller.state.value.state)
        assertFalse(service.dotInteraction.careSession.visible.value)
        assertEquals(DotForegroundMode.Screen, service.dotInteraction.foreground.state.value.mode)
        assertEquals(sessionId, chat().binding.value!!.sessionId)
        assertTrue(service.dotInteraction.careSession.controller.verifiedSteps.value.isEmpty())
    }

    private enum class Shape { Circle, Rectangle, Irregular }
    private fun select(name: String, shape: Shape): DotPreview {
        assertHostHasNoVisualAnswerSemantics()
        val content = requireNotNull(hostContent())
        val y = content.top + content.height() * when (shape) { Shape.Circle -> .43f; Shape.Rectangle -> .69f; Shape.Irregular -> .32f }
        moveOrb(y)
        val existingPanel = main { service.dotOverlay.panelView }
        val start = orb()
        val left = start.exactCenterX(); val top = start.exactCenterY()
        val points = if (shape == Shape.Circle) {
            val radius = min((content.right - start.width() / 2f - left) / 2f, content.height() * .32f)
            (0..96).map { n ->
                val angle = Math.PI + n * Math.PI * 2 / 96
                PointPx((left + radius + radius * cos(angle)).toFloat(), (top + radius * sin(angle)).toFloat())
            }
        } else {
            val right = content.left + content.width() * .90f
            val bottom = content.top + content.height() * if (shape == Shape.Rectangle) .91f else .54f
            val corners = if (shape == Shape.Rectangle) listOf(PointPx(left, top), PointPx(right, top), PointPx(right, bottom), PointPx(left, bottom), PointPx(left, top))
                else listOf(PointPx(left, top), PointPx(right, top), PointPx(right, content.top + content.height() * .37f),
                    PointPx(content.left + content.width() * .62f, content.top + content.height() * .37f),
                    PointPx(content.left + content.width() * .62f, bottom), PointPx(left, bottom), PointPx(left, top))
            buildList { add(corners.first()); corners.zipWithNext().forEach { (a, b) ->
                for (n in 1..16) { val t = n / 16f; add(PointPx(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)) }
            } }
        }
        draw(name, points)
        await("Closed path must create actual region pixels") { main { chat().preview.value?.regionText != null &&
            service.dotInteraction.foreground.state.value.operation != DotForegroundOperation.Capturing } }
        if (existingPanel != null) assertSame("A new region in this conversation must retain its native focused card",
            existingPanel, main { service.dotOverlay.panelView })
        val preview = main { requireNotNull(chat().preview.value) }
        assertFalse("Window crop must contain pixels", preview.textOnly)
        assertEquals(sessionId, main { chat().binding.value!!.sessionId })
        assertEquals(start.left, orb().left); assertEquals(start.top, orb().top)
        assertNotNull(outline())
        val selected = requireNotNull(preview.regionText).readText()
        assertFalse("Selected semantics must not contain the ordinary control", selected.contains("Inspect card"))
        listOf("ALPHA", "BETA", "triangle", "circle", "square", "blue", "orange", "green", "magenta").forEach {
            assertFalse("Visual answers must be absent from selected semantics", selected.contains(it, ignoreCase = true))
        }
        preview.file.copyTo(File(evidence, "$name-crop.png"))
        File(evidence, "$name-selected-nodes.txt").writeText(selected)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(preview.file.path))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            fun count(color: Int) = pixels.count { it == color }
            val blue = count(Color.rgb(30, 95, 225)); val orange = count(Color.rgb(245, 145, 25)); val green = count(Color.rgb(35, 165, 75))
            val white = count(Color.WHITE); val grid = count(Color.DKGRAY)
            val cellInk = mutableListOf<Int>()
            assertEquals("Outside distractor pixels must be erased", 0, count(Color.MAGENTA))
            when (shape) {
                Shape.Circle -> { assertTrue(blue > 100); assertTrue(orange > 100); assertTrue(green > 100); assertTrue(count(Color.BLACK) > 100) }
                Shape.Rectangle -> {
                    assertEquals(0, blue + orange + green)
                    assertTrue("The table crop must include its white interior", white > pixels.size / 2)
                    assertTrue("The raster table grid must actually be present", grid > 100)
                    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
                    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
                    assertTrue("Rectangle crop width must match the selected screen extent", kotlin.math.abs(bitmap.width - (maxX - minX)) <= 2f)
                    assertTrue("Rectangle crop height must match the selected screen extent", kotlin.math.abs(bitmap.height - (maxY - minY)) <= 2f)
                    // Hand-positioned cell interiors from the public Canvas fixture. Grid-only,
                    // shifted, blank-white and all-black captures cannot satisfy this oracle.
                    fun ink(x0: Float, y0: Float, x1: Float, y1: Float): Int {
                        val a = ((content.left + content.width() * x0 - minX) / (maxX - minX) * bitmap.width).toInt()
                        val b = ((content.left + content.width() * x1 - minX) / (maxX - minX) * bitmap.width).toInt()
                        val c = ((content.top + content.height() * y0 - minY) / (maxY - minY) * bitmap.height).toInt()
                        val d = ((content.top + content.height() * y1 - minY) / (maxY - minY) * bitmap.height).toInt()
                        assertTrue("Each expected cell interior must be in the actual crop", a >= 0 && b <= bitmap.width && c >= 0 && d <= bitmap.height && b > a && d > c)
                        var result = 0
                        for (py in c until d) for (px in a until b) if (bitmap.getPixel(px, py) == Color.BLACK) result++
                        return result
                    }
                    cellInk += ink(.225f, .733f, .56f, .779f)
                    cellInk += ink(.62f, .733f, .75f, .779f)
                    cellInk += ink(.225f, .813f, .56f, .859f)
                    cellInk += ink(.62f, .813f, .75f, .859f)
                    assertTrue("All four raster cells must contain real glyph pixels", cellInk.all { it > 20 })
                }
                Shape.Irregular -> { assertTrue(blue > 100); assertTrue(orange > 100); assertEquals(0, green); assertTrue(count(Color.BLACK) > 100) }
            }
            File(evidence, "$name-crop-receipt.json").writeText(JSONObject().put("shape", shape.name)
                .put("width", bitmap.width).put("height", bitmap.height).put("sha256", sha(preview.file.readBytes()))
                .put("bluePixels", blue).put("orangePixels", orange).put("greenPixels", green)
                .put("whitePixels", white).put("gridPixels", grid).put("cellInkPixels", JSONArray(cellInk))
                .put("selectedSemanticAnswers", false).put("capturedAtMs", preview.capturedAtMs).toString())
        } finally { bitmap.recycle() }
        screenshot("$name-preview")
        return preview
    }
    private fun reselect() {
        val prior = main { requireNotNull(chat().preview.value) }
        tapLabel(context.getString(R.string.nm_dot_region_reselect))
        await("Select again must close and release its local preview") {
            main { service.dotOverlay.panelView == null && chat().preview.value == null } &&
                !prior.file.exists() && prior.regionText?.exists() == false
        }
    }
    private fun stopAndCheck(history: List<File>) {
        val prior = main { requireNotNull(chat().preview.value) }
        tapLabel(context.getString(R.string.nm_dot_chat_stop))
        await("Stop must revoke preview, outline and owned capture without deleting history") {
            main { chat().preview.value == null && !chat().session!!.isStreaming.value } &&
                !prior.file.exists() && prior.regionText?.exists() == false && outline() == null
        }
        assertTrue(history.all { it.isFile })
        assertEquals(sessionId, main { chat().binding.value!!.sessionId })
        screenshot("stopped")
    }
    private fun sendAndObserve(name: String, prompt: String, image: Boolean): ChatMessage {
        requireConfiguredVision()
        assertCarePausedForRegionChat()
        assertTrue("At most one Care plus four chat sends", initiated in 1..4)
        val before = main { chat().session!!.messages.value.map { it.id }.toSet() }
        val binding = main { requireNotNull(chat().binding.value) }
        typeNative(context.getString(R.string.nm_dot_chat_message), prompt, name) { main { chat().session!!.inputText.value } }
        hideKeyboard(context.getString(R.string.nm_dot_chat_send))
        screenshot("$name-before-send")
        initiated++
        tapLabel(context.getString(R.string.nm_dot_chat_send))
        record("chat_physical_send", JSONObject().put("name", name))
        var reply: ChatMessage? = null
        await("Genuine completed reply required; failed or timed-out sends are never retried", 65_000) {
            main {
                val session = chat().session!!
                assertNull("Canonical provider error", session.error.value)
                val fresh = session.messages.value.filter { it.id !in before }
                assertFalse("No tool execution in this image proof", fresh.any { row -> row.toolBlocks.any { it.kind == "tool_use" } })
                reply = fresh.lastOrNull { it.role == "assistant" && !it.isStreaming && it.content.isNotBlank() }
                !session.isStreaming.value && reply != null
            }
        }
        val user = main { chat().session!!.messages.value.last { it.role == "user" } }
        assertEquals("Canonical request text must contain only the answer-free user prompt", prompt, user.content)
        assertEquals(if (image) 1 else 0, user.imageUris.size)
        assertEquals(binding, main { chat().binding.value })
        assertNull(main { chat().preview.value })
        val answer = requireNotNull(reply)
        await("Completed genuine reply must be rendered in the actual foreground bubble") { hit(answer.content) != null }
        screenshot("$name-reply")
        val imagePaths = user.imageUris.map { requireNotNull(it.path) }
        val imageArtifacts = JSONArray(imagePaths.mapIndexed { ordinal, path ->
            val file = File(path)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(path))
            try { JSONObject().put("ordinal", ordinal).put("path", path).put("sha256", sha(file.readBytes()))
                .put("width", bitmap.width).put("height", bitmap.height) }
            finally { bitmap.recycle() }
        })
        File(evidence, "$name-chat-receipt.json").writeText(JSONObject().put("sessionId", sessionId)
            .put("userId", user.id).put("user", user.content).put("userSha256", sha(user.content.toByteArray()))
            .put("replyId", answer.id).put("reply", answer.content).put("replySha256", sha(answer.content.toByteArray()))
            .put("imagePaths", JSONArray(imagePaths)).put("imageArtifacts", imageArtifacts)
            .put("images", imagePaths.size).put("requestIndex", initiated)
            .put("source", "pixels").put("send", "native_touch").put("input", "native_shell_keyboard").toString())
        record("chat_reply_visible", JSONObject().put("name", name))
        return answer
    }
    private fun observePairs(name: String, text: String, vararg pairs: String) {
        // Advisory only: correct answers may use another language or synonym. The coordinator
        // grades the full retained answer and extra claims against the actual crop independently.
        val normalized = text.lowercase().replace(Regex("[^a-z0-9]+"), " ")
        val observations = JSONObject()
        pairs.forEach { pair ->
            val (first, second) = pair.split(' ')
            val related = Regex("\\b$first\\s+(?:[a-z]+\\s+){0,2}$second\\b|\\b$second\\s+(?:[a-z]+\\s+){0,3}$first\\b")
            observations.put(pair, related.containsMatchIn(normalized))
        }
        File(evidence, "$name-lexical-observation.json").writeText(JSONObject().put("advisoryOnly", true)
            .put("semanticGradeRequired", true).put("pairs", observations).toString())
    }

    private fun moveOrb(y: Float) {
        val current = orb(); val content = requireNotNull(hostContent())
        val start = PointPx(current.exactCenterX(), current.exactCenterY())
        val end = PointPx(content.left + content.width() * .24f, y)
        draw("move-${SystemClock.uptimeMillis()}", (0..20).map { n -> val t = n / 20f; PointPx(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t) })
        var previous: Rect? = null; var since = SystemClock.uptimeMillis()
        await("Open orb path must settle at the left edge without selecting") {
            val now = orb()
            if (now != previous) { previous = now; since = SystemClock.uptimeMillis() }
            now.left <= content.left + 4 && SystemClock.uptimeMillis() - since >= 300
        }
    }
    private fun orb(): Rect = main {
        val view = requireNotNull(service.dotOverlay.orbView)
        val at = IntArray(2); view.getLocationOnScreen(at)
        Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }
    private fun outline(): View? = main {
        val field = service.dotOverlay.javaClass.getDeclaredField("selectionOutline").apply { isAccessible = true }
        field.get(service.dotOverlay) as? View
    }
    private fun touch(action: Int, down: Long, point: PointPx) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertEquals(true, inject.invoke(automation, event, true, false)) } finally { event.recycle() }
    }
    private fun tap(point: PointPx) { val down = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN, down, point); touch(MotionEvent.ACTION_UP, down, point) }
    private fun draw(name: String, points: List<PointPx>) {
        File(evidence, "$name-path.json").writeText(JSONArray(points.map { listOf(it.x, it.y) }).toString())
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, points.first())
        try {
            points.drop(1).forEach { SystemClock.sleep(16); touch(MotionEvent.ACTION_MOVE, down, it) }
            touch(MotionEvent.ACTION_UP, down, points.last())
        } catch (failure: Throwable) { touch(MotionEvent.ACTION_CANCEL, down, points.last()); throw failure }
    }
    private data class Node(val text: String, val description: String, val id: String, val bounds: Rect,
        val editable: Boolean, val visible: Boolean, val enabled: Boolean, val windowId: Int, val focused: Boolean)
    private fun nodes(packageName: String): List<Node> {
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val result = mutableListOf<Node>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == packageName) result += Node(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(), Rect().also(node::getBoundsInScreen), node.isEditable,
                node.isVisibleToUser, node.isEnabled, node.windowId, node.isFocused)
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> try { visit(child) } finally { child.recycle() } }
        }
        val windows = automation.windows
        try { windows.forEach { window -> window.root?.let { root -> try { visit(root) } finally { root.recycle() } } } }
        finally { windows.forEach { it.recycle() } }
        return result
    }
    private fun hostContent(): Rect? = nodes(instrumentation.context.packageName).firstOrNull { it.id == "android:id/content" && !it.bounds.isEmpty }?.bounds
    private fun assertHostHasNoVisualAnswerSemantics() {
        val host = nodes(instrumentation.context.packageName)
        assertTrue("Ordinary Care control must be observable", host.any { it.text == "Inspect card" })
        val semantics = host.flatMap { listOf(it.text, it.description) }.filter { it.isNotBlank() }
        assertTrue("Raster answers have no accessible labels", semantics.all { it == "Inspect card" })
        File(evidence, "host-semantic-receipt.json").writeText(JSONObject().put("textAndDescriptions", JSONArray(semantics))
            .put("visualAnswerSemantics", false).toString())
    }
    private fun hit(label: String): Rect? = nodes(context.packageName).firstOrNull {
        (it.text == label || it.description == label) && it.visible && it.enabled && !it.bounds.isEmpty
    }?.bounds
    private fun editorValue(label: String): String {
        val all = nodes(context.packageName)
        val field = all.singleOrNull { it.description == label && it.visible && it.enabled && !it.bounds.isEmpty }
            ?: return ""
        // Compose can expose the label on a parent and editable text on its child.
        return all.singleOrNull { it.windowId == field.windowId &&
            (field.bounds.contains(it.bounds) || it.bounds.contains(field.bounds)) &&
            it.editable && it.focused && it.visible && it.enabled }?.text.orEmpty()
    }
    private fun tapLabel(label: String) {
        var bounds: Rect? = null
        await("Native action must be visible: $label") { bounds = hit(label); bounds != null }
        File(evidence, "native-taps.jsonl").appendText(JSONObject().put("label", label)
            .put("bounds", requireNotNull(bounds).toShortString()).put("uptimeMs", SystemClock.uptimeMillis()).toString() + "\n")
        tap(requireNotNull(bounds).let { PointPx(it.exactCenterX(), it.exactCenterY()) })
    }
    private fun imeVisible(): Boolean {
        val windows = automation.windows
        return try { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } finally { windows.forEach { it.recycle() } }
    }
    private fun typeNative(label: String, text: String, name: String, value: () -> String) {
        require(text.matches(Regex("[A-Za-z .]+")))
        tapLabel(label)
        var previous: Rect? = null; var since = SystemClock.uptimeMillis()
        await("Actual keyboard and editor must settle") {
            val next = hit(label)
            if (next != previous) { previous = next; since = SystemClock.uptimeMillis() }
            imeVisible() && next != null && SystemClock.uptimeMillis() - since >= 250
        }
        screenshot("$name-keyboard")
        var expected = ""
        for (chunk in text.chunked(8)) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input text " + chunk.replace(" ", "%s"))).use { it.readBytes() }
            expected += chunk
            try {
                await("Native keyboard prefix must reach the real editor") { value() == expected }
            } finally {
                File(evidence, "$name-keyboard-observations.jsonl").appendText(JSONObject()
                    .put("expected", expected).put("actual", value())
                    .put("nodes", JSONArray(nodes(context.packageName).filter { it.editable || it.description == label }.map {
                        JSONObject().put("text", it.text).put("description", it.description).put("editable", it.editable)
                            .put("focused", it.focused).put("windowId", it.windowId).put("bounds", it.bounds.toShortString())
                    })).toString() + "\n")
            }
        }
        assertEquals(text, value())
    }
    private fun hideKeyboard(nextLabel: String) {
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        var previous: Rect? = null; var since = SystemClock.uptimeMillis()
        await("Back must hide IME without closing the active panel") {
            val next = hit(nextLabel)
            if (next != previous) { previous = next; since = SystemClock.uptimeMillis() }
            !imeVisible() && next != null && SystemClock.uptimeMillis() - since >= 250
        }
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
