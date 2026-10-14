package io.github.nanomuse.dot.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import io.github.nanomuse.ui.home.HomeBus
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MainActivity
import io.github.nanomuse.ui.onboarding.FirstRunSetup
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity resources, native IME and actual keyless loopback ChatViewModel stream. */
@RunWith(AndroidJUnit4::class)
class DotAccessibilityTest {
    @Test fun nativeKeyboardKeepsComposerSendStopAndCanonicalOwnerReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MinisApp
        val repo = app.providerRepository
        val oldDefault = repo.defaultPrimaryGroupId
        val oldSubDefault = repo.defaultSubGroupId
        val oldLastEntry = repo.lastUsedEntryId
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val oldDone = prefs.getBoolean("setup.done", false)
        val hadDone = prefs.contains("setup.done")
        var oldMain = io.github.nanomuse.home.MainChat.sessionId.value
        val introKeys = listOf("first_conversation.phase", "first_conversation.session", "first_conversation.address", "first_conversation.suggestions")
        val oldIntro = introKeys.associateWith { prefs.getString(it, null) }
        val suffix = SystemClock.uptimeMillis().toString()
        val providerId = "task3-local-$suffix"
        val draftId = "__new__task3-stream-$suffix"
        val model = com.openminis.app.data.model.LLMModel("local-navigation", "Local navigation fixture", "openAI", contextWindow = 128000, maxOutputTokens = 512, supportsReasoning = false)
        // ProviderRepository persists canonical instance/model IDs; group members use that authority.
        val entry = com.openminis.app.data.model.ModelEntry(providerId, model, isCustom = true, uuid = "$providerId/${model.id}")
        val group = com.openminis.app.data.model.ModelGroup(name = "Local navigation fixture", memberEntryIds = mutableListOf(entry.id))
        val server = LocalNavigationSse()
        var vm: ChatViewModel? = null
        var persisted: String? = null
        try {
            FirstRunSetup.markDone(context)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                settle()
                scenario.onActivity {
                    oldMain = io.github.nanomuse.home.MainChat.sessionId.value
                    repo.addInstance(com.openminis.app.data.model.ProviderInstance(providerId, "Local navigation fixture",
                        com.openminis.app.data.model.ProviderType.openAI, com.openminis.app.data.model.ProviderCredential.apiKey,
                        customBaseURL = "http://127.0.0.1:${server.port}", appendV1Suffix = false))
                    repo.addEntry(entry)
                    repo.addGroup(group)
                    repo.defaultPrimaryGroupId = group.id
                    repo.defaultSubGroupId = group.id
                    val owner = ChatViewModelStore.ownerFor(draftId)
                    vm = ViewModelProvider(owner, ChatViewModel.factory(draftId, app.chatRepository, repo,
                        app, app.memoryRepository, app.skillRepository, app.mcpRepository))[ChatViewModel::class.java]
                    assertTrue("Local fixture usable: instance=${repo.instance(providerId) != null}, entry=${repo.config.value.modelEntries.any { it.id == entry.id }}, members=${group.memberEntryIds.size}, request=${server.requestLine}", repo.availableMemberEntries(group).any { it.id == entry.id })
                    vm!!.selectGroupEntry(group.id, entry.id)
                    HomeBus.showSession(draftId)
                }
                assertEquals("Production model selection used the local fixture", group.id, vm!!.selectedGroupId.value)
                scenario.onActivity { vm!!.sendMessage("A bounded local navigation stream") }
                val reached = server.firstChunk.await(25, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue("Real stream reached loopback; VM error=${vm!!.error.value}; server=${server.failure}", reached)
                await("Real VM consumed first SSE chunk") { vm!!.streamingById.value.values.any { it.content.contains("First local chunk") } }
                val automation = instrumentation.uiAutomation
                if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
                fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isVisibleToUser && node.isEditable) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { return it } }
                    return null
                }
                var imeVisible = false
                scenario.onActivity { activity ->
                    imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
                if (!imeVisible) {
                    touch(requireNotNull(automation.rootInActiveWindow?.let { editable(it) }) { "Actual editable composer exists" }, "composer")
                    settle()
                }
                fun assertAboveIme(label: String, requireTarget: Boolean = true) {
                    var keyboardTop = 0
                    scenario.onActivity { activity ->
                        val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView))
                        assertTrue("Native IME stays shown", insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
                        keyboardTop = activity.window.decorView.height - insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                    }
                    capture("keyboard-$label")
                    val node = requireNotNull(if (label == "composer") automation.rootInActiveWindow?.let { editable(it) } else findImmediate(label)) { "Visible $label while native keyboard stays shown" }
                    val action = if (requireTarget) clickable(node) else node
                    val bounds = Rect().also { action.getBoundsInScreen(it) }
                    val minimum = (48 * context.resources.displayMetrics.density).toInt()
                    assertTrue("$label bounds stay above IME: $bounds, imeTop=$keyboardTop", bounds.top >= 0 && bounds.bottom <= keyboardTop)
                    assertTrue("$label target is at least 48dp: $bounds", bounds.width() >= minimum && bounds.height() >= minimum)
                }
                assertAboveIme(context.getString(R.string.nm_coding_stop))
                assertAboveIme("composer", false)
                persisted = vm!!.currentSessionId
                scenario.onActivity { vm!!.setInputText("Next draft stays accessible with keyboard") }
                settle()
                assertAboveIme(context.getString(R.string.nm_coding_send))
                assertAboveIme("composer", false)
                assertEquals("Draft stays in real VM", "Next draft stays accessible with keyboard", vm!!.inputText.value)
                assertTrue("Canonical session retains actual VM", ChatViewModelStore.ownerFor(draftId).viewModelStore === ChatViewModelStore.ownerFor(persisted!!).viewModelStore)
                // Stop cancellation uses the actual native control and existing VM callback.
                scenario.onActivity { vm!!.setInputText("") }
                settle()
                assertAboveIme(context.getString(R.string.nm_coding_stop))
                touch(clickable(requireNotNull(findImmediate(context.getString(R.string.nm_coding_stop)))), "Stop")
                await("Native Stop cancels actual VM stream") { !vm!!.isStreaming.value }
                assertTrue("Only loopback chat transport", server.requestLine.contains("chat/completions"))
            }
        } finally {
            server.releaseSecond.countDown()
            instrumentation.runOnMainSync { vm?.cancelStream(); ChatViewModelStore.release(draftId) }
            server.close()
            persisted = persisted ?: vm?.currentSessionId?.takeUnless { io.github.nanomuse.home.MainChat.isDraftId(it) }
            persisted?.let { id -> kotlinx.coroutines.runBlocking { app.chatRepository.deleteSession(id) } }
            repo.removeGroup(group.id)
            repo.removeInstance(providerId)
            assertTrue("Fixture provider removed", repo.instance(providerId) == null)
            persisted?.let { id -> assertTrue("Fixture session removed", kotlinx.coroutines.runBlocking { app.chatRepository.getSession(id) } == null) }
            repo.defaultPrimaryGroupId = oldDefault
            repo.defaultSubGroupId = oldSubDefault
            repo.lastUsedEntryId = oldLastEntry
            prefs.edit().apply { oldIntro.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
            prefs.edit().apply { if (hadDone) putBoolean("setup.done", oldDone) else remove("setup.done") }.commit()
            // The pre-existing unpersisted home acquired the temporary fixture model while shown.
            // Release that fixture VM, without deleting or changing the saved main session.
            oldMain?.takeIf { io.github.nanomuse.home.MainChat.isDraftId(it) }?.let { previousDraft ->
                instrumentation.runOnMainSync { ChatViewModelStore.release(previousDraft) }
            }
        }
    }

    @Test fun installedTalkBackTraversesRealPrimaryNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val services = manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN)
        assertTrue("A verified installed TalkBack service must be enabled; native semantics alone do not satisfy traversal",
            services.any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" })
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = automation.serviceInfo
        info.eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK
        info.notificationTimeout = 0
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val rawEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
        val focused = java.util.Collections.synchronizedList(mutableListOf<String>())
        automation.setOnAccessibilityEventListener { event ->
            rawEvents.add("type=${event.eventType} text=${event.text} desc=${event.contentDescription} source=${event.source?.className}")
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                val node = event.source
                val value = (event.text.map { it.toString() } + listOfNotNull(event.contentDescription?.toString(), node?.text?.toString(), node?.contentDescription?.toString())).distinct().joinToString(" | ")
                if (value.isNotBlank()) focused.add(value)
            }
        }
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val oldDone = prefs.getBoolean("setup.done", false)
        val hadDone = prefs.contains("setup.done")
        FirstRunSetup.markDone(context)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                Thread.sleep(2500)
                val exploreDeadline = SystemClock.uptimeMillis() + 15000
                while (!manager.isTouchExplorationEnabled && SystemClock.uptimeMillis() < exploreDeadline) Thread.sleep(250)
                val output = File(context.getExternalFilesDir(null), "dot-accessibility").apply { mkdirs() }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys accessibility")).use {
                    File(output, "talkback-runtime-accessibility.txt").writeBytes(it.readBytes())
                }
                assertTrue("Actual TalkBack touch exploration active before native next-item shortcut", manager.isTouchExplorationEnabled)
                var shown = false
                scenario.onActivity { activity -> shown = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                // Separate screen-reader traversal; keyboard reachability has its own mandatory test.
                if (shown) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                val labels = listOf(R.string.nm_tab_chat, R.string.nm_tab_tasks, R.string.nm_devices_title, R.string.nm_drawer_settings)
                    .map(context::getString)
                repeat(36) {
                    if (labels.all { label -> focused.any { it.contains(label) } }) return@repeat
                    // Official TalkBack next-item keyboard shortcut. Shell pointer injection bypasses
                    // TouchExplorer on this emulator; actual focus events must still come from TalkBack.
                    // https://support.google.com/accessibility/android/answer/6006598
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        automation.executeShellCommand("input keycombination KEYCODE_ALT_LEFT KEYCODE_DPAD_RIGHT")
                    ).use { it.readBytes() }
                    Thread.sleep(250)
                }
                capture("talkback-primary-navigation-focus", automation)
                File(output, "talkback-focus-events.txt").writeText(focused.joinToString("\n"))
                File(output, "talkback-native-events.txt").writeText("subscription=$info\n" + rawEvents.joinToString("\n"))
                labels.forEach { label -> assertTrue("TalkBack actually focused $label via native TalkBack next-item keyboard shortcuts: $focused", focused.any { it.contains(label) }) }
            }
        } finally {
            automation.setOnAccessibilityEventListener(null)
            prefs.edit().apply { if (hadDone) putBoolean("setup.done", oldDone) else remove("setup.done") }.commit()
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        capture("timeout-$label")
        assertTrue(label, condition())
    }

    /** Loopback-only transport: first chunk holds until the real production Tasks path is shown. */
    private class LocalNavigationSse : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val firstChunk = java.util.concurrent.CountDownLatch(1)
        val releaseSecond = java.util.concurrent.CountDownLatch(1)
        @Volatile var requestLine = ""
        @Volatile var failure: Throwable? = null
        private val thread = kotlin.concurrent.thread(name = "task3-local-sse", isDaemon = true) {
            try { socket.accept().use { connection ->
                connection.soTimeout = 30_000
                val input = connection.getInputStream()
                val headers = java.io.ByteArrayOutputStream()
                var tail = ""
                while (!tail.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    check(next >= 0 && headers.size() < 64_000)
                    headers.write(next)
                    tail = (tail + next.toChar()).takeLast(4)
                }
                val text = headers.toString("UTF-8")
                requestLine = text.lineSequence().first()
                val length = Regex("(?im)^Content-Length: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                var remaining = length
                val buffer = ByteArray(8192)
                while (remaining > 0) { val count = input.read(buffer, 0, minOf(remaining, buffer.size)); check(count > 0); remaining -= count }
                val output = connection.getOutputStream().bufferedWriter()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                fun chunk(content: String, finish: String = "null") {
                    output.write("data: {\"id\":\"local-navigation\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$content\"},\"finish_reason\":$finish}]}\n\n")
                    output.flush()
                }
                chunk("First local chunk ")
                firstChunk.countDown()
                check(releaseSecond.await(30, java.util.concurrent.TimeUnit.SECONDS))
                chunk("continued after Tasks", "\"stop\"")
                output.write("data: [DONE]\n\n")
                output.flush()
            } } catch (error: Throwable) {
                if (!socket.isClosed) failure = error
            }
        }
        override fun close() { releaseSecond.countDown(); socket.close(); thread.join(1000) }
    }

    private fun touch(node: AccessibilityNodeInfo, label: String) {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val now = SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (eventType in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, now + eventType * 50L, eventType,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Real native touch injected: $label", automation.injectInputEvent(event, true))
            event.recycle()
        }
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && !current.isSelected && current.parent != null) current = current.parent
        return current
    }

    private fun settle() {
        Thread.sleep(800)
        // Animating production avatars need bounded readiness rather than an unbounded Looper-idle wait.
    }

    private fun capture(name: String, automation: android.app.UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "dot-accessibility").apply { mkdirs() }
        val screenshot = requireNotNull(automation.takeScreenshot())
        File(output, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        val log = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            log.append(" ".repeat(depth)).append("text=${node.text} desc=${node.contentDescription} selected=${node.isSelected} clickable=${node.isClickable} visible=${node.isVisibleToUser} bounds=$bounds\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        automation.rootInActiveWindow?.let { visit(it, 0) }
        File(output, "$name.txt").writeText(log.toString())
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        repeat(40) {
            findImmediate(text)?.let { return it }
            Thread.sleep(250)
        }
        capture("missing-label-$text")
        return null
    }

    private fun findImmediate(text: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if ((node.text?.toString() == text || node.contentDescription?.toString() == text) && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child)?.let { return it }
            }
            return null
        }
        return visit(root)
    }
}
