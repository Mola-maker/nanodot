package io.github.nanomuse.dot.selection

import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.RectPx
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SelectionGeometryTest {
    private fun path(vararg xy: Float) = xy.toList().chunked(2).map { PointPx(it[0], it[1]) }
    private fun square(side: Float = 100f) = path(0f,0f, side,0f, side,side, 0f,side, 0f,0f)
    private fun selected(points: List<PointPx>) = requireNotNull(SelectionGeometry.classify(points, 1f))
    // Each diagonal is 12-16-20; seven drawn edges total exactly 160, closing gap is 24.
    private fun thresholdPath(lastVertical: Float = 24f) = path(0f,0f, 12f,-16f, 40f,-16f,
        52f,0f, 52f,lastVertical, 40f,lastVertical+16f, 12f,lastVertical+16f, 0f,lastVertical)
    private fun hole() = square() + path(30f,30f, 70f,30f, 70f,70f, 30f,70f, 30f,30f, 0f,0f)

    @Test fun areaThresholdUsesFilledAreaNotBoundingBox() {
        assertEquals(2304f, selected(square(48f)).areaDp2, .001f)
        assertNull(SelectionGeometry.classify(square(47.99f), 1f))
        assertNull(SelectionGeometry.classify(path(0f,0f,60f,60f,0f,60f,60f,0f,0f,0f),1f))
    }
    @Test fun drawnPathLengthExcludesTheImplicitClosingSegment() {
        assertEquals(2528f, selected(thresholdPath()).areaDp2, .001f)
        assertNull(SelectionGeometry.classify(thresholdPath(23.99f),1f))
    }
    @Test fun endpointDistanceUsesAbsoluteOrDiagonalAllowance() {
        assertNotNull(SelectionGeometry.classify(thresholdPath(),1f))
        assertNull(SelectionGeometry.classify(thresholdPath(24.1f),1f))
        assertNotNull(SelectionGeometry.classify(path(0f,0f,300f,0f,300f,300f,0f,300f,0f,50f),1f))
        assertNull(SelectionGeometry.classify(path(0f,0f,300f,0f,300f,300f,0f,300f,0f,64f),1f))
    }
    @Test fun openDragDoesNotBecomeASelectionThroughImplicitClosure() {
        assertNull(SelectionGeometry.classify(path(0f,0f,200f,0f,200f,200f),1f))
    }
    @Test fun longRetracedLineAndCollinearVerticesHaveNoArea() {
        assertNull(SelectionGeometry.classify(path(0f,0f,400f,0f,100f,0f,400f,0f,0f,0f),1f))
        assertNull(SelectionGeometry.classify(List(30) { PointPx(0f,0f) },1f))
    }
    @Test fun crossingBowTieHasTwoPositiveLobesDespiteZeroSignedArea() {
        assertEquals(3200f, selected(path(0f,0f,80f,80f,0f,80f,80f,0f,0f,0f)).areaDp2,.001f)
    }
    @Test fun tracingTheSameLoopTwiceCancelsItsEvenOddFill() {
        assertNull(SelectionGeometry.classify(square() + square().drop(1),1f))
    }
    @Test fun overlappingLoopsCountTheirSymmetricDifference() {
        val loops = square() + path(50f,0f,150f,0f,150f,100f,50f,100f,50f,0f,0f,0f)
        assertEquals(10000f, selected(loops).areaDp2,.001f)
    }
    @Test fun innerLoopCutsAHoleAndReversingWindingChangesNothing() {
        assertEquals(8400f,selected(hole()).areaDp2,.001f)
        assertEquals(8400f,selected(hole().reversed()).areaDp2,.001f)
    }
    @Test fun densityAndScreenTranslationDoNotChangeDpArea() {
        for (density in listOf(1f,2f,3f)) {
            val p=square(48f).map { PointPx(it.x*density+103f,it.y*density-57f) }
            val result=requireNotNull(SelectionGeometry.classify(p,density))
            assertEquals(2304f,result.areaDp2,.001f)
            assertEquals(RectPx(103f,-57f,103f+48f*density,-57f+48f*density),result.bounds)
        }
    }
    @Test fun circleIsMeasuredAsItsPolygonNotItsBoundingRectangle() {
        val p=(0..64).map { val a=2*PI*it/64; PointPx((40*cos(a)).toFloat(),(40*sin(a)).toFloat()) }
        assertEquals(5018.4775f,selected(p).areaDp2,.02f)
    }
    @Test fun rejectsInvalidDensityNonFiniteCoordinatesAndTooFewDistinctPoints() {
        for (density in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertNull(SelectionGeometry.classify(square(),density))
        }
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            assertNull(SelectionGeometry.classify(square()+PointPx(bad,0f),1f))
            assertNull(SelectionGeometry.classify(square()+PointPx(0f,bad),1f))
        }
        assertNull(SelectionGeometry.classify(emptyList(),1f))
        assertNull(SelectionGeometry.classify(path(0f,0f,100f,0f,0f,0f),1f))
    }
    @Test fun boundsAndPolygonAreAnOwnedCopyAfterClassification() {
        val input=square().toMutableList()
        val result=selected(input)
        input.clear()
        assertEquals(RectPx(0f,0f,100f,100f),result.bounds)
        assertEquals(10000f,result.areaDp2,.001f)
        assertTrue(SelectionGeometry.contains(result,PointPx(50f,50f)))
    }
    @Test fun excessiveInputFailsClosedRatherThanDroppingAClosingSegment() {
        val p=(0..256).map { val a=2*PI*it/256; PointPx((100*cos(a)).toFloat(),(100*sin(a)).toFloat()) }
        assertNull(SelectionGeometry.classify(p,1f))
        val bounded=p.filterIndexed { i,_ -> i!=1 }
        assertNotNull(SelectionGeometry.classify(bounded,1f))
    }
    @Test fun denseCrossingArrangementFailsClosedWithinThePointLimit() {
        // A 251-vertex star, visiting nearly opposite vertices, has a dense intersection arrangement.
        val p=(0..251).map { val a=2*PI*((it*125)%251)/251
            PointPx((100*cos(a)).toFloat(),(100*sin(a)).toFloat()) }
        assertNull(SelectionGeometry.classify(p,1f))
    }
    @Test fun numericOverflowCannotProduceAnAcceptedArea() {
        assertNull(SelectionGeometry.classify(square(),Float.MIN_VALUE))
        assertNull(SelectionGeometry.classify(path(-Float.MAX_VALUE,0f,Float.MAX_VALUE,0f,
            Float.MAX_VALUE,100f,-Float.MAX_VALUE,100f,-Float.MAX_VALUE,0f),1f))
    }
    @Test fun pointContainmentUsesEvenOddFillWithInclusiveBoundary() {
        val region=selected(hole())
        assertTrue(SelectionGeometry.contains(region,PointPx(10f,10f)))
        assertTrue(SelectionGeometry.contains(region,PointPx(0f,50f)))
        assertFalse(SelectionGeometry.contains(region,PointPx(50f,50f)))
        assertFalse(SelectionGeometry.contains(region,PointPx(-.01f,50f)))
        assertFalse(SelectionGeometry.contains(region,PointPx(Float.NaN,0f)))
    }
    @Test fun fullyContainedNodeAndBoundaryAlignedNodeAreRetained() {
        val region=selected(square())
        assertTrue(SelectionGeometry.containsRect(region,RectPx(10f,10f,90f,90f)))
        assertTrue(SelectionGeometry.containsRect(region,RectPx(0f,0f,100f,100f)))
        assertFalse(SelectionGeometry.containsRect(region,RectPx(-1f,10f,90f,90f)))
    }
    @Test fun ancestorWithAllCornersInsideStillCannotIncludeAHole() {
        val region=selected(hole())
        assertFalse(SelectionGeometry.containsRect(region,RectPx(10f,10f,90f,90f)))
        assertTrue(SelectionGeometry.containsRect(region,RectPx(5f,40f,20f,60f)))
    }
    @Test fun concaveCutThroughNodeIsRejectedEvenWhenAllFourCornersAreInside() {
        val region=selected(path(0f,0f,100f,0f,100f,100f,70f,100f,70f,30f,
            30f,30f,30f,100f,0f,100f,0f,0f))
        assertFalse(SelectionGeometry.containsRect(region,RectPx(10f,10f,90f,90f)))
    }
    @Test fun invalidAndEmptyNodeBoundsAreNeverRetained() {
        val region=selected(square())
        for (r in listOf(RectPx(10f,10f,10f,20f),RectPx(20f,10f,10f,20f),
            RectPx(10f,20f,20f,10f),RectPx(0f,0f,Float.POSITIVE_INFINITY,50f))) {
            assertFalse(SelectionGeometry.containsRect(region,r))
        }
    }
}
