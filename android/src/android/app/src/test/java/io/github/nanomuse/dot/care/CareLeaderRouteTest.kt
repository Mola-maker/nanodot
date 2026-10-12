package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.RectPx
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class CareLeaderRouteTest {
    private val viewport = RectPx(0f, 96f, 1080f, 2400f)
    private val stroke = 7.875f
    private val clearance = 10.5f

    @Test fun measuredBottomTargetDoesNotDrawThroughCareControls() {
        // Native8 actual measured target/card/bubble, not a generated layout expectation.
        assertSafeRoute(RectPx(251f,2085f,829f,2232f), RectPx(68f,744f,1013f,2064f),
            RectPx(100f,1281f,893f,1429f), RectPx(68f,1430f,1013f,2064f))
    }

    @Test fun pixelMeasuredTopTargetDoesNotDrawThroughChatHeaderOrTranscript() {
        // Root's original after-click PNG measurement; exact Compose bounds remain native acceptance.
        assertSafeRoute(RectPx(251f,254f,829f,401f), RectPx(68f,1059f,1013f,2378f),
            RectPx(100f,1695f,893f,1906f), RectPx(68f,1059f,1013f,1694f))
    }

    @Test fun clippedLeftGutterUsesTheSafeRightSide() {
        val bubble=RectPx(100f,1281f,893f,1429f)
        val route=requireNotNull(careLeaderRoute(RectPx(251f,2085f,829f,2232f),bubble,
            RectPx(68f,744f,1013f,2064f),RectPx(60f,96f,1080f,2400f),stroke,clearance))
        assertEquals(bubble.right,route.last().x,0f)
        assertTrue(route.dropLast(1).all { it.x>=60f })
    }

    @Test fun noExteriorGutterDoesNotFallBackToCrossingTheCard() {
        assertNull(careLeaderRoute(RectPx(251f,2085f,829f,2232f),RectPx(100f,1281f,893f,1429f),
            RectPx(68f,744f,1013f,2064f),RectPx(68f,96f,1013f,2400f),stroke,clearance))
    }

    @Test fun keyboardClippedTargetCannotProduceOffHostInk() {
        assertNull(careLeaderRoute(RectPx(251f,2085f,829f,2232f),RectPx(100f,1281f,893f,1429f),
            RectPx(68f,744f,1013f,2064f),RectPx(0f,96f,1080f,2050f),stroke,clearance))
    }

    @Test fun unmeasuredCardOrViewportNeverUsesTheOldCenterRoute() {
        val target=RectPx(251f,2085f,829f,2232f); val bubble=RectPx(100f,1281f,893f,1429f)
        val card=RectPx(68f,744f,1013f,2064f)
        assertNull(careLeaderRoute(target,bubble,null,viewport,stroke,clearance))
        assertNull(careLeaderRoute(target,bubble,card,null,stroke,clearance))
    }

    @Test fun overlappingContainerTargetKeepsItsOutlineWithoutAnUnsafeLeader() {
        assertNull(careLeaderRoute(RectPx(0f,96f,1080f,2400f),RectPx(100f,1281f,893f,1429f),
            RectPx(68f,744f,1013f,2064f),viewport,stroke,clearance))
    }

    @Test fun staleOrInvalidBubbleAndStrokeAreRejected() {
        val target=RectPx(251f,2085f,829f,2232f); val card=RectPx(68f,744f,1013f,2064f)
        listOf(RectPx(0f,1281f,893f,1429f),RectPx(100f,1281f,893f,1281f),
            RectPx(100f,Float.NaN,893f,1429f),RectPx(100f,1281f,Float.POSITIVE_INFINITY,1429f)).forEach {
            assertNull(careLeaderRoute(target,it,card,viewport,stroke,clearance))
        }
        val bubble=RectPx(100f,1281f,893f,1429f)
        assertNull(careLeaderRoute(target,bubble,card,viewport,0f,clearance))
        assertNull(careLeaderRoute(target,bubble,card,viewport,stroke,-1f))
        assertNull(careLeaderRoute(RectPx(Float.NaN,2085f,829f,2232f),bubble,card,viewport,stroke,clearance))
    }

    @Test fun screenOriginOffsetMovesEveryRoutePointExactlyOnce() {
        val target=RectPx(251f,2085f,829f,2232f); val card=RectPx(68f,744f,1013f,2064f)
        val bubble=RectPx(100f,1281f,893f,1429f)
        fun move(r: RectPx)=RectPx(r.left+120f,r.top+70f,r.right+120f,r.bottom+70f)
        val original=requireNotNull(careLeaderRoute(target,bubble,card,viewport,stroke,clearance))
        val moved=requireNotNull(careLeaderRoute(move(target),move(bubble),move(card),move(viewport),stroke,clearance))
        assertEquals(original.map { PointPx(it.x+120f,it.y+70f) },moved)
    }

    @Test fun shortestValidRouteUsesTheNearbyAssistantBubbleSide() {
        val route=requireNotNull(careLeaderRoute(RectPx(251f,2085f,829f,2232f),RectPx(100f,1281f,893f,1429f),
            RectPx(68f,744f,1013f,2064f),viewport,stroke,clearance))
        // Independently hand-calculated left route 1051.3125px; right route 1140.3125px.
        assertEquals(100f,route.last().x,0f)
    }

    private fun assertSafeRoute(target: RectPx, card: RectPx, bubble: RectPx, protectedContent: RectPx) {
        val points = requireNotNull(careLeaderRoute(target,bubble,card,viewport,stroke,clearance))
        assertTrue("The leader must connect the target to a current bubble side", points.size >= 2)
        points.zipWithNext().forEach { (a,b) ->
            assertFalse("Blue ink crosses measured chat content outside the instruction row",
                overlaps(protectedContent,segmentInk(a,b,stroke/2)))
        }
        val end = points.last()
        assertTrue("Connect to a bubble side rather than cross its text", end.x == bubble.left || end.x == bubble.right)
        assertTrue(end.y > bubble.top && end.y < bubble.bottom)
        assertEquals("The only card entry is horizontal at the instruction row", end.y, points[points.lastIndex-1].y, 0f)
        points.zipWithNext().forEachIndexed { index, (a,b) ->
            val ink = segmentInk(a,b,stroke/2)
            assertTrue("Entire stroke stays in the clipped host", contains(viewport,ink))
            if(index < points.size-2) {
                val protectedCard=RectPx(card.left-clearance,card.top-clearance,card.right+clearance,card.bottom+clearance)
                assertFalse("Leader ink loses the required clearance around chat before its final bubble entry", overlaps(protectedCard,ink))
            }
            else {
                assertTrue("Final entry cannot cross instruction text", if(end.x==bubble.left) max(a.x,b.x)<=bubble.left else min(a.x,b.x)>=bubble.right)
            }
        }
        val start=points.first()
        assertTrue("Start connects the existing target contour", start.y>target.top && start.y<target.bottom &&
            (start.x==target.left+stroke/2 || start.x==target.right-stroke/2))
    }

    private fun segmentInk(a: PointPx,b: PointPx,r: Float) = RectPx(min(a.x,b.x)-r,min(a.y,b.y)-r,max(a.x,b.x)+r,max(a.y,b.y)+r)
    private fun contains(a: RectPx,b: RectPx) = a.left<=b.left && a.top<=b.top && a.right>=b.right && a.bottom>=b.bottom
    private fun overlaps(a: RectPx,b: RectPx) = a.left<b.right && a.right>b.left && a.top<b.bottom && a.bottom>b.top
}
