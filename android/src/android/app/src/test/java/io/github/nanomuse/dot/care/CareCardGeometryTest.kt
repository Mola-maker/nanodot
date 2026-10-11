package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.RectPx
import org.junit.Assert.*
import org.junit.Test

class CareCardGeometryTest {
    @Test fun fullHeightTargetCannotCollapseEveryControlIntoOnePixel() {
        val host=RectPx(0f,24f,400f,624f)
        assertTrue("The card needs a visible scrollable touch area even for a large target",
            careCardMaxHeight(host,host,1f)>=96)
    }
    @Test fun keyboardAndLandscapeHeightIsClampedToActualHost() {
        val host=RectPx(100f,24f,700f,224f)
        assertEquals(110,careCardMaxHeight(host,null,2f))
    }
    @Test fun ordinaryTargetLeavesItsTouchAreaClear() {
        val host=RectPx(0f,0f,400f,800f)
        assertEquals(440,careCardMaxHeight(host,RectPx(0f,20f,400f,100f),1f))
    }
}
