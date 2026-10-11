package io.github.nanomuse.dot.overlay

import kotlin.math.roundToInt

enum class OverlayKind(val touchable: Boolean) {
    Orb(true), Panel(true), Trail(false), Focus(false),
}

object OverlayPolicy {
    fun windows(connected: Boolean, locked: Boolean, enabled: Boolean): Set<OverlayKind> =
        if (connected && !locked && enabled) setOf(OverlayKind.Orb) else emptySet()
}

/** The registry owns each add/remove exactly once, including future auxiliary windows. */
internal class DotWindowRegistry(
    private val add: (OverlayKind) -> Unit,
    private val remove: (OverlayKind) -> Unit,
) {
    private val attached = mutableSetOf<OverlayKind>()
    val size get() = attached.size
    fun sync(wanted: Set<OverlayKind>) {
        (attached - wanted).forEach { remove(it); attached.remove(it) }
        (wanted - attached).forEach { add(it); attached.add(it) }
    }
}

/** Native usable frame in screen pixels; legacy layout offsets are relative to its parent. */
internal data class DotWindowGeometry(
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val parentRelative: Boolean,
) {
    private val layoutLeft get() = if (parentRelative) 0 else left
    private val layoutTop get() = if (parentRelative) 0 else top
    private fun travelX(size: Int) = (right - left - size).coerceAtLeast(0)
    private fun travelY(size: Int) = (bottom - top - size).coerceAtLeast(0)

    fun position(size: Int, x: Float, y: Float) =
        (layoutLeft + (travelX(size) * x.coerceIn(0f, 1f)).roundToInt()) to
            (layoutTop + (travelY(size) * y.coerceIn(0f, 1f)).roundToInt())

    fun clamp(size: Int, x: Int, y: Int) =
        x.coerceIn(layoutLeft, layoutLeft + travelX(size)) to
            y.coerceIn(layoutTop, layoutTop + travelY(size))

    fun edgeX(size: Int, rawX: Float) =
        if (rawX < left + (right - left) / 2f) layoutLeft else layoutLeft + travelX(size)

    fun fractions(size: Int, x: Int, y: Int): Pair<Float, Float> {
        fun fraction(value: Int, range: Int) =
            if (range > 0) (value.toFloat() / range).coerceIn(0f, 1f) else 0f
        return fraction(x - layoutLeft, travelX(size)) to fraction(y - layoutTop, travelY(size))
    }

    fun screenPosition(x: Int, y: Int) =
        (x + if (parentRelative) left else 0) to (y + if (parentRelative) top else 0)
}
