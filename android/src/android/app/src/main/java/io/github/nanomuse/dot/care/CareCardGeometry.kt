package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.overlay.isUsable
import kotlin.math.roundToInt

/** Care's layout space only: capture/scene coordinates keep their original host authority. */
internal fun careSafeViewport(host: RectPx, displaySafe: RectPx): RectPx? {
    if (!host.isUsable() || !displaySafe.isUsable()) return null
    return RectPx(maxOf(host.left,displaySafe.left),maxOf(host.top,displaySafe.top),
        minOf(host.right,displaySafe.right),minOf(host.bottom,displaySafe.bottom)).takeIf { it.isUsable() }
}

/** Available scroll height from the observed host and current target, in screen pixels. */
internal fun careCardMaxHeight(host: RectPx, target: RectPx?, density: Float): Int {
    val above=target?.let { (it.top-host.top).coerceAtLeast(0f) } ?: 0f
    val below=target?.let { (host.bottom-it.bottom).coerceAtLeast(0f) } ?: host.height
    val room=maxOf(above,below)
    // Large container targets can fill the whole host. Keep a usable scroll area;
    // the rest of that large target remains touchable outside the small card.
    val desired=maxOf(room,96*density)
    return minOf((host.height*0.55f).roundToInt(),desired.roundToInt()).coerceAtLeast(1)
}

/** Normalize against the mounted integer size, never the pre-rounding dp budget. */
internal fun careWindowFrame(host: RectPx, height: Float, density: Float, target: RectPx?,
    expanded: Boolean, adjustment: Pair<Float, Float>?, compactHeight: Float,
    mountedWidth: Int? = null, mountedHeight: Int? = null): io.github.nanomuse.dot.overlay.ForegroundFrame {
    val fitted = io.github.nanomuse.dot.overlay.requestedForegroundFrame(host, height, density, target,
        expanded, null, compactHeight)
    val width = (mountedWidth ?: fitted.bounds.width.roundToInt()).coerceIn(1, host.width.toInt().coerceAtLeast(1))
    val pixels = (mountedHeight ?: fitted.bounds.height.roundToInt()).coerceIn(1, host.height.toInt().coerceAtLeast(1))
    fun pixelBounds(left: Float, top: Float): RectPx {
        val x = left.roundToInt().coerceIn(kotlin.math.ceil(host.left).toInt(),
            maxOf(kotlin.math.ceil(host.left).toInt(), kotlin.math.floor(host.right).toInt()-width))
        val y = top.roundToInt().coerceIn(kotlin.math.ceil(host.top).toInt(),
            maxOf(kotlin.math.ceil(host.top).toInt(), kotlin.math.floor(host.bottom).toInt()-pixels))
        return RectPx(x.toFloat(),y.toFloat(),(x+width).toFloat(),(y+pixels).toFloat())
    }
    val base = fitted.copy(bounds = pixelBounds(fitted.bounds.left, fitted.bounds.top))
    val adjusted = io.github.nanomuse.dot.overlay.adjustedForegroundFrame(host, base, target, adjustment)
    val mounted = pixelBounds(adjusted.bounds.left, adjusted.bounds.top)
    // Final pixel rounding must not undo a target avoidance decision at a fractional edge.
    if (target != null && target.cueUsable() && !base.bounds.cueOverlaps(target) && mounted.cueOverlaps(target)) return base
    return adjusted.copy(bounds = mounted)
}
