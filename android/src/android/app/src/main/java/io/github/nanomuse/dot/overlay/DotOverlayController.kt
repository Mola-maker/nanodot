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
import kotlin.math.roundToInt

/** Main-thread owner of window, gesture clock and service-scoped observers. No capture/voice APIs. */
class DotOverlayController(
    private val service: AccessibilityService,
    private val delegate: ((DotIntent) -> Unit)? = null,
) {
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
    internal val windowCount get() = registry.size
    private var orbComposition: ComposeView? = null
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
        if (locked) cancelGesture()
        sync()
    }

    fun setEnabled(enabled: Boolean) {
        checkMainThread()
        this.enabled = enabled
        if (!enabled) cancelGesture()
        sync()
    }

    fun refreshLockState() { if (isConnected) setLocked(deviceLocked()) }

    private fun deviceLocked() = keyguard.isKeyguardLocked || !power.isInteractive

    /** Recreate with new display bounds, clearing all pending touch/tap work. */
    fun onDisplayChanged() {
        checkMainThread()
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
        val node = orbView?.createAccessibilityNodeInfo() ?: return false
        return try { node.windowId >= 0 && node.windowId == window.id } finally { node.recycle() }
    }

    /** Hide alpha without removing/recreating owners or changing the user's preference.
     * OnDraw plus a following frame ensures traversal submits the hidden window before capture.
     */
    suspend fun <T> withHiddenForCapture(block: suspend () -> T): T {
        checkMainThread()
        val view = orbView
        val previousAlpha = view?.alpha
        try {
            if (view != null && view.isAttachedToWindow) {
                view.alpha = 0f
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
            if (orbView === view && view?.isAttachedToWindow == true && previousAlpha != null) {
                view.alpha = previousAlpha; view.invalidate()
            }
        }
    }

    private fun sync() {
        registry.sync(OverlayPolicy.windows(isConnected, locked, enabled))
    }

    private fun addWindow(kind: OverlayKind) {
        check(kind == OverlayKind.Orb) // Auxiliary windows are created by later coordinator tasks.
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
            setContent { DotOrb(::onIntent) }
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
                dragStarted = false; downAtMs = event.eventTime
                accept(GestureEvent.Down(point, event.eventTime, id))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                restoreDownPosition(); blockedTouch = true
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
                    accept(GestureEvent.Move(point, event.eventTime, event.getPointerId(0)))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
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
        accept(GestureEvent.Cancel)
        downPoint = null; downPosition = null; blockedTouch = false; dragStarted = false
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
