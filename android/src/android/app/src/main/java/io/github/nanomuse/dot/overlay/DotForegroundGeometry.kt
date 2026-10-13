package io.github.nanomuse.dot.overlay

import io.github.nanomuse.dot.screen.RectPx

enum class ForegroundPlacement { Bottom, AboveTarget, Compact }

internal data class ForegroundFrame(
    val bounds: RectPx, val movedUp: Boolean,
    val placement: ForegroundPlacement = ForegroundPlacement.Bottom,
)

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
    // A short IME host needs room for transcript + a full 48dp control viewport.
    // Normal hosts retain the 55% cap; short hosts use the available height.
    val availableHeight = host.height - verticalMargin * 2f
    val minimumHeight = 156f * density
    val measured = measuredHeight.takeIf { it.isFinite() && it > 0f } ?: minimumHeight
    val maximumHeight = minOf(maxOf(host.height * 0.55f, minimumHeight), availableHeight)
    val height = minOf(maxOf(measured, minimumHeight), maximumHeight)
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
    val minimumUsefulHeight = minOf(minimumHeight, height)
    val moved = if (aboveHeight >= minimumUsefulHeight) {
        val fittedHeight = minOf(height, aboveHeight)
        RectPx(left, aboveBottom - fittedHeight, left + width, aboveBottom)
    } else {
        // A container target can cover the host. Keep the controls visible and
        // leave the rest of that target touchable outside the panel.
        RectPx(left, top, left + width, top + height)
    }
    return ForegroundFrame(moved, moved.top < ordinary.top,
        if (aboveHeight >= minimumUsefulHeight) ForegroundPlacement.AboveTarget else ForegroundPlacement.Compact)
}

internal fun RectPx.isUsable(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        width.isFinite() && height.isFinite() && width > 0f && height > 0f

private fun RectPx.overlaps(other: RectPx): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

/** User placement remains subordinate to a target that can actually be avoided. */
internal fun adjustedForegroundFrame(
    host: RectPx, fitted: ForegroundFrame, target: RectPx?, adjustment: Pair<Float, Float>?,
): ForegroundFrame {
    if (adjustment == null || !host.isUsable()) return fitted
    val (x, y) = adjustment
    if (!x.isFinite() || !y.isFinite()) return fitted
    val b = fitted.bounds
    val left = host.left + (host.width - b.width).coerceAtLeast(0f) * x.coerceIn(0f, 1f)
    val top = host.top + (host.height - b.height).coerceAtLeast(0f) * y.coerceIn(0f, 1f)
    val moved = RectPx(left, top, left + b.width, top + b.height)
    val validTarget = target?.takeIf { it.isUsable() }
    if (validTarget != null && !b.overlaps(validTarget) && moved.overlaps(validTarget)) return fitted
    return fitted.copy(bounds = moved)
}

/** Content requests are bounded; expansion changes compact size, never target authority. */
internal fun requestedForegroundFrame(
    host: RectPx, requestedHeight: Float, density: Float, target: RectPx?,
    expanded: Boolean, adjustment: Pair<Float, Float>? = null,
    compactHeight: Float = 156f * density,
): ForegroundFrame {
    var fitted = foregroundFrame(host, requestedHeight, density, target)
    if (!expanded && fitted.placement == ForegroundPlacement.Compact) {
        fitted = foregroundFrame(host, minOf(requestedHeight, compactHeight), density, target)
    }
    return adjustedForegroundFrame(host, fitted, target, adjustment)
}
