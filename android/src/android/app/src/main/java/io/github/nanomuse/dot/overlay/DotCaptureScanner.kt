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
    }
}
