package io.github.nanomuse.dot.overlay

import io.github.nanomuse.dot.screen.RectPx
import org.junit.Assert.*
import org.junit.Test

class DotForegroundGeometryTest {
    @Test fun bottomFrameUsesTheObservedHostOrigin() {
        val frame = foregroundFrame(RectPx(120f, 70f, 720f, 870f), 320f, 1f)
        assertEquals(RectPx(240f, 542f, 600f, 862f), frame.bounds)
        assertFalse(frame.movedUp)
    }

    @Test fun narrowHostRetainsSideMarginsWithoutExceedingItsWidth() {
        val frame = foregroundFrame(RectPx(100f, 24f, 420f, 824f), 240f, 1f)
        assertEquals(RectPx(108f, 576f, 412f, 816f), frame.bounds)
    }

    @Test fun densityScalesTheWidthAndMarginsInScreenPixels() {
        val frame = foregroundFrame(RectPx(100f, 24f, 900f, 824f), 800f, 2f)
        assertEquals(RectPx(140f, 368f, 860f, 808f), frame.bounds)
    }

    @Test fun keyboardClippedHostBoundsTheHeightAndBottom() {
        val frame = foregroundFrame(RectPx(100f, 24f, 700f, 324f), 500f, 2f)
        assertEquals(RectPx(116f, 143f, 684f, 308f), frame.bounds)
        assertEquals(165f, frame.bounds.height, 0.001f)
    }

    @Test fun largerFontMeasurementUsesTheFullAllowedScrollHeight() {
        val frame = foregroundFrame(RectPx(0f, 24f, 400f, 824f), 600f, 1f)
        assertEquals(RectPx(20f, 376f, 380f, 816f), frame.bounds)
    }

    @Test fun overlappingBottomTargetMovesThePanelAboveItsTouchArea() {
        val target = RectPx(0f, 650f, 400f, 780f)
        val frame = foregroundFrame(RectPx(0f, 24f, 400f, 824f), 240f, 1f, target)
        assertEquals(RectPx(20f, 402f, 380f, 642f), frame.bounds)
        assertTrue(frame.movedUp)
        assertTrue(frame.bounds.bottom < target.top)
    }

    @Test fun endingTheValidTargetStepReturnsThePanelToBottom() {
        val host = RectPx(0f, 24f, 400f, 824f)
        assertTrue(foregroundFrame(host, 240f, 1f, RectPx(0f, 650f, 400f, 780f)).movedUp)
        val returned = foregroundFrame(host, 240f, 1f, null)
        assertEquals(RectPx(20f, 576f, 380f, 816f), returned.bounds)
        assertFalse(returned.movedUp)
    }

    @Test fun panelShrinksIntoUsableSpaceAboveATargetInsteadOfCoveringIt() {
        val target = RectPx(60f, 200f, 440f, 420f)
        val frame = foregroundFrame(RectPx(50f, 20f, 450f, 420f), 220f, 1f, target)
        assertEquals(RectPx(70f, 28f, 430f, 192f), frame.bounds)
        assertTrue(frame.movedUp)
        assertTrue(frame.bounds.bottom < target.top)
    }

    @Test fun wholeHostTargetKeepsAVisiblePanelAndTouchableHostRemainder() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val frame = foregroundFrame(host, 240f, 1f, host)
        assertEquals(RectPx(20f, 32f, 380f, 272f), frame.bounds)
        assertTrue(frame.movedUp)
        assertTrue(frame.bounds.height >= 96f)
        assertTrue(frame.bounds.bottom < host.bottom)
    }

    @Test fun insufficientSpaceDoesNotReduceControlsToATinyStrip() {
        val frame = foregroundFrame(RectPx(0f, 24f, 400f, 824f), 240f, 1f,
            RectPx(0f, 60f, 400f, 824f))
        assertEquals(RectPx(20f, 32f, 380f, 272f), frame.bounds)
        assertTrue(frame.movedUp)
    }

    @Test fun horizontallySeparateTargetDoesNotMoveThePanel() {
        val frame = foregroundFrame(RectPx(0f, 24f, 1000f, 824f), 240f, 1f,
            RectPx(0f, 650f, 200f, 780f))
        assertEquals(RectPx(320f, 576f, 680f, 816f), frame.bounds)
        assertFalse(frame.movedUp)
    }

    @Test fun invalidOrOffHostTargetsCannotMoveThePanel() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val targets = listOf(RectPx(Float.NaN, 650f, 400f, 800f),
            RectPx(400f, 650f, 0f, 800f), RectPx(0f, 850f, 400f, 900f),
            RectPx(0f, 650f, 400f, Float.POSITIVE_INFINITY))
        for (target in targets) {
            val frame = foregroundFrame(host, 240f, 1f, target)
            assertEquals(RectPx(20f, 576f, 380f, 816f), frame.bounds)
            assertFalse(frame.movedUp)
        }
    }

    @Test fun invalidMeasurementsRetainAUsefulInitialPanel() {
        for (height in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val frame = foregroundFrame(RectPx(0f, 24f, 400f, 824f), height, 1f)
            assertEquals(RectPx(20f, 720f, 380f, 816f), frame.bounds)
        }
    }

    @Test fun shortInitialMeasurementCannotCollapseWholeHostTargetControls() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val frame = foregroundFrame(host, 1f, 1f, host)
        assertEquals(RectPx(20f, 32f, 380f, 128f), frame.bounds)
        assertTrue(frame.movedUp)
    }

    @Test fun tinyHostStillContainsAPositiveFrame() {
        val host = RectPx(100f, 200f, 112f, 220f)
        val frame = foregroundFrame(host, 240f, 1f, host)
        assertTrue(frame.bounds.width > 0f && frame.bounds.height > 0f)
        assertTrue(frame.bounds.left >= host.left && frame.bounds.right <= host.right)
        assertTrue(frame.bounds.top >= host.top && frame.bounds.bottom <= host.bottom)
        assertTrue(frame.bounds.height <= 11f)
    }

    @Test fun invalidHostOrDensityIsRejectedInsteadOfEscapingHostBounds() {
        val invalidHosts = listOf(RectPx(0f, 0f, 0f, 800f), RectPx(10f, 0f, 0f, 800f),
            RectPx(0f, 0f, 400f, 0f), RectPx(Float.NaN, 0f, 400f, 800f),
            RectPx(0f, 0f, Float.POSITIVE_INFINITY, 800f))
        for (host in invalidHosts) {
            assertThrows(IllegalArgumentException::class.java) { foregroundFrame(host, 240f, 1f) }
        }
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                foregroundFrame(RectPx(0f, 24f, 400f, 824f), 240f, density)
            }
        }
    }
}
