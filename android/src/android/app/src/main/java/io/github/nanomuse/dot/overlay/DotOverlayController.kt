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
import android.view.VelocityTracker
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import com.openminis.app.BuildConfig
import com.openminis.app.ui.settings.PREF_APPEARANCE
import io.github.nanomuse.dot.DotDefaults
import io.github.nanomuse.dot.DotPreferences
import io.github.nanomuse.dot.gesture.*
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.chat.DotChatPanel
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.selection.*
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
    private val orbMenu = DotOrbMenuWindow(service, ::onIntent)
    private data class OrbMenuPlacement(val hostId: Int, val safe: RectPx, val orb: RectPx, val protectedBounds: List<RectPx>)
    private var orbMenuPlacement: OrbMenuPlacement? = null
    private var captureDepth = 0
    internal val orbMenuView get() = orbMenu.view
    internal val orbMenuBounds get() = orbMenu.bounds
    private val idleVoiceUi = kotlinx.coroutines.flow.MutableStateFlow(io.github.nanomuse.dot.voice.DotVoiceController.Ui())
    private var selectionOutline: SelectionOutlineView? = null
    private var selectionOrigin: SelectionOrigin? = null
    private fun ownedViews() = listOfNotNull(orbView, panelView, trailView, selectionOutline, captureScanner.view, orbMenu.view) + careWindows.toList()

    fun clearSelectionOutline() {
        checkMainThread()
        selectionOutline?.let { windows.removeViewImmediate(it) }
        selectionOutline=null
    }

    fun showSelectionOutline(selection: RegionSelection) {
        checkMainThread()
        clearSelectionOutline()
        if (!isConnected || locked || !enabled) return
        val view=SelectionOutlineView(service,selection)
        val layout=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; title="Nanodot selected region"
            if (Build.VERSION.SDK_INT >= 30) {
                // Region points are absolute screen coordinates, like the Care focus contour.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windows.addView(view,layout)
        selectionOutline=view
    }

    /** Only presentation is dismissed. Chat/Care/voice retain their existing admission. */
    fun dismissOrbMenu(): Boolean {
        checkMainThread()
        orbMenuPlacement = null
        return orbMenu.dismiss()
    }

    private fun viewBounds(view: View): RectPx? {
        if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) return null
        val position = IntArray(2); view.getLocationOnScreen(position)
        return RectPx(position[0].toFloat(), position[1].toFloat(),
            (position[0] + view.width).toFloat(), (position[1] + view.height).toFloat())
    }

    private fun currentOrbMenuPlacement(): OrbMenuPlacement? {
        val repository = (service as? MinisAccessibilityService)?.dotScreen ?: return null
        val hostId = repository.hostWindowId() ?: return null
        val host = repository.hostViewport(hostId) ?: return null
        val display = availableBounds() ?: return null
        val safe = RectPx(maxOf(host.left, display.left.toFloat()), maxOf(host.top, display.top.toFloat()),
            minOf(host.right, display.right.toFloat()), minOf(host.bottom, display.bottom.toFloat()))
        if (!safe.isUsable()) return null
        val orb = orbView?.let(::viewBounds) ?: return null
        val protectedBounds = (listOfNotNull(panelView) + careWindows).filter {
            val flags = (it.layoutParams as? WindowManager.LayoutParams)?.flags ?: return@filter false
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        }.mapNotNull(::viewBounds) + listOfNotNull(foregroundTarget?.takeIf { it.isUsable() })
        return OrbMenuPlacement(hostId, safe, orb, protectedBounds)
    }

    private fun showOrbMenu() {
        if (voicePermissionWindow || captureDepth > 0 || !visualActive.value ||
            foregroundState?.value?.operation == io.github.nanomuse.dot.DotForegroundOperation.Capturing) return
        cancelSnap()
        val placement = currentOrbMenuPlacement()
        val frame = placement?.let { orbMenuFrame(it.safe, it.orb, service.resources.displayMetrics.density, it.protectedBounds) }
        if (frame == null) {
            dismissOrbMenu()
            Toast.makeText(service, R.string.nm_dot_action_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        orbMenuPlacement = placement
        val voice = (service as? MinisAccessibilityService)?.dotInteraction?.voiceUi ?: idleVoiceUi
        if (!orbMenu.show(frame, voice)) orbMenuPlacement = null
    }

    private fun refreshOrbMenuGeometry() {
        if (orbMenu.view != null && orbMenuPlacement != currentOrbMenuPlacement()) dismissOrbMenu()
    }
    private val captureScanner = DotCaptureScanWindow(service)
    fun beginCaptureScan(generation: Long) {
        checkMainThread()
        dismissOrbMenu()
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
    private val motionScope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    private var snapJob: Job? = null
    private var snapBounds: DotWindowGeometry? = null
    private var velocityTracker: VelocityTracker? = null
    private var releaseVelocityX = 0f
    private val trail = CrystalTrail()
    private var trailView: DotTrailView? = null
    private val trailTick = object : Runnable {
        override fun run() {
            val points = trail.visible(SystemClock.uptimeMillis())
            if (!trail.hasPending(SystemClock.uptimeMillis())) { removeTrailWindow(); return }
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
    internal val windowCount get() = registry.size + (if (trailView != null) 1 else 0) + (if (orbMenu.view != null) 1 else 0)
    private var orbComposition: ComposeView? = null
    internal var panelView: View? = null
        private set
    private var panelComposition: ComposeView? = null
    private var panelOwner: DotOverlayOwner? = null
    private var panelAdapter: DotChatAdapter? = null
    internal var panelTranscriptState: androidx.compose.foundation.lazy.LazyListState? = null
        private set
    private var panelCollapse: (() -> Unit)? = null
    private var panelForeground: kotlinx.coroutines.flow.StateFlow<io.github.nanomuse.dot.DotForegroundState>? = null
    private var panelStop: ((io.github.nanomuse.dot.chat.SessionBinding) -> Unit)? = null
    internal var panelHostWindowId: Int? = null
        private set
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelMenuDismiss: (() -> Boolean)? = null
    private var panelRequestedHeight = 0
    private var geometryPending = false
    private var foregroundTarget: RectPx? = null
    private val panelPlacement = androidx.compose.runtime.mutableStateOf(ForegroundPlacement.Bottom)
    private var panelExpanded = false
    private var panelMotion: Job? = null
    private var panelAdjustment: Pair<Float, Float>? = null
    private var panelGeometry: Pair<RectPx, ForegroundFrame>? = null
    /** Current validated Care bounds only; relayout never changes conversation admission. */
    fun setForegroundTarget(target: RectPx?) {
        checkMainThread()
        if (foregroundTarget == target) return
        dismissOrbMenu()
        foregroundTarget = target
        panelExpanded = false; panelAdjustment = null; panelGeometry = null
        panelMotion?.cancel(); panelMotion = null
        refreshPanelGeometry()
    }
    private fun expandPanel() {
        panelExpanded = true; panelAdjustment = null; panelGeometry = null
        refreshPanelGeometry()
    }
    private fun adjustPanel() {
        val host = hostViewport() ?: return
        val p = panelParams ?: return
        movePanel(0f, if (p.y < host.top + host.height / 2f) host.height else -host.height)
    }
    private fun movePanel(dx: Float, dy: Float) {
        panelMotion?.cancel(); panelMotion = null
        val view = panelView ?: return
        val p = panelParams ?: return
        val host = hostViewport() ?: return
        if (!dx.isFinite() || !dy.isFinite() || !host.isUsable()) return
        val proposedX = (p.x + dx - host.left) / (host.width - p.width).coerceAtLeast(1f)
        val proposedY = (p.y + dy - host.top) / (host.height - p.height).coerceAtLeast(1f)
        val base = foregroundFrame(host, p.height.toFloat(), service.resources.displayMetrics.density, foregroundTarget)
        val fitted = adjustedForegroundFrame(host, base, foregroundTarget, proposedX to proposedY)
        p.x = fitted.bounds.left.roundToInt(); p.y = fitted.bounds.top.roundToInt()
        panelAdjustment = (if (host.width > p.width) (p.x - host.left) / (host.width - p.width) else 0f) to
            (if (host.height > p.height) (p.y - host.top) / (host.height - p.height) else 0f)
        panelGeometry = null
        windows.updateViewLayout(view, p)
    }
    private var voicePermissionWindow = false
    /** Keep the binding/window owners while allowing the real permission Activity to be visible. */
    fun setVoicePermissionWindow(visible: Boolean) {
        if (visible) dismissOrbMenu()
        voicePermissionWindow = visible
        if (visible) { panelMotion?.cancel(); panelMotion = null; panelGeometry = null }
        ownedViews().forEach { it.visibility = if (visible) View.INVISIBLE else View.VISIBLE }
        if (!visible) refreshPanelGeometry()
    }
    private var removeBackCallback: (() -> Unit)? = null
    private var dragStarted = false
    private var params: WindowManager.LayoutParams? = null
    private var legacyGeometry: DotWindowGeometry? = null
    private var firstLayout: ViewTreeObserver.OnPreDrawListener? = null
    private var downPoint: PointPx? = null
    private var downPosition: Pair<Int, Int>? = null
    private var blockedTouch = false
    private val registry = DotWindowRegistry(::addWindow, ::removeWindow)
    private var attachAttemptSequence = 0

    @Suppress("DEPRECATION") // Read the cached manager's target display without another WM lookup.
    private fun traceAttachment(
        phase: String,
        attempt: Int = 0,
        kind: OverlayKind? = null,
        layout: WindowManager.LayoutParams? = null,
        view: View? = null,
        failure: RuntimeException? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.d("nanoMuse.DotAttach", "phase=$phase uptimeMs=${SystemClock.uptimeMillis()} " +
            "pid=${android.os.Process.myPid()} tid=${android.os.Process.myTid()} " +
            "service=${System.identityHashCode(service)} " +
            "connection=${(service as? MinisAccessibilityService)?.dotConnectionSequence ?: 0} " +
            "controller=${System.identityHashCode(this)} wm=${System.identityHashCode(windows)} " +
            "connected=$isConnected registryCount=${registry.size} display=${windows.defaultDisplay.displayId} " +
            "attempt=$attempt kind=$kind type=${layout?.type} paramsPresent=${layout != null} " +
            "tokenNull=${layout?.token == null} tokenIdentity=${layout?.token?.let { System.identityHashCode(it) } ?: 0} " +
            "viewAttached=${view?.isAttachedToWindow == true} failure=${failure?.javaClass?.simpleName}")
    }

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
        traceAttachment("controller.connect.begin")
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
        traceAttachment("controller.connect.end")
    }

    fun disconnect() {
        checkMainThread()
        traceAttachment("controller.disconnect.begin")
        setForegroundTarget(null)
        cancelCaptureScan()
        cancelGesture()
        if (isConnected) {
            isConnected = false
            appearance.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            service.unregisterReceiver(screenReceiver)
            displays.unregisterDisplayListener(displayListener)
        }
        sync()
        traceAttachment("controller.disconnect.end")
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

    /** Refresh native bounds and orb while retaining the mounted conversation owner. */
    fun onDisplayChanged() {
        checkMainThread()
        cancelCaptureScan()
        (service as? MinisAccessibilityService)?.dotInteraction?.onHostGeometryChanged()
        cancelGesture()
        panelMotion?.cancel(); panelMotion = null
        panelGeometry = null
        registry.sync(if (panelView != null) setOf(OverlayKind.Panel) else emptySet())
        sync()
        refreshPanelGeometry()
    }

    fun onIntent(intent: DotIntent) {
        checkMainThread()
        if (!isConnected || locked || !enabled) return
        if (intent == DotIntent.LongPress) { showOrbMenu(); return }
        dismissOrbMenu()
        if (intent is DotIntent.Drag) {
            val selection=SelectionGeometry.classify(intent.points,service.resources.displayMetrics.density)
            val origin=selectionOrigin
            val interaction=(service as? MinisAccessibilityService)?.dotInteraction
            if (selection != null && origin != null && interaction != null) {
                restoreDownPosition()
                clearTrail()
                interaction.selectRegion(selection,origin)
                return
            }
            // Open/degenerate paths remain ordinary movement, using the measured release velocity.
            val point = intent.points.lastOrNull() ?: return
            val bounds = availableBounds() ?: return
            val p = params ?: return
            snapToEdge(bounds, p, bounds.edgeX(size, point.x))
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
    suspend fun <T> withHiddenForCapture(hide: Boolean = true, block: suspend () -> T): T {
        checkMainThread()
        dismissOrbMenu()
        captureDepth++
        try { return hideOwnedForCapture(hide, block) }
        finally { captureDepth-- }
    }

    private suspend fun <T> hideOwnedForCapture(hide: Boolean, block: suspend () -> T): T {
        // Window screenshots exclude accessibility overlays at the platform boundary;
        // nodes are read only from application windows. Their controls need no alpha hiding.
        if (!hide) return block()
        cancelSnap()
        panelMotion?.cancel(); panelMotion = null; panelGeometry = null
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
            refreshPanelGeometry()
            owned.forEachIndexed { index, view ->
                if (view in ownedViews()) {
                    view.alpha = alphas[index]; view.invalidate()
                }
            }
        }
    }

    /** Only the same owner and host can retain a native focused conversation window. */
    internal fun retainedPanel(adapter: DotChatAdapter,
        foreground: kotlinx.coroutines.flow.StateFlow<io.github.nanomuse.dot.DotForegroundState>,
        hostWindowId: Int): View? {
        checkMainThread()
        return panelView?.takeIf {
            isConnected && enabled && !locked && it.isAttachedToWindow && it.hasWindowFocus() &&
                panelAdapter === adapter && panelForeground === foreground &&
                panelHostWindowId == hostWindowId &&
                (service as? MinisAccessibilityService)?.dotScreen?.hostWindowId() == hostWindowId &&
                hostViewport() != null
        }
    }

    fun showPanel(adapter: DotChatAdapter, onStop: ((io.github.nanomuse.dot.chat.SessionBinding) -> Unit)? = null,
        foreground: kotlinx.coroutines.flow.StateFlow<io.github.nanomuse.dot.DotForegroundState>? = null, onCollapse: () -> Unit) {
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
        panelForeground = foreground ?: foregroundState
        sync()
    }

    fun collapsePanel() {
        checkMainThread()
        if (panelAdapter == null && panelView == null) return
        panelAdapter = null
        sync()
    }

    private fun hostViewport(): RectPx? = (service as? MinisAccessibilityService)?.dotScreen?.hostViewport(panelHostWindowId)?.takeIf { it.isUsable() }

    /** Two traversals must agree before adopting host/IME bounds, including split-screen hosts. */
    fun refreshPanelGeometry() {
        refreshOrbMenuGeometry()
        if (panelGeometry?.first != hostViewport()) { panelMotion?.cancel(); panelMotion = null }
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
                        val density = service.resources.displayMetrics.density
                        val requested = panelRequestedHeight.takeIf { it > 0 }?.toFloat() ?: (240f * density)
                        val next = requestedForegroundFrame(first, requested, density,
                            foregroundTarget, panelExpanded, panelAdjustment,
                            (156f + 48f * (service.resources.configuration.fontScale - 1f).coerceAtLeast(0f)) * density)
                        if (panelGeometry == (first to next)) return@let
                        panelGeometry = first to next
                        panelPlacement.value = next.placement
                        val frame = next.bounds
                        panelMotion?.cancel(); panelMotion = null
                        val fromX = p.x; val fromY = p.y
                        val animate = foregroundTarget == null && motionEnabled && visualActive.value && (p.x != frame.left.roundToInt() || p.y != frame.top.roundToInt()) && p.width == frame.width.roundToInt() && p.height == frame.height.roundToInt()
                        p.width = frame.width.roundToInt(); p.height = frame.height.roundToInt()
                        fun apply(progress: Float) {
                            if (panelView !== view || panelParams !== p || hostViewport() != first) return
                            p.x = (fromX + (frame.left - fromX) * progress).roundToInt().coerceIn(first.left.roundToInt(), (first.right - p.width).roundToInt().coerceAtLeast(first.left.roundToInt()))
                            p.y = (fromY + (frame.top - fromY) * progress).roundToInt().coerceIn(first.top.roundToInt(), (first.bottom - p.height).roundToInt().coerceAtLeast(first.top.roundToInt()))
                            if (p.width > 0 && p.height > 0) windows.updateViewLayout(view, p)
                        }
                        if (animate) panelMotion = motionScope.launch { DotCrystalMotion.snap(0f, 1f, 0f) { apply(it.coerceIn(0f, 1f)) } }
                        else apply(1f)
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
        if (dismissOrbMenu()) return
        if (panelMenuDismiss?.invoke() == true) return
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
            setContent { MinisTheme { io.github.nanomuse.dot.ui.DotMotionHost {
                val evidence by (panelForeground ?: kotlinx.coroutines.flow.MutableStateFlow(io.github.nanomuse.dot.DotForegroundState())).collectAsState()
                DotChatPanel(adapter, ::collapsePanel,
                    panelStop ?: { binding -> adapter.cancel(binding); adapter.bind(binding.sessionId); Unit },
                    evidence, panelPlacement.value, ::expandPanel, ::adjustPanel, ::movePanel,
                    onTranscriptState = { panelTranscriptState = it },
                    onMenuDismissHandler = { panelMenuDismiss = it },
                    onClearRegion = ::clearSelectionOutline,
                    onReselectRegion = { (service as? MinisAccessibilityService)?.dotInteraction?.reselectRegion() },
                    onContentHeightChanged = { height ->
                        if (height > 0 && panelRequestedHeight != height) {
                            panelRequestedHeight = height; panelGeometry = null; refreshPanelGeometry()
                        }
                    })
            } } }
        }
        view.addView(composition, FrameLayout.LayoutParams(-1,-1))
        val host = hostViewport()
        val initial = host?.let { requestedForegroundFrame(it,240f * service.resources.displayMetrics.density,service.resources.displayMetrics.density, foregroundTarget, panelExpanded,
            compactHeight = (156f + 48f * (service.resources.configuration.fontScale - 1f).coerceAtLeast(0f)) * service.resources.displayMetrics.density) }
        panelPlacement.value = initial?.placement ?: ForegroundPlacement.Bottom
        val frame = initial?.bounds
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
            panelCollapse?.invoke(); panelCollapse = null; panelStop = null; panelForeground = null
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
        panelMotion?.cancel(); panelMotion = null; panelExpanded = false; panelTranscriptState = null; panelAdjustment = null; panelGeometry = null
        val onCollapse = panelCollapse
        panelCollapse = null; panelAdapter = null; panelStop = null; panelForeground = null
        removeBackCallback?.invoke(); removeBackCallback = null
        panelView?.let { windows.removeViewImmediate(it) }
        panelComposition?.disposeComposition(); panelOwner?.destroy()
        panelView = null; panelComposition = null; panelOwner = null; panelParams = null; panelHostWindowId = null
        geometryPending = false; panelRequestedHeight = 0; panelMenuDismiss = null
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
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                // An inward drag beginning on the edge orb belongs to the orb, not system Back.
                // Exclude only this measured 48dp touch window; host gestures outside it remain.
                if (Build.VERSION.SDK_INT >= 29) {
                    systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, w, h))
                }
            }
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
                        if (!it) { cancelSnap(); clearTrail(); panelMotion?.cancel(); panelMotion = null; panelGeometry = null; refreshPanelGeometry() }
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
        val attempt = if (BuildConfig.DEBUG) ++attachAttemptSequence else 0
        traceAttachment("add.begin", attempt, kind, p, view)
        try {
            windows.addView(view, p)
            // addView applies the framework default token to p before returning or throwing.
            traceAttachment("add.returned", attempt, kind, p, view)
            owner.start()
            traceAttachment("add.success", attempt, kind, p, view)
        } catch (failure: RuntimeException) {
            traceAttachment("add.failure", attempt, kind, p, view, failure)
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
                dismissOrbMenu()
                clearSelectionOutline()
                val screen=(service as? MinisAccessibilityService)?.dotScreen
                selectionOrigin=screen?.currentSceneKey()?.let { scene ->
                    screen.hostViewport()?.let { SelectionOrigin(scene,it) }
                }
                cancelSnap()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                trackVelocity(event)
                downPoint = point; downPosition = params?.let { it.x to it.y }; blockedTouch = false
                clearTrail(); pressed.value = true; dragging.value = false
                dragStarted = false
                accept(GestureEvent.Down(point, event.eventTime, id))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                velocityTracker?.recycle(); velocityTracker = null
                restoreDownPosition(); clearTrail(); pressed.value = false; dragging.value = false; blockedTouch = true
                accept(GestureEvent.Down(point, event.eventTime, id))
            }
            MotionEvent.ACTION_MOVE -> {
                if (!blockedTouch) {
                    trackVelocity(event)
                    val start = downPoint
                    val position = downPosition
                    if (start != null && !dragStarted &&
                        !reducer.longPressCommitted &&
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
                trackVelocity(event)
                velocityTracker?.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
                releaseVelocityX = velocityTracker?.getXVelocity(id) ?: 0f
                pressed.value = false; dragging.value = false
                accept(GestureEvent.Up(point, event.eventTime, id))
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    velocityTracker?.recycle(); velocityTracker = null
                    downPoint = null; downPosition = null
                    selectionOrigin = null
                }
            }
            MotionEvent.ACTION_CANCEL -> { restoreDownPosition(); cancelGesture() }
        }
    }

    /** Window-relative MotionEvents must be converted to screen space before velocity tracking. */
    private fun trackVelocity(event: MotionEvent) {
        val tracker = velocityTracker ?: return
        val raw = MotionEvent.obtain(event)
        try {
            raw.offsetLocation(event.rawX - event.x, event.rawY - event.y)
            tracker.addMovement(raw)
        } finally { raw.recycle() }
    }

    private fun savePosition(bounds: DotWindowGeometry, p: WindowManager.LayoutParams) {
        val position = bounds.fractions(size, p.x, p.y)
        preferences.saveOrbPosition(position.first, position.second)
    }

    private fun cancelSnap() {
        snapJob?.cancel(); snapJob = null
        // Keep the normalized position in the parent used by this animation, even on rotation.
        snapBounds?.let { bounds -> params?.let { savePosition(bounds, it) } }
        snapBounds = null
    }

    private fun snapToEdge(bounds: DotWindowGeometry, p: WindowManager.LayoutParams, edge: Int) {
        cancelSnap()
        val view = orbView ?: return
        if (!motionEnabled || !visualActive.value) {
            val target = bounds.clamp(size, edge, p.y)
            p.x = target.first; p.y = target.second
            windows.updateViewLayout(view, p)
            savePosition(bounds, p)
            return
        }
        snapBounds = bounds
        snapJob = motionScope.launch {
            DotCrystalMotion.snap(p.x.toFloat(), edge.toFloat(), releaseVelocityX) { x ->
                // A native parent/viewport invalidation cannot advance a stale window.
                if (orbView !== view || params !== p || availableBounds() != bounds ||
                    !isConnected || locked || !enabled || !motionEnabled || !visualActive.value) {
                    cancelSnap()
                } else {
                    val position = bounds.clamp(size, x.roundToInt(), p.y)
                    p.x = position.first; p.y = position.second
                    windows.updateViewLayout(view, p)
                }
            }
            savePosition(bounds, p)
            snapBounds = null; snapJob = null
        }
    }

    private fun accept(event: GestureEvent) {
        val result = reducer.accept(event)
        handler.removeCallbacks(tick)
        result.deadlineMs?.let { handler.postAtTime(tick, it) }
        result.intents.forEach(::onIntent)
    }

    private fun cancelGesture() {
        dismissOrbMenu()
        clearSelectionOutline()
        selectionOrigin=null
        cancelSnap()
        velocityTracker?.recycle(); velocityTracker = null
        releaseVelocityX = 0f
        clearTrail(); pressed.value = false; dragging.value = false
        accept(GestureEvent.Cancel)
        downPoint = null; downPosition = null; blockedTouch = false; dragStarted = false
    }

    private fun recordTrail(point: PointPx, at: Long) {
        if (!motionEnabled || !visualActive.value || !isConnected || locked || !enabled) return
        trail.add(point.x, point.y, at, motionEnabled)
        if (!trail.hasPending(at)) return
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
