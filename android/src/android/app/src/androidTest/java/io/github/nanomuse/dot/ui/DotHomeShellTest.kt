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

/** Exercises the real production Activity; no debug shell or LocalDensity override. */
@RunWith(AndroidJUnit4::class)
class DotHomeShellTest {
    private var localeRestore: (() -> Unit)? = null

    @org.junit.Before fun applyRequestedNativeLocale() {
        val language = InstrumentationRegistry.getArguments().getString("nativeLocale") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = com.openminis.app.ui.settings.getAppearancePrefs(context)
        val key = com.openminis.app.ui.settings.KEY_LANGUAGE
        val had = prefs.contains(key)
        val old = prefs.getString(key, "")
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val locales = manager.applicationLocales
        localeRestore = {
            prefs.edit().apply { if (had) putString(key, old) else remove(key) }.commit()
            manager.applicationLocales = locales
        }
        prefs.edit().putString(key, language).commit()
        manager.applicationLocales = android.os.LocaleList.forLanguageTags(language)
    }

    @org.junit.After fun restoreRequestedNativeLocale() { localeRestore?.invoke() }
    @Test fun primaryDestinationsHaveVisibleLabels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val oldDone = prefs.getBoolean("setup.done", false)
        val hadDone = prefs.contains("setup.done")
        FirstRunSetup.markDone(context)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                Thread.sleep(2500)
                instrumentation.waitForIdleSync()
                dismissNativeKeyboard(scenario, "primary")
                for (label in listOf(context.getString(R.string.nm_tab_chat), context.getString(R.string.nm_tab_tasks),
                    context.getString(R.string.nm_devices_title), context.getString(R.string.nm_drawer_settings))) {
                    assertNotNull("Visible primary destination: $label", find(label))
                }
            }
        } finally {
            prefs.edit().apply { if (hadDone) putBoolean("setup.done", oldDone) else remove("setup.done") }.commit()
        }
    }


    @Test fun tasksDevicesSettingsAndBackReturnToTheSameHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val prefix = args.getString("capture", "default")!!
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val oldDone = prefs.getBoolean("setup.done", false)
        val hadDone = prefs.contains("setup.done")
        FirstRunSetup.markDone(context)
        try {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                args.getString("fontScale")?.let { expected ->
                    assertEquals("Actual Activity resource font scale", expected.toFloat(), activity.resources.configuration.fontScale)
                }
                args.getString("nativeLocale")?.let { expected ->
                    assertEquals("Actual Activity locale", expected.substringBefore('-'), activity.resources.configuration.locales[0].language)
                }
                args.getString("layoutDirection")?.let { expected ->
                    assertEquals("Actual Activity layout direction", if (expected == "rtl") android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR,
                        activity.resources.configuration.layoutDirection)
                }
                args.getString("heightDp")?.let { expected ->
                    assertEquals("Actual native window height", expected.toInt(),
                        (activity.windowManager.currentWindowMetrics.bounds.height() / activity.resources.displayMetrics.density).toInt())
                }
                args.getString("widthDp")?.let { expected ->
                    assertEquals("Actual native window width", expected.toInt(), activity.resources.configuration.screenWidthDp)
                }
            }
            dismissNativeKeyboard(scenario, prefix)
            capture("$prefix-chat")
            click(context.getString(R.string.nm_tab_tasks))
            for (label in listOf(R.string.nm_tab_feed, R.string.nm_tab_ideas, R.string.nm_tab_goals, R.string.nm_task_library)) {
                click(context.getString(label))
                assertNotNull("Task subroute remains visible", find(context.getString(label)))
                capture("$prefix-task-${context.resources.getResourceEntryName(label)}")
            }
            back()
            capture("$prefix-task-back")
            assertTrue("Back selected the primary Chat host", clickable(find(context.getString(R.string.nm_tab_chat))!!).isSelected)
            // Re-entering Tasks preserves the Library selection rather than resetting to Feed.
            click(context.getString(R.string.nm_tab_tasks))
            assertTrue("Library selection retained", clickable(find(context.getString(R.string.nm_task_library))!!).isSelected)
            back()
            capture("$prefix-task-back-again")
            assertTrue("Repeated Tasks Back selected Chat", clickable(find(context.getString(R.string.nm_tab_chat))!!).isSelected)
            click(context.getString(R.string.nm_devices_title))
            capture("$prefix-devices")
            back()
            assertNotNull("Devices Back returned to the same host", find(context.getString(R.string.nm_tab_tasks)))
            click(context.getString(R.string.nm_drawer_settings))
            capture("$prefix-settings")
            back()
            assertNotNull("Settings Back returned to the same host", find(context.getString(R.string.nm_tab_tasks)))
            capture("$prefix-return")
            click(context.getString(R.string.nm_open_drawer))
            reachDrawerFooter(context.getString(R.string.nm_drawer_search))
            assertNotNull("Drawer Settings remains reachable", find(context.getString(R.string.nm_drawer_settings)))
            assertNotNull("Drawer system files remains reachable", find(context.getString(R.string.nm_sysfiles_title)))
            assertNotNull("Drawer new conversation remains reachable", find(context.getString(R.string.nm_drawer_new_chat)))
            assertNotNull("Drawer search has a localized accessible label", find(context.getString(R.string.nm_drawer_search)))
            capture("$prefix-session-drawer")
            back()
            assertNotNull("Drawer Back returned to primary home", find(context.getString(R.string.nm_tab_tasks)))
        }
        } finally {
            prefs.edit().apply { if (hadDone) putBoolean("setup.done", oldDone) else remove("setup.done") }.commit()
        }
    }

    @Test fun canonicalDraftOwnerAndActiveJobSurviveTaskNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = "__new__task2-owner-${SystemClock.uptimeMillis()}"
        lateinit var vm: ChatViewModel
        lateinit var owner: androidx.lifecycle.ViewModelStoreOwner
        var running: kotlinx.coroutines.Job? = null
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val introKeys = listOf("first_conversation.phase", "first_conversation.session", "first_conversation.address", "first_conversation.suggestions")
        val oldIntro = introKeys.associateWith { prefs.getString(it, null) }
        try {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val app = activity.application as MinisApp
                owner = ChatViewModelStore.ownerFor(id)
                vm = ViewModelProvider(owner, ChatViewModel.factory(id, app.chatRepository, app.providerRepository,
                    app, app.memoryRepository, app.skillRepository, app.mcpRepository))[ChatViewModel::class.java]
                vm.setInputText("Draft kept across primary navigation")
                running = vm.viewModelScope.launch { delay(60_000) }
                ChatViewModelStore.rename(id, "$id-canonical")
                HomeBus.showSession(id)
            }
            settle()
            dismissNativeKeyboard(scenario, "owner")
            click(context.getString(R.string.nm_tab_tasks))
            click(context.getString(R.string.nm_tab_chat))
            scenario.onActivity {
                assertTrue("Running owner job retained", running!!.isActive)
                assertEquals("$id-canonical", ChatViewModelStore.resolvePersistedId(id))
                assertTrue("Canonical ViewModelStore retained", owner.viewModelStore === ChatViewModelStore.ownerFor(id).viewModelStore)
                assertEquals("Draft kept across primary navigation", vm.inputText.value)
                running!!.cancel()
            }
        }
        } finally {
            // This is an active owner-job fixture, not a real model streaming/network acceptance.
            instrumentation.runOnMainSync { running?.cancel(); ChatViewModelStore.release(id) }
            prefs.edit().apply { oldIntro.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
        }
    }


    @Test fun coldAndWarmSessionDeepLinksRetainStateAndReturnToOneHomeHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val oldDone = prefs.getBoolean("setup.done", false)
        val hadDone = prefs.contains("setup.done")
        val id = "__new__task2-deeplink-${SystemClock.uptimeMillis()}"
        val introKeys = listOf("first_conversation.phase", "first_conversation.session", "first_conversation.address", "first_conversation.suggestions")
        val oldIntro = introKeys.associateWith { prefs.getString(it, null) }
        var vm: ChatViewModel? = null
        val repo = (context.applicationContext as MinisApp).providerRepository
        kotlinx.coroutines.runBlocking { repo.awaitConfigLoaded() }
        @Suppress("UNCHECKED_CAST")
        val configReady = repo.javaClass.getDeclaredField("_configLoaded").apply { isAccessible = true }.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        val oldReady = configReady.value
        val delivered = java.util.concurrent.atomic.AtomicInteger()
        val collectScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val collector = collectScope.launch { HomeBus.requests.collect { request ->
            if (request is HomeBus.Request.ShowSession && request.sessionId == id) delivered.incrementAndGet()
        } }
        configReady.value = false
        FirstRunSetup.markDone(context)
        fun link(scheme: String) = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$scheme://session/$id"))
            .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<MainActivity>(link("nanodot")).use { scenario ->
                settle()
                assertEquals("Pending config does not dispatch or consume the cold session link", 0, delivered.get())
                capture("deeplink-config-pending")
                configReady.value = true
                await("Cold session link delivered after real repository readiness") { delivered.get() == 1 }
                settle()
                scenario.onActivity { activity ->
                    val app = activity.application as MinisApp
                    vm = ViewModelProvider(ChatViewModelStore.ownerFor(id), ChatViewModel.factory(id,
                        app.chatRepository, app.providerRepository, app, app.memoryRepository,
                        app.skillRepository, app.mcpRepository))[ChatViewModel::class.java]
                    vm!!.setInputText("Deep-link draft retained")
                }
                dismissNativeKeyboard(scenario, "deeplink")
                capture("deeplink-cold-chat")
                click(context.getString(R.string.nm_tab_tasks))
                click(context.getString(R.string.nm_task_library))
                scenario.recreate()
                settle()
                assertTrue("Tasks and Library survive actual Activity recreation",
                    clickable(find(context.getString(R.string.nm_task_library))!!).isSelected)
                assertEquals("Deep-link draft retained", vm!!.inputText.value)
                assertEquals("Accepted cold link delivered only once after recreation", 1, delivered.get())
                capture("deeplink-recreated-library")
                for (scheme in listOf("nanodot", "minis")) {
                    context.startActivity(link(scheme))
                    settle()
                    assertTrue("Warm $scheme link selected the Chat host",
                        clickable(find(context.getString(R.string.nm_tab_chat))!!).isSelected)
                    assertEquals("Deep-link draft retained", vm!!.inputText.value)
                    capture("deeplink-warm-$scheme")
                    back()
                    assertNotNull("One Back after $scheme link returned to home", find(context.getString(R.string.nm_tab_tasks)))
                    capture("deeplink-back-$scheme")
                    click(context.getString(R.string.nm_tab_tasks))
                }
            }
        } finally {
            configReady.value = oldReady
            collector.cancel()
            collectScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            instrumentation.runOnMainSync { ChatViewModelStore.release(id) }
            prefs.edit().apply {
                if (hadDone) putBoolean("setup.done", oldDone) else remove("setup.done")
                oldIntro.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }.commit()
        }
    }

    @Test fun realVmStreamContinuesAcrossTasksAndRetainsDraftAndCanonicalOwner() {
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
        val providerId = "task2-local-$suffix"
        val draftId = "__new__task2-stream-$suffix"
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
                if (InstrumentationRegistry.getArguments().getString("openKeyboard") == "true") {
                    var alreadyVisible = false
                    scenario.onActivity { activity -> alreadyVisible = activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
                    capture("stream-before-keyboard-touch")
                    if (!alreadyVisible) {
                    assertNotNull("Native Stop is rendered before opening the keyboard", find("Stop"))
                    val automation = instrumentation.uiAutomation
                    if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
                    fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                        if (node.isVisibleToUser && (node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })) return node
                        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> editable(child)?.let { return it } }
                        return null
                    }
                    touch(requireNotNull(automation.rootInActiveWindow?.let { editable(it) }) { "Actual composer input is visible" }, "composer input")
                    settle()
                    }
                    scenario.onActivity { activity ->
                        assertTrue("Real composer touch opened the native IME", activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                    }
                }
                dismissNativeKeyboard(scenario, "stream")
                assertTrue("Actual VM stream in progress", vm!!.isStreaming.value)
                assertNotNull("Native Stop remains reachable during the real stream", find("Stop"))
                persisted = vm!!.currentSessionId
                assertTrue("Draft promoted through canonical alias", persisted != draftId)
                scenario.onActivity { vm!!.setInputText("Next draft survives the task switch") }
                assertNotNull("Native Send remains reachable with the retained next draft", find("Send"))
                assertNotNull("Shown side-session header reflects the real stream", find(context.getString(R.string.nm_status_replying)))
                capture("stream-first-chat")
                click(context.getString(R.string.nm_tab_tasks))
                assertNotNull("Tasks header follows the same shown stream", find(context.getString(R.string.nm_status_replying)))
                capture("stream-tasks")
                assertTrue("Real VM stream stayed active on Tasks", vm!!.isStreaming.value)
                server.releaseSecond.countDown()
                await("Real VM consumed delayed final chunk") {
                    !vm!!.isStreaming.value && vm!!.uiMessages.value.any { it.content.contains("First local chunk continued after Tasks") }
                }
                assertTrue("Only local chat/completions transport used", server.requestLine.startsWith("POST ") && server.requestLine.contains("chat/completions"))
                click(context.getString(R.string.nm_tab_chat))
                assertEquals("Next draft survives the task switch", vm!!.inputText.value)
                assertTrue("Draft and persisted ID share real VM store",
                    ChatViewModelStore.ownerFor(draftId).viewModelStore === ChatViewModelStore.ownerFor(persisted!!).viewModelStore)
                assertEquals(persisted, ChatViewModelStore.resolvePersistedId(draftId))
                assertTrue("Completed stream clears its working header", findImmediate(context.getString(R.string.nm_status_replying)) == null)
                capture("stream-final-chat")
            }
        } finally {
            server.releaseSecond.countDown()
            instrumentation.runOnMainSync { vm?.cancelStream(); ChatViewModelStore.release(draftId) }
            server.close()
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
        private val thread = kotlin.concurrent.thread(name = "task2-local-sse", isDaemon = true) {
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

    private fun reachDrawerFooter(search: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(6) {
            val node = findImmediate(search)
            val bounds = node?.let { Rect().also { rect -> it.getBoundsInScreen(rect) } }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val minimum = (48 * context.resources.displayMetrics.density).toInt()
            val screenHeight = context.resources.displayMetrics.heightPixels
            if (bounds != null && bounds.height() >= minimum && bounds.bottom < screenHeight - 24 * context.resources.displayMetrics.density) return
            fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isScrollable && node.isVisibleToUser) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> scrollable(child)?.let { return it } }
                return null
            }
            val scroll = requireNotNull(automation.rootInActiveWindow?.let { scrollable(it) }) { "Drawer footer is fully visible or genuinely scrollable" }
            val rect = Rect().also { scroll.getBoundsInScreen(it) }
            val now = SystemClock.uptimeMillis()
            for (step in 0..6) {
                val type = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 6 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                val y = (rect.bottom - 32) + (rect.top + 32 - (rect.bottom - 32)) * step / 6f
                val event = android.view.MotionEvent.obtain(now, now + step * 60L, type, rect.exactCenterX(), y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("Real native drawer scroll", automation.injectInputEvent(event, true))
                event.recycle()
                Thread.sleep(60)
            }
            settle()
        }
        throw AssertionError("Drawer search remains below native safe viewport after real scroll")
    }

    private fun dismissNativeKeyboard(scenario: ActivityScenario<MainActivity>, prefix: String) {
        var shown = false
        var native = ""
        scenario.onActivity { activity ->
            val insets = activity.window.decorView.rootWindowInsets
            shown = insets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            native = "window=${activity.windowManager.currentWindowMetrics.bounds} imeVisible=$shown ime=${insets?.getInsets(android.view.WindowInsets.Type.ime())} systemBars=${insets?.getInsets(android.view.WindowInsets.Type.systemBars())}\n"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "dot-home-shell").apply { mkdirs() }
        File(output, "$prefix-native-insets.txt").writeText(native)
        capture("$prefix-before-ime-dismiss")
        if (shown) {
            // Real Back dismisses the keyboard, then all destination labels must be visibly reachable.
            back()
            scenario.onActivity { activity ->
                assertTrue("Actual keyboard dismissed", activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true)
            }
        }
        capture("$prefix-after-ime-dismiss")
    }

    private fun click(label: String) {
        val node = requireNotNull(find(label)) { "Missing visible label: $label" }
        val action = clickable(node)
        val rect = Rect().also { action.getBoundsInScreen(it) }
        val minimum = (48 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()
        assertTrue("Native target is at least 48dp for $label: $rect", rect.width() >= minimum && rect.height() >= minimum)
        // Android removes ACTION_CLICK from an already selected native Tab.
        if (!action.isSelected) touch(action, label)
        settle()
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

    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        settle()
    }

    private fun settle() {
        Thread.sleep(800)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "dot-home-shell").apply { mkdirs() }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        val log = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            log.append(" ".repeat(depth)).append("text=${node.text} desc=${node.contentDescription} selected=${node.isSelected} clickable=${node.isClickable} visible=${node.isVisibleToUser} bounds=$bounds\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it, 0) }
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
