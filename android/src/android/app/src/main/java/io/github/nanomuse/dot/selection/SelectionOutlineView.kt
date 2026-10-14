package io.github.nanomuse.dot.selection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.compose.ui.graphics.toArgb
import io.github.nanomuse.dot.DotColors

/** Owned decoration only: no input, accessibility nodes, or independent animation loop. */
class SelectionOutlineView(context: Context, selection: RegionSelection) : View(context) {
    private val path=Path().apply {
        fillType=Path.FillType.EVEN_ODD
        selection.polygon.forEachIndexed { index,p -> if(index==0) moveTo(p.x,p.y) else lineTo(p.x,p.y) }
        close()
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=DotColors.LightPrimary.toArgb(); style=Paint.Style.STROKE
        strokeWidth=2f*resources.displayMetrics.density
    }
    init { isFocusable=false; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas:Canvas) { super.onDraw(canvas); canvas.drawPath(path,paint) }
}
