package io.github.nanomuse.dot.overlay

import kotlin.math.hypot

internal data class CrystalTrailGhost(val x: Float, val y: Float, val alpha: Float)

/** Distance-spaced decorations only; these interpolated points never enter gesture recognition. */
internal fun crystalTrailGhosts(points: List<CrystalTrailPoint>, density: Float): List<CrystalTrailGhost> {
    if (points.size < 2 || !density.isFinite() || density <= 0f ||
        points.any { !it.x.isFinite() || !it.y.isFinite() || !it.alpha.isFinite() }) return emptyList()
    val ghosts = ArrayList<CrystalTrailGhost>(3)
    val clearance = 32f * density
    val spacing = 40f * density
    var nextDistance = clearance
    var traversed = 0f
    var candidates = 0
    for (index in points.lastIndex downTo 1) {
        val newer = points[index]
        val older = points[index - 1]
        val dx = older.x - newer.x
        val dy = older.y - newer.y
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (!length.isFinite()) return emptyList()
        if (length == 0f) continue
        while (nextDistance <= traversed + length && ghosts.size < 3 && candidates < 36) {
            candidates++
            val fraction = ((nextDistance - traversed) / length).coerceIn(0f, 1f)
            val candidate = CrystalTrailGhost(newer.x + dx * fraction, newer.y + dy * fraction,
                (newer.alpha + (older.alpha - newer.alpha) * fraction).coerceIn(0f, 1f))
            // Do not stack spheres when the same arc returns through a small circle.
            if (candidate.alpha > 0f && ghosts.none {
                hypot((it.x - candidate.x).toDouble(), (it.y - candidate.y).toDouble()) < clearance
            }) ghosts.add(candidate)
            nextDistance += spacing
        }
        if (ghosts.size == 3 || candidates == 36) break
        traversed += length
    }
    return ghosts.asReversed() // Oldest first, so stronger recent highlights remain on top.
}
