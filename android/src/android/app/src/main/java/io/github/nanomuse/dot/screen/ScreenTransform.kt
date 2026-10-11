package io.github.nanomuse.dot.screen

import io.github.nanomuse.dot.gesture.PointPx

/** Android returns pixels in the current display rotation; never rotate them a second time.
 * Bounds describe the captured display/window origin, independently of the host content insets.
 */
class ScreenTransform(val bounds: RectPx, val imageWidth: Int, val imageHeight: Int) {
    init {
        require(bounds.width > 0 && bounds.height > 0 && imageWidth > 0 && imageHeight > 0)
        require(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() })
    }
    fun screenToImage(point: PointPx) = PointPx(
        (point.x - bounds.left) * imageWidth / bounds.width,
        (point.y - bounds.top) * imageHeight / bounds.height)
    fun imageToScreen(point: PointPx) = PointPx(
        point.x * bounds.width / imageWidth + bounds.left,
        point.y * bounds.height / imageHeight + bounds.top)
}

/** Node coordinates and window pixels share the selected root frame. AX window regions may
 * round a transformed edge outward (observed 598px region vs 597px root/surface in split).
 * Identity and positive finite geometry are mandatory; the capture still checks exact pixel size.
 */
internal fun hostFrameBounds(windowId: Int, rootWindowId: Int, rootBounds: RectPx): RectPx? =
    rootBounds.takeIf { windowId >= 0 && rootWindowId == windowId &&
        listOf(it.left,it.top,it.right,it.bottom).all(Float::isFinite) && it.width > 0 && it.height > 0 }
