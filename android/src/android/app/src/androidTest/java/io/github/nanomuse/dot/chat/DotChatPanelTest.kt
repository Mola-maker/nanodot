package io.github.nanomuse.dot.chat

import android.app.UiAutomation
import android.graphics.Bitmap
import io.github.nanomuse.dot.screen.*
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
    private var previousObservationFlags: Int? = null
    private var fixtureId = ""
    private var fixtureTitle = ""
    private var fixtureParts = ""
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
            data.append(" ".repeat(depth)).append("id=${node.windowId} class=${node.className} text=${node.text} editable=${node.isEditable} bounds=$bounds children=${node.childCount}\n")
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
            matches = point[0] == host.left.toInt() && point[1] == host.top.toInt() &&
                view.width == host.width.toInt() && view.height == host.height.toInt()
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
    @Before fun connected() {
        val observation = automation.serviceInfo
        previousObservationFlags = observation.flags
        observation.flags = observation.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = observation
        println("T4_NATIVE_OBSERVATION flagsBefore=$previousObservationFlags flagsAfter=${automation.serviceInfo.flags}")
        shell("input keyevent 224"); shell("input keyevent 82")
        if (MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected != true) {
            shell("settings delete secure enabled_accessibility_services")
            // Instrumentation termination force-stops the target service. Wait for the framework
            // to observe removal before re-enabling, otherwise two setting writes can coalesce
            // and leave its old DEAD binding in AccessibilityManager on API29.
            waitFor {
                val state = shell("dumpsys accessibility")
                state.contains("Bound services:{}") && state.contains("Enabled services:{}") && state.contains("Binding services:{}")
            }
            shell("settings put secure enabled_accessibility_services io.github.nanodot.app/com.openminis.app.accessibility.MinisAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
        }
        waitFor { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true }
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
        fixtureTitle = "DOT_T4_LOCAL_ONLY_$fixtureId"
        fixtureParts = org.json.JSONArray().put(org.json.JSONObject().put("type","text").put("value",fixtureTitle)).toString()
        runBlocking {
            app.chatRepository.dao.insertSession(com.openminis.app.data.db.ChatSessionEntity(
                id=fixtureId,title=fixtureTitle,modelId=localModel,createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis(),memoryEnabled=0))
            app.chatRepository.dao.insertMessage(com.openminis.app.data.db.MessageEntity(
                id=java.util.UUID.randomUUID().toString(),sessionId=fixtureId,role="user",partsJson=fixtureParts,createdAt=System.currentTimeMillis(),sortOrder=0))
        }
        java.io.File(app.cacheDir,"t4-owned-session-$fixtureId.json").writeText(org.json.JSONObject()
            .put("id",fixtureId).put("title",fixtureTitle).put("modelId",localModel).put("modelBinding",org.json.JSONObject.NULL)
            .put("roles",org.json.JSONArray().put("user")).toString())
    }
    @After fun cleanupOwnedFixture() {
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
                messages.all { it.role == "user" && it.partsJson == fixtureParts }) { "Owned fixture guard changed; preserve row for controller" }
            app.chatRepository.dao.deleteMessages(fixtureId); app.chatRepository.dao.deleteSession(fixtureId)
            check(app.chatRepository.getSession(fixtureId) == null)
            println("T4_OWNED_CLEANUP id=$fixtureId rows=${messages.size} localNullBinding=true")
        }
        val edit = app.getSharedPreferences("nanomuse",android.content.Context.MODE_PRIVATE).edit()
        if (previousFirstSession == null) edit.remove("first_conversation.session") else edit.putString("first_conversation.session",previousFirstSession)
        if (previousFirstPhase == null) edit.remove("first_conversation.phase") else edit.putString("first_conversation.phase",previousFirstPhase)
        check(edit.commit())
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
            shell("wm size reset"); shell("wm density reset"); shell("input keyevent 3")
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
