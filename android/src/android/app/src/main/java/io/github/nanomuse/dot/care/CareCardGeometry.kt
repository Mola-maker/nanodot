package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.RectPx
import kotlin.math.roundToInt

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
