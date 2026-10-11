package io.github.nanomuse.dot.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/** The controller's single decorative window; capture tickets and outcomes keep their existing meaning. */
internal class DotCaptureScanWindow(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val ticket = CaptureScanTicket()
    var view: DotCaptureScanner? = null
        private set
    private var generation: Long? = null
    private var animation: ValueAnimator? = null
    private var removal: Runnable? = null
    private var cancelReady: (() -> Unit)? = null
    private var captureHidden = false
    private var captureEpoch = 0L
    private var finishPending = false
    private var hiddenReplacement: DotCaptureScanner? = null
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            if (this@DotCaptureScanWindow.view === view) retire(removeWindow = false)
        }
    }

    fun begin(generation: Long) {
        checkMainThread()
        if (view != null) return
        if (ticket.begin(generation) == null) return
        val target = DotCaptureScanner(service)
        if (captureHidden) { target.alpha = 0f; hiddenReplacement = target }
        try { windows.addView(target, captureScanLayoutParams()) }
        catch (_: RuntimeException) { ticket.cancel(); return }
        this.generation = generation
        view = target
        target.addOnAttachStateChangeListener(detach)
        requestVisual(finish = false)
    }

    fun finish(generation: Long, success: Boolean) {
        checkMainThread()
        if (this.generation != generation || !ticket.finish(generation, success)) return
        view?.outcome = success
        requestVisual(finish = true)
    }

    private fun motionEnabled() = ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private fun stopAnimation() {
        animation?.removeAllListeners()
        animation?.removeAllUpdateListeners()
        animation?.cancel()
        animation = null
    }

    private fun requestVisual(finish: Boolean) {
        cancelReady?.invoke(); cancelReady = null
        stopAnimation()
        finishPending = finish
        val current = generation ?: return
        val target = view ?: return
        val motion = motionEnabled() && target.outcome != false
        target.progress = if (motion) 0f else 1f
        target.invalidate()
        if (captureHidden) return
        cancelReady = target.afterVisibleDraw {
            cancelReady = null
            if (generation != current || view !== target || captureHidden) return@afterVisibleDraw
            // A system motion change while waiting must render the static outcome before timing its hold.
            if (motion && !motionEnabled()) { requestVisual(finish); return@afterVisibleDraw }
            if (!motion) {
                if (finish) scheduleRemoval(current, 250)
            } else {
                animation = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 720L
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        if (generation == current && view === target) {
                            target.progress = it.animatedValue as Float
                            target.invalidate()
                        }
                    }
                    if (finish) addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (this@DotCaptureScanWindow.animation === animation) this@DotCaptureScanWindow.animation = null
                            scheduleRemoval(current, 100)
                        }
                    })
                    start()
                }
            }
        }
    }

    /** Invalidate pre-capture draw callbacks; restoration requires a new visible traversal. */
    fun pauseForCapture(): () -> Unit {
        checkMainThread()
        val epoch = ++captureEpoch
        val current = generation
        captureHidden = true
        cancelReady?.invoke(); cancelReady = null
        val paused = animation
        paused?.pause()
        // A completed static scan must not expire while hidden by another owned capture.
        removal?.let(handler::removeCallbacks); removal = null
        return resume@{
            checkMainThread()
            if (captureEpoch != epoch) return@resume
            captureHidden = false
            val target = view ?: return@resume
            if (hiddenReplacement === target) { target.alpha = 1f; hiddenReplacement = null }
            if (current != generation) { requestVisual(finishPending); return@resume }
            if (paused != null && animation === paused) {
                cancelReady = target.afterVisibleDraw {
                    cancelReady = null
                    if (view === target && generation == current && animation === paused && !captureHidden) {
                        if (motionEnabled()) paused.resume() else requestVisual(finishPending)
                    }
                }
            } else requestVisual(finishPending)
        }
    }

    private fun scheduleRemoval(generation: Long, delay: Long) {
        if (this.generation != generation || captureHidden) return
        removal?.let(handler::removeCallbacks)
        removal = Runnable { cancel(generation) }.also { handler.postDelayed(it, delay) }
    }

    fun cancel(generation: Long? = null) {
        checkMainThread()
        if (generation != null && this.generation != generation) return
        retire(removeWindow = true)
    }

    private fun retire(removeWindow: Boolean) {
        generation = null; ticket.cancel()
        cancelReady?.invoke(); cancelReady = null
        removal?.let(handler::removeCallbacks); removal = null
        stopAnimation()
        val old = view
        view = null
        if (hiddenReplacement === old) hiddenReplacement = null
        old?.removeOnAttachStateChangeListener(detach)
        if (removeWindow && old != null) windows.removeViewImmediate(old)
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}

/** A display decoration covers system bars and cutouts, while receiving no input or focus. */
internal fun captureScanLayoutParams() = WindowManager.LayoutParams(
    -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.LEFT
    title = "Nanodot screen scan"
    if (Build.VERSION.SDK_INT >= 30) {
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else if (Build.VERSION.SDK_INT >= 28) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
