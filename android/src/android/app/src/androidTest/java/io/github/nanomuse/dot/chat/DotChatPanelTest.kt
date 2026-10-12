package io.github.nanomuse.dot.chat

import android.app.UiAutomation
import android.graphics.Bitmap
import io.github.nanomuse.dot.screen.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import com.openminis.app.data.model.*
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual service window tests. No Activity substitute and no network/model invocation. */
@RunWith(AndroidJUnit4::class)
class DotChatPanelTest {
    @Volatile private var imeAnimationRunning = false
    @Volatile private var imeAnimationEnded = false
    private var previousShownGeometry: RectPx? = null
    private var previousPanelGeometryObservation: String? = null
    private var previousObservationFlags: Int? = null
    private var originalNativeSettings: Map<String,String?>? = null
    private var originalDisplay: Pair<String,String>? = null
    private val ownService = "io.github.nanodot.app/com.openminis.app.accessibility.MinisAccessibilityService"
    private var fixtureId = ""
    private var fixtureTitle = ""
    private var fixtureParts = ""
    private val ownedMessageParts = mutableMapOf<String,String>()
    private val localModel = "DOT_T4_LOCAL_NULL_BINDING"
    private var previousFirstSession: String? = null
    private var previousFirstPhase: String? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { String(it.readBytes()) }
    private fun saveNative(label: String) {
        java.io.File(instrumentation.targetContext.cacheDir,"t4-$label-windows.txt").writeText(shell("dumpsys window windows"))
        java.io.File(instrumentation.targetContext.cacheDir,"t4-$label-accessibility.txt").writeText(shell("dumpsys accessibility"))
    }
    private fun saveHostState(label: String) {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val output = StringBuilder("hostId=${service.dotScreen.hostWindowId()} viewport=${service.dotScreen.hostViewport()}\n")
        val display = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        val size = android.graphics.Point(); display.getRealSize(size)
        output.append("display=$size rotation=${display.rotation} locked=${service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked}\n")
        val windows = service.windows
        try { windows.forEach { window ->
            val bounds = android.graphics.Rect(); window.getBoundsInScreen(bounds)
            val root = window.root; val rootBounds = android.graphics.Rect(); root?.getBoundsInScreen(rootBounds)
            try { output.append("window=${window.id} type=${window.type} active=${window.isActive} focused=${window.isFocused} bounds=$bounds rootId=${root?.windowId} rootBounds=$rootBounds package=${root?.packageName}\n") }
            finally { root?.recycle() }
        } } finally { windows.forEach { it.recycle() } }
        java.io.File(instrumentation.targetContext.cacheDir,"t4-$label-host.txt").writeText(output.toString())
        println("T4_HOST $label $output")
    }
    private fun savePanelNative(label: String) {
        val data = StringBuilder()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            data.append(" ".repeat(depth)).append("id=${node.windowId} class=${node.className} text=${node.text} editable=${node.isEditable} clickable=${node.isClickable} enabled=${node.isEnabled} visible=${node.isVisibleToUser} scrollable=${node.isScrollable} bounds=$bounds children=${node.childCount}\n")
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { walk(child,depth+1) } finally { child.recycle() }
            }
        }
        val windows = automation.windows
        try { windows.forEach { w ->
            data.append("WINDOW id=${w.id} type=${w.type}\n")
            w.root?.let { try { walk(it,0) } finally { it.recycle() } }
        } } finally { windows.forEach { it.recycle() } }
        java.io.File(instrumentation.targetContext.cacheDir,"t4-$label-native-tree.txt").writeText(data.toString())
        automation.takeScreenshot()?.let { bitmap ->
            try { java.io.File(instrumentation.targetContext.cacheDir,"t4-$label-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
            finally { bitmap.recycle() }
        }
        saveNative(label)
    }
    private fun panelWithinSettledHost(): Boolean {
        var matches = false
        instrumentation.runOnMainSync {
            val service = MinisAccessibilityService.getInstance() ?: return@runOnMainSync
            val view = service.dotOverlay.panelView ?: return@runOnMainSync
            val host = service.dotScreen.hostViewport(service.dotOverlay.panelHostWindowId) ?: return@runOnMainSync
            val point = IntArray(2); view.getLocationOnScreen(point)
            val expected = io.github.nanomuse.dot.overlay.foregroundFrame(host, host.height * .55f, service.resources.displayMetrics.density).bounds
            matches = point[0] == Math.round(expected.left) && point[1] == Math.round(expected.top) &&
                view.width == Math.round(expected.width) && view.height == Math.round(expected.height)
            // Preserve the exact acceptance predicate. On failure, distinguish a stale host/root
            // observation from controller parameters or a WindowManager-clipped native frame.
            val params = view.layoutParams as WindowManager.LayoutParams
            val display = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
            val size = android.graphics.Point(); display.getRealSize(size)
            val observation = "hostId=${service.dotOverlay.panelHostWindowId} host=$host expected=$expected " +
                "actual=${point[0]},${point[1]},${view.width},${view.height} " +
                "params=${params.x},${params.y},${params.width},${params.height} " +
                "attached=${view.isAttachedToWindow} shown=${view.isShown} " +
                "display=$size rotation=${display.rotation} density=${service.resources.displayMetrics.density} " +
                "fontScale=${service.resources.configuration.fontScale} matches=$matches"
            if (observation != previousPanelGeometryObservation) {
                println("T4_PANEL_GEOMETRY $observation")
                previousPanelGeometryObservation = observation
            }
            if (matches) println("T4_SETTLED_PANEL host=$host frame=${point[0]},${point[1]},${view.width},${view.height}")
        }
        return matches
    }
    private fun observeImeAnimation(service: MinisAccessibilityService) {
        if (Build.VERSION.SDK_INT < 30) return
        instrumentation.runOnMainSync {
            requireNotNull(service.dotOverlay.panelView).setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: android.view.WindowInsetsAnimation) {
                    if (animation.typeMask and android.view.WindowInsets.Type.ime() != 0) { imeAnimationRunning = true; imeAnimationEnded = false }
                }
                override fun onProgress(insets: android.view.WindowInsets, runningAnimations: MutableList<android.view.WindowInsetsAnimation>): android.view.WindowInsets = insets
                override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                    if (animation.typeMask and android.view.WindowInsets.Type.ime() != 0) {
                        imeAnimationRunning = false; imeAnimationEnded = true
                        println("T4_NATIVE_IME animationEnded=true")
                    }
                }
            })
        }
    }
    private fun imeBounds(windows: List<AccessibilityWindowInfo>): android.graphics.Rect? {
        try {
            return windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let {
                android.graphics.Rect().also(it::getBoundsInScreen)
            }
        } finally { windows.forEach { it.recycle() } }
    }
    private fun serverImeBounds(): android.graphics.Rect? {
        // Independent server observation prevents the two client caches agreeing on an early
        // animation frame from being mistaken for the keyboard's final visible geometry.
        val line = shell("dumpsys accessibility").lineSequence().firstOrNull {
            it.startsWith("A11yWindow[") && it.contains("type=TYPE_INPUT_METHOD,")
        } ?: return null
        val match = Regex("""bounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""").find(line) ?: return null
        return android.graphics.Rect(match.groupValues[1].toInt(),match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),match.groupValues[4].toInt())
    }
    private fun keyboardShown(service: MinisAccessibilityService, originalHostHeight: Float): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && service.dotOverlay.panelView?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true) return false
        val serviceIme = imeBounds(service.windows)
        val observedIme = imeBounds(automation.windows)
        val actualIme = serverImeBounds()
        val host = service.dotScreen.hostViewport(service.dotOverlay.panelHostWindowId) ?: return false
        if (actualIme == null || serviceIme != actualIme || observedIme != actualIme ||
            host.height >= originalHostHeight || host.bottom.toInt() != actualIme.top ||
            host.right <= actualIme.left || host.left >= actualIme.right) {
            previousShownGeometry = null; return false
        }
        if (Build.VERSION.SDK_INT >= 30 && (imeAnimationRunning || !imeAnimationEnded)) {
            previousShownGeometry = null; return false
        }
        val editor = android.graphics.Rect()
        val observedWindows = automation.windows
        val editorVisible = try { observedWindows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.any { window ->
            window.root?.let { root -> try { editableBounds(root,editor) && editor.width() > 0 && editor.height() > 0 &&
                editor.left >= host.left && editor.top >= host.top && editor.right <= host.right && editor.bottom <= host.bottom }
                finally { root.recycle() } } == true
        } } finally { observedWindows.forEach { it.recycle() } }
        val settled = editorVisible && host == previousShownGeometry && panelWithinSettledHost()
        previousShownGeometry = host
        if (settled) println("T4_NATIVE_IME settled=true server=$actualIme service=$serviceIme automation=$observedIme host=$host editor=$editor")
        return settled
    }
    private fun keyboardHidden(service: MinisAccessibilityService): Boolean {
        val windows = service.windows
        val nativeHidden = try { windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } finally { windows.forEach { it.recycle() } }
        return nativeHidden && automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } &&
            (Build.VERSION.SDK_INT < 30 || service.dotOverlay.panelView?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false)
    }
    // Compose virtual nodes are read through actual native child traversal; provider text-search
    // support is optional. This asserts the same visible text, without a Compose semantic action.
    private fun containsNativeText(node: android.view.accessibility.AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString() == text) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (containsNativeText(child,text)) return true } finally { child.recycle() }
        }
        return false
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis()+10000
        while (!condition() && SystemClock.uptimeMillis()<until) Thread.sleep(100)
        assertTrue("native state did not settle", condition())
    }
    private fun overlayConnectedOnMain(): Boolean {
        var connected = false
        // The instance is published in onCreate before onServiceConnected initializes the
        // lazy main-thread controller. A background first read can deadlock its initialization.
        instrumentation.runOnMainSync {
            connected = MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true
        }
        return connected
    }
    private fun waitForSettledFixtureHost() {
        var previous: Pair<Int, RectPx>? = null
        waitFor {
            var current: Pair<Int, RectPx>? = null
            instrumentation.runOnMainSync {
                val service = MinisAccessibilityService.getInstance() ?: return@runOnMainSync
                val id = service.dotScreen.hostWindowId() ?: return@runOnMainSync
                val viewport = service.dotScreen.hostViewport(id) ?: return@runOnMainSync
                val display = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
                val size = android.graphics.Point(); display.getRealSize(size)
                val windows = service.windows
                try {
                    val window = windows.firstOrNull { it.id == id && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                        ?: return@runOnMainSync
                    val bounds = android.graphics.Rect(); window.getBoundsInScreen(bounds)
                    val root = window.root ?: return@runOnMainSync
                    try {
                        val rootBounds = android.graphics.Rect(); root.getBoundsInScreen(rootBounds)
                        // Font configuration can recreate the fixture after service.resources
                        // changes. Reject its transient root translation and absent native host.
                        if (window.isActive && root.windowId == id && root.packageName == "io.github.nanodot.app.test" &&
                            containsNativeText(root, "HOST_DOT_CHAT_T4_8C6A") && rootBounds == bounds &&
                            bounds.width() > 0 && bounds.height() > 0 && bounds.left >= 0 && bounds.top >= 0 &&
                            bounds.right <= size.x && bounds.bottom <= size.y &&
                            viewport == RectPx(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())) {
                            current = id to viewport
                        }
                    } finally { root.recycle() }
                } finally { windows.forEach { it.recycle() } }
            }
            val settled = current != null && current == previous
            previous = current
            settled
        }
    }
    // Read before obtaining UiAutomation: its connection temporarily enables accessibility.
    private fun secureSetting(key: String): String? = android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver,key)
    private fun restoreSecure(key: String, value: String?) {
        if (value == null) shell("settings delete secure $key")
        else {
            // executeShellCommand executes argv directly; shell quotes would become literal values.
            val valid = when (key) {
                "accessibility_enabled" -> value == "0" || value == "1"
                "enabled_accessibility_services" -> value.split(':').all { it.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) }
                else -> false
            }
            if (!valid && secureSetting(key) == value) return // Unsupported original was never mutated.
            require(valid) { "Unsupported secure setting argv; preserve for controller" }
            shell("settings put secure $key $value")
        }
    }
    private fun restoreNativeSettings() {
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
        originalDisplay?.let { (size,density) ->
            val sizeOverride = size.lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(':')?.trim()
            val densityOverride = density.lineSequence().firstOrNull { it.startsWith("Override density:") }?.substringAfter(':')?.trim()
            attempt { shell("wm size ${sizeOverride ?: "reset"}") }
            attempt { shell("wm density ${densityOverride ?: "reset"}") }
            attempt { assertEquals(size,shell("wm size")) }
            attempt { assertEquals(density,shell("wm density")) }
        }
        // Attempt every restoration and retain the first failure even when several checks fail.
        originalNativeSettings?.forEach { (key,value) -> attempt { restoreSecure(key,value) } }
        originalNativeSettings?.forEach { (key,value) ->
            attempt { assertEquals("Original $key was not restored",value,secureSetting(key)) }
        }
        if (failures.isNotEmpty()) throw failures.first().apply { failures.drop(1).forEach(::addSuppressed) }
        println("T4_NATIVE_SETTINGS originalAccessibilityAndDisplayRestored=true")
    }

    @Before fun connected() {
        // Capture before any mutation, including failures partway through @Before.
        originalNativeSettings = listOf("enabled_accessibility_services","accessibility_enabled").associateWith(::secureSetting)
        // Reject unsupported originals before the first UiAutomation connection/mutation.
        originalNativeSettings?.forEach { (key,value) ->
            if (value != null) require(when (key) {
                "accessibility_enabled" -> value == "0" || value == "1"
                else -> value.split(':').all { it.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) }
            }) { "Unsupported original native setting; preserve for controller" }
        }
        originalDisplay = shell("wm size") to shell("wm density")
        val observation = automation.serviceInfo
        previousObservationFlags = observation.flags
        observation.flags = observation.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = observation
        println("T4_NATIVE_OBSERVATION flagsBefore=$previousObservationFlags flagsAfter=${automation.serviceInfo.flags}")
        shell("input keyevent 224"); shell("input keyevent 82")
        if (!overlayConnectedOnMain()) {
            // Remove only our DEAD binding; preserve TalkBack and every unrelated service.
            val originalServices = originalNativeSettings?.get("enabled_accessibility_services")
            val others = originalServices.orEmpty().split(':').filter { it.isNotEmpty() && it != ownService }
            restoreSecure("enabled_accessibility_services",if (others.isEmpty()) null else others.joinToString(":"))
            waitFor {
                shell("dumpsys accessibility").lineSequence().filter {
                    it.contains("Bound services:") || it.contains("Binding services:")
                }.none { it.contains("MinisAccessibilityService") }
            }
            restoreSecure("enabled_accessibility_services",(others + ownService).joinToString(":"))
            restoreSecure("accessibility_enabled","1")
        }
        waitFor { overlayConnectedOnMain() }
        shell("am start -W -n io.github.nanodot.app.test/io.github.nanomuse.dot.screen.ScreenHostFixture --es marker HOST_DOT_CHAT_T4_8C6A -f 0x10008000")
        waitFor {
            val root = MinisAccessibilityService.getInstance()?.rootInActiveWindow
            root?.let { try { it.packageName == "io.github.nanodot.app.test" } finally { it.recycle() } } == true
        }
        // rootInActiveWindow can arrive before the interactive windows list. Admission requires
        // the intended host's matching native identity/frame in two consecutive observations.
        var previous: Pair<Int,RectPx>? = null
        waitFor {
            val service = requireNotNull(MinisAccessibilityService.getInstance())
            val id = service.dotScreen.hostWindowId()
            val viewport = id?.let { service.dotScreen.hostViewport(it) }
            val root = service.rootInActiveWindow
            val intended = root?.let { try { it.windowId == id && it.packageName == "io.github.nanodot.app.test" && containsNativeText(it,"HOST_DOT_CHAT_T4_8C6A") } finally { it.recycle() } } == true
            val current = if (id != null && viewport != null && intended) id to viewport else null
            val settled = current != null && current == previous
            previous = current
            settled
        }
        saveHostState("before-admission")
        val app = instrumentation.targetContext.applicationContext as MinisApp
        val prefs = app.getSharedPreferences("nanomuse",android.content.Context.MODE_PRIVATE)
        previousFirstSession = prefs.getString("first_conversation.session",null)
        previousFirstPhase = prefs.getString("first_conversation.phase",null)
        fixtureId = java.util.UUID.randomUUID().toString()
        check(!com.openminis.app.service.SessionBadgeStore.byId.value.containsKey(fixtureId))
        fixtureTitle = "DOT_T4_LOCAL_ONLY_$fixtureId"
        fixtureParts = org.json.JSONArray().put(org.json.JSONObject().put("type","text").put("value",fixtureTitle)).toString()
        runBlocking {
            app.chatRepository.dao.insertSession(com.openminis.app.data.db.ChatSessionEntity(
                id=fixtureId,title=fixtureTitle,modelId=localModel,createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis(),memoryEnabled=0))
            val firstMessageId=java.util.UUID.randomUUID().toString()
            ownedMessageParts[firstMessageId]=fixtureParts
            app.chatRepository.dao.insertMessage(com.openminis.app.data.db.MessageEntity(
                id=firstMessageId,sessionId=fixtureId,role="user",partsJson=fixtureParts,createdAt=System.currentTimeMillis(),sortOrder=0))
        }
        java.io.File(app.cacheDir,"t4-owned-session-$fixtureId.json").writeText(org.json.JSONObject()
            .put("id",fixtureId).put("title",fixtureTitle).put("modelId",localModel).put("modelBinding",org.json.JSONObject.NULL)
            .put("roles",org.json.JSONArray().put("user")).toString())
    }
    @After fun cleanupOwnedFixture() {
        try {
            previousObservationFlags?.let { original ->
                val observation = automation.serviceInfo; observation.flags = original; automation.serviceInfo = observation
                assertEquals(original,automation.serviceInfo.flags)
                println("T4_NATIVE_OBSERVATION restored=${automation.serviceInfo.flags}")
            }
            if (fixtureId.isEmpty()) return
            val app = instrumentation.targetContext.applicationContext as MinisApp
            instrumentation.runOnMainSync { MinisAccessibilityService.getInstance()?.dotOverlay?.collapsePanel(); ChatViewModelStore.release(fixtureId) }
            runBlocking {
                val row = requireNotNull(app.chatRepository.getSession(fixtureId))
                val messages = app.chatRepository.dao.loadMessages(fixtureId)
                check(row.id == fixtureId && row.title == fixtureTitle && row.modelId == localModel && row.modelBinding == null &&
                    messages.all { it.role == "user" && ownedMessageParts[it.id] == it.partsJson }) { "Owned fixture guard changed; preserve row for controller" }
                java.io.File(app.cacheDir,"t4-cleanup-owned-$fixtureId.json").writeText(org.json.JSONObject()
                    .put("id",row.id).put("title",row.title).put("modelId",row.modelId)
                    .put("rows",org.json.JSONArray(messages.map { org.json.JSONObject().put("id",it.id).put("role",it.role).put("parts",it.partsJson) })).toString())
                app.chatRepository.dao.deleteMessages(fixtureId); app.chatRepository.dao.deleteSession(fixtureId)
                val sync = io.github.nanomuse.sync.SyncDatabase.get(app).dao()
                sync.removeOwners(listOf(fixtureId)); sync.removeConversations(listOf(fixtureId)); sync.removeMessagesOfAll(listOf(fixtureId))
                com.openminis.app.data.storage.MediaStore(app).deleteSessionMedia(fixtureId)
                java.io.File(app.filesDir,"minis-sessions/$fixtureId").takeIf { it.name == fixtureId }?.deleteRecursively()
                check(app.chatRepository.getSession(fixtureId) == null)
                val badgesBefore = com.openminis.app.service.SessionBadgeStore.byId.value
                check(badgesBefore[fixtureId].orEmpty().all { it == com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED })
                com.openminis.app.service.SessionBadgeStore.remove(fixtureId,com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED)
                assertEquals(badgesBefore-fixtureId,com.openminis.app.service.SessionBadgeStore.byId.value)
                println("T4_OWNED_CLEANUP id=$fixtureId rows=${messages.size} localNullBinding=true")
            }
            val edit = app.getSharedPreferences("nanomuse",android.content.Context.MODE_PRIVATE).edit()
            if (previousFirstSession == null) edit.remove("first_conversation.session") else edit.putString("first_conversation.session",previousFirstSession)
            if (previousFirstPhase == null) edit.remove("first_conversation.phase") else edit.putString("first_conversation.phase",previousFirstPhase)
            check(edit.commit())
        } finally {
            // Runs even if observation, DB ownership, or partial @Before assertions fail.
            restoreNativeSettings()
        }
    }
    /** In-memory provider only: actual canonical code executes, no URL/key/socket exists. */
    private class InertProvider(override var model: LLMModel): LLMProvider {
        override val name = "Task4 inert local control"
        val compactEntered = CompletableDeferred<Unit>()
        val streamEntered = CompletableDeferred<Unit>()
        val releaseCompact = CompletableDeferred<Unit>()
        val compactCalls = java.util.concurrent.atomic.AtomicInteger()
        val streamCalls = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): LLMResponse {
            compactCalls.incrementAndGet(); compactEntered.complete(Unit)
            releaseCompact.await()
            error("Controlled late compaction failure, zero sockets")
        }
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) = flow<LLMStreamChunk> {
            streamCalls.incrementAndGet(); streamEntered.complete(Unit); awaitCancellation()
        }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(owner,value)
    @Suppress("UNCHECKED_CAST")
    private fun configureInert(vm: ChatViewModel, marker: String): InertProvider {
        val provider = InertProvider(LLMModel(marker,marker,"Task4 inert",contextWindow=128000,maxOutputTokens=128,inputModalities=listOf("text","image")))
        instrumentation.runOnMainSync {
            setField(vm,"currentProvider",provider); setField(vm,"currentModel",provider.model)
            (field(vm,"_modelName") as kotlinx.coroutines.flow.MutableStateFlow<String>).value=marker
        }
        return provider
    }
    private fun openCanonical(adapter: DotChatAdapter, id: String,
        foregroundOwner: io.github.nanomuse.dot.DotInteractionCoordinator? = null): ChatViewModel {
        val service=requireNotNull(MinisAccessibilityService.getInstance())
        val app=service.application as MinisApp
        lateinit var vm: ChatViewModel
        instrumentation.runOnMainSync {
            adapter.bind(id); vm=requireNotNull(adapter.businessViewModel)
            assertSame(vm,ViewModelProvider(ChatViewModelStore.ownerFor(id),ChatViewModel.factory(
                id,app.chatRepository,app.providerRepository,app,app.memoryRepository,app.skillRepository,app.mcpRepository))[ChatViewModel::class.java])
            service.dotOverlay.setEnabled(true)
            if (foregroundOwner == null) service.dotOverlay.showPanel(adapter) { adapter.binding.value?.let(adapter::cancel) }
            else {
                val previousActive = ChatViewModelStore.activeSessionId
                ChatViewModelStore.setActiveSession(id)
                try { foregroundOwner.connect(); foregroundOwner.handle(io.github.nanomuse.dot.gesture.DotIntent.Single) }
                finally { ChatViewModelStore.setActiveSession(previousActive) }
            }
        }
        waitFor { shell("logcat -d -v brief -s System.out:I").contains("loadSession EXIT session=$id ") }
        try {
            waitFor { service.dotOverlay.panelView?.isAttachedToWindow == true && panelWithinSettledHost() }
        } catch (failure: AssertionError) {
            // Capture before placement's finally collapses the window/restores font settings.
            try { saveHostState("canonical-settle-timeout"); savePanelNative("canonical-settle-timeout") }
            catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        }
        return vm
    }
    private class HeldRoom(app: MinisApp) : AutoCloseable {
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>()
        private val release=CompletableDeferred<Unit>()
        private val job=scope.launch {
            app.database.withTransaction {
                check(app.database.openHelper.writableDatabase.inTransaction())
                println("T4_ROOM heldTransaction=true")
                entered.complete(Unit); release.await()
            }
        }
        init { runBlocking { withTimeout(10000) { entered.await() } } }
        override fun close() { release.complete(Unit);runBlocking { withTimeout(10000) { job.join() } };scope.cancel() }
    }
    private fun tapNativeStop() {
        val app=instrumentation.targetContext
        val text=app.getString(com.openminis.app.R.string.nm_dot_chat_stop)
        val bounds=android.graphics.Rect()
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if(node.text?.toString()==text) { node.getBoundsInScreen(bounds);return bounds.width()>0 && bounds.height()>0 }
            for(i in 0 until node.childCount) { val child=node.getChild(i) ?: continue
                try { if(find(child)) return true } finally { child.recycle() } }
            return false
        }
        waitFor {
            val windows=automation.windows
            try { windows.filter { it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.any { w ->
                w.root?.let { root -> try { find(root) } finally { root.recycle() } }==true }
            } finally { windows.forEach { it.recycle() } }
        }
        println("T4_NATIVE_STOP bounds=$bounds")
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    @Test fun freshActiveSetupStopRevokesHeldRoomWork() {
        val app=instrumentation.targetContext.applicationContext as MinisApp
        val adapter=DotChatAdapter.create(app)
        val draft="__new__${java.util.UUID.randomUUID()}"
        val marker="DOT_T4_FRESH_$draft"
        val vm=openCanonical(adapter,draft)
        val provider=configureInert(vm,marker)
        try {
            HeldRoom(app).use {
                instrumentation.runOnMainSync { assertTrue(adapter.send(requireNotNull(adapter.binding.value),marker)) }
                assertTrue(vm.isStreaming.value)
                val setup=field(vm,"sendSetupJob") as Job
                assertTrue(setup.isActive); assertEquals("",field(vm,"realSessionId"))
                savePanelNative("fresh-active-before-stop")
                tapNativeStop()
                waitFor { !vm.isStreaming.value && setup.isCancelled }
                assertEquals(0,provider.streamCalls.get())
            }
            runBlocking {
                assertTrue(app.chatRepository.dao.listSessions().none { it.modelId==marker })
                (field(vm,"sendSetupJob") as Job).join()
            }
            assertTrue(vm.messages.value.none { it.role=="user" && it.content==marker })
            assertEquals(0,provider.streamCalls.get()); assertEquals("",field(vm,"realSessionId"))
            println("T4_FRESH_ACTIVE cancelledSetup=true persistedRows=0 lateRequests=0 zeroSockets=true introNativeFirstInstall=false")
        } finally { instrumentation.runOnMainSync { adapter.binding.value?.let(adapter::cancel);MinisAccessibilityService.getInstance()?.dotOverlay?.collapsePanel();ChatViewModelStore.release(draft) } }
    }
    @Test fun nativeCompactionStopRevokesQueuedContinuation() {
        val app=instrumentation.targetContext.applicationContext as MinisApp
        val adapter=DotChatAdapter.create(app)
        val vm=openCanonical(adapter,fixtureId)
        waitFor { vm.messages.value.any { it.content==fixtureTitle } }
        val provider=configureInert(vm,"DOT_T4_COMPACT_$fixtureId")
        val before=runBlocking { app.chatRepository.dao.loadMessages(fixtureId) }
        try {
            instrumentation.runOnMainSync { setField(vm,"pendingSendText",fixtureTitle+" cancelled continuation"); vm.compactAndSendPending() }
            waitFor { provider.compactEntered.isCompleted && vm.isCompacting.value && vm.pendingCompactSend.value }
            assertFalse(vm.isStreaming.value)
            savePanelNative("compaction-before-stop");tapNativeStop()
            waitFor { !vm.isCompacting.value && !vm.pendingCompactSend.value }
            provider.releaseCompact.complete(Unit)
            runBlocking { (field(vm,"compactThenSendJob") as Job).join();(field(vm,"compactJob") as? Job)?.join() }
            assertEquals(before,runBlocking { app.chatRepository.dao.loadMessages(fixtureId) })
            assertEquals(1,provider.compactCalls.get());assertEquals(0,provider.streamCalls.get())
            println("T4_COMPACT nativeStopReachable=true queuedContinuationRevoked=true lateRequests=0 zeroSockets=true")
        } finally { instrumentation.runOnMainSync { adapter.binding.value?.let(adapter::cancel);MinisAccessibilityService.getInstance()?.dotOverlay?.collapsePanel() };provider.releaseCompact.complete(Unit) }
    }
    @Test fun queuedCanonicalPromptKeepsOwnedScreenshotUntilPersistedMediaReceipt() {
        val app=instrumentation.targetContext.applicationContext as MinisApp
        val adapter=DotChatAdapter.create(app)
        val vm=openCanonical(adapter,fixtureId)
        waitFor { vm.messages.value.any { it.content==fixtureTitle } }
        val provider=configureInert(vm,"DOT_T4_MEDIA_$fixtureId")
        val caption=fixtureTitle+" owned screenshot"
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val bounds=RectPx(0f,0f,16f,16f)
        val binding=requireNotNull(adapter.binding.value)
        try {
            instrumentation.runOnMainSync {
                assertTrue(adapter.attach(binding,ScreenSnapshot("t4-$fixtureId",binding.generation,System.currentTimeMillis(),
                    SceneKey("io.github.nanodot.app.test",1,0,1),bounds,0,RectPx(0f,0f,0f,0f),null,emptyList(),bitmap,ScreenTransform(bounds,16,16))))
            }
            val stagedName=requireNotNull(adapter.preview.value).file.name
            val admittedAt=System.currentTimeMillis()
            lateinit var attachment:DotOwnedAttachment
            HeldRoom(app).use {
                instrumentation.runOnMainSync {
                    assertTrue(adapter.send(binding,caption))
                    attachment=field(requireNotNull(field(adapter,"owned")),"attachment") as DotOwnedAttachment
                    vm.enqueuePrompt(fixtureTitle+" independent queued prompt")
                    adapter.releaseConsumedFiles()
                }
                assertTrue(vm.messages.value.any { it.isQueued })
                assertNull(vm.consumedAttachmentMessages.value[attachment.id]);assertTrue(attachment.file.exists())
                println("T4_MEDIA queuedPublication=true receiptAbsent=true inboundRetained=true attachmentId=${attachment.id}")
            }
            waitFor { vm.consumedAttachmentMessages.value[attachment.id]!=null }
            val messageId=requireNotNull(vm.consumedAttachmentMessages.value[attachment.id])
            val row=runBlocking { app.chatRepository.dao.loadMessages(fixtureId).single { it.id==messageId } }
            val parts=org.json.JSONArray(row.partsJson)
            assertEquals(listOf("text","mediaRef","text"),(0 until parts.length()).map { parts.getJSONObject(it).getString("type") })
            val media=(0 until parts.length()).map { parts.getJSONObject(it) }.single { it.getString("type")=="mediaRef" }.getJSONObject("value")
            val mediaFile=java.io.File(app.filesDir,"media/"+media.getString("relativePath"))
            assertEquals("user",row.role);assertEquals(fixtureId,row.sessionId)
            assertEquals(caption,parts.getJSONObject(0).getString("value"))
            assertTrue(media.getString("relativePath").split('/').contains(fixtureId));assertTrue(mediaFile.exists())
            assertEquals(stagedName,media.getString("originalFileName"))
            assertEquals("image/png",media.getString("mimeType"))
            val linuxPath="/var/minis/attachments/uploads/$stagedName"
            assertEquals(linuxPath,media.getString("linuxPath"))
            val upload=java.io.File(app.filesDir,"minis-sessions/$fixtureId/attachments/uploads/$stagedName")
            assertTrue(upload.exists());assertArrayEquals(mediaFile.readBytes(),upload.readBytes())
            val xml=parts.getJSONObject(2).getString("value")
            val modified=requireNotNull(Regex("modified=\"([^\"]+)\"").find(xml)).groupValues[1]
            val parser=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",java.util.Locale.US).apply { timeZone=java.util.TimeZone.getTimeZone("UTC");isLenient=false }
            val timestamp=requireNotNull(parser.parse(modified)).time
            assertTrue(timestamp >= admittedAt-1000 && timestamp <= System.currentTimeMillis()) // metadata has second precision.
            val expectedXml="<user-attached-files>\n  <file path=\"$linuxPath\" url=\"minis://attachments/uploads/$stagedName\" size=\"${mediaFile.length()}\" modified=\"$modified\" />\n</user-attached-files>"
            assertEquals(expectedXml,xml)
            // Canonical parts also carry attached-files XML; pin the exact owned row/metadata,
            // rather than mistaking that additional text part for a foreign message.
            ownedMessageParts[row.id]=row.partsJson
            assertTrue(vm.messages.value.any { it.id==messageId && !it.isQueued && it.imageUris.any { uri -> uri.path==mediaFile.path } })
            instrumentation.runOnMainSync { adapter.releaseConsumedFiles() }
            assertFalse(attachment.file.exists())
            waitFor { provider.streamEntered.isCompleted }
            instrumentation.runOnMainSync { vm.messages.value.filter { it.isQueued }.forEach { vm.removeQueuedPrompt(requireNotNull(it.queuedPromptId)) } }
            tapNativeStop();waitFor { !vm.isStreaming.value }
            runBlocking { (field(vm,"sendSetupJob") as Job).join() }
            assertTrue("Confirmed history media belongs to persisted canonical row",mediaFile.exists())
            assertEquals(1,provider.streamCalls.get())
            println("T4_MEDIA attachmentId=${attachment.id} persistedMessageId=$messageId mediaId=${media.getString("id")} exactReceipt=true confirmedHistoryKept=true admittedStreamStopped=true zeroSockets=true heldBoundary=RoomUserInsert")
        } finally { instrumentation.runOnMainSync { vm.messages.value.filter { it.isQueued }.forEach { vm.removeQueuedPrompt(requireNotNull(it.queuedPromptId)) };adapter.binding.value?.let(adapter::cancel);MinisAccessibilityService.getInstance()?.dotOverlay?.collapsePanel() } }
    }
    /** Actual mounted service panel, canonical VM, inert held stream and native 200% controls. */
    @Test fun placementKeepsDraftHistoryAndBinding() {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val app = service.application as MinisApp
        val originalFont = shell("settings get system font_scale").trim()
        require(originalFont == "null" || originalFont.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        val adapter = DotChatAdapter.create(app)
        val foregroundOwner = io.github.nanomuse.dot.DotInteractionCoordinator(service, adapter)
        var restoreCanonicalHistory: (() -> Unit)? = null
        try {
            shell("settings put system font_scale 2.0")
            waitFor { service.resources.configuration.fontScale == 2f }
            waitForSettledFixtureHost()
            saveHostState("font-ready")
            val vm = openCanonical(adapter, fixtureId, foregroundOwner)
            val provider = configureInert(vm, "DOT_PLACEMENT_INERT")
            val binding = requireNotNull(adapter.binding.value)
            instrumentation.runOnMainSync { assertTrue(adapter.send(binding, "Owned placement stream")) }
            waitFor { provider.streamEntered.isCompleted &&
                foregroundOwner.foreground.state.value.operation == io.github.nanomuse.dot.DotForegroundOperation.Replying }
            assertEquals(binding, foregroundOwner.foreground.state.value.binding)
            // Register only this fixture's locally persisted user rows for its existing cleanup guard.
            runBlocking { app.chatRepository.dao.loadMessages(fixtureId).forEach { row ->
                check(row.role == "user"); ownedMessageParts[row.id] = row.partsJson
            } }
            @Suppress("UNCHECKED_CAST")
            val history = field(vm, "_messages") as kotlinx.coroutines.flow.MutableStateFlow<List<com.openminis.app.ui.chat.ChatMessage>>
            instrumentation.runOnMainSync {
                val canonicalBeforeFixture = history.value
                restoreCanonicalHistory = { history.value = canonicalBeforeFixture }
                history.value = (0 until 40).map { index ->
                    com.openminis.app.ui.chat.ChatMessage("placement-$index", if (index % 2 == 0) "user" else "assistant", "Retained history $index")
                }
                adapter.editInput("Retained placement draft")
            }
            waitFor { service.dotOverlay.panelTranscriptState?.layoutInfo?.totalItemsCount == 40 }
            val view = requireNotNull(service.dotOverlay.panelView)
            val list = requireNotNull(service.dotOverlay.panelTranscriptState)
            runBlocking(Dispatchers.Main) { list.scrollToItem(8, 17) }
            val before = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            val host = requireNotNull(service.dotScreen.hostViewport(service.dotOverlay.panelHostWindowId))
            for (target in listOf(host, null, RectPx(host.left, host.bottom - 120f, host.right, host.bottom))) {
                instrumentation.runOnMainSync { service.dotOverlay.setForegroundTarget(target) }
                instrumentation.waitForIdleSync()
                // Wait for two host traversals and the shared 240ms placement response.
                Thread.sleep(400)
                instrumentation.runOnMainSync {
                    assertSame(view, service.dotOverlay.panelView)
                    assertSame(list, service.dotOverlay.panelTranscriptState)
                    assertEquals(before, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
                    assertEquals(binding, adapter.binding.value)
                    assertEquals("Retained placement draft", vm.inputText.value)
                    assertTrue(vm.isStreaming.value)
                    assertEquals(1, provider.streamCalls.get())
                }
            }
            instrumentation.runOnMainSync { service.dotOverlay.onDisplayChanged(); service.dotOverlay.setForegroundTarget(host) }
            Thread.sleep(400)
            instrumentation.runOnMainSync {
                assertSame(view, service.dotOverlay.panelView)
                assertSame(list, service.dotOverlay.panelTranscriptState)
                assertEquals(before, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
                assertEquals(binding, adapter.binding.value)
            }
            val expand = app.getString(com.openminis.app.R.string.nm_dot_foreground_expand)
            val stop = app.getString(com.openminis.app.R.string.nm_dot_chat_stop)
            fun reachable(root: android.view.accessibility.AccessibilityNodeInfo, text: String?, input: Boolean): Boolean {
                val bounds = android.graphics.Rect(); root.getBoundsInScreen(bounds)
                val panel = requireNotNull(service.dotOverlay.panelView)
                val panelOrigin = IntArray(2); panel.getLocationOnScreen(panelOrigin)
                val panelBounds = android.graphics.Rect(panelOrigin[0], panelOrigin[1], panelOrigin[0] + panel.width, panelOrigin[1] + panel.height)
                val currentHost = requireNotNull(service.dotScreen.hostViewport(service.dotOverlay.panelHostWindowId))
                val hostBounds = android.graphics.Rect(Math.round(currentHost.left), Math.round(currentHost.top), Math.round(currentHost.right), Math.round(currentHost.bottom))
                val visibleBounds = android.graphics.Rect(bounds)
                val intersects = visibleBounds.intersect(panelBounds) && visibleBounds.intersect(hostBounds)
                if (root.isVisibleToUser && intersects && (if (input) root.isEditable else root.isClickable && root.isEnabled && containsNativeText(root, requireNotNull(text))) &&
                    visibleBounds.width() > 0 && visibleBounds.height() >= 48 * service.resources.displayMetrics.density - 1) return true
                for (i in 0 until root.childCount) { val child = root.getChild(i) ?: continue
                    try { if (reachable(child,text,input)) return true } finally { child.recycle() } }
                return false
            }
            fun scrollControls(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
                var selected: android.view.accessibility.AccessibilityNodeInfo? = null
                var selectedTop = Int.MIN_VALUE
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                    val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                    // Offscreen labels are absent from native AX. Locate the actual LOWER
                    // ScrollView instead of requiring the inaccessible Expand descendant.
                    if (node.isScrollable && node.isVisibleToUser && node.className?.toString() == "android.widget.ScrollView" &&
                        bounds.height() >= 48 * service.resources.displayMetrics.density - 1 && bounds.top > selectedTop) {
                        selected?.recycle(); selected = android.view.accessibility.AccessibilityNodeInfo.obtain(node); selectedTop = bounds.top
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { try { visit(it) } finally { it.recycle() } }
                }
                visit(root)
                return try {
                    selected?.let { node ->
                        val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                        val accepted = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        println("T3_PLACEMENT_SCROLL lowerViewport=$bounds accepted=$accepted")
                        accepted
                    } ?: false
                } finally { selected?.recycle() }
            }
            savePanelNative("placement-controls-before")
            for ((text,input) in listOf(expand to false, null to true, stop to false)) {
                val control = if (input) "input" else if (text == expand) "expand" else "stop"
                println("T3_PLACEMENT_CONTROL seeking=$control minimumVisibleDp=48")
                try { waitFor {
                    val windows = service.windows
                    try { windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.any { window ->
                        window.root?.let { root -> try {
                            if (reachable(root,text,input)) true else { scrollControls(root); false }
                        } finally { root.recycle() } } == true }
                    } finally { windows.forEach { it.recycle() } }
                } } catch (failure: AssertionError) {
                    try { saveHostState("placement-$control-timeout"); savePanelNative("placement-$control-timeout") }
                    catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
                    throw AssertionError("Native placement control $control did not reach visible48dp", failure)
                }
                println("T3_PLACEMENT_CONTROL reachable=$control minimumVisibleDp=48")
            }
            println("T3_PLACEMENT sameWindow=true sameList=true sameBinding=true draftAndPositionRetained=true inertStreamCalls=1 fontScale=2 controlsReachable=true")
        } finally {
            instrumentation.runOnMainSync {
                // The synthetic transcript is a presentation fixture, not an assistant reply.
                // Restore the exact canonical messages before cancellation can persist them.
                restoreCanonicalHistory?.invoke()
                service.dotOverlay.setForegroundTarget(null)
                foregroundOwner.disconnect()
                service.dotInteraction.disconnect(); service.dotInteraction.connect()
            }
            shell(if (originalFont == "null") "settings delete system font_scale" else "settings put system font_scale $originalFont")
            assertEquals(originalFont, shell("settings get system font_scale").trim())
        }
    }

    @Test fun idleCanonicalNewDraftCancellationDoesNotInventInterruptedReply() {
        val app = instrumentation.targetContext.applicationContext as MinisApp
        val adapter = DotChatAdapter.create(app)
        val id = "__new__${java.util.UUID.randomUUID()}"
        lateinit var admitted: SessionBinding
        instrumentation.runOnMainSync { admitted=adapter.bind(id) }
        try {
            waitFor { adapter.businessViewModel!!.messages.value.any { it.id.startsWith("nm_intro_") } }
            instrumentation.runOnMainSync {
                val vm = requireNotNull(adapter.businessViewModel)
                assertFalse(vm.isStreaming.value); assertFalse(vm.canResume.value)
                val before = vm.messages.value
                adapter.cancel(admitted)
                assertNull(adapter.binding.value)
                assertEquals(before,vm.messages.value)
                assertFalse("Idle new-chat intro must not become an interrupted response",vm.canResume.value)
            }
        } finally { instrumentation.runOnMainSync { ChatViewModelStore.release(id) } }
    }
    @Test fun coordinatorReopeningExistingPanelAdmitsFreshGeneration() {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val previous = ChatViewModelStore.activeSessionId
        try {
            instrumentation.runOnMainSync {
                ChatViewModelStore.setActiveSession(fixtureId)
                service.dotOverlay.setEnabled(true)
                service.dotInteraction.handle(io.github.nanomuse.dot.gesture.DotIntent.Single)
            }
            waitFor { service.dotOverlay.panelView?.isAttachedToWindow == true }
            val original = service.dotOverlay.panelView
            instrumentation.runOnMainSync { service.dotInteraction.handle(io.github.nanomuse.dot.gesture.DotIntent.Single) }
            waitFor { service.dotOverlay.panelView != null && service.dotOverlay.panelView !== original }
        } finally { instrumentation.runOnMainSync { service.dotInteraction.close(); ChatViewModelStore.setActiveSession(previous) } }
    }
    @Test fun actualPanelHasAccessibilityOverlayTypeAndCanonicalBusinessOwner() {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val app = service.application as MinisApp
        val adapter = DotChatAdapter.create(app)
        val id = fixtureId
        try {
            instrumentation.runOnMainSync {
                service.dotOverlay.setEnabled(true)
                adapter.bind(id)
                val canonicalVM = ViewModelProvider(ChatViewModelStore.ownerFor(id), ChatViewModel.factory(
                    id,app.chatRepository,app.providerRepository,app,app.memoryRepository,app.skillRepository,app.mcpRepository
                ))[ChatViewModel::class.java]
                assertSame(canonicalVM, adapter.businessViewModel)
                service.dotOverlay.showPanel(adapter) { adapter.binding.value?.let(adapter::cancel) }
                val view = requireNotNull(service.dotOverlay.panelView)
                assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    (view.layoutParams as WindowManager.LayoutParams).type)
                assertTrue((view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0)
            }
            waitFor { service.dotOverlay.panelView?.isAttachedToWindow == true }
            instrumentation.runOnMainSync { assertTrue(requireNotNull(service.dotOverlay.panelView).isAttachedToWindow) }
            savePanelNative("panel-attached")
            waitFor { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                it.root?.let { root -> try { containsNativeText(root,"Dot") } finally { root.recycle() } } == true } }
            waitFor { panelWithinSettledHost() }; saveNative("panel")
            instrumentation.runOnMainSync { service.dotOverlay.collapsePanel() }
            instrumentation.runOnMainSync { assertNull(service.dotOverlay.panelView); assertNotNull(service.dotOverlay.orbView) }
        } finally {
            savePanelNative("panel-final")
            instrumentation.runOnMainSync { service.dotOverlay.collapsePanel(); ChatViewModelStore.release(id) }
        }
    }
    @Test fun nativeBackFirstHidesImeThenCollapsesPanel() {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val adapter = DotChatAdapter.create(service.application as MinisApp)
        instrumentation.runOnMainSync {
            adapter.bind(fixtureId)
            service.dotOverlay.setEnabled(true)
            service.dotOverlay.showPanel(adapter) { adapter.binding.value?.let(adapter::cancel) }
        }
        try {
            waitFor { automation.windows.any { window -> window.root?.let { root ->
                try { root.findAccessibilityNodeInfosByViewId("dot_composer").isNotEmpty() || findEditable(root) } finally { root.recycle() }
            } == true } }
            // Tap the actual native editable bounds, never a semantic click behind the IME.
            val nodes = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.mapNotNull { it.root }
            val bounds = android.graphics.Rect()
            try { assertTrue(nodes.any { editableBounds(it,bounds) }) } finally { nodes.forEach { it.recycle() } }
            val originalHostHeight = requireNotNull(service.dotScreen.hostViewport(service.dotOverlay.panelHostWindowId)).height
            observeImeAnimation(service)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            waitFor { keyboardShown(service,originalHostHeight) }; saveNative("ime")
            shell("input keyevent ${KeyEvent.KEYCODE_BACK}")
            waitFor { keyboardHidden(service) }; saveNative("after-first-back")
            instrumentation.runOnMainSync { assertNotNull(service.dotOverlay.panelView) }
            shell("input keyevent ${KeyEvent.KEYCODE_BACK}")
            waitFor { service.dotOverlay.panelView == null }
        } finally {
            savePanelNative("ime-final"); saveHostState("ime-final")
            instrumentation.runOnMainSync { service.dotOverlay.collapsePanel(); ChatViewModelStore.release(fixtureId) }
        }
    }
    @Test fun confirmedHistoryMediaSurvivesOwnedPreviewCancellation() = runBlocking<Unit> {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val app = service.application as MinisApp
        val historyFile = java.io.File(app.cacheDir,"t4-history-${fixtureId}.png").apply { writeBytes(byteArrayOf(1,2,3)) }
        // This method uses a real history URI on the canonical shared VM; no fake session.
        val adapter = DotChatAdapter.create(app)
        val binding = withContext(Dispatchers.Main) { adapter.bind(fixtureId) }
        try {
            val vm = requireNotNull(adapter.businessViewModel)
            val history = com.openminis.app.ui.chat.ChatMessage("t4-history","user","history",imageUris=listOf(android.net.Uri.fromFile(historyFile)))
            val field = ChatViewModel::class.java.getDeclaredField("_messages").apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST") val messages = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<com.openminis.app.ui.chat.ChatMessage>>
            val prior = messages.value
            withContext(Dispatchers.Main) { messages.value=prior + history }
            try {
                withContext(Dispatchers.Main) { adapter.cancel(binding) }
                assertTrue(historyFile.exists()); assertEquals(historyFile.path,messages.value.last().imageUris.single().path)
            } finally { withContext(Dispatchers.Main) { messages.value=prior } }
        } finally { historyFile.delete(); withContext(Dispatchers.Main) { ChatViewModelStore.release(fixtureId) } }
    }
    @Test fun historicalPreviewKeepsCaptureBindingAndClosesNativeSnapshot() = runBlocking<Unit> {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val adapter = DotChatAdapter.create(service.application as MinisApp)
        val id = fixtureId
        val binding = withContext(Dispatchers.Main) { adapter.bind(id) }
        Thread.sleep(500)
        val result = service.dotScreen.capture(CapturePurpose.Chat,binding.generation)
        assertTrue("fresh host screenshot/text unavailable: $result",result is CaptureOutcome.Success)
        val snapshot = (result as CaptureOutcome.Success).snapshot
        val capturedAt = snapshot.capturedAtMs
        try {
            if (Build.VERSION.SDK_INT >= 30) assertNotNull("Modern native capture must contain real pixels",snapshot.image)
            assertTrue(snapshot.nodes.any { it.text == "HOST_DOT_CHAT_T4_8C6A" })
            println("T4_CAPTURE generation=${snapshot.generation} capturedAt=$capturedAt pixels=${snapshot.image?.width}x${snapshot.image?.height}")
            withContext(Dispatchers.Main) {
                assertTrue(adapter.attach(binding,snapshot))
                assertTrue(snapshot.image?.isRecycled ?: true)
                service.dotOverlay.setEnabled(true)
                service.dotOverlay.showPanel(adapter) { adapter.binding.value?.let(adapter::cancel) }
                assertEquals(binding,adapter.binding.value)
                assertEquals(capturedAt,adapter.preview.value!!.capturedAtMs)
                assertTrue(adapter.preview.value!!.file.exists())
                assertTrue(adapter.businessViewModel!!.attachments.value.isEmpty())
                assertFalse(adapter.businessViewModel!!.isStreaming.value)
            }
        } finally {
            withContext(Dispatchers.Main) { service.dotOverlay.collapsePanel(); adapter.cancel(binding); ChatViewModelStore.release(id) }
            snapshot.close()
        }
    }
    @Test fun staleNativeBitmapIsRecycledAndCanonicalPromotionKeepsSameVm() {
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val adapter = DotChatAdapter.create(service.application as MinisApp)
        instrumentation.runOnMainSync {
            val a = adapter.bind("__new__dot-alias-fixture")
            val vm = adapter.businessViewModel
            ChatViewModelStore.rename(a.sessionId,"dot-promoted-fixture")
            assertEquals(a,adapter.bind("dot-promoted-fixture"))
            assertSame(vm,adapter.businessViewModel)
            val b = adapter.bind(fixtureId)
            val beforeLateCallback = adapter.businessViewModel!!.messages.value
            val image = Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888)
            val snapshot = ScreenSnapshot("late",a.generation,1234,SceneKey("fixture.host",7,0,1),
                RectPx(0f,0f,48f,48f),0,RectPx(0f,0f,0f,0f),null,emptyList(),image,
                ScreenTransform(RectPx(0f,0f,48f,48f),48,48))
            assertFalse(adapter.attach(a,snapshot)); assertTrue(image.isRecycled)
            assertNull(adapter.preview.value)
            adapter.cancel(b)
            assertFalse(adapter.send(b,"late callback"))
            assertEquals(beforeLateCallback,adapter.businessViewModel!!.messages.value)
            assertFalse(adapter.businessViewModel!!.isStreaming.value)
            ChatViewModelStore.release("dot-promoted-fixture")
            ChatViewModelStore.release(b.sessionId)
        }
    }
    @Test fun actualSplitScreenImeClampsFocusablePanelToSettledHost() {
        org.junit.Assume.assumeTrue("Native split-screen fixture requires API34+",Build.VERSION.SDK_INT >= 34)
        val service = requireNotNull(MinisAccessibilityService.getInstance())
        val adapter = DotChatAdapter.create(service.application as MinisApp)
        val id = fixtureId
        try {
            shell("wm size 1200x800"); shell("wm density 160")
            shell("am start -W -a android.settings.SETTINGS")
            shell("am start -W -n io.github.nanodot.app.test/io.github.nanomuse.dot.screen.ScreenHostFixture --es marker HOST_DOT_SPLIT_T4_3E18 -f 0x10008000")
            shell("input keyevent 187")
            fun nodes(): List<NodeDto> {
                val root = automation.rootInActiveWindow ?: return emptyList()
                return NodeReader.read(root,"t4-split")
            }
            fun tap(node: NodeDto) { shell("input tap ${((node.bounds.left+node.bounds.right)/2).toInt()} ${((node.bounds.top+node.bounds.bottom)/2).toInt()}") }
            waitFor { nodes().any { it.viewId == "com.google.android.apps.nexuslauncher:id/action_split" && it.clickable } }
            tap(nodes().first { it.viewId == "com.google.android.apps.nexuslauncher:id/action_split" && it.clickable })
            waitFor { nodes().any { it.viewId == "com.google.android.apps.nexuslauncher:id/split_instructions_text" } }
            waitFor { nodes().any { it.viewId == "com.google.android.apps.nexuslauncher:id/task" && it.description == "Settings" } }
            tap(nodes().first { it.viewId == "com.google.android.apps.nexuslauncher:id/task" && it.description == "Settings" })
            val hostBounds = android.graphics.Rect()
            waitFor {
                val windows = service.windows
                try { windows.any { window ->
                    val root = window.root
                    try {
                        window.getBoundsInScreen(hostBounds)
                        root?.packageName == "io.github.nanodot.app.test" && hostBounds.width() < 1200 && hostBounds.left > 0
                    } finally { root?.recycle() }
                } } finally { windows.forEach { it.recycle() } }
            }
            shell("input tap ${hostBounds.centerX()} ${hostBounds.centerY()}")
            waitFor { shell("dumpsys window windows").contains("mWindowingMode=multi-window") }
            var previousSplit: Pair<Int,RectPx>? = null
            waitFor {
                val id = service.dotScreen.hostWindowId(); val viewport = id?.let { service.dotScreen.hostViewport(it) }
                val root = service.rootInActiveWindow
                val intended = root?.let { try { it.windowId == id && it.packageName == "io.github.nanodot.app.test" && containsNativeText(it,"HOST_DOT_SPLIT_T4_3E18") } finally { it.recycle() } } == true
                val current = if (id != null && viewport != null && viewport.left > 0 && intended) id to viewport else null
                val settled = current != null && current == previousSplit; previousSplit = current; settled
            }
            saveHostState("split-admission")
            instrumentation.runOnMainSync {
                service.dotOverlay.setEnabled(true); adapter.bind(id)
                service.dotOverlay.showPanel(adapter) { adapter.binding.value?.let(adapter::cancel) }
            }
            waitFor { panelWithinSettledHost() }; saveNative("split-before-ime")
            waitFor { automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.any { window ->
                window.root?.let { try { findEditable(it) } finally { it.recycle() } } == true
            } }
            val roots = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.mapNotNull { it.root }
            val edit = android.graphics.Rect()
            try { assertTrue(roots.any { editableBounds(it,edit) }) } finally { roots.forEach { it.recycle() } }
            val originalHostHeight = requireNotNull(service.dotScreen.hostViewport(service.dotOverlay.panelHostWindowId)).height
            observeImeAnimation(service)
            shell("input tap ${edit.centerX()} ${edit.centerY()}")
            waitFor { keyboardShown(service,originalHostHeight) }; saveNative("split-ime")
            instrumentation.runOnMainSync {
                val panel = requireNotNull(service.dotOverlay.panelView)
                val host = requireNotNull(service.dotScreen.hostViewport(service.dotOverlay.panelHostWindowId))
                assertTrue("Nonzero split host origin lost: $host",host.left > 0)
                assertTrue(panel.width < 1200)
            }
            shell("input keyevent 4")
            waitFor { keyboardHidden(service) }; saveNative("split-after-first-back")
            instrumentation.runOnMainSync { assertNotNull(service.dotOverlay.panelView) }
            shell("input keyevent 4")
            waitFor { service.dotOverlay.panelView == null }
        } finally {
            instrumentation.runOnMainSync { service.dotOverlay.collapsePanel(); ChatViewModelStore.release(id) }
            restoreNativeSettings(); shell("input keyevent 3")
        }
    }
    private fun findEditable(node: android.view.accessibility.AccessibilityNodeInfo): Boolean = editableBounds(node,android.graphics.Rect())
    private fun editableBounds(node: android.view.accessibility.AccessibilityNodeInfo, bounds: android.graphics.Rect): Boolean {
        if (node.isEditable) { node.getBoundsInScreen(bounds); return true }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { if (editableBounds(child,bounds)) return true } finally { child.recycle() }
        }
        return false
    }
}
