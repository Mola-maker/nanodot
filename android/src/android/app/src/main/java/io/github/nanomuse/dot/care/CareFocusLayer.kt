package io.github.nanomuse.dot.care

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.compose.ui.graphics.toArgb
import io.github.nanomuse.dot.DotColors
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.screen.SceneKey

/** One non-touchable visual layer. It never owns focus, requests a model, or advances a step. */
class CareFocusLayer(private val service: AccessibilityService, private val changed: (View?) -> Unit = {}) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val playback = CareInstructionPlayback()
    private var animation: ValueAnimator? = null
    private var observingMotion = false
    private var outlineStarted = 0L
    private var outlineMotion = true
    private var instructionText: String? = null
    private var instructionLayout: StaticLayout? = null
    private var graphemes = intArrayOf()
    private var cueBounds: RectPx? = null
    private var timeline: CareInstructionTimeline? = null
    private var padding = 0f
    var bounds: RectPx? = null
        private set
    var view: View? = null
        private set
    private val expire = Runnable { playback.retire(); view?.invalidate() }
    private val motionObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!motionEnabled()) {
                outlineMotion = false
                if (playback.startedAt != null && timeline?.motion == true) {
                    playback.identity?.let { playback.replay(it, SystemClock.uptimeMillis()) }
                    timeline = CareInstructionTimeline(graphemes.size, false)
                }
                schedule()
            }
        }
    }

    fun show(bounds: RectPx, key: String = "", scene: SceneKey? = null) {
        if (!bounds.cueUsable()) { clear(); return }
        val identity = CareInstructionIdentity(key, scene)
        if (playback.identity == identity && view != null) {
            this.bounds = bounds; view?.invalidate(); return
        }
        clear()
        this.bounds = bounds; playback.bind(identity)
        outlineStarted = SystemClock.uptimeMillis(); outlineMotion = motionEnabled()
        val target = object : View(service) {
            override fun onDraw(canvas: Canvas) { drawVisuals(canvas, this) }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; title = "Nanodot Care focus"
            if (Build.VERSION.SDK_INT >= 30) {
                // Targets use screen coordinates; system fitting would clip their top edge.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windows.addView(target, params); view = target; changed(target)
        service.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver)
        observingMotion = true
        schedule()
    }

    /** Called with actual on-screen card bounds, after layout and after any IME/host change. */
    fun setInstruction(text: String, card: RectPx, host: RectPx, key: String, scene: SceneKey?) {
        val identity = CareInstructionIdentity(key, scene)
        if (playback.identity != identity || view == null) return
        val target = bounds ?: return
        val metrics = service.resources.displayMetrics
        val rtl = service.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        padding = 12f * metrics.density
        val width = minOf(280f * metrics.density, host.width - 24f * metrics.density).toInt()
        if (text.isBlank() || width <= padding * 2) { suppressInstruction(); return }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18f * metrics.scaledDensity
            color = if (dark()) DotColors.DarkText.toArgb() else DotColors.LightText.toArgb()
        }
        val layout = careInstructionLayout(text, paint, (width - padding * 2).toInt(), rtl)
        val measured = careInstructionBounds(target, card, host, width.toFloat(), layout.height + padding * 2,
            8f * metrics.density, rtl)
        if (measured == null) { suppressInstruction(); return }
        cueBounds = measured; instructionLayout = layout
        if (instructionText != text) {
            instructionText = text
            graphemes = careGraphemeEnds(text, service.resources.configuration.locales[0])
        }
        if (playback.admit(identity, SystemClock.uptimeMillis())) {
            timeline = CareInstructionTimeline(graphemes.size, motionEnabled()); schedule()
        }
        view?.invalidate()
    }

    /** Replays decoration only; no speech provider, capture, model request or controller event. */
    fun replayInstruction(key: String, scene: SceneKey?) {
        if (cueBounds == null || instructionLayout == null) return
        if (playback.replay(CareInstructionIdentity(key, scene), SystemClock.uptimeMillis())) {
            outlineStarted = SystemClock.uptimeMillis(); outlineMotion = motionEnabled()
            timeline = CareInstructionTimeline(graphemes.size, motionEnabled()); schedule()
        }
    }

    private fun suppressInstruction() {
        cueBounds = null; instructionLayout = null
        if (playback.startedAt != null) { playback.retire(); schedule() }
        view?.invalidate()
    }
    private fun motionEnabled() = Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    private fun dark() = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun schedule() {
        animation?.cancel(); animation = null; handler.removeCallbacks(expire)
        val now = SystemClock.uptimeMillis()
        val outlineLeft = if (outlineMotion) (600L - (now - outlineStarted)).coerceAtLeast(0L) else 0L
        val cueLeft = playback.startedAt?.let { (timeline?.duration ?: 0L) - (now - it) }?.coerceAtLeast(0L) ?: 0L
        if (cueLeft > 0) handler.postDelayed(expire, cueLeft)
        if (motionEnabled() && maxOf(outlineLeft, cueLeft) > 0) {
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = maxOf(outlineLeft, cueLeft); interpolator = LinearInterpolator()
                addUpdateListener { view?.invalidate() }; start()
            }
        }
        view?.invalidate()
    }

    private fun drawVisuals(canvas: Canvas, targetView: View) {
        val target = bounds ?: return
        val now = SystemClock.uptimeMillis()
        val density = service.resources.displayMetrics.density
        val location = IntArray(2); targetView.getLocationOnScreen(location)
        canvas.save(); canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f * density
            color = if (dark()) DotColors.DarkCare.toArgb() else DotColors.LightCare.toArgb()
        }
        val inset = paint.strokeWidth / 2
        val outline = Path().apply { addRoundRect(target.left+inset, target.top+inset, target.right-inset, target.bottom-inset,
            8f*density, 8f*density, Path.Direction.CW) }
        val progress = if (!outlineMotion) 1f else ((now-outlineStarted)/600f).coerceIn(0f,1f)
        val measure = PathMeasure(outline, false)
        val traced = Path(); measure.getSegment(0f, measure.length*progress, traced, true)
        canvas.drawPath(traced, paint)
        val box = cueBounds; val layout = instructionLayout; val started = playback.startedAt
        if (box != null && layout != null && started != null) {
            timeline?.frame(now-started)?.takeUnless { it.finished }?.let { frame ->
                paint.style = Paint.Style.FILL
                paint.color = if (dark()) DotColors.DarkSurface.toArgb() else DotColors.LightBackground.toArgb()
                paint.alpha = (224 * frame.alpha).toInt()
                canvas.drawRoundRect(box.left,box.top,box.right,box.bottom,16f*density,16f*density,paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = density
                paint.color = if (dark()) DotColors.DarkCare.toArgb() else DotColors.LightCare.toArgb()
                paint.alpha = (150 * frame.alpha).toInt()
                canvas.drawRoundRect(box.left,box.top,box.right,box.bottom,16f*density,16f*density,paint)
                if (frame.graphemes > 0) {
                    canvas.save(); canvas.translate(box.left+padding,box.top+padding)
                    val end = graphemes[frame.graphemes.coerceAtMost(graphemes.size)-1]
                    val clip = Path(); layout.getSelectionPath(0, end, clip); canvas.clipPath(clip)
                    layout.paint.alpha = (255*frame.alpha).toInt(); layout.draw(canvas); canvas.restore()
                }
            }
        }
        canvas.restore()
    }
    fun clear() {
        animation?.cancel(); animation = null; handler.removeCallbacks(expire)
        if (observingMotion) { service.contentResolver.unregisterContentObserver(motionObserver); observingMotion = false }
        view?.let { windows.removeViewImmediate(it) }; view = null; bounds = null
        playback.clear(); cueBounds = null; instructionLayout = null; instructionText = null; timeline = null
        graphemes = intArrayOf(); changed(null)
    }
}
