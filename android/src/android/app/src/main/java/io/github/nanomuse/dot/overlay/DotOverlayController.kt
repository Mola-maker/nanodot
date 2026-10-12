package io.github.nanomuse.dot.overlay

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openminis.app.R
import com.openminis.app.ui.settings.PREF_APPEARANCE
import io.github.nanomuse.dot.DotDefaults
import io.github.nanomuse.dot.DotPreferences
import io.github.nanomuse.dot.gesture.*
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.chat.DotChatPanel
import io.github.nanomuse.dot.screen.RectPx
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.ui.theme.MinisTheme
import kotlin.math.roundToInt

/** Main-thread owner of window, gesture clock and service-scoped observers. No capture/voice APIs. */
class DotOverlayController(
    private val service: AccessibilityService,
    private val delegate: ((DotIntent) -> Unit)? = null,
) {
    private val careWindows = mutableSetOf<View>()
    private val careActive = androidx.compose.runtime.mutableStateOf(false)
    fun registerCareWindow(view: View) { careWindows.add(view) }
    fun unregisterCareWindow(view: View) { careWindows.remove(view) }
    fun setCareActive(active: Boolean) { careActive.value = active }
    private fun ownedViews() = listOfNotNull(orbView, panelView, trailView, captureScanner.view) + careWindows.toList()
    private val captureScanner = DotCaptureScanWindow(service)
    fun beginCaptureScan(generation: Long) {
        checkMainThread()
        if (isConnected && !locked && enabled) captureScanner.begin(generation)
    }
    fun finishCaptureScan(generation: Long, success: Boolean) {
        checkMainThread()
        captureScanner.finish(generation, success)
    }
    fun cancelCaptureScan(generation: Long? = null) {
        checkMainThread()
        captureScanner.cancel(generation)
    }
    private fun suspendCare() { (service as? MinisAccessibilityService)?.dotInteraction?.pauseCare(hide=true) }
    private val visualActive = androidx.compose.runtime.mutableStateOf(true)
    private val pressed = androidx.compose.runtime.mutableStateOf(false)
    private val dragging = androidx.compose.runtime.mutableStateOf(false)
    private var motionEnabled = true
    private val trail = CrystalTrail()
    private var trailView: DotTrailView? = null
    private val trailTick = object : Runnable {
        override fun run() {
            val points = trail.visible(SystemClock.uptimeMillis())
            if (points.isEmpty()) { removeTrailWindow(); return }
            if (!motionEnabled || !visualActive.value || !isConnected || locked || !enabled) {
                clearTrail(); return
            }
            trailView?.let { it.points = points; it.invalidate() }
            handler.postDelayed(this, 16)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val windows = service.getSystemService(WindowManager::class.java)
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val preferences = DotPreferences(service)
    private val appearance = service.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)
    private val config = ViewConfiguration.get(service)
    private val reducer = DotGestureReducer(GestureLimits(
        ViewConfiguration.getDoubleTapTimeout().toLong(), ViewConfiguration.getLongPressTimeout().toLong(),
        config.scaledTouchSlop.toFloat(), config.scaledDoubleTapSlop.toFloat(),
    ))
    private val tick = Runnable { accept(GestureEvent.Tick(SystemClock.uptimeMillis())) }
    private val size = (48 * service.resources.displayMetrics.density).roundToInt()
    private var enabled = preferences.showOnAccessibilityConnected()
    private var locked = false
    var isConnected = false
        private set
    internal var orbView: View? = null
        private set
    internal var orbOwner: DotOverlayOwner? = null
        private set
    internal val windowCount get() = registry.size + if (trailView != null) 1 else 0
    private var orbComposition: ComposeView? = null
    internal var panelView: View? = null
        private set
    private var panelComposition: ComposeView? = null
    private var panelOwner: DotOverlayOwner? = null
    private var panelAdapter: DotChatAdapter? = null
    private var panelCollapse: (() -> Unit)? = null
    private var panelStop: ((io.github.nanomuse.dot.chat.SessionBinding) -> Unit)? = null
    internal var panelHostWindowId: Int? = null
        private set
    private var panelParams: WindowManager.LayoutParams? = null
    private var geometryPending = false
    private var voicePermissionWindow = false
    /** Keep the binding/window owners while allowing the real permission Activity to be visible. */
    fun setVoicePermissionWindow(visible: Boolean) {
        voicePermissionWindow = visible
        ownedViews().forEach { it.visibility = if (visible) View.INVISIBLE else View.VISIBLE }
        if (!visible) refreshPanelGeometry()
    }
    private var removeBackCallback: (() -> Unit)? = null
    private var dragStarted = false
    private var downAtMs = 0L
    private var params: WindowManager.LayoutParams? = null
    private var legacyGeometry: DotWindowGeometry? = null
    private var firstLayout: ViewTreeObserver.OnPreDrawListener? = null
    private var downPoint: PointPx? = null
    private var downPosition: Pair<Int, Int>? = null
    private var blockedTouch = false
    private val registry = DotWindowRegistry(::addWindow, ::removeWindow)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DotDefaults.SHOW_ON_ACCESSIBILITY_CONNECTED) {
            handler.post { if (isConnected) setEnabled(preferences.showOnAccessibilityConnected()) }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            setLocked(intent?.action == Intent.ACTION_SCREEN_OFF || deviceLocked())
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { onDisplayChanged() }
        override fun onDisplayChanged(displayId: Int) { onDisplayChanged() }
    }

    /** Shared immutable presentation; the coordinator retains effect ownership. */
    val foregroundState get() = (service as? MinisAccessibilityService)?.dotInteraction?.foreground?.state

    fun connect() {
        checkMainThread()
        if (!isConnected) {
            isConnected = true
            enabled = preferences.showOnAccessibilityConnected()
            locked = deviceLocked()
            appearance.registerOnSharedPreferenceChangeListener(preferenceListener)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else service.registerReceiver(screenReceiver, filter)
            displays.registerDisplayListener(displayListener, handler)
        }
        sync()
    }

    fun disconnect() {
        checkMainThread()
        cancelCaptureScan()
        cancelGesture()
        if (isConnected) {
            isConnected = false
            appearance.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            service.unregisterReceiver(screenReceiver)
            displays.unregisterDisplayListener(displayListener)
        }
        sync()
    }

    fun setLocked(locked: Boolean) {
        checkMainThread()
        this.locked = locked
        if (locked) { cancelCaptureScan(); cancelGesture(); suspendCare() }
        sync()
    }

    fun setEnabled(enabled: Boolean) {
        checkMainThread()
        this.enabled = enabled
        if (!enabled) { cancelCaptureScan(); cancelGesture(); suspendCare() }
        sync()
    }

    fun refreshLockState() { if (isConnected) setLocked(deviceLocked()) }

    private fun deviceLocked() = keyguard.isKeyguardLocked || !power.isInteractive

    /** Recreate with new display bounds, clearing all pending touch/tap work. */
    fun onDisplayChanged() {
        checkMainThread()
        cancelCaptureScan()
        (service as? MinisAccessibilityService)?.dotInteraction?.onHostGeometryChanged()
        cancelGesture()
        registry.sync(emptySet())
        sync()
    }

    fun onIntent(intent: DotIntent) {
        checkMainThread()
        if (!isConnected || locked || !enabled) return
        if (intent is DotIntent.Drag) {
            // Unclassified movement fallback. Task6 replaces this with full selection geometry;
            // downPosition remains available throughout this synchronous disposition.
            val point = intent.points.lastOrNull() ?: return
            val bounds = availableBounds() ?: return
            val p = params ?: return
            p.x = bounds.edgeX(size, point.x)
            p.y = bounds.clamp(size, p.x, p.y).second
            orbView?.let { windows.updateViewLayout(it, p) }
            val position = bounds.fractions(size, p.x, p.y)
            preferences.saveOrbPosition(position.first, position.second)
        } else {
            // Later coordinator tasks provide these capabilities. Never pretend an action ran.
            if (delegate != null) delegate.invoke(intent)
            else Toast.makeText(service, R.string.nm_dot_action_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Identity is limited to windows this owner creates; our main Activity is a valid host. */
    @Suppress("DEPRECATION")
    fun ownsWindow(window: android.view.accessibility.AccessibilityWindowInfo): Boolean {
        if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false
        return ownedViews().any { view ->
            val node = view.createAccessibilityNodeInfo() ?: return@any false
            try { node.windowId >= 0 && node.windowId == window.id } finally { node.recycle() }
        }
    }

    /** Hide alpha without removing/recreating owners or changing the user's preference.
     * OnDraw plus a following frame ensures traversal submits the hidden window before capture.
     */
    suspend fun <T> withHiddenForCapture(block: suspend () -> T): T {
        checkMainThread()
        visualActive.value = false
        val resumeScan = captureScanner.pauseForCapture()
        clearTrail()
        // A newly added window may attach during capture; hide its alpha before
        // that first traversal too, and only await drawing for attached windows.
        val owned = ownedViews()
        val alphas = owned.map { it.alpha }
        try {
            for (view in owned) {
                view.alpha = 0f
                if(!view.isAttachedToWindow) continue
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    lateinit var listener: ViewTreeObserver.OnDrawListener
                    listener = ViewTreeObserver.OnDrawListener {
                        view.post {
                            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
                            view.postOnAnimation { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
                        }
                    }
                    view.viewTreeObserver.addOnDrawListener(listener)
                    continuation.invokeOnCancellation { view.post {
                        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
                    } }
                    view.invalidate()
                }
            }
            return block()
        } finally {
            resumeScan()
            visualActive.value = true
            owned.forEachIndexed { index, view ->
                if (view in ownedViews()) {
                    view.alpha = alphas[index]; view.invalidate()
                }
            }
        }
    }

    fun showPanel(adapter: DotChatAdapter, onStop: ((io.github.nanomuse.dot.chat.SessionBinding) -> Unit)? = null, onCollapse: () -> Unit) {
        checkMainThread()
        if (!isConnected || locked || !enabled || hostViewport() == null) {
            adapter.binding.value?.let(adapter::cancel)
            return
        }
        collapsePanel()
        panelHostWindowId = (service as? MinisAccessibilityService)?.dotScreen?.hostWindowId()
        if (panelHostWindowId == null) { adapter.binding.value?.let(adapter::cancel); return }
        panelAdapter = adapter
        panelCollapse = onCollapse
        panelStop = onStop
        sync()
    }

    fun collapsePanel() {
        checkMainThread()
        if (panelAdapter == null && panelView == null) return
        panelAdapter = null
        sync()
    }

    private fun hostViewport(): RectPx? = (service as? MinisAccessibilityService)?.dotScreen?.hostViewport(panelHostWindowId)

    /** Two traversals must agree before adopting host/IME bounds, including split-screen hosts. */
    fun refreshPanelGeometry() {
        if (voicePermissionWindow) return
        val view = panelView ?: return
        if (geometryPending) return
        geometryPending = true
        view.postOnAnimation {
            val first = hostViewport()
            view.postOnAnimation {
                geometryPending = false
                if (voicePermissionWindow) return@postOnAnimation
                if (panelView === view && first != null && first == hostViewport()) {
                    panelParams?.let { p ->
                        val frame=foregroundFrame(first,first.height*.55f,service.resources.displayMetrics.density).bounds
                        p.x = frame.left.roundToInt(); p.y = frame.top.roundToInt()
                        p.width = frame.width.roundToInt(); p.height = frame.height.roundToInt()
                        if (p.width > 0 && p.height > 0) windows.updateViewLayout(view,p)
                    }
                } else if (panelView === view) {
                    if (first == null && hostViewport() == null) collapsePanel()
                    else refreshPanelGeometry()
                }
            }
        }
    }

    @Suppress("DEPRECATION") // Required native keyboard/back support on API26–32.
    private fun hideImeOrCollapse(view: View) {
        val nativeWindows = service.windows
        val keyboard = try { nativeWindows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            finally { nativeWindows.forEach { it.recycle() } }
        if (keyboard) service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
        else collapsePanel()
    }

    @Suppress("DEPRECATION") // ADJUST_RESIZE remains the legacy keyboard window contract.
    private fun addPanel() {
        val adapter = panelAdapter ?: return
        val owner = DotOverlayOwner()
        val view = object : FrameLayout(service) {
            private var backDown = false
            private fun back(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
                if (event.action == KeyEvent.ACTION_DOWN) { backDown = true; return true }
                if (event.action == KeyEvent.ACTION_UP && backDown) {
                    backDown = false
                    hideImeOrCollapse(this)
                    return true
                }
                return false
            }
            override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean = back(event) || super.dispatchKeyEventPreIme(event)
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = back(event) || super.dispatchKeyEvent(event)
        }
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        val composition = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { MinisTheme { io.github.nanomuse.dot.ui.DotMotionHost { DotChatPanel(adapter, ::collapsePanel, panelStop ?: { binding -> adapter.cancel(binding); adapter.bind(binding.sessionId); Unit }) } } }
        }
        view.addView(composition, FrameLayout.LayoutParams(-1,-1))
        val host = hostViewport()
        val frame=host?.let { foregroundFrame(it,it.height*.55f,service.resources.displayMetrics.density).bounds }
        val p = WindowManager.LayoutParams(frame?.width?.roundToInt() ?: 1, frame?.height?.roundToInt() ?: 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; title = "Nanodot Dot conversation"
            x = frame?.left?.roundToInt() ?: 0; y = frame?.top?.roundToInt() ?: 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try { windows.addView(view,p); owner.start() }
        catch (failure: RuntimeException) {
            owner.destroy(); composition.disposeComposition()
            panelAdapter = null
            panelCollapse?.invoke(); panelCollapse = null; panelStop = null
            throw failure
        }
        panelView = view; panelOwner = owner; panelComposition = composition; panelParams = p
        view.setOnApplyWindowInsetsListener { _, insets -> refreshPanelGeometry(); insets }
        if (Build.VERSION.SDK_INT >= 33) view.post {
            if (panelView === view) {
                val dispatcher = view.findOnBackInvokedDispatcher()
                val callback = android.window.OnBackInvokedCallback { hideImeOrCollapse(view) }
                dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
                removeBackCallback = { dispatcher?.unregisterOnBackInvokedCallback(callback) }
            }
        }
        refreshPanelGeometry()
    }

    private fun removePanel() {
        val onCollapse = panelCollapse
        panelCollapse = null; panelAdapter = null; panelStop = null
        removeBackCallback?.invoke(); removeBackCallback = null
        panelView?.let { windows.removeViewImmediate(it) }
        panelComposition?.disposeComposition(); panelOwner?.destroy()
        panelView = null; panelComposition = null; panelOwner = null; panelParams = null; panelHostWindowId = null
        geometryPending = false
        onCollapse?.invoke()
    }

    private fun sync() {
        val wanted = OverlayPolicy.windows(isConnected, locked, enabled)
        registry.sync(if (wanted.isNotEmpty() && panelAdapter != null) wanted + OverlayKind.Panel else wanted)
    }

    private fun addWindow(kind: OverlayKind) {
        if (kind == OverlayKind.Panel) { addPanel(); return }
        check(kind == OverlayKind.Orb)
        val owner = DotOverlayOwner()
        val view = object : FrameLayout(service) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                handleMotion(event)
                return true
            }
        }
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        val composition = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                io.github.nanomuse.dot.ui.DotMotionHost {
                    DotOrb(::onIntent, careActive.value, visualActive.value, pressed.value, dragging.value) {
                        motionEnabled = it
                        if (!it) clearTrail()
                    }
                }
            }
        }
        view.addView(composition, FrameLayout.LayoutParams(size, size))
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            // Legacy native policy supplies the content/cutout-safe parent; x/y are parent offsets.
            (if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else 0) or
            if (kind.touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; title = "Nanodot Dot orb"
            if (Build.VERSION.SDK_INT < 30) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT in 28..29) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        val bounds = availableBounds()
        if (bounds != null) restorePosition(p, bounds)
        else view.alpha = 0f // Never show at a guessed origin before the native parent is known.
        try {
            windows.addView(view, p)
            owner.start()
        } catch (failure: RuntimeException) {
            owner.destroy()
            composition.disposeComposition()
            throw failure
        }
        orbView = view; orbOwner = owner; orbComposition = composition; params = p
        if (Build.VERSION.SDK_INT < 30) waitForLegacyLayout(view, p)
    }

    private fun restorePosition(p: WindowManager.LayoutParams, bounds: DotWindowGeometry) {
        val saved = preferences.orbPosition()
        val position = bounds.position(size, saved.first, saved.second)
        p.x = position.first; p.y = position.second
    }

    private fun waitForLegacyLayout(view: View, p: WindowManager.LayoutParams) {
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (orbView !== view || !view.isAttachedToWindow) return true
                val geometry = legacyBounds(view) ?: return false
                if (geometry != legacyGeometry) {
                    legacyGeometry = geometry
                    restorePosition(p, geometry)
                    windows.updateViewLayout(view, p)
                    return false
                }
                val actual = IntArray(2)
                view.getLocationOnScreen(actual)
                val expected = geometry.screenPosition(p.x, p.y)
                if (actual[0] != expected.first || actual[1] != expected.second) return false
                view.viewTreeObserver.removeOnPreDrawListener(this)
                firstLayout = null
                view.alpha = 1f
                return true
            }
        }
        firstLayout = listener
        view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun removeWindow(kind: OverlayKind) {
        if (kind == OverlayKind.Panel) { removePanel(); return }
        cancelGesture()
        orbView?.let { view ->
            firstLayout?.let { view.viewTreeObserver.removeOnPreDrawListener(it) }
            windows.removeViewImmediate(view)
        }
        firstLayout = null; legacyGeometry = null
        orbComposition?.disposeComposition()
        orbOwner?.destroy()
        orbView = null; orbOwner = null; orbComposition = null; params = null
    }

    private fun handleMotion(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < 30 && (legacyGeometry == null || orbView?.alpha == 0f)) return
        val point = PointPx(event.rawX, event.rawY)
        val id = event.getPointerId(event.actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPoint = point; downPosition = params?.let { it.x to it.y }; blockedTouch = false
                clearTrail(); pressed.value = true; dragging.value = false
                dragStarted = false; downAtMs = event.eventTime
                accept(GestureEvent.Down(point, event.eventTime, id))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                restoreDownPosition(); clearTrail(); pressed.value = false; dragging.value = false; blockedTouch = true
                accept(GestureEvent.Down(point, event.eventTime, id))
            }
            MotionEvent.ACTION_MOVE -> {
                if (!blockedTouch) {
                    val start = downPoint
                    val position = downPosition
                    if (start != null && !dragStarted &&
                        event.eventTime - downAtMs < ViewConfiguration.getLongPressTimeout() &&
                        kotlin.math.hypot(point.x - start.x, point.y - start.y) > config.scaledTouchSlop) {
                        dragStarted = true
                        dragging.value = true
                    }
                    if (dragStarted && start != null && position != null) {
                        val bounds = availableBounds() ?: return
                        params?.let { p ->
                            val target = bounds.clamp(size, (position.first + point.x - start.x).roundToInt(),
                                (position.second + point.y - start.y).roundToInt())
                            p.x = target.first; p.y = target.second
                            orbView?.let { windows.updateViewLayout(it, p) }
                        }
                    }
                    if (dragStarted) recordTrail(point, event.eventTime)
                    accept(GestureEvent.Move(point, event.eventTime, event.getPointerId(0)))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pressed.value = false; dragging.value = false
                accept(GestureEvent.Up(point, event.eventTime, id))
                if (event.actionMasked == MotionEvent.ACTION_UP) { downPoint = null; downPosition = null }
            }
            MotionEvent.ACTION_CANCEL -> { restoreDownPosition(); cancelGesture() }
        }
    }

    private fun accept(event: GestureEvent) {
        val result = reducer.accept(event)
        handler.removeCallbacks(tick)
        result.deadlineMs?.let { handler.postAtTime(tick, it) }
        result.intents.forEach(::onIntent)
    }

    private fun cancelGesture() {
        clearTrail(); pressed.value = false; dragging.value = false
        accept(GestureEvent.Cancel)
        downPoint = null; downPosition = null; blockedTouch = false; dragStarted = false
    }

    private fun recordTrail(point: PointPx, at: Long) {
        if (!motionEnabled || !visualActive.value || !isConnected || locked || !enabled) return
        trail.add(point.x, point.y, at, motionEnabled)
        if (trail.visible(at).isEmpty()) return
        if (trailView == null) {
            val view = DotTrailView(service)
            val p = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT; title = "Nanodot Dot crystal trail"
            }
            // Owned here, included in capture exclusion, and removed after the final sample expires.
            try { windows.addView(view, p); trailView = view }
            catch (_: RuntimeException) { trail.clear(); return }
        }
        handler.removeCallbacks(trailTick)
        trailTick.run()
    }

    private fun clearTrail() {
        trail.clear()
        removeTrailWindow()
    }

    private fun removeTrailWindow() {
        handler.removeCallbacks(trailTick)
        trailView?.let { windows.removeViewImmediate(it) }
        trailView = null
    }

    private fun restoreDownPosition() {
        downPosition?.let { position -> params?.let { p ->
            p.x = position.first; p.y = position.second
            orbView?.let { windows.updateViewLayout(it, p) }
        } }
    }

    private fun availableBounds(): DotWindowGeometry? {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val frame = metrics.bounds
            return DotWindowGeometry(frame.left + insets.left, frame.top + insets.top,
                frame.right - insets.right, frame.bottom - insets.bottom, false)
        }
        // Re-read after initial placement: native decor can change without a display rotation.
        return if (legacyGeometry == null) null else orbView?.let(::legacyBounds)
    }

    private fun legacyBounds(view: View): DotWindowGeometry? {
        if (!view.isAttachedToWindow) return null
        val frame = Rect()
        // Attached display frame is the full native parent, not the clipped 48dp visible frame.
        view.getWindowVisibleDisplayFrame(frame)
        if (frame.isEmpty) return null
        return DotWindowGeometry(frame.left, frame.top, frame.right, frame.bottom, true)
    }

    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
}
