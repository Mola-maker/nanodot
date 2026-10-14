package io.github.nanomuse.dot.selection

import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.RectPx
import java.util.TreeSet
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class RegionSelection(val polygon: List<PointPx>, val bounds: RectPx, val areaDp2: Float)

/** Pure screen-pixel geometry. Only thresholds and the reported area use density-independent units. */
object SelectionGeometry {
    // Bound both acquisition input and the quadratic intersection arrangement. Never truncate a path:
    // losing its tail could change an ordinary drag into an apparently closed selection.
    private const val MAX_POINTS = 256
    private const val MAX_Y_EVENTS = 4096
    private data class Point(val x: Double, val y: Double)

    fun classify(points: List<PointPx>, density: Float): RegionSelection? {
        if (!density.isFinite() || density <= 0f || !validPolygon(points)) return null
        val polygon = points.filterIndexed { i, point -> i == 0 || point != points[i - 1] }
        if (polygon.distinct().size < 3) return null
        val bounds = RectPx(polygon.minOf { it.x }, polygon.minOf { it.y },
            polygon.maxOf { it.x }, polygon.maxOf { it.y })
        if (!bounds.width.isFinite() || !bounds.height.isFinite() || bounds.width <= 0 || bounds.height <= 0) return null
        // Translate before computing intersections, and use Double for products and accumulated area.
        val local = polygon.map { Point((it.x.toDouble() - bounds.left) / density,
            (it.y.toDouble() - bounds.top) / density) }
        val length = local.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        if (length < 160.0) return null // The undrawn closing edge must not help qualify a drag.
        val gap = hypot(local.last().x - local.first().x, local.last().y - local.first().y)
        val diagonal = hypot(bounds.width.toDouble() / density, bounds.height.toDouble() / density)
        if (gap > max(24.0, diagonal * .15)) return null
        val area = evenOddArea(local) ?: return null
        if (area < 48.0 * 48.0 || !area.toFloat().isFinite()) return null
        return RegionSelection(polygon.toList(), bounds, area.toFloat())
    }

    /** Even-odd membership, with the polygon boundary included. No bounding-box-only shortcut. */
    fun contains(selection: RegionSelection, point: PointPx): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite() || !validPolygon(selection.polygon)) return false
        return containsPoint(selection.polygon, point)
    }

    /** Conservative text admission: all four corners must be selected, and no polygon boundary
     * may pass through the rectangle's open interior. The latter rejects holes and concave cuts
     * even when every corner is inside. A boundary coincident with the rectangle edge is allowed.
     * Retraced interior edges can reject an otherwise filled rectangle; retaining less text is safe.
     */
    fun containsRect(selection: RegionSelection, rect: RectPx): Boolean {
        if (!validPolygon(selection.polygon) ||
            !listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() } ||
            rect.left >= rect.right || rect.top >= rect.bottom) return false
        val corners = listOf(PointPx(rect.left, rect.top), PointPx(rect.right, rect.top),
            PointPx(rect.right, rect.bottom), PointPx(rect.left, rect.bottom))
        if (!corners.all { containsPoint(selection.polygon, it) }) return false
        return selection.polygon.indices.none { i ->
            segmentEntersRect(selection.polygon[i], selection.polygon[(i + 1) % selection.polygon.size], rect)
        }
    }

    private fun validPolygon(points: List<PointPx>) = points.size in 3..MAX_POINTS &&
        points.all { it.x.isFinite() && it.y.isFinite() }

    /** Between consecutive vertex/intersection Y values, edge order is fixed and each X is linear.
     * Pairing sorted intersections implements even-odd fill; midpoint integration is exact for each
     * such linear strip. Signed shoelace area would cancel the two lobes of a bow tie incorrectly.
     */
    private fun evenOddArea(points: List<Point>): Double? {
        val events = TreeSet<Double>()
        points.forEach { events.add(it.y) }
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            for (j in i + 1 until points.size) {
                val c = points[j]
                val d = points[(j + 1) % points.size]
                val rx = b.x - a.x; val ry = b.y - a.y
                val sx = d.x - c.x; val sy = d.y - c.y
                val denominator = rx * sy - ry * sx
                if (denominator == 0.0) continue // Parallel/overlapping edges need only vertex events.
                val qx = c.x - a.x; val qy = c.y - a.y
                val t = (qx * sy - qy * sx) / denominator
                val u = (qx * ry - qy * rx) / denominator
                if (t > 0.0 && t < 1.0 && u > 0.0 && u < 1.0) {
                    events.add(a.y + t * ry)
                    if (events.size > MAX_Y_EVENTS) return null
                }
            }
        }
        var area = 0.0
        val levels = events.toList()
        for (i in 0 until levels.lastIndex) {
            val height = levels[i + 1] - levels[i]
            val y = levels[i] + height / 2.0
            val xs = ArrayList<Double>(points.size)
            for (j in points.indices) {
                val a = points[j]; val b = points[(j + 1) % points.size]
                // Half-open edge admission also handles a rounded midpoint landing on a vertex.
                if ((a.y > y) != (b.y > y)) {
                    xs.add(a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y))
                }
            }
            if (xs.size % 2 != 0) return null
            xs.sort()
            for (j in xs.indices step 2) area += (xs[j + 1] - xs[j]) * height
        }
        return area.takeIf { it.isFinite() }
    }

    private fun containsPoint(polygon: List<PointPx>, point: PointPx): Boolean {
        val x = point.x.toDouble(); val y = point.y.toDouble()
        var inside = false
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            val ax = a.x.toDouble(); val ay = a.y.toDouble()
            val bx = b.x.toDouble(); val by = b.y.toDouble()
            val cross = (x - ax) * (by - ay) - (y - ay) * (bx - ax)
            if (cross == 0.0 && x >= min(ax, bx) && x <= max(ax, bx) &&
                y >= min(ay, by) && y <= max(ay, by)) return true
            if ((ay > y) != (by > y) && x < ax + (y - ay) * (bx - ax) / (by - ay)) inside = !inside
        }
        return inside
    }

    private fun segmentEntersRect(a: PointPx, b: PointPx, rect: RectPx): Boolean {
        if (a == b) return false
        var start = 0.0
        var end = 1.0
        fun clip(origin: Double, delta: Double, lower: Double, upper: Double): Boolean {
            if (delta == 0.0) return origin > lower && origin < upper
            val first = (lower - origin) / delta
            val second = (upper - origin) / delta
            start = max(start, min(first, second))
            end = min(end, max(first, second))
            return start < end
        }
        return clip(a.x.toDouble(), b.x.toDouble() - a.x, rect.left.toDouble(), rect.right.toDouble()) &&
            clip(a.y.toDouble(), b.y.toDouble() - a.y, rect.top.toDouble(), rect.bottom.toDouble())
    }
}
