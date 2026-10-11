package io.github.nanomuse.dot.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** A short-lived, non-interactive surface; its controller owns the only refresh callback. */
internal class DotTrailView(context: Context) : View(context) {
    var points: List<CrystalTrailPoint> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val origin = IntArray(2)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        val density = resources.displayMetrics.density
        points.forEachIndexed { index, point ->
            val previous = points.getOrNull(index - 1) ?: point
            paint.color = 0xFF6E9FC9.toInt()
            paint.alpha = (point.alpha * 38).toInt()
            paint.strokeWidth = 16 * density * point.alpha
            canvas.drawLine(previous.x - origin[0], previous.y - origin[1],
                point.x - origin[0], point.y - origin[1], paint)
            paint.color = 0xFFCFE8FF.toInt()
            paint.alpha = (point.alpha * 100).toInt()
            paint.strokeWidth = 5 * density * point.alpha
            canvas.drawLine(previous.x - origin[0], previous.y - origin[1],
                point.x - origin[0], point.y - origin[1], paint)
        }
    }
}
