package io.github.nanomuse.dot.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.View
import io.github.nanomuse.dot.DotColors
import androidx.compose.ui.graphics.toArgb

/** Decorative display perimeter. The owner excludes this window from every capture. */
internal class DotCaptureScanner(context: Context) : View(context) {
    var progress = 0f
    var outcome: Boolean? = null
    private var ready: (() -> Unit)? = null
    private var posted: Runnable? = null
    private var readyToken: Any? = null
    /** Registration alone is not readiness: onDraw must render an attached, visible, positive frame. */
    fun afterVisibleDraw(callback: () -> Unit): () -> Unit {
        clearReady()
        val token = Any()
        readyToken = token
        ready = callback
        invalidate()
        return { if (readyToken === token) clearReady() }
    }
    private fun clearReady() {
        ready = null
        readyToken = null
        posted?.let(::removeCallbacks)
        posted = null
    }
    private fun visibleFrame() = isAttachedToWindow && isShown && visibility == VISIBLE && windowVisibility == VISIBLE &&
        alpha > 0f && width > 0 && height > 0
    override fun onDetachedFromWindow() {
        clearReady()
        super.onDetachedFromWindow()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DotColors.LightPrimary.toArgb(); style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
    override fun onDraw(canvas: Canvas) {
        val inset = paint.strokeWidth / 2
        val edge = Path().apply { addRoundRect(inset,inset,width-inset,height-inset,12f,12f,Path.Direction.CW) }
        paint.alpha = 60; canvas.drawPath(edge,paint)
        val measured = PathMeasure(edge,false)
        val trace = Path()
        measured.getSegment(0f,measured.length*progress.coerceIn(0f,1f),trace,true)
        paint.alpha = if(outcome == false) 130 else 255
        canvas.drawPath(trace,paint)
        if (visibleFrame()) ready?.let { callback ->
            ready = null
            posted = Runnable {
                posted = null
                if (visibleFrame()) { readyToken = null; callback() }
                else { ready = callback; invalidate() }
            }.also(::postOnAnimation)
        }
    }
}
