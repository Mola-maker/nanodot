package io.github.nanomuse.dot.care

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.view.WindowInsets
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import com.openminis.app.data.model.*
import com.openminis.app.provider.LLMProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import io.github.nanomuse.dot.overlay.DotNativeServiceFixture
import org.junit.runner.RunWith

/** Root-only native gate. Production controller, Compose card, window owner and focus layer;
 * injected inert planner; actual host nodes and touches. No network or persistent data. */
@RunWith(AndroidJUnit4::class)
class CareOverlayTest {
    private val nativeService = DotNativeServiceFixture()
    @Before fun connectFrameworkServiceBeforeCareTests() { nativeService.connect() }
    @After fun restoreExactNativeServiceSettings() { nativeService.close() }
    private lateinit var session: CareSession
    private lateinit var window: CareOverlayWindow
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    // Reuse the framework TestApi approach from DotForegroundMotionNativeTest: native
    // input stays synchronized, but persistent foreground motion must not delay DOWN/UP.
    private val injectWithoutAnimationWait by lazy {
        try {
            android.app.UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
        } catch (failure: ReflectiveOperationException) {
            throw AssertionError("Native Care interaction requires the framework " +
                "UiAutomation.injectInputEvent(InputEvent, boolean, boolean) test API; " +
                "no hidden-API policy override or public waiting fallback is permitted", failure)
        }
    }
    private fun injectTouch(event: MotionEvent) {
        val started = SystemClock.uptimeMillis()
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val accepted = try {
            injectWithoutAnimationWait.invoke(automation, event, true, false) as Boolean
        } catch (failure: ReflectiveOperationException) {
            throw AssertionError("Framework Care touch injection without animation wait is unavailable", failure)
        }
        println("CARE_NATIVE_INPUT action=${event.action} accepted=$accepted " +
            "injectMs=${SystemClock.uptimeMillis() - started} waitForDispatch=true waitForAnimations=false")
        assertTrue("Native Care touch accepted", accepted)
    }
    @Suppress("DEPRECATION")
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result+=node
            for(i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        val windows=automation.windows
        try { windows.forEach { it.root?.let(::visit) } } finally { windows.forEach { it.recycle() } }
        return result
    }
    @Suppress("DEPRECATION")
    private fun withNodes(block: (List<AccessibilityNodeInfo>)->Unit) {
        if(android.os.Build.VERSION.SDK_INT>=33) automation.clearCache()
        val all=nodes(); try { block(all) } finally { all.forEach { it.recycle() } }
    }
    private fun tap(x: Float,y: Float) {
        val t=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,x,y,0)
            try { injectTouch(e) } finally { e.recycle() }
        }
    }
    private data class CareControlsFrame(val owner: Int, val bounds: Rect, val rows: List<String>)
    private var lastCareControls = "not sampled"
    private fun careControlsFrame(): CareControlsFrame? {
        var owned: Pair<Int, Rect>? = null
        instrumentation.runOnMainSync {
            val card = if (!::window.isInitialized) null else CareOverlayWindow::class.java.getDeclaredField("card").let {
                it.isAccessible = true; it.get(window) as? android.view.View
            }
            if (card != null && card.isAttachedToWindow && card.isLaidOut && !card.isLayoutRequested) {
                val location = IntArray(2); card.getLocationOnScreen(location)
                val bounds = Rect(location[0],location[1],location[0]+card.width,location[1]+card.height)
                val node = card.createAccessibilityNodeInfo()
                try { owned = node.windowId to bounds } finally { node.recycle() }
            }
        }
        val (owner, bounds) = owned ?: return null
        val windows = automation.windows
        val native = try { windows.firstOrNull { it.id == owner }?.let { Rect().apply(it::getBoundsInScreen) } }
            finally { windows.forEach { it.recycle() } }
        val rows = mutableListOf<String>()
        withNodes { all -> all.filter { it.windowId == owner && it.isVisibleToUser }.forEach { node ->
            rows += "${node.className}:${node.text}:${node.contentDescription}:" +
                "${Rect().also(node::getBoundsInScreen)}:${node.isEnabled}:${node.isClickable}:${node.actionList.map { it.id }}"
        } }
        lastCareControls = "time=${SystemClock.uptimeMillis()} owner=$owner actual=$bounds native=$native rows=$rows"
        return if (native == bounds && !bounds.isEmpty && rows.isNotEmpty()) CareControlsFrame(owner,bounds,rows) else null
    }
    private fun saveCareControlsFailure(stage: String) {
        java.io.File(instrumentation.targetContext.cacheDir,"care-controls-timeout.txt")
            .writeText("stage=$stage\n$lastCareControls\n")
        automation.takeScreenshot()?.let { bitmap ->
            try { java.io.File(instrumentation.targetContext.cacheDir,"care-controls-timeout.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            } } finally { bitmap.recycle() }
        }
    }
    private fun saveCareBackEvidence(stage: String, accepted: Boolean? = null) {
        var owner: Int? = null
        var cardState = "card absent"
        instrumentation.runOnMainSync {
            val view = CareOverlayWindow::class.java.getDeclaredField("card").let {
                it.isAccessible = true; it.get(window) as? android.view.View
            }
            view?.let {
                val node = it.createAccessibilityNodeInfo()
                try { owner = node.windowId } finally { node.recycle() }
                val p = it.layoutParams as? WindowManager.LayoutParams
                cardState = "attached=${it.isAttachedToWindow} shown=${it.isShown} focused=${it.hasFocus()} " +
                    "windowFocus=${it.hasWindowFocus()} flags=${p?.flags} type=${p?.type}"
            }
            cardState += " state=${session.controller.state.value.state} visible=${session.visible.value} " +
                "editing=${session.editingGoal.value} focusBounds=${window.focus.bounds}"
        }
        val windows = automation.windows
        val native = try { windows.map {
            "id=${it.id} type=${it.type} focused=${it.isFocused} active=${it.isActive} " +
                "bounds=${Rect().also(it::getBoundsInScreen)}"
        } } finally { windows.forEach { it.recycle() } }
        val rows = mutableListOf<String>()
        var menuVisible = false
        withNodes { all -> all.filter { it.windowId == owner && it.isVisibleToUser }.forEach {
            if (it.className?.toString() == "android.widget.ScrollView") menuVisible = true
            rows += "${it.className}:${it.text}:${it.contentDescription}:${Rect().also(it::getBoundsInScreen)}"
        } }
        val observation = "CARE_BACK stage=$stage time=${SystemClock.uptimeMillis()} accepted=$accepted " +
            "owner=$owner menuVisible=$menuVisible $cardState windows=$native rows=$rows"
        println(observation)
        java.io.File(instrumentation.targetContext.cacheDir,"care-back-$stage.txt").writeText(observation)
        automation.takeScreenshot()?.let { bitmap ->
            try { java.io.File(instrumentation.targetContext.cacheDir,"care-back-$stage.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            } } finally { bitmap.recycle() }
        }
    }
    private fun settledCareControls(stage: String): CareControlsFrame {
        var previous: CareControlsFrame? = null
        var unchangedSince = SystemClock.uptimeMillis()
        try { awaitNativeState("Care $stage controls and actual owning window must settle") {
            val current = careControlsFrame()
            val now = SystemClock.uptimeMillis()
            if (current == null || current != previous) { previous = current; unchangedSince = now }
            current != null && now-unchangedSince >= 240L
        } } catch (failure: AssertionError) { saveCareControlsFailure(stage); throw failure }
        return requireNotNull(previous)
    }
    private fun careControlBounds(label: String, frame: CareControlsFrame, menuOnly: Boolean = false): Rect? {
        val hits = mutableListOf<Rect>()
        val minimum = kotlin.math.ceil(48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        withNodes { all -> all.filter { it.windowId == frame.owner && it.isVisibleToUser && it.isEnabled &&
            (it.text?.toString() == label || it.contentDescription?.toString() == label) }.forEach { node ->
            var action = AccessibilityNodeInfo.obtain(node)
            try {
                while (!action.isClickable && action.windowId == frame.owner) {
                    val parent = action.parent ?: break
                    action.recycle(); action = parent
                }
                if (action.windowId == frame.owner && action.isEnabled && action.isVisibleToUser && action.isClickable &&
                    action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    val visible = Rect(); action.getBoundsInScreen(visible)
                    var valid = visible.intersect(frame.bounds)
                    var inMenu = false
                    val ancestry = mutableListOf<String>()
                    var ancestor = action.parent
                    while (ancestor != null) {
                        val current = requireNotNull(ancestor)
                        try {
                            if (current.windowId != frame.owner) valid = false
                            if (current.isScrollable) valid = visible.intersect(Rect().also(current::getBoundsInScreen)) && valid
                            if (current.className?.toString() == "android.widget.ScrollView") inMenu = true
                            ancestry += "${current.className}:${Rect().also(current::getBoundsInScreen)}"
                            ancestor = current.parent
                        } finally { current.recycle() }
                    }
                    if (menuOnly) println("CARE_CONTROL_PATH label=$label owner=${frame.owner} " +
                        "visible=$visible inMenu=$inMenu ancestors=$ancestry")
                    if (valid && (!menuOnly || inMenu) && visible.width() >= minimum && visible.height() >= minimum) hits += visible
                }
            } finally { action.recycle() }
        } }
        return hits.distinct().singleOrNull()
    }
    private fun scrollCare(frame: CareControlsFrame, direction: Int): Boolean {
        var accepted = false
        withNodes { all ->
            // The history LazyColumn is a separate scroller: menu actions belong to this ScrollView.
            val menu = all.singleOrNull { it.windowId == frame.owner && it.isVisibleToUser && it.isScrollable &&
                it.className?.toString() == "android.widget.ScrollView" &&
                frame.bounds.contains(Rect().also(it::getBoundsInScreen)) }
            if (menu?.actionList?.any { it.id == direction } == true) {
                accepted = menu.performAction(direction)
                assertTrue("Advertised Care menu scroll must be accepted",accepted)
            }
        }
        return accepted
    }
    private fun click(label: String, menuOnly: Boolean = false) {
        val frame = settledCareControls("click-$label")
        val bounds = careControlBounds(label,frame,menuOnly)
        if (bounds == null) saveCareControlsFailure("click-$label")
        requireNotNull(bounds) { "One visible 48dp Care action required: $label" }
        println("CARE_STAGE click label=$label owner=${frame.owner} actual=${frame.bounds} touch=$bounds")
        tap(bounds.exactCenterX(),bounds.exactCenterY())
        instrumentation.runOnMainSync { }
        val context = instrumentation.targetContext
        val more = context.getString(com.openminis.app.R.string.nm_more)
        val copy = context.getString(com.openminis.app.R.string.nm_care_copy)
        if (label == more || label == copy) {
            awaitNativeState("Care menu must ${if (label == more) "open" else "close"} after its single action") {
                var menuVisible = false
                withNodes { all -> menuVisible = all.any { node ->
                    // Labels can all be clipped at 200% font. The menu's ScrollView
                    // remains present; the transcript's LazyColumn has a different class.
                    node.windowId == frame.owner && node.isVisibleToUser &&
                        node.className?.toString() == "android.widget.ScrollView"
                } }
                menuVisible == (label == more)
            }
        }
        println("CARE_STAGE click=$label outcomeObserved=true")
    }
    private fun scrollCardToStart() {
        val deadline = SystemClock.uptimeMillis() + 5000
        var acceptedScrolls = 0
        do {
            val frame = settledCareControls("scroll-start")
            if (!scrollCare(frame,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                println("CARE_STAGE scrollStartObserved=true acceptedScrolls=$acceptedScrolls")
                return
            }
            acceptedScrolls++
        } while (SystemClock.uptimeMillis() < deadline)
        saveCareControlsFailure("scroll-start")
        fail("Care scroll container must report its beginning within the bounded observation")
    }
    private fun awaitNativeState(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { }
            ready = condition()
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue(description, ready)
    }
    private fun assertReachable(label: String) {
        scrollCardToStart()
        repeat(12) {
            val frame = settledCareControls("reach-$label")
            if (careControlBounds(label,frame) != null) return
            if (!scrollCare(frame,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                saveCareControlsFailure("reach-$label")
                fail("Visible 48dp Care control unreachable at menu end: $label")
            }
        }
        saveCareControlsFailure("reach-$label")
        fail("Control must remain reachable at this font scale/orientation: $label")
    }
    private fun closeOwnedFixture(service: MinisAccessibilityService) {
        var owned=false
        instrumentation.runOnMainSync { owned=service.dotScreen.currentSceneKey()?.packageName==instrumentation.context.packageName }
        if(owned) automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }
    private fun awaitMain(description: String, onTimeout: () -> Unit = {}, condition: () -> Boolean) {
        val deadline=SystemClock.uptimeMillis()+5000
        var ready=false
        while(!ready && SystemClock.uptimeMillis()<deadline) {
            instrumentation.runOnMainSync { ready=condition() }
            if(!ready) SystemClock.sleep(50)
        }
        if (!ready) onTimeout()
        assertTrue(description,ready)
    }
    private data class CareHostAdmission(val scene: SceneKey, val host: RectPx,
        val display: Rect, val window: Rect, val root: Rect, val target: Rect)
    private fun admitSettledCareFixture(service: MinisAccessibilityService, label: String, admit: () -> Unit) {
        var previous: CareHostAdmission? = null
        var unchangedSince = SystemClock.uptimeMillis()
        var samples = 0
        var observation = "not sampled"
        var previousObservation = ""
        val evidence = StringBuilder()
        fun save() {
            java.io.File(service.cacheDir, "care-$label-host-admission.txt").writeText(evidence.toString())
        }
        fun targetBounds(node: AccessibilityNodeInfo): Rect? {
            if (node.contentDescription?.toString() == "CARE_TOUCH_TARGET" && node.isVisibleToUser &&
                node.isEnabled && node.isClickable) {
                return Rect().also(node::getBoundsInScreen).takeUnless(Rect::isEmpty)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { targetBounds(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        awaitMain("Care fixture marker, native window, scene and host must settle before one admission", onTimeout = {
            evidence.append("timeout $observation\n"); save()
            println("CARE_HOST_ADMISSION_TIMEOUT label=$label $observation")
        }) {
            val scene = service.dotScreen.currentSceneKey()
            val hostId = service.dotScreen.hostWindowId()
            val host = scene?.let { service.dotScreen.hostViewport(it.windowId) }
            val display = scene?.let {
                service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(it.displayId)
            }
            val displaySize = android.graphics.Point()
            display?.getRealSize(displaySize)
            val displayBounds = Rect(0, 0, displaySize.x, displaySize.y).takeUnless(Rect::isEmpty)
            var nativeWindow: Rect? = null
            var nativeRoot: Rect? = null
            var rootWindowId: Int? = null
            var rootVisible = false
            var expectedPackage = false
            var target: Rect? = null
            val windows = service.windows
            try { windows.firstOrNull { it.id == scene?.windowId &&
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }?.let { window ->
                nativeWindow = Rect().also(window::getBoundsInScreen)
                window.root?.let { root ->
                    try {
                        rootWindowId = root.windowId
                        rootVisible = root.isVisibleToUser
                        expectedPackage = root.packageName?.toString() == instrumentation.context.packageName
                        nativeRoot = Rect().also(root::getBoundsInScreen)
                        if (expectedPackage) target = targetBounds(root)
                    } finally { root.recycle() }
                }
            } } finally { windows.forEach { it.recycle() } }
            val afterScene = service.dotScreen.currentSceneKey()
            val afterHost = scene?.let { service.dotScreen.hostViewport(it.windowId) }
            val frame = nativeWindow
            val root = nativeRoot
            val marker = target
            val current = if (scene != null && scene.packageName == instrumentation.context.packageName &&
                scene == afterScene && hostId == scene.windowId && rootWindowId == scene.windowId &&
                expectedPackage && frame != null && root != null && marker != null && host != null && host == afterHost &&
                rootVisible && displayBounds != null && frame == displayBounds && displayBounds.contains(root) &&
                frame.contains(root) && root.contains(marker) && host.left == root.left.toFloat() &&
                host.top == root.top.toFloat() && host.right == root.right.toFloat() &&
                host.bottom > host.top && host.bottom <= root.bottom && marker.left >= host.left &&
                marker.top >= host.top && marker.right <= host.right && marker.bottom <= host.bottom) {
                CareHostAdmission(scene, host, displayBounds, frame, root, marker)
            } else null
            val now = SystemClock.uptimeMillis()
            if (current == null || current != previous) {
                previous = current; unchangedSince = now; samples = if (current == null) 0 else 1
            } else samples++
            observation = "sceneWindow=${scene?.windowId} sceneVersion=${scene?.version} hostId=$hostId " +
                "rootWindowId=$rootWindowId expectedPackage=$expectedPackage markerPresent=${marker != null} " +
                "display=$displayBounds window=$frame root=$root rootVisible=$rootVisible host=$host marker=$marker sceneStable=${scene == afterScene} " +
                "hostStable=${host == afterHost} admissible=${current != null}"
            if (observation != previousObservation) {
                evidence.append("time=$now $observation\n")
                println("CARE_HOST_ADMISSION label=$label $observation")
                previousObservation = observation
            }
            val ready = current != null && samples >= 2 && now - unchangedSince >= 100L
            if (ready) {
                evidence.append("admitted samples=$samples unchangedMs=${now - unchangedSince}\n"); save()
                println("CARE_STAGE hostAdmitted=$label sceneWindow=${scene?.windowId} sceneVersion=${scene?.version} " +
                    "display=$displayBounds window=$frame host=$host unchangedMs=${now - unchangedSince}")
                // awaitMain caches this success. Construction and submit happen once in this
                // same Main observation, with no controller retry if later capture fails.
                admit()
                println("CARE_STAGE goalSubmitted=$label submitCount=1")
            }
            ready
        }
    }
    @Test fun compactTargetCareRetainsTranscriptWhileAdjustedDraggedAndExpanded() {
        val service = nativeService.service()
        val flags = automation.serviceInfo.flags
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var viewport: RectPx? = null
        var requestedScene: SceneKey? = null
        var sampledScene: SceneKey? = null
        var plannedScene: SceneKey? = null
        fun cardOrNull(): android.view.View? = if (!::window.isInitialized) null else
            CareOverlayWindow::class.java.getDeclaredField("card").let {
                it.isAccessible = true; it.get(window) as? android.view.View
            }
        fun card(): android.view.View = requireNotNull(cardOrNull())
        data class CardGeometry(val actual: Rect, val parameters: Rect, val native: Rect,
            val windowId: Int, val host: RectPx, val safe: RectPx)
        var lastGeometryObservation = "not sampled"
        fun safeHost(host: RectPx): RectPx {
            if (Build.VERSION.SDK_INT < 30) return host
            val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val display = metrics.bounds
            return RectPx(maxOf(host.left,(display.left+insets.left).toFloat()),
                maxOf(host.top,(display.top+insets.top).toFloat()),
                minOf(host.right,(display.right-insets.right).toFloat()),
                minOf(host.bottom,(display.bottom-insets.bottom).toFloat()))
        }
        fun contains(host: RectPx, actual: Rect) = !actual.isEmpty && actual.left >= host.left &&
            actual.top >= host.top && actual.right <= host.right && actual.bottom <= host.bottom
        // Called on Main: collect native View, parameters and AX identity in one observation.
        // A later identical observation is still required because AX can lag a layout traversal.
        fun observeCard(): CardGeometry? {
            val view = cardOrNull() ?: return null
            val p = view.layoutParams as? WindowManager.LayoutParams ?: return null
            val host = viewport ?: service.dotScreen.hostViewport() ?: return null
            val safe = safeHost(host)
            val location = IntArray(2); view.getLocationOnScreen(location)
            val actual = Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)
            val parameters = Rect(p.x,p.y,p.x+p.width,p.y+p.height)
            val node = view.createAccessibilityNodeInfo()
            val id = try { node.windowId } finally { node.recycle() }
            val observed = automation.windows
            val native = try { observed.firstOrNull { it.id == id }?.let { Rect().apply(it::getBoundsInScreen) } }
                finally { observed.forEach { it.recycle() } }
            lastGeometryObservation = "actual=$actual parameters=$parameters native=$native windowId=$id " +
                "host=$host safe=$safe attached=${view.isAttachedToWindow} laidOut=${view.isLaidOut} requested=${view.isLayoutRequested} " +
                "fitTypes=${if(Build.VERSION.SDK_INT>=30) p.getFitInsetsTypes() else null}"
            if (!view.isAttachedToWindow || !view.isLaidOut || view.isLayoutRequested || native != actual ||
                view.width != p.width || view.height != p.height || !contains(host,actual) || !contains(safe,actual) ||
                (Build.VERSION.SDK_INT >= 30 && actual != parameters) ||
                host != (viewport ?: service.dotScreen.hostViewport())) return null
            return CardGeometry(actual,parameters,requireNotNull(native),id,host,safe)
        }
        data class ControlHit(val label: String, val geometry: CardGeometry, val action: Rect,
            val visible: Rect, val labelBounds: Rect)
        var lastControlObservation = "not sampled"
        var lastInput = "no input"
        fun controlHit(label: String): ControlHit? {
            var geometry: CardGeometry? = null
            instrumentation.runOnMainSync { geometry = observeCard() }
            val current = geometry ?: return null
            val minimum = kotlin.math.ceil(48 * service.resources.displayMetrics.density).toInt()
            val candidates = mutableListOf<ControlHit>()
            val inspected = mutableListOf<String>()
            withNodes { all -> all.filter { it.windowId == current.windowId && it.isVisibleToUser && it.isEnabled &&
                (it.text?.toString() == label || it.contentDescription?.toString() == label) }.forEach { node ->
                var action = AccessibilityNodeInfo.obtain(node)
                try {
                    while (!action.isClickable && action.windowId == current.windowId) {
                        val parent = action.parent ?: break
                        action.recycle(); action = parent
                    }
                    if (action.windowId == current.windowId && action.isClickable && action.isVisibleToUser && action.isEnabled &&
                        action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                        val raw = Rect(); action.getBoundsInScreen(raw)
                        val visible = Rect(raw)
                        var clipped = visible.intersect(current.actual) && visible.intersect(current.native)
                        var ancestor = action.parent
                        while (ancestor != null) {
                            val owned = requireNotNull(ancestor)
                            try {
                                if (owned.windowId != current.windowId) clipped = false
                                if (owned.isScrollable) {
                                    val viewportBounds = Rect(); owned.getBoundsInScreen(viewportBounds)
                                    clipped = visible.intersect(viewportBounds) && clipped
                                }
                                ancestor = owned.parent
                            } finally { owned.recycle() }
                        }
                        val labelBounds = Rect(); node.getBoundsInScreen(labelBounds)
                        inspected += "action=$raw visible=$visible clipped=$clipped minimum=$minimum labelBounds=$labelBounds"
                        if (clipped && contains(current.safe,visible) && visible.width() >= minimum && visible.height() >= minimum)
                            candidates += ControlHit(label,current,raw,visible,labelBounds)
                    }
                } finally { action.recycle() }
            } }
            var after: CardGeometry? = null
            instrumentation.runOnMainSync { after = observeCard() }
            val unique = candidates.distinctBy { it.action to it.visible }
            lastControlObservation = "label=$label owner=${current.windowId} hostStable=${after == current} inspected=$inspected candidates=$unique"
            // A duplicate labelled action is ambiguous; never silently select the first one.
            return unique.singleOrNull()?.takeIf { after == current }
        }
        fun saveControls(stage: String) {
            val dump = StringBuilder("CARE_COMPACT $stage\ninput=[$lastInput]\ncontrol=[$lastControlObservation]\n")
            instrumentation.runOnMainSync {
                val view = cardOrNull(); val p = view?.layoutParams as? WindowManager.LayoutParams
                val state = if (::session.isInitialized) session.controller.state.value else null
                val observedHost = service.dotScreen.hostViewport()
                val effectiveHost = viewport ?: observedHost
                val location = IntArray(2)
                val actualBounds = view?.takeIf { it.isAttachedToWindow }?.let {
                    it.getLocationOnScreen(location)
                    Rect(location[0], location[1], location[0] + it.width, location[1] + it.height)
                }
                fun containedIn(host: RectPx?): Boolean? = if (actualBounds != null && host != null)
                    actualBounds.width() > 0 && actualBounds.height() > 0 &&
                        actualBounds.left >= host.left && actualBounds.top >= host.top &&
                        actualBounds.right <= host.right && actualBounds.bottom <= host.bottom
                else null
                // Keep the raw observation as well as the strict settled assertion below;
                // an in-flight screenshot or layout request is not a settled frame.
                val geometry = "CARE_COMPACT_GEOMETRY stage=$stage time=${SystemClock.uptimeMillis()} " +
                    "params=${p?.x},${p?.y},${p?.width},${p?.height} gravity=${p?.gravity} flags=${p?.flags} " +
                    "actual=$actualBounds laidOut=${view?.width}x${view?.height} measured=${view?.measuredWidth}x${view?.measuredHeight} " +
                    "attached=${view?.isAttachedToWindow} isLaidOut=${view?.isLaidOut} layoutRequested=${view?.isLayoutRequested} " +
                    "offsetFromParams=${if (actualBounds != null && p != null) "${actualBounds.left - p.x},${actualBounds.top - p.y}" else null} " +
                    "host=$effectiveHost observedHost=$observedHost hostMatchesObserved=${effectiveHost == observedHost} " +
                    "actualContained=${containedIn(effectiveHost)} observedHostContained=${containedIn(observedHost)} " +
                    "safeHost=${effectiveHost?.let(::safeHost)} safeContained=${effectiveHost?.let { containedIn(safeHost(it)) }} " +
                    "lastSettledObservation=[$lastGeometryObservation]"
                dump.append("card=${p?.x},${p?.y},${p?.width},${p?.height} attached=${view?.isAttachedToWindow} measured=${view?.width}x${view?.height} host=$observedHost density=${service.resources.displayMetrics.density} font=${service.resources.configuration.fontScale}\n")
                dump.append(geometry).append('\n')
                println(geometry)
                dump.append("state=${state?.state} error=${state?.errorCode} requested=$requestedScene sampled=$sampledScene planned=$plannedScene current=${service.dotScreen.currentSceneKey()}\n")
            }
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                dump.append(" ".repeat(depth)).append("class=${node.className} text=${node.text} description=${node.contentDescription} windowId=${node.windowId} bounds=$bounds visible=${node.isVisibleToUser} scrollable=${node.isScrollable} clickable=${node.isClickable} enabled=${node.isEnabled} actions=${node.actionList.map { it.id }}\n")
                for (i in 0 until node.childCount) node.getChild(i)?.let { try { visit(it, depth + 1) } finally { it.recycle() } }
            }
            val windows = automation.windows
            try { windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.forEach { native ->
                val bounds = Rect(); native.getBoundsInScreen(bounds)
                dump.append("nativeWindow id=${native.id} bounds=$bounds focused=${native.isFocused}\n")
                native.root?.let { try { visit(it, 0) } finally { it.recycle() } }
            } } finally { windows.forEach { it.recycle() } }
            java.io.File(service.cacheDir, "care-compact-$stage-native-tree.txt").writeText(dump.toString())
            if (stage in setOf("before", "adjusted", "after-refresh", "dragged", "expanded", "draft-relayout") ||
                stage.endsWith("timeout") || stage.endsWith("after-tap")) automation.takeScreenshot()?.let { bitmap ->
                try { java.io.File(service.cacheDir, "care-compact-$stage-native.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }
        fun settledCard(stage: String): CardGeometry {
            var previous: CardGeometry? = null
            var unchangedSince = SystemClock.uptimeMillis()
            awaitMain("Care $stage must settle at its exact native screen rectangle inside safe/IME bounds",
                onTimeout = { saveControls("$stage-geometry-timeout") }) {
                val current = observeCard()
                val now = SystemClock.uptimeMillis()
                if (current == null || current != previous) { previous = current; unchangedSince = now }
                current != null && now-unchangedSince >= 240L
            }
            return requireNotNull(previous).also { println("CARE_COMPACT_SETTLED stage=$stage geometry=$it") }
        }
        fun settledDragHeader(): Rect {
            val label = service.getString(com.openminis.app.R.string.nm_dot_foreground_adjust)
            val minimum = kotlin.math.ceil(48 * service.resources.displayMetrics.density).toInt()
            var previous: Pair<CardGeometry,Rect>? = null
            var unchangedSince = SystemClock.uptimeMillis()
            try { awaitNativeState("Visible Care drag header and owning native window must settle") {
                var geometry: CardGeometry? = null
                instrumentation.runOnMainSync { geometry = observeCard() }
                var header: Rect? = null
                geometry?.let { current -> withNodes { all ->
                    all.filter { it.windowId == current.windowId && it.isVisibleToUser && it.isEnabled &&
                        !it.isClickable && it.contentDescription?.toString() == label }.forEach { node ->
                        val rect = Rect(); node.getBoundsInScreen(rect)
                        if (header == null && current.actual.contains(rect) && current.native.contains(rect) &&
                            rect.width() >= minimum && rect.height() >= minimum) header = rect
                    }
                } }
                val current = geometry?.let { g -> header?.let { g to it } }
                val now = SystemClock.uptimeMillis()
                if (current == null || current != previous) { previous = current; unchangedSince = now }
                current != null && now-unchangedSince >= 240L
            } } catch (failure: AssertionError) { saveControls("drag-header-timeout"); throw failure }
            return Rect(requireNotNull(previous).second)
        }
        fun settledControl(label: String, stage: String): ControlHit {
            var previous: ControlHit? = null
            var unchangedSince = SystemClock.uptimeMillis()
            try { awaitNativeState("Care $stage action and owning window must settle before touch") {
                val current = controlHit(label)
                val now = SystemClock.uptimeMillis()
                if (current == null || current != previous) { previous = current; unchangedSince = now }
                current != null && now-unchangedSince >= 240L
            } } catch (failure: AssertionError) { saveControls("$stage-control-timeout"); throw failure }
            return requireNotNull(previous)
        }
        val inputTrace = StringBuilder()
        fun tapControl(hit: ControlHit, stage: String) {
            lastInput = "time=${SystemClock.uptimeMillis()} stage=$stage label=${hit.label} windowId=${hit.geometry.windowId} " +
                "window=${hit.geometry.native} action=${hit.action} visible=${hit.visible} labelBounds=${hit.labelBounds} " +
                "point=${hit.visible.exactCenterX()},${hit.visible.exactCenterY()}"
            println("CARE_COMPACT_INPUT $lastInput")
            inputTrace.append(lastInput).append('\n')
            java.io.File(service.cacheDir,"care-compact-input.txt").writeText(inputTrace.toString())
            try { tap(hit.visible.exactCenterX(),hit.visible.exactCenterY()) }
            finally { saveControls("$stage-after-tap") }
        }
        data class MenuSnapshot(val geometry: CardGeometry, val bounds: Rect, val rows: List<Rect>, val forward: Boolean)
        fun menuSnapshot(): MenuSnapshot? {
            var geometry: CardGeometry? = null
            instrumentation.runOnMainSync { geometry = observeCard() }
            val current = geometry ?: return null
            var result: MenuSnapshot? = null
            withNodes { all -> all.singleOrNull { it.windowId == current.windowId && it.isVisibleToUser &&
                it.isScrollable && it.className?.toString() == "android.widget.ScrollView" }?.let { scroll ->
                val bounds = Rect(); scroll.getBoundsInScreen(bounds)
                val rows = (0 until scroll.childCount).mapNotNull { index -> scroll.getChild(index)?.let { child ->
                    try { Rect().apply(child::getBoundsInScreen) } finally { child.recycle() }
                } }
                if (current.actual.contains(bounds)) result = MenuSnapshot(current,bounds,rows,
                    scroll.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
            } }
            var after: CardGeometry? = null
            instrumentation.runOnMainSync { after = observeCard() }
            return result?.takeIf { after == current }
        }
        fun settledMenu(stage: String): MenuSnapshot {
            var previous: MenuSnapshot? = null
            var unchangedSince = SystemClock.uptimeMillis()
            try { awaitNativeState("Care $stage menu rows must stop moving before further input") {
                val current = menuSnapshot()
                val now = SystemClock.uptimeMillis()
                if (current == null || current != previous) { previous = current; unchangedSince = now }
                current != null && now-unchangedSince >= 240L
            } } catch (failure: AssertionError) { saveControls("$stage-scroll-timeout"); throw failure }
            return requireNotNull(previous)
        }
        fun scrollMenu(menu: MenuSnapshot, stage: String) {
            // A full-page AX scroll skips the narrow interval where a 48dp row fits
            // in a ~60dp compact viewport. Overlap each measured viewport by 48dp.
            val minimum = kotlin.math.ceil(48 * service.resources.displayMetrics.density).toInt()
            val slack = menu.bounds.height() - minimum
            check(slack > 0) { "Care menu viewport cannot expose a complete 48dp action: ${menu.bounds}" }
            val slop = android.view.ViewConfiguration.get(service).scaledTouchSlop.toFloat()
            val shift = minOf(slack * .9f, 24f * service.resources.displayMetrics.density)
            val distance = slop + shift
            check(distance < menu.bounds.height() - 2) { "Care menu has no room for a native scroll gesture" }
            val x = menu.bounds.exactCenterX()
            val from = menu.bounds.exactCenterY() + distance / 2f
            val to = from - distance
            lastInput = "time=${SystemClock.uptimeMillis()} stage=$stage scroll owner=${menu.geometry.windowId} " +
                "viewport=${menu.bounds} maximumShift=$shift point=$x,$from->$x,$to"
            println("CARE_COMPACT_INPUT $lastInput")
            inputTrace.append(lastInput).append('\n')
            java.io.File(service.cacheDir,"care-compact-input.txt").writeText(inputTrace.toString())
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float) {
                val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
                try { injectTouch(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN,from)
            repeat(4) { index ->
                SystemClock.sleep(16)
                send(MotionEvent.ACTION_MOVE,from - distance * (index + 1) / 4f)
            }
            // Hold still before UP so fling velocity cannot skip the next admission interval.
            SystemClock.sleep(200)
            send(MotionEvent.ACTION_MOVE,to)
            send(MotionEvent.ACTION_UP,to)
        }
        fun reach(label: String, stage: String): ControlHit {
            val deadline = SystemClock.uptimeMillis() + 45000L
            repeat(80) { step ->
                controlHit(label)?.let { return settledControl(label,stage) }
                if (step == 0) {
                    val more = settledControl(service.getString(com.openminis.app.R.string.nm_more),"$stage-more")
                    tapControl(more,"$stage-open-menu")
                } else {
                    val menu = settledMenu("$stage-before-scroll-$step")
                    // The prior traversal may have arrived while waiting for the scroll to settle.
                    controlHit(label)?.let { return settledControl(label,stage) }
                    if (!menu.forward || SystemClock.uptimeMillis() >= deadline) {
                        saveControls("$stage-scroll-timeout")
                        error("Care menu cannot scroll to the 48dp action: $label")
                    }
                    scrollMenu(menu,"$stage-scroll-$step")
                }
                settledMenu("$stage-scroll-$step")
                if (step < 3) saveControls("scroll-$step")
            }
            saveControls("$stage-control-timeout")
            error("48dp Care control unreachable: $label")
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.runOnMainSync { service.dotInteraction.close() }
            instrumentation.context.startActivity(Intent(instrumentation.context, CareOverlayFixture::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            admitSettledCareFixture(service, "compact") {
                val controller = CareController(scope, { generation ->
                    // Controlled wide target is delivered through the real Care controller,
                    // validator and session, never through ordinary Chat's injected target hook.
                    val scene = requireNotNull(service.dotScreen.currentSceneKey())
                    sampledScene = scene
                    val host = requireNotNull(service.dotScreen.hostViewport())
                    val node = NodeDto("wide-target", host, "Wide fixture", null, null, "container", false, true, false, true, false)
                    CaptureOutcome.Success(ScreenSnapshot("wide-fixture", generation, 1, scene, host, 0,
                        RectPx(0f, 0f, 0f, 0f), null, listOf(node), null,
                        ScreenTransform(host, host.width.toInt(), host.height.toInt())))
                }, { service.dotScreen.currentSceneKey().also { requestedScene = it } }, { _, snapshot, _ ->
                    plannedScene = snapshot.scene
                    CarePlanResult.Step(CareProposal(snapshot.id, snapshot.scene, "wide-target", CareAction.Focus,
                        "Touch the fixture target", null, CareExpectation.Focused("wide-target"), .9f))
                })
                session = CareSession(controller, { if (::window.isInitialized) window.clearFocus() }, {})
                window = CareOverlayWindow(service, scope, session, { viewport ?: service.dotScreen.hostViewport() }, { service.dotScreen.currentSceneKey() })
                session.enter(); session.submitGoal("Keep this Care goal")
            }
            var previousEntry = ""
            awaitMain("Real Care owner must reach compact target collision", onTimeout = { saveControls("entry-timeout") }) {
                val state = session.controller.state.value
                val view = cardOrNull()
                val current = service.dotScreen.currentSceneKey()
                val observation = "state=${state.state} error=${state.errorCode} generation=${session.controller.generation} visible=${session.visible.value} editing=${session.editingGoal.value} requested=$requestedScene sampled=$sampledScene planned=$plannedScene current=$current attached=${view?.isAttachedToWindow} shown=${view?.isShown} height=${view?.height} host=${service.dotScreen.hostViewport()}"
                if (observation != previousEntry) {
                    println("CARE_COMPACT_ENTRY time=${SystemClock.uptimeMillis()} $observation")
                    previousEntry = observation
                }
                state.state == CareState.AwaitingUser && view?.isAttachedToWindow == true && view.height > 1
            }
            val beforeGeometry = settledCard("before")
            val retained = card()
            instrumentation.runOnMainSync {
                repeat(3) { index ->
                    assertFalse("Settled card must have no pending layout before refresh $index",retained.isLayoutRequested)
                    window.refreshGeometry()
                    assertSame("No-op refresh retains the actual card",retained,card())
                    assertFalse("No-op refresh must not request another native layout $index",retained.isLayoutRequested)
                    val p = retained.layoutParams as WindowManager.LayoutParams
                    val location = IntArray(2); retained.getLocationOnScreen(location)
                    assertEquals(beforeGeometry.parameters,Rect(p.x,p.y,p.x+p.width,p.y+p.height))
                    assertEquals(beforeGeometry.actual,Rect(location[0],location[1],location[0]+retained.width,location[1]+retained.height))
                }
                println("CARE_STAGE noOpRefreshCount=3 sameWindow=true layoutRequested=false")
            }
            val history = session.conversation.state.value
            val generation = session.controller.generation
            val before = (retained.layoutParams as WindowManager.LayoutParams).y
            saveControls("before")
            val adjust = reach(service.getString(com.openminis.app.R.string.nm_dot_foreground_adjust),"adjust")
            tapControl(adjust,"adjust")
            awaitMain("Actual named Care adjustment moves its same window", onTimeout = { saveControls("adjust-outcome-timeout") }) { (card().layoutParams as WindowManager.LayoutParams).y > before }
            val adjustedGeometry = settledCard("adjusted")
            assertTrue("Actual native card moves with Adjust", adjustedGeometry.actual.top > beforeGeometry.actual.top)
            val adjusted = (retained.layoutParams as WindowManager.LayoutParams).y
            saveControls("adjusted")
            try {
                instrumentation.runOnMainSync { window.refreshGeometry(); assertEquals(adjusted, (retained.layoutParams as WindowManager.LayoutParams).y) }
                settledCard("after-refresh")
            } finally { saveControls("after-refresh") }
            reach(service.getString(com.openminis.app.R.string.nm_dot_foreground_adjust),"drag-menu")
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            // Use the actual named header in its owning window, not LayoutParams offsets.
            val drag = settledDragHeader()
            val down = SystemClock.uptimeMillis()
            for ((action, y) in listOf(MotionEvent.ACTION_DOWN to drag.exactCenterY(),
                MotionEvent.ACTION_MOVE to (drag.exactCenterY() - 80f),
                MotionEvent.ACTION_MOVE to (drag.exactCenterY() - 220f), MotionEvent.ACTION_UP to (drag.exactCenterY() - 220f))) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, drag.exactCenterX(), y, 0)
                try { injectTouch(event) } finally { event.recycle() }
            }
            awaitMain("Actual drag changes Care placement", onTimeout = { saveControls("drag-outcome-timeout") }) { (card().layoutParams as WindowManager.LayoutParams).y < adjusted }
            val draggedGeometry = settledCard("dragged")
            assertTrue("Actual native card moves with the header drag", draggedGeometry.actual.top < adjustedGeometry.actual.top)
            saveControls("dragged")
            instrumentation.runOnMainSync {
                val host = requireNotNull(service.dotScreen.hostViewport())
                val p = retained.layoutParams as WindowManager.LayoutParams
                assertTrue(p.x >= host.left && p.y >= host.top && p.x + p.width <= host.right + 1 && p.y + p.height <= host.bottom + 1)
                assertSame(retained, card()); assertEquals(history, session.conversation.state.value)
                assertEquals(generation, session.controller.generation); assertEquals("Keep this Care goal", session.controller.state.value.goal)
                window.refreshGeometry()
            }
            val height = retained.height
            val expand = reach(service.getString(com.openminis.app.R.string.nm_dot_foreground_expand),"expand")
            tapControl(expand,"expand")
            awaitMain("Expand retains a bounded Care panel", onTimeout = { saveControls("expand-outcome-timeout") }) { card().height >= height }
            settledCard("expanded")
            saveControls("expanded")
            instrumentation.runOnMainSync {
                val host = requireNotNull(service.dotScreen.hostViewport())
                val p = card().layoutParams as WindowManager.LayoutParams
                assertTrue(p.y >= host.top && p.y + p.height <= host.bottom + 1)
                // This fixture target covers the host: expansion must retain truthful compact placement.
                assertTrue(p.height < host.height)
            }
            instrumentation.runOnMainSync {
                assertSame(retained, card()); assertEquals(history, session.conversation.state.value)
                assertEquals(generation, session.controller.generation)
                session.changeGoal()
            }
            awaitMain("Same Care composition must show the goal editor") { session.editingGoal.value }
            var typed = false
            repeat(20) {
                if (!typed) withNodes { all ->
                    all.firstOrNull { it.isEditable && it.isVisibleToUser }?.let { editor ->
                        typed = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "retained Care draft")
                        })
                    }
                    if (!typed) all.filter { it.isScrollable }.maxByOrNull { Rect().also(it::getBoundsInScreen).top }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                automation.waitForIdle(100, 2000)
            }
            assertTrue("Actual editable goal accepts an unsent draft", typed)
            instrumentation.runOnMainSync {
                val host = requireNotNull(service.dotScreen.hostViewport())
                viewport = host.copy(bottom = minOf(host.bottom, host.top + 200f * service.resources.displayMetrics.density))
                window.refreshGeometry()
                assertSame(retained, card())
            }
            settledCard("draft-relayout")
            saveControls("draft-relayout")
            withNodes { all -> assertTrue("Relayout must retain the unsent goal in the same editor", all.any {
                it.isEditable && it.text?.toString() == "retained Care draft"
            }) }
        } finally {
            instrumentation.runOnMainSync { if (::window.isInitialized) window.close(); if (::session.isInitialized) session.disconnect(); scope.cancel() }
            closeOwnedFixture(service)
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }
    @Test fun fontScaleTwoKeepsAllControlsReachable() {
        val service=nativeService.service()
        val previous=android.provider.Settings.System.getString(service.contentResolver,"font_scale")
        require(previous==null || previous.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            shell("settings put system font_scale 2.0")
            awaitMain("Actual service configuration must reach font scale 2.0") { service.resources.configuration.fontScale==2f }
            oneTouchThroughTargetUserCopyBackPauseAndAccessibleControls()
        } finally {
            shell(if(previous==null) "settings delete system font_scale" else "settings put system font_scale $previous")
        }
    }
    @Test fun animatorScaleZeroKeepsOneStaticNonfocusedOutline() {
        val service=nativeService.service()
        val previous=android.provider.Settings.Global.getString(service.contentResolver,"animator_duration_scale")
        require(previous==null || previous.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        var layer: CareFocusLayer?=null
        val originalFlags=automation.serviceInfo.flags
        try {
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            shell("settings put global animator_duration_scale 0")
            instrumentation.context.startActivity(Intent(instrumentation.context,CareOverlayFixture::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            data class StaticGeometry(val scene: SceneKey, val host: RectPx, val target: Rect,
                val hostWindow: Rect, val focusWindowId: Int?, val focus: Rect?, val native: Rect?,
                val parameters: String?, val mode: Int)
            var lastGeometry = "not sampled"
            val evidence = StringBuilder()
            fun geometry(withFocus: Boolean): StaticGeometry? {
                var scene: SceneKey? = null
                var host: RectPx? = null
                var focus: Rect? = null
                var focusId: Int? = null
                var parameters: String? = null
                var mode = 0
                var ready = !withFocus
                instrumentation.runOnMainSync {
                    scene = service.dotScreen.currentSceneKey(); host = service.dotScreen.hostViewport()
                    mode = service.resources.configuration.uiMode
                    if (withFocus) layer?.view?.let { view ->
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        focus = Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)
                        val node = view.createAccessibilityNodeInfo()
                        try { focusId = node.windowId } finally { node.recycle() }
                        val p = view.layoutParams as? WindowManager.LayoutParams
                        val fitTypes = if (Build.VERSION.SDK_INT >= 30) p?.getFitInsetsTypes() else null
                        val cutout = if (Build.VERSION.SDK_INT >= 28) p?.layoutInDisplayCutoutMode else null
                        parameters = "type=${p?.type} flags=${p?.flags} fitTypes=$fitTypes cutout=$cutout"
                        val flags = p?.flags ?: 0
                        val correctInsets = when {
                            Build.VERSION.SDK_INT >= 30 -> fitTypes == 0 &&
                                cutout == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            Build.VERSION.SDK_INT >= 28 ->
                                cutout == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            else -> true
                        }
                        ready = view.isAttachedToWindow && view.isLaidOut && !view.isLayoutRequested &&
                            view.isShown && view.windowVisibility == android.view.View.VISIBLE && view.alpha > 0f &&
                            view.windowToken != null && !view.hasFocus() && !view.hasWindowFocus() &&
                            view.importantForAccessibility == android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO &&
                            p?.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
                            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0 &&
                            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 && correctInsets
                    }
                }
                val windows = automation.windows
                var hostWindow: Rect? = null
                var native: Rect? = null
                try {
                    hostWindow = windows.firstOrNull { it.id == scene?.windowId }?.let { Rect().apply(it::getBoundsInScreen) }
                    native = windows.firstOrNull { it.id == focusId }?.let { Rect().apply(it::getBoundsInScreen) }
                } finally { windows.forEach { it.recycle() } }
                var target: Rect? = null
                withNodes { all -> all.singleOrNull { it.windowId == scene?.windowId && it.isVisibleToUser &&
                    it.isEnabled && it.isClickable && it.contentDescription?.toString() == "CARE_TOUCH_TARGET"
                }?.let { target = Rect().apply(it::getBoundsInScreen) } }
                val scale = android.provider.Settings.Global.getFloat(service.contentResolver,"animator_duration_scale")
                var unchanged = false
                instrumentation.runOnMainSync {
                    unchanged = scene == service.dotScreen.currentSceneKey() && host == service.dotScreen.hostViewport() &&
                        (!withFocus || layer?.bounds == target?.let { RectPx(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat()) })
                }
                lastGeometry = "time=${SystemClock.uptimeMillis()} scale=$scale ready=$ready scene=$scene host=$host " +
                    "target=$target hostWindow=$hostWindow focusId=$focusId focus=$focus native=$native " +
                    "parameters=$parameters mode=$mode unchanged=$unchanged"
                val marker = target ?: return null
                val owned = scene ?: return null
                val viewport = host ?: return null
                val hostBounds = hostWindow ?: return null
                if (!ready || !unchanged || scale != 0f || owned.packageName != instrumentation.context.packageName ||
                    marker.isEmpty || !hostBounds.contains(marker) || marker.left < viewport.left || marker.top < viewport.top ||
                    marker.right > viewport.right || marker.bottom > viewport.bottom ||
                    // A non-touchable/non-focusable visual window need not be in AX.
                    // Its actual View must cover the target, and all four pixel bands below must render.
                    (withFocus && (focus == null || (native != null && focus != native) || focus?.contains(marker) != true))) return null
                return StaticGeometry(owned,viewport,marker,hostBounds,focusId,focus,native,parameters,mode)
            }
            fun save(bitmap: android.graphics.Bitmap, stage: String, target: Rect?) {
                java.io.File(service.cacheDir,"care-static-$stage.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                if (target != null && Rect(0,0,bitmap.width,bitmap.height).contains(target)) {
                    val roi = android.graphics.Bitmap.createBitmap(bitmap,target.left,target.top,target.width(),target.height())
                    try { java.io.File(service.cacheDir,"care-static-$stage-roi.png").outputStream().use {
                        roi.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    } } finally { roi.recycle() }
                }
            }
            fun saveEvidence() { java.io.File(service.cacheDir,"care-static-geometry.txt").writeText(evidence.toString()) }
            var previousGeometry: StaticGeometry? = null
            var unchangedSince = SystemClock.uptimeMillis()
            try { awaitNativeState("Actual reduced-motion target and host must settle before outline creation") {
                val current = geometry(false)
                val now = SystemClock.uptimeMillis()
                if (current == null || current != previousGeometry) { previousGeometry = current; unchangedSince = now }
                current != null && now-unchangedSince >= 240L
            } } catch (failure: AssertionError) {
                evidence.append("host-timeout $lastGeometry\n"); saveEvidence()
                automation.takeScreenshot()?.let { try { save(it,"host-timeout",null) } finally { it.recycle() } }
                throw failure
            }
            println("CARE_STAGE staticHostSettled $lastGeometry")
            val rect = Rect(requireNotNull(previousGeometry).target)
            val bounds = RectPx(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat())
            instrumentation.runOnMainSync {
                assertEquals(0f,android.provider.Settings.Global.getFloat(service.contentResolver,"animator_duration_scale"),0f)
                layer=CareFocusLayer(service)
                layer!!.show(bounds)
                val first=layer!!.view
                layer!!.show(bounds)
                assertSame(first,layer!!.view)
                assertFalse(first!!.hasFocus())
            }
            previousGeometry = null
            unchangedSince = SystemClock.uptimeMillis()
            var first: android.graphics.Bitmap? = null
            var firstGeometry: StaticGeometry? = null
            var lastCandidate: android.graphics.Bitmap? = null
            try {
                try { awaitNativeState("All four static outline edges must actually render in a settled native frame") {
                    val current = geometry(true)
                    val now = SystemClock.uptimeMillis()
                    if (current == null || current != previousGeometry) { previousGeometry = current; unchangedSince = now }
                    if (current == null || now-unchangedSince < 240L) {
                        evidence.append("layout $lastGeometry\n"); saveEvidence()
                        return@awaitNativeState false
                    }
                    val bitmap = automation.takeScreenshot() ?: return@awaitNativeState false
                    lastCandidate?.recycle(); lastCandidate = bitmap
                    val density = service.resources.displayMetrics.density
                    val stroke = kotlin.math.ceil(3f*density).toInt()
                    val middle = kotlin.math.ceil(4f*density).toInt()
                    assertTrue("Fixture must expose straight segments outside its rounded corners",
                        rect.width() > 2*(8f*density+middle+stroke) && rect.height() > 2*(8f*density+middle+stroke))
                    val blue = if (current.mode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) io.github.nanomuse.dot.DotColors.DarkCare.toArgb()
                        else io.github.nanomuse.dot.DotColors.LightCare.toArgb()
                    val bands = listOf(Rect(rect.left,rect.centerY()-middle,rect.left+stroke,rect.centerY()+middle),
                        Rect(rect.right-stroke,rect.centerY()-middle,rect.right,rect.centerY()+middle),
                        Rect(rect.centerX()-middle,rect.top,rect.centerX()+middle,rect.top+stroke),
                        Rect(rect.centerX()-middle,rect.bottom-stroke,rect.centerX()+middle,rect.bottom))
                    val counts = bands.map { band ->
                        assertTrue("Measured outline band must be inside the actual screenshot",
                            Rect(0,0,bitmap.width,bitmap.height).contains(band))
                        var count = 0
                        for (y in band.top until band.bottom) for (x in band.left until band.right)
                            if (bitmap.getPixel(x,y) == blue) count++
                        count
                    }
                    val after = geometry(true)
                    evidence.append("gate counts=$counts bands=$bands color=${Integer.toHexString(blue)} $lastGeometry\n")
                    saveEvidence()
                    if (counts.all { it >= middle } && after == current) {
                        first = bitmap; lastCandidate = null; firstGeometry = current
                        true
                    } else false
                } } catch (failure: AssertionError) {
                    evidence.append("gate-timeout $lastGeometry\n"); saveEvidence()
                    val failedFrame = lastCandidate ?: automation.takeScreenshot()
                    failedFrame?.let {
                        try { save(it,"gate-timeout",rect) } finally { if (it !== lastCandidate) it.recycle() }
                    }
                    throw failure
                }
                val rendered = requireNotNull(first)
                save(rendered,"first",rect)
                evidence.append("first $lastGeometry\n"); saveEvidence()
                println("CARE_STAGE staticFirstRendered $lastGeometry")
                SystemClock.sleep(300)
                val beforeSecond = geometry(true)
                val second = requireNotNull(automation.takeScreenshot())
                try {
                    val secondGeometry = geometry(true)
                    save(second,"second",rect)
                    evidence.append("second $lastGeometry\n"); saveEvidence()
                    assertEquals("Static comparison must keep the actual host/target/focus geometry",firstGeometry,beforeSecond)
                    assertEquals("Screenshot capture must keep the actual host/target/focus geometry",beforeSecond,secondGeometry)
                    val a = IntArray(rect.width()*rect.height()); val b = IntArray(a.size)
                    rendered.getPixels(a,0,rect.width(),rect.left,rect.top,rect.width(),rect.height())
                    second.getPixels(b,0,rect.width(),rect.left,rect.top,rect.width(),rect.height())
                    assertArrayEquals("Reduced motion must remain static",a,b)
                } finally { second.recycle() }
            } finally { first?.recycle(); lastCandidate?.recycle() }
        } finally {
            try {
                instrumentation.runOnMainSync { layer?.clear() }
            } finally {
                try {
                    shell(if(previous==null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $previous")
                    closeOwnedFixture(service)
                } finally {
                    automation.serviceInfo=automation.serviceInfo.apply { flags=originalFlags }
                }
            }
        }
    }
    @Test fun oneTouchThroughTargetUserCopyBackPauseAndAccessibleControls() {
        val service=nativeService.service()
        val originalFlags=automation.serviceInfo.flags
        var originalClip: android.content.ClipData?=null
        var clipboardSaved=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var target: RectPx?=null
        try {
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.context.startActivity(Intent(instrumentation.context,CareOverlayFixture::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            admitSettledCareFixture(service, "controls") {
                val controller=CareController(scope, { gen -> service.dotScreen.captureNodes(gen) },
                    { service.dotScreen.currentSceneKey() }, { _,snapshot,_ ->
                        val node=snapshot.nodes.single { it.description=="CARE_TOUCH_TARGET" }
                        target=node.bounds
                        CarePlanner(FixtureProvider(),"Which control can you see?").next("Touch fixture",snapshot,emptyList())
                    })
                session=CareSession(controller,{ if(::window.isInitialized) window.clearFocus() },{})
                window=CareOverlayWindow(service,scope,session,{service.dotScreen.hostViewport()},{service.dotScreen.currentSceneKey()},
                    kotlinx.coroutines.flow.MutableStateFlow(io.github.nanomuse.dot.DotForegroundState(connected=true,
                        mode=io.github.nanomuse.dot.DotForegroundMode.Care, operation=io.github.nanomuse.dot.DotForegroundOperation.CareWaiting)))
                session.enter(); session.submitGoal("Touch fixture")
            }
            awaitMain("Care proposal and actual focus window must be ready on Main") {
                session.controller.state.value.state==CareState.AwaitingUser && window.focus.view?.let {
                    it.isAttachedToWindow && it.isShown && it.windowVisibility==android.view.View.VISIBLE && it.alpha>0f
                }==true
            }
            automation.waitForIdle(500,5000)
            instrumentation.runOnMainSync {
                assertEquals(CareState.AwaitingUser,session.controller.state.value.state)
                assertEquals(target,window.focus.bounds)
                val params=window.focus.view!!.layoutParams as WindowManager.LayoutParams
                assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,params.type)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,window.focus.view!!.importantForAccessibility)
            }
            // Copy explicitly requires confirmation in the real controller; it is not an
            // ordinary AwaitingUser step. Reaching/copying the control must not credit it.
            instrumentation.runOnMainSync {
                assertEquals("needs_user_confirmation", session.controller.state.value.errorCode)
                assertNotNull(session.controller.state.value.pendingInstruction)
            }
            assertReachable(service.getString(com.openminis.app.R.string.nm_care_confirm))
            val reachable = listOf(com.openminis.app.R.string.nm_care_copy,
                com.openminis.app.R.string.nm_care_repeat,com.openminis.app.R.string.nm_care_pause,
                com.openminis.app.R.string.nm_care_reobserve,com.openminis.app.R.string.nm_care_end,
                com.openminis.app.R.string.nm_care_change_goal)
            click(service.getString(com.openminis.app.R.string.nm_more))
            for (label in reachable) assertReachable(service.getString(label))
            instrumentation.runOnMainSync {
                originalClip=service.getSystemService(ClipboardManager::class.java).primaryClip
                clipboardSaved=true
            }
            val copy=service.getString(com.openminis.app.R.string.nm_care_copy)
            assertReachable(copy) // The bounded in-panel menu may have scrolled past Copy.
            click(copy,menuOnly = true)
            instrumentation.runOnMainSync {
                assertEquals("fixture copy",service.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
                assertTrue(session.controller.verifiedSteps.value.isEmpty())
            }
            val bounds=requireNotNull(target); tap((bounds.left+bounds.right)/2,(bounds.top+bounds.bottom)/2)
            automation.waitForIdle(300,5000)
            withNodes { all -> assertTrue(all.any { it.text?.toString()=="CARE_TOUCH_RECEIVED" }) }
            // Compose semantics: announced status plus visible controls. Run this same method under
            // root's 1.0/1.5/2.0 font-scale + landscape matrix; scroll the measured card when needed.
            withNodes { all ->
                if(android.os.Build.VERSION.SDK_INT>=30) assertTrue(all.any { it.stateDescription?.isNotBlank()==true })
                assertTrue(all.any { it.text?.toString()==service.getString(com.openminis.app.R.string.nm_care_awaiting) })
            }
            saveCareBackEvidence("before")
            val accepted = automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            saveCareBackEvidence("dispatched",accepted)
            assertTrue("Single native Back must be accepted",accepted)
            awaitMain("Single Back must pause Care and clear its focus", onTimeout = {
                saveCareBackEvidence("timeout",accepted)
            }) { session.controller.state.value.state == CareState.Paused && window.focus.bounds == null }
            saveCareBackEvidence("paused",accepted)
            instrumentation.runOnMainSync {
                assertEquals(CareState.Paused,session.controller.state.value.state)
                assertNull(window.focus.bounds)
            }
        } finally {
            try {
            instrumentation.runOnMainSync {
                if(clipboardSaved) {
                    val clipboard=service.getSystemService(ClipboardManager::class.java)
                    originalClip?.let(clipboard::setPrimaryClip) ?: run {
                        if(android.os.Build.VERSION.SDK_INT>=28) clipboard.clearPrimaryClip()
                        else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("",""))
                    }
                }
                if(::window.isInitialized) window.close()
                if(::session.isInitialized) session.disconnect()
                scope.cancel()
            }
            closeOwnedFixture(service)
            } finally {
                automation.serviceInfo=automation.serviceInfo.apply { flags=originalFlags }
            }
        }
    }
}

/** Public provider contract, inert test transport; the real CarePlanner validates its reply. */
private class FixtureProvider: LLMProvider {
    override val name="care-overlay-fixture"
    override var model=LLMModel("fixture","Fixture","test",contextWindow=32768,maxOutputTokens=1024)
    override suspend fun sendMessageClamped(messages: List<LLMMessage>,systemPrompt: String?,maxTokens: Int,
        temperature: Double?,imageParts: List<LLMMessage.ImagePart>,tools: List<AgentToolDefinition>,thinkingLevel: ThinkingLevel): LLMResponse {
        check(tools.isEmpty() && imageParts.isEmpty())
        val request=Json.parseToJsonElement(messages.single().content).jsonObject
        val node=request.getValue("nodes").jsonArray.single { it.jsonObject["description"]?.jsonPrimitive?.content=="CARE_TOUCH_TARGET" }.jsonObject
        val reply=buildJsonObject {
            put("type","Step"); put("snapshotId",request.getValue("snapshotId")); put("scene",request.getValue("scene"))
            put("targetRef",node.getValue("ref")); put("action","Copy"); put("instruction","Touch the fixture target")
            put("copyText","fixture copy"); put("confidence",0.9)
            put("expectation",buildJsonObject { put("type","PageContains"); put("text","CARE_TOUCH_RECEIVED") })
        }
        return LLMResponse(reply.toString(),"stop",null)
    }
    override fun streamMessageClamped(messages: List<LLMMessage>,systemPrompt: String?,maxTokens: Int,
        temperature: Double?,imageParts: List<LLMMessage.ImagePart>,tools: List<AgentToolDefinition>,thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> =
        error("Care does not stream or run an agent loop")
}
