package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.RectPx
import kotlin.math.abs

/** A cue never covers the target, the entire chat card, or the IME-clipped host edge. */
internal fun careInstructionBounds(target: RectPx, card: RectPx, host: RectPx,
    width: Float, height: Float, gap: Float, rtl: Boolean): RectPx? {
    if (listOf(target, card, host).any { !it.cueUsable() } ||
        !host.cueContains(target) || !listOf(width,height,gap).all(Float::isFinite) ||
        width <= 0 || height <= 0 || gap < 0 || width + gap * 2 > host.width || height + gap * 2 > host.height) return null
    val left = (target.left + (target.width-width)/2).coerceIn(host.left+gap,host.right-gap-width)
    val top = (target.top + (target.height-height)/2).coerceIn(host.top+gap,host.bottom-gap-height)
    val vertical = listOf(RectPx(left,target.top-gap-height,left+width,target.top-gap),
        RectPx(left,target.bottom+gap,left+width,target.bottom+gap+height))
    val sides = listOf(RectPx(target.right+gap,top,target.right+gap+width,top+height),
        RectPx(target.left-gap-width,top,target.left-gap,top+height)).let { if (rtl) it.reversed() else it }
    val candidates = vertical + sides + listOf(
        RectPx(host.left+gap,host.top+gap,host.left+gap+width,host.top+gap+height),
        RectPx(host.right-gap-width,host.top+gap,host.right-gap,host.top+gap+height),
        RectPx(host.left+gap,host.bottom-gap-height,host.left+gap+width,host.bottom-gap),
        RectPx(host.right-gap-width,host.bottom-gap-height,host.right-gap,host.bottom-gap))
    return candidates.filter { host.cueContains(it) && !it.cueOverlaps(target) &&
        !it.cueOverlaps(RectPx(card.left-gap,card.top-gap,card.right+gap,card.bottom+gap)) }
        .minByOrNull { abs(it.left+it.width/2-target.left-target.width/2) + abs(it.top+it.height/2-target.top-target.height/2) }
}
internal fun RectPx.cueUsable() = listOf(left,top,right,bottom,width,height).all(Float::isFinite) && width>0 && height>0
internal fun RectPx.cueContains(other: RectPx) = left<=other.left && top<=other.top && right>=other.right && bottom>=other.bottom
internal fun RectPx.cueOverlaps(other: RectPx) = left<other.right && right>other.left && top<other.bottom && bottom>other.top
