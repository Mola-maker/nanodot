package io.github.nanomuse.dot.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.openminis.app.R
import io.github.nanomuse.dot.DotColors

/** A short-lived, non-interactive surface; its controller owns the only refresh callback. */
internal class DotTrailView(context: Context) : View(context) {
    var points: List<CrystalTrailPoint> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val crystalPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val origin = IntArray(2)
    private val widths = floatArrayOf(16f, 8f, 2f)
    private val strengths = intArrayOf(24, 42, 112)
    private val softBlue = DotColors.DarkPrimary.toArgb()
    private val glint = DotColors.LightSurface.toArgb()
    private val crystalBounds = RectF()
    // Decode only the first cropped cell, not a second full 16-frame atlas for every drag.
    private val crystal: Bitmap? by lazy { decodeCrystal() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        val density = resources.displayMetrics.density
        points.forEachIndexed { index, point ->
            val previous = points.getOrNull(index - 1) ?: point
            val opacity = ((previous.alpha + point.alpha) * .5f).coerceIn(0f, 1f)
            // Three narrow layers retain host detail while easing the blue-white edge.
            for (layer in widths.indices) {
                paint.color = if (layer == widths.lastIndex) glint else softBlue
                paint.alpha = (opacity * strengths[layer]).toInt()
                paint.strokeWidth = widths[layer] * density * opacity
                canvas.drawLine(previous.x - origin[0], previous.y - origin[1],
                    point.x - origin[0], point.y - origin[1], paint)
            }
        }
        val ghosts = crystalTrailGhosts(points, density)
        if (ghosts.isEmpty()) return
        val bitmap = crystal ?: return
        ghosts.forEach { ghost ->
            val radius = (8f + 6f * ghost.alpha) * density
            val x = ghost.x - origin[0]
            val y = ghost.y - origin[1]
            crystalBounds.set(x - radius, y - radius, x + radius, y + radius)
            crystalPaint.alpha = (ghost.alpha * 158).toInt()
            canvas.drawBitmap(bitmap, null, crystalBounds, crystalPaint)
        }
    }

    @Suppress("DEPRECATION") // InputStream overload supports the application's API 26 minimum.
    private fun decodeCrystal(): Bitmap? = resources.openRawResource(R.drawable.nm_crystal_orb_atlas).use { input ->
        val decoder = BitmapRegionDecoder.newInstance(input, false) ?: return@use null
        try {
            val cell = CrystalFrames.rect(decoder.width, decoder.height, 0)
            val insetX = (cell.right - cell.left) / 10
            val insetY = (cell.bottom - cell.top) / 10
            decoder.decodeRegion(Rect(cell.left + insetX, cell.top + insetY,
                cell.right - insetX, cell.bottom - insetY), BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } finally { decoder.recycle() }
    }
}
