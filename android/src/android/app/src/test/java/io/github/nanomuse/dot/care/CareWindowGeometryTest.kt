package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.RectPx
import org.junit.Assert.*
import org.junit.Test

class CareWindowGeometryTest {
    private val host = RectPx(0f,0f,1080f,2400f)
    private fun fit(target: RectPx?, position: Pair<Float,Float>?) = careWindowFrame(host,410f,2.625f,target,
        false,position,409.5f,945,410).bounds
    @Test fun fractionalDensityCompactWindowKeepsExactMountedDimensionsAtDockAndRefresh() {
        val target = host
        val dock = fit(target,0.5f to 1f)
        assertEquals(1990f,dock.top,0f); assertEquals(2400f,dock.bottom,0f)
        val normalized = (dock.top-host.top)/(host.height-410)
        repeat(4) { assertEquals(dock,fit(target,0.5f to normalized)) }
    }
    @Test fun intermediateDragUsesSameIntegerBasisAndRemainsInsideHost() {
        val moved = fit(host,0.27f to 0.43f)
        val x=(moved.left-host.left)/(host.width-945)
        val y=(moved.top-host.top)/(host.height-410)
        assertEquals(moved,fit(host,x to y)); assertTrue(host.cueContains(moved))
        assertEquals(945f,moved.width,0f); assertEquals(410f,moved.height,0f)
    }
    @Test fun ManualAdjustmentCannotCoverAnOtherwiseAvoidableTarget() {
        val target = RectPx(200f,1950f,800f,2150f)
        val base = fit(target,null)
        assertFalse(base.cueOverlaps(target))
        assertEquals(base,fit(target,0.5f to 1f))
    }
    @Test fun screenSpaceViewportExcludesBarsWithoutLosingImeOrHostOrigin() {
        val safe = RectPx(0f,107f,1080f,2337f)
        assertEquals(safe, careSafeViewport(host, safe))
        assertEquals(RectPx(0f,107f,1080f,525f),
            careSafeViewport(host.copy(bottom=525f), safe))
        assertEquals(RectPx(180f,320f,780f,900f),
            careSafeViewport(RectPx(180f,320f,780f,900f), safe))
        assertEquals(RectPx(80f,107f,1000f,2337f),
            careSafeViewport(host, RectPx(80f,107f,1000f,2337f)))
    }
    @Test fun invalidOrDisjointSafeViewportDoesNotInventAWindowOrigin() {
        val safe = RectPx(0f,107f,1080f,2337f)
        assertNull(careSafeViewport(RectPx(0f,0f,1080f,107f),safe))
        assertNull(careSafeViewport(host,RectPx(Float.NaN,0f,1080f,2400f)))
        assertNull(careSafeViewport(RectPx(1f,2f,1f,3f),safe))
    }
    @Test fun safeScreenSpaceDockAndShortHostKeepMountedPixelsInsideBounds() {
        for (bottom in listOf(2400f,525f)) {
            val safe = requireNotNull(careSafeViewport(host.copy(bottom=bottom),RectPx(0f,107f,1080f,2337f)))
            val fitted = careWindowFrame(safe,410f,2.625f,host,false,0.5f to 1f,409.5f).bounds
            assertTrue(safe.cueContains(fitted))
            assertEquals(safe.bottom,fitted.bottom,0f)
            val ratio = (fitted.top-safe.top)/(safe.height-fitted.height)
            assertEquals(fitted,careWindowFrame(safe,fitted.height,2.625f,host,false,0.5f to ratio,
                409.5f,fitted.width.toInt(),fitted.height.toInt()).bounds)
        }
    }
}
