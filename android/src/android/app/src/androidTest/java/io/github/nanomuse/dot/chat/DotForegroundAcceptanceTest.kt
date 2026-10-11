package io.github.nanomuse.dot.chat

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.openminis.app.MinisApp
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.*
import com.openminis.app.provider.LLMProvider
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import io.github.nanomuse.dot.gesture.DotIntent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual coordinator/window/ChatScreen acceptance. Public in-memory stream; no URL/key/API. */
@RunWith(AndroidJUnit4::class)
class DotForegroundAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MinisApp
    private val service get() = requireNotNull(MinisAccessibilityService.getInstance())
    private lateinit var automation: UiAutomation
    private val id = UUID.randomUUID().toString()
    private val title = "DOT_T4_UI_$id"
    private val history = "PUBLIC_HISTORY_$id"
    private val prompt = "PUBLIC_PROMPT_$id"
    private val prefix = "PUBLIC_STREAM_$id"
    private val reply get() = "$prefix COMPLETE"
    private val historyId = UUID.randomUUID().toString()
    private var created = false
    private var previousSession: String? = null
    private var previousObservationFlags: Int? = null
    private var previousPrefs: Map<String, String?>? = null
    private var ordinarySessions: List<ChatSessionEntity>? = null
    private var ordinaryMessages: Map<String, List<MessageEntity>>? = null
    private var vm: ChatViewModel? = null
    private var provider: PublicStream? = null
    private var ownedEvidence: File? = null
    private var hostStarted = false
    private var originalActivities: Set<Activity> = emptySet()

    @Before fun prepareOwnedSession() {
        // Controller reconnects the service, unlocks the host and saves/restores system settings.
        // Fail before UI/DB mutation if there is existing panel work we do not own.
        waitFor("controller-preconnected accessibility service") {
            MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true
        }
        assertNull("Existing panel belongs to the user", service.dotOverlay.panelView)
        assertTrue("Controller must enable/unlock the visible Dot orb", service.dotOverlay.orbView?.isAttachedToWindow == true)
        previousSession = ChatViewModelStore.activeSessionId
        originalActivities = onMain { activities() }
        check(previousSession != null || originalActivities.none { it.javaClass.name == "com.openminis.app.MainActivity" }) {
            "Warm non-chat route is not owned; controller must preserve its route"
        }
        previousPrefs = preferenceKeys.associateWith { prefs.getString(it, null) }
        runBlocking {
            ordinarySessions = app.chatRepository.dao.listSessions()
            ordinaryMessages = requireNotNull(ordinarySessions).associate { it.id to app.chatRepository.dao.loadMessages(it.id) }
            check(requireNotNull(ordinaryMessages).values.flatten().none { it.id == historyId })
            assertNull(app.chatRepository.getSession(id))
            assertTrue(app.chatRepository.dao.loadMessages(id).isEmpty())
        }
        assertFalse(sessionDirectory.exists())
        assertTrue(ownedMediaDirectories().isEmpty())
        assertFalse(com.openminis.app.service.SessionBadgeStore.byId.value.containsKey(id))
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val observation = automation.serviceInfo
        previousObservationFlags = observation.flags
        observation.flags = observation.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = observation
        // Mark ownership immediately after the unique session insert, before any later step can fail.
        ownedEvidence = File(app.cacheDir, "t4-ui-$id").apply { check(mkdir()) }
        runBlocking {
            app.chatRepository.dao.insertSession(ChatSessionEntity(id = id, title = title, modelId = title,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), memoryEnabled = 0))
            created = true
            app.chatRepository.dao.insertMessage(MessageEntity(id = historyId, sessionId = id, role = "user",
                partsJson = textParts(history), createdAt = System.currentTimeMillis(), sortOrder = 0))
        }
    }

    @Test fun coordinatorOverlayStreamsAndMainShowsSameCanonicalHistory() {
        // Use actual product navigation to mount ChatScreen and publish activeSessionId.
        openMain(id)
        waitFor("actual main ChatScreen history") { ChatViewModelStore.activeSessionId == id &&
            nativeBounds(AccessibilityWindowInfo.TYPE_APPLICATION, app.packageName, history) != null }
        val canonical = onMain { canonicalVm() }
        vm = canonical
        waitFor("canonical history/model load") { canonical.messages.value.any { it.id == historyId && it.content == history } &&
            canonical.sessionTitle.value == title && state<Boolean>(canonical, "sessionLoaded").value }
        assertFalse(canonical.isStreaming.value)
        val inert = PublicStream(LLMModel(title, title, "Task4 public in-memory", contextWindow = 128000, maxOutputTokens = 128))
        provider = inert
        // A private field seam is confined to this uniquely owned VM. Production factory/store,
        // send, stream publication, persistence, coordinator and both renderers remain real.
        onMain {
            setField(canonical, "currentProvider", inert)
            setField(canonical, "currentModel", inert.model)
            state<String>(canonical, "_modelName").value = title
            state<String?>(canonical, "_activeEntryId").value = null
            state<String?>(canonical, "_selectedGroupId").value = null
        }
        hostStarted = true
        openOwnedHost()
        waitFor("external host is foreground") { nativeBounds(AccessibilityWindowInfo.TYPE_APPLICATION,
            instrumentation.context.packageName, "PUBLIC_HOST_$id") != null }
        waitFor("service host window available") { foregroundHostReady() }
        assertEquals(id, ChatViewModelStore.activeSessionId)
        // No setActiveSession shortcut and no manual showPanel: real coordinator consumes main hook.
        onMain { service.dotInteraction.handle(DotIntent.Single) }
        waitFor("focusable actual overlay") { onMain {
            service.dotOverlay.panelView?.isAttachedToWindow == true &&
                (service.dotOverlay.panelView!!.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } }
        val adapter = field(service.dotInteraction, "adapter\$delegate") as Lazy<*>
        val actualAdapter = adapter.value as DotChatAdapter
        onMain {
            assertEquals(id, actualAdapter.binding.value?.sessionId)
            assertSame(canonical, actualAdapter.businessViewModel)
            assertSame(canonical, canonicalVm())
            val params = service.dotOverlay.panelView!!.layoutParams as WindowManager.LayoutParams
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            canonical.setInputText(prompt) // Composer setup; native Send dispatch is the UI assertion.
        }
        waitFor("public prompt reaches actual overlay composer") {
            nativeBounds(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, app.packageName, prompt) != null
        }
        tapNative(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, app.getString(com.openminis.app.R.string.nm_dot_chat_send))
        waitFor("public stream entered") { inert.entered.isCompleted }
        assertEquals(1, inert.calls.get())
        assertTrue(canonical.isStreaming.value)
        waitFor("canonical public live delta") { canonical.streamingById.value.values.any { it.content == prefix } }
        waitFor("stream prefix rendered in overlay native tree") {
            nativeBounds(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, app.packageName, prefix) != null
        }
        saveCrop("overlay-stream", requireNotNull(nativeBounds(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, app.packageName, prefix)))
        assertNotNull(nativeBounds(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, app.packageName,
            app.getString(com.openminis.app.R.string.nm_dot_chat_stop)))
        inert.finish.complete(Unit)
        waitFor("canonical completion") { !canonical.isStreaming.value && canonical.messages.value.any { it.role == "assistant" && it.content == reply } }
        waitFor("Room persisted completed assistant") { runBlocking {
            app.chatRepository.dao.loadMessages(id).any { it.role == "assistant" && plainText(it) == reply }
        } }
        assertEquals(1, inert.calls.get())
        assertNull(canonical.error.value)
        tapNative(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, app.getString(com.openminis.app.R.string.nm_dot_chat_collapse))
        waitFor("panel collapsed") { onMain { service.dotOverlay.panelView == null } }
        openMain(id)
        waitFor("same session in actual main ChatScreen") { ChatViewModelStore.activeSessionId == id &&
            nativeBounds(AccessibilityWindowInfo.TYPE_APPLICATION, app.packageName, reply) != null }
        onMain { assertSame(canonical, canonicalVm()); assertSame(canonical, actualAdapter.businessViewModel) }
        assertTrue(canonical.messages.value.any { it.id == historyId && it.content == history })
        assertTrue(canonical.messages.value.any { it.role == "user" && it.content == prompt })
        val rows = runBlocking { app.chatRepository.dao.loadMessages(id) }
        assertEquals(listOf("user", "user", "assistant"), rows.map { it.role })
        assertEquals(listOf(history, prompt, reply), rows.map(::plainText))
        saveCrop("main-completed", requireNotNull(nativeBounds(AccessibilityWindowInfo.TYPE_APPLICATION, app.packageName, reply)))
        requireNotNull(ownedEvidence).resolve("result.json").writeText(JSONObject().put("sessionId", id)
            .put("provider", "public-in-memory").put("streamCalls", inert.calls.get()).put("canonicalVmShared", true)
            .put("actualOverlayStream", true).put("actualMainCompleted", true)
            .put("messageIds", JSONArray(rows.map { it.id })).toString())
        println("T4_UI_ACCEPTANCE sessionId=$id actualOverlayStream=true actualMainCompleted=true sameVm=true roomRows=3 publicInMemory=true")
    }

    @After fun cleanupExactlyOwnedState() {
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
        if (vm != null) attempt { onMain {
            if (vm!!.isStreaming.value) vm!!.cancelStream()
            service.dotInteraction.close()
        } }
        provider?.finish?.complete(Unit)
        attempt { runBlocking { withTimeout(10000) {
            (vm?.let { field(it, "sendSetupJob") } as? Job)?.join()
            (vm?.let { field(it, "streamJob") } as? Job)?.join()
        } } }
        if (created) {
            previousSession?.let { original -> attempt {
                openMain(original)
                waitFor("original main session restored") { ChatViewModelStore.activeSessionId == original }
            } }
            if (previousSession == null) attempt {
                onMain { activities().filter { it.javaClass.name == "com.openminis.app.MainActivity" && it !in originalActivities }.forEach(Activity::finish) }
                waitFor("cold route unmounted") { ChatViewModelStore.activeSessionId == null }
            }
            if (hostStarted) attempt { openOwnedHost(finish = true) }
            attempt { onMain { ChatViewModelStore.release(id) } }
            attempt { runBlocking {
                app.database.withTransaction {
                    val row = requireNotNull(app.chatRepository.getSession(id))
                    val rows = app.chatRepository.dao.loadMessages(id)
                    check(row.id == id && row.title == title && row.modelId == title && row.modelBinding == null && row.memoryEnabled == 0)
                    check(ownedMediaDirectories().isEmpty()) { "Text fixture produced unexpected media; preserve it" }
                    check(rows.size <= 3 && rows.all { ownedRow(it) }) { "Unexpected row; preserve fixture for controller" }
                    requireNotNull(ownedEvidence).resolve("cleanup-owned.json").writeText(JSONObject().put("sessionId", id)
                        .put("messageIds", JSONArray(rows.map { it.id })).put("rows", rows.size).toString())
                    app.chatRepository.dao.deleteMessages(id)
                    app.chatRepository.dao.deleteSession(id)
                }
                val sync = io.github.nanomuse.sync.SyncDatabase.get(app).dao()
                sync.removeOwners(listOf(id)); sync.removeConversations(listOf(id)); sync.removeMessagesOfAll(listOf(id))
                // Only the UUID session subtree whose absence we checked before creation.
                if (sessionDirectory.exists()) check(sessionDirectory.deleteRecursively())
                check(app.chatRepository.getSession(id) == null)
                check(app.chatRepository.dao.loadMessages(id).isEmpty())
                assertEquals(ordinarySessions, app.chatRepository.dao.listSessions())
                requireNotNull(ordinaryMessages).forEach { (ordinaryId, rows) -> assertEquals(rows, app.chatRepository.dao.loadMessages(ordinaryId)) }
            } }
            attempt { val badges = com.openminis.app.service.SessionBadgeStore.byId.value
                check(badges[id].orEmpty().all { it == com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED })
                com.openminis.app.service.SessionBadgeStore.remove(id, com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED)
                assertEquals(badges - id, com.openminis.app.service.SessionBadgeStore.byId.value)
            }
        }
        previousPrefs?.let { originals -> attempt {
            val edit = prefs.edit()
            originals.forEach { (key, value) ->
                val current = prefs.getString(key, null)
                if (current != value) {
                    check(current == id) { "Unexpected preference mutation; preserve for controller" }
                    if (value == null) edit.remove(key) else edit.putString(key, value)
                }
            }
            check(edit.commit())
            originals.forEach { (key, value) -> assertEquals(value, prefs.getString(key, null)) }
        } }
        previousObservationFlags?.let { flags -> attempt {
            val observation = automation.serviceInfo; observation.flags = flags; automation.serviceInfo = observation
            assertEquals(flags, automation.serviceInfo.flags)
        } }
        if (failures.isNotEmpty()) throw failures.first().apply { failures.drop(1).forEach(::addSuppressed) }
        if (created) {
            requireNotNull(ownedEvidence).resolve("cleanup-result.json").writeText(JSONObject().put("sessionId", id)
                .put("ownedRowsRemoved", true).put("ordinaryRoomRowsUnchanged", true).put("originalSessionRestored", true)
                .put("originalRouteWasCold", previousSession == null)
                .put("preferenceKeysRestored", JSONArray(preferenceKeys)).toString())
            println("T4_UI_CLEANUP sessionId=$id exactOwnedRowsRemoved=true ordinaryRoomRowsUnchanged=true originalSessionRestored=true evidenceDir=t4-ui-$id")
        }
    }

    private fun activities() = Stage.entries.filter { it != Stage.DESTROYED }
        .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.toSet()
    private val preferenceKeys = listOf("main_chat.session", "first_conversation.session", "first_conversation.phase")
    private val prefs get() = app.getSharedPreferences("nanomuse", android.content.Context.MODE_PRIVATE)
    private val sessionDirectory get() = File(app.filesDir, "minis-sessions/$id")
    private fun ownedMediaDirectories() = File(app.filesDir, "media").listFiles().orEmpty()
        .filter { it.isDirectory }.map { File(it, id) }.filter { it.exists() }
    private fun canonicalVm() = ViewModelProvider(ChatViewModelStore.ownerFor(id), ChatViewModel.factory(id,
        app.chatRepository, app.providerRepository, app, app.memoryRepository, app.skillRepository, app.mcpRepository))[ChatViewModel::class.java]
    private fun openMain(sessionId: String) { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$sessionId"))
        .setClassName(app.packageName, "com.openminis.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
    private fun openOwnedHost(finish: Boolean = false) {
        instrumentation.context.startActivity(Intent().setClassName(instrumentation.context.packageName,
            "io.github.nanomuse.dot.chat.DotForegroundHost").putExtra("ownerId", id)
            .apply { if (finish) putExtra("finishOwnedId", id) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun textParts(value: String) = JSONArray().put(JSONObject().put("type", "text").put("value", value)).toString()
    private fun plainText(row: MessageEntity): String {
        val parts = JSONArray(row.partsJson)
        return (0 until parts.length()).map { parts.getJSONObject(it) }.onEach { check(it.getString("type") == "text") }
            .joinToString("") { it.getString("value") }
    }
    private fun ownedRow(row: MessageEntity): Boolean = row.sessionId == id && when (row.role) {
        "user" -> (row.id == historyId && plainText(row) == history) || plainText(row) == prompt
        "assistant" -> plainText(row) == prefix || plainText(row) == reply
        else -> false
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    @Suppress("UNCHECKED_CAST") private fun <T> state(owner: Any, name: String) = field(owner, name) as MutableStateFlow<T>
    private fun <T> onMain(action: () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { value = action() } catch (caught: Throwable) { failure = caught }
        }
        // Rethrow on the JUnit caller so assertion/reflection failures reach @After/attempt.
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        assertTrue(label, condition())
    }
    @Suppress("DEPRECATION") // Node/window recycling and real display bounds support older APIs.
    private fun nativeBounds(type: Int, packageName: String, text: String): Rect? {
        val displaySize = android.graphics.Point()
        app.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(displaySize)
        val displayBounds = Rect(0, 0, displaySize.x, displaySize.y)
        val panelBounds = if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) onMain {
            val panel = service.dotOverlay.panelView
            if (panel?.isAttachedToWindow == true && panel.isShown && panel.windowVisibility == android.view.View.VISIBLE)
                Rect().takeIf { panel.getGlobalVisibleRect(it) } else null
        } else null
        if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && panelBounds == null) return null
        fun find(node: AccessibilityNodeInfo, windowId: Int, viewport: Rect): Rect? {
            if (node.isVisibleToUser && node.windowId == windowId && node.text?.toString()?.contains(text) == true) {
                val visible = Rect().also(node::getBoundsInScreen)
                if (visible.width() > 0 && visible.height() > 0 && visible.intersect(viewport)) return visible
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { find(child, windowId, viewport)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val windows = automation.windows
        try { for (window in windows.filter { it.type == type }) {
            // Application matches must belong to the current foreground/focused window.
            // Overlay matches additionally use the actual attached, shown panel's visible frame.
            if (type == AccessibilityWindowInfo.TYPE_APPLICATION && !window.isActive && !window.isFocused) continue
            val viewport = Rect().also(window::getBoundsInScreen)
            if (viewport.width() <= 0 || viewport.height() <= 0 || !viewport.intersect(displayBounds)) continue
            if (panelBounds != null && !viewport.intersect(panelBounds)) continue
            val root = window.root ?: continue
            try { if (root.isVisibleToUser && root.windowId == window.id && root.packageName?.toString() == packageName)
                find(root, window.id, viewport)?.let { return it }
            } finally { root.recycle() }
        } } finally { windows.forEach { it.recycle() } }
        return null
    }
    @Suppress("DEPRECATION") // Match the real service host identity before admitting its panel.
    private fun foregroundHostReady(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try { return root.packageName?.toString() == instrumentation.context.packageName &&
            root.windowId == service.dotScreen.hostWindowId() && service.dotScreen.hostViewport(root.windowId) != null
        } finally { root.recycle() }
    }
    private fun tapNative(type: Int, text: String) {
        waitFor("native button $text") { nativeBounds(type, app.packageName, text) != null }
        val bounds = requireNotNull(nativeBounds(type, app.packageName, text))
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun saveCrop(label: String, bounds: Rect) {
        val screen = requireNotNull(automation.takeScreenshot())
        try {
            check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= screen.width && bounds.bottom <= screen.height)
            val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
            try { requireNotNull(ownedEvidence).resolve("$label.png").outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { if (crop !== screen) crop.recycle() }
        } finally { screen.recycle() }
    }

    private inner class PublicStream(override var model: LLMModel) : LLMProvider {
        override val name = "Task4 public in-memory stream"
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): LLMResponse =
            error("Nonstream request is outside the owned UI fixture")
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) = flow {
            check(calls.incrementAndGet() == 1)
            check(messages.any { it.role == LLMMessage.Role.USER && it.content.contains(prompt) })
            check(imageParts.isEmpty())
            emit(LLMStreamChunk.Started)
            emit(LLMStreamChunk.Text(prefix))
            entered.complete(Unit)
            withTimeout(30000) { finish.await() }
            emit(LLMStreamChunk.Text(" COMPLETE"))
            emit(LLMStreamChunk.Finished("stop"))
        }
    }
}
