package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.RectPx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Screen-space route outside the measured chat card, entering only the current bubble's side. */
internal fun careLeaderRoute(target: RectPx, bubble: RectPx, card: RectPx?, viewport: RectPx?,
    strokeWidth: Float, clearance: Float): List<PointPx>? {
    if(card==null || viewport==null || listOf(target,bubble,card,viewport).any { !it.usable() } ||
        !strokeWidth.isFinite() || strokeWidth<=0f || !clearance.isFinite() || clearance<0f ||
        target.width<=strokeWidth || target.height<=strokeWidth || bubble.height<=strokeWidth*2 ||
        !viewport.contains(target) || !viewport.contains(card) || !card.contains(bubble) || target.overlaps(card)) return null
    val radius=strokeWidth/2
    val targetY=target.top+target.height/2
    val bubbleY=bubble.top+bubble.height/2
    fun candidate(left: Boolean): List<PointPx>? {
        val rail=if(left) card.left-radius-clearance else card.right+radius+clearance
        val end=PointPx(if(left) bubble.left else bubble.right,bubbleY)
        val points=listOf(PointPx(if(left) target.left+radius else target.right-radius,targetY),
            PointPx(rail,targetY),PointPx(rail,bubbleY),end)
        // Before the final side entry, even the stroke plus its clearance stays outside the card.
        if(points.zipWithNext().any { (a,b) -> !viewport.contains(ink(a,b,radius)) } ||
            points.zipWithNext().dropLast(1).any { (a,b) -> card.overlaps(ink(a,b,radius+clearance)) } ||
            !viewport.contains(RectPx(end.x-strokeWidth,end.y-strokeWidth,end.x+strokeWidth,end.y+strokeWidth))) return null
        // The final horizontal segment approaches from the same exterior side; never cross the bubble.
        if(if(left) rail>end.x else rail<end.x) return null
        return points
    }
    return listOfNotNull(candidate(true),candidate(false)).minByOrNull { points ->
        points.zipWithNext().sumOf { (a,b) -> (abs(a.x-b.x)+abs(a.y-b.y)).toDouble() }
    }
}

private fun ink(a: PointPx,b: PointPx,r: Float) = RectPx(min(a.x,b.x)-r,min(a.y,b.y)-r,max(a.x,b.x)+r,max(a.y,b.y)+r)
private fun RectPx.usable() = listOf(left,top,right,bottom,width,height).all(Float::isFinite) && width>0f && height>0f
private fun RectPx.contains(other: RectPx) = other.usable() && left<=other.left && top<=other.top && right>=other.right && bottom>=other.bottom
private fun RectPx.overlaps(other: RectPx) = left<other.right && right>other.left && top<other.bottom && bottom>other.top
