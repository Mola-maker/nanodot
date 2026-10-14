package io.github.nanomuse.dot.overlay

import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.RectPx
import kotlin.math.ceil

/** Screen-pixel geometry only. Raw gesture points and foreground placement never enter here. */
internal data class DotOrbMenuFrame(val bounds: RectPx, val buttons: List<RectPx>, val origin: PointPx)

internal fun orbMenuFrame(safe: RectPx, orb: RectPx, density: Float,
    protectedBounds: List<RectPx> = emptyList()): DotOrbMenuFrame? {
    fun valid(r: RectPx) = listOf(r.left, r.top, r.right, r.bottom).all(Float::isFinite) && r.width > 0 && r.height > 0
    if (!valid(safe) || !valid(orb) || !density.isFinite() || density <= 0 || protectedBounds.any { !valid(it) }) return null
    fun overlaps(a: RectPx, b: RectPx) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    val size = ceil(48f * density)
    val gap = ceil(8f * density)
    val step = size + gap
    val mirrored = (orb.left + orb.right) / 2f > (safe.left + safe.right) / 2f
    val shapes = listOf(
        listOf(0f to 0f, step to step, step to 2 * step, 0f to 3 * step),
        listOf(0f to 0f, step to 0f, 0f to step, step to step),
        listOf(0f to 0f, step to 0f, 2 * step to 0f, 3 * step to 0f),
        listOf(0f to 0f, 0f to step, 0f to 2 * step, 0f to 3 * step),
    )
    val avoid = protectedBounds + orb
    val cx = (orb.left + orb.right) / 2f
    val cy = (orb.top + orb.bottom) / 2f
    for (shape in shapes) {
        val width = shape.maxOf { it.first } + size
        val height = shape.maxOf { it.second } + size
        if (width > safe.width || height > safe.height) continue
        val preferredX = if (mirrored) orb.left - gap - width else orb.right + gap
        val xs = (listOf(preferredX, safe.left, safe.right - width, cx - width / 2f) +
            avoid.flatMap { listOf(it.right + gap, it.left - gap - width) })
            .map { it.coerceIn(safe.left, safe.right - width) }.distinct()
        val ys = (listOf(cy - height / 2f, safe.top, safe.bottom - height) +
            avoid.flatMap { listOf(it.bottom + gap, it.top - gap - height) })
            .map { it.coerceIn(safe.top, safe.bottom - height) }.distinct()
        val candidate = xs.flatMap { x -> ys.map { y -> RectPx(x, y, x + width, y + height) } }
            .filter { frame -> avoid.none { overlaps(frame, it) } }
            .minByOrNull { frame ->
                val dx = (frame.left + frame.right) / 2f - cx
                val dy = (frame.top + frame.bottom) / 2f - cy
                dx * dx + dy * dy
            } ?: continue
        val buttons = shape.map { (x, y) ->
            val left = candidate.left + if (mirrored) width - size - x else x
            RectPx(left, candidate.top + y, left + size, candidate.top + y + size)
        }
        return DotOrbMenuFrame(candidate, buttons, PointPx(
            cx.coerceIn(candidate.left + size / 2f, candidate.right - size / 2f),
            cy.coerceIn(candidate.top + size / 2f, candidate.bottom - size / 2f)))
    }
    return null
}
