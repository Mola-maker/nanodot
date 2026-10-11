package io.github.nanomuse.dot.overlay

import io.github.nanomuse.dot.screen.RectPx

internal data class ForegroundFrame(val bounds: RectPx, val movedUp: Boolean)

/** Placement in the observed host's screen coordinates, after its IME clipping. */
internal fun foregroundFrame(
    host: RectPx,
    measuredHeight: Float,
    density: Float,
    target: RectPx? = null,
): ForegroundFrame {
    require(host.isUsable()) { "Foreground host must have finite, positive bounds" }
    require(density.isFinite() && density > 0f) { "Foreground density must be positive" }

    // Reduce the margins only for a host too small to fit the ordinary spacing.
    val sideMargin = minOf(8f * density, host.width * 0.25f)
    val verticalMargin = minOf(8f * density, host.height * 0.225f)
    val width = minOf(360f * density, host.width - sideMargin * 2f)
    val measured = measuredHeight.takeIf { it.isFinite() && it > 0f } ?: (96f * density)
    val height = minOf(maxOf(measured, 96f * density), host.height * 0.55f)
    val left = host.left + (host.width - width) / 2f
    val bottom = host.bottom - verticalMargin
    val ordinary = RectPx(left, bottom - height, left + width, bottom)

    val observedTarget = target?.takeIf { it.isUsable() }
    if (observedTarget == null || !ordinary.overlaps(observedTarget)) {
        return ForegroundFrame(ordinary, false)
    }

    val top = host.top + verticalMargin
    val aboveBottom = minOf(observedTarget.top - verticalMargin, bottom)
    val aboveHeight = aboveBottom - top
    val minimumUsefulHeight = minOf(96f * density, height)
    val moved = if (aboveHeight >= minimumUsefulHeight) {
        val fittedHeight = minOf(height, aboveHeight)
        RectPx(left, aboveBottom - fittedHeight, left + width, aboveBottom)
    } else {
        // A container target can cover the host. Keep the controls visible and
        // leave the rest of that target touchable outside the panel.
        RectPx(left, top, left + width, top + height)
    }
    return ForegroundFrame(moved, moved.top < ordinary.top)
}

private fun RectPx.isUsable(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        width.isFinite() && height.isFinite() && width > 0f && height > 0f

private fun RectPx.overlaps(other: RectPx): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top
