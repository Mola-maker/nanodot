package io.github.nanomuse.dot.overlay

import io.github.nanomuse.dot.screen.RectPx
import org.junit.Assert.*
import org.junit.Test

class DotForegroundGeometryTest {
    @Test fun compactLargeFontBudgetFitsControlsAndTextWithinActualHost() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val compact = requestedForegroundFrame(host, 300f, 1f, host, false, compactHeight = 204f)
        assertEquals(204f, compact.bounds.height, 0f)
        assertTrue(compact.bounds.height >= 48f + 64f + 44f + 12f)
        val imeHost = RectPx(0f, 24f, 400f, 184f)
        val clipped = requestedForegroundFrame(imeHost, 300f, 1f, imeHost, false, compactHeight = 204f)
        assertEquals(144f, clipped.bounds.height, 0f)
        assertTrue(clipped.bounds.top >= imeHost.top && clipped.bounds.bottom <= imeHost.bottom)
    }

    @Test fun compactExpansionAddsRoomWithoutDiscardingKnownTarget() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val compact = requestedForegroundFrame(host, 300f, 1f, host, false)
        val expanded = requestedForegroundFrame(host, 300f, 1f, host, true)
        assertEquals(156f, compact.bounds.height, 0f)
        assertEquals(300f, expanded.bounds.height, 0f)
        assertEquals(ForegroundPlacement.Compact, expanded.placement)
        assertEquals(32f, expanded.bounds.top, 0f)
    }

    @Test fun expansionAndAdjustmentKeepAvoidableTargetExposed() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val target = RectPx(0f, 650f, 400f, 780f)
        for (expanded in listOf(false, true)) {
            val fitted = requestedForegroundFrame(host, 420f, 1f, target, expanded, 0.5f to 1f)
            assertEquals(ForegroundPlacement.AboveTarget, fitted.placement)
            assertTrue(fitted.bounds.bottom < target.top)
        }
    }

    @Test fun estimatedShortContentDoesNotReserveMaximumHostBudget() {
        val host = RectPx(120f, 70f, 720f, 870f)
        val short = foregroundFrame(host, 200f, 1f)
        val long = foregroundFrame(host, 900f, 1f)
        assertEquals(200f, short.bounds.height, 0f)
        assertEquals(440f, long.bounds.height, 0f)
    }

    @Test fun adjustmentCannotCoverTargetWhenAutomaticPlacementAvoidsIt() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val target = RectPx(0f, 650f, 400f, 780f)
        val fitted = foregroundFrame(host, 240f, 1f, target)
        assertEquals(fitted, adjustedForegroundFrame(host, fitted, target, 0.5f to 1f))
        val moved = adjustedForegroundFrame(host, fitted, target, 0.5f to 0f)
        assertTrue(moved.bounds.bottom <= target.top)
    }

    @Test fun containerTargetStillAllowsBoundedManualAdjustment() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val fitted = foregroundFrame(host, 156f, 1f, host)
        val moved = adjustedForegroundFrame(host, fitted, host, 0.5f to 1f)
        assertEquals(host.bottom, moved.bounds.bottom, 0f)
        assertEquals(ForegroundPlacement.Compact, moved.placement)
    }

    @Test fun shortImeHostBudgetsVisibleTranscriptAndOneFullTouchControl() {
        val frame = foregroundFrame(RectPx(0f,0f,360f,160f),88f,1f)
        assertEquals("Short hosts use available height instead of the 55 percent cap",144f,frame.bounds.height,0f)
        assertEquals(8f,frame.bounds.top,0f)
        assertEquals(152f,frame.bounds.bottom,0f)
        // Compact shell: 8 padding + 32 largest status + 8 gaps + 48 control leaves 48 transcript.
        assertTrue(frame.bounds.height - 8f - 32f - 8f - 48f >= 48f)
    }
    @Test fun bottomFrameUsesTheObservedHostOrigin() {
        val frame = foregroundFrame(RectPx(120f, 70f, 720f, 870f), 320f, 1f)
        assertEquals(RectPx(240f, 542f, 600f, 862f), frame.bounds)
        assertFalse(frame.movedUp)
        assertEquals(ForegroundPlacement.Bottom, frame.placement)
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
        assertEquals(RectPx(116f, 40f, 684f, 308f), frame.bounds)
        assertEquals(268f, frame.bounds.height, 0.001f)
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
        assertEquals(ForegroundPlacement.AboveTarget, frame.placement)
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
        assertEquals(ForegroundPlacement.AboveTarget, frame.placement)
        assertTrue(frame.bounds.bottom < target.top)
    }

    @Test fun wholeHostTargetKeepsAVisiblePanelAndTouchableHostRemainder() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val frame = foregroundFrame(host, 240f, 1f, host)
        assertEquals(RectPx(20f, 32f, 380f, 272f), frame.bounds)
        assertTrue(frame.movedUp)
        assertEquals(ForegroundPlacement.Compact, frame.placement)
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
        assertEquals(ForegroundPlacement.Bottom, frame.placement)
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
        assertEquals(ForegroundPlacement.Bottom, frame.placement)
        }
    }

    @Test fun invalidMeasurementsRetainAUsefulInitialPanel() {
        for (height in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val frame = foregroundFrame(RectPx(0f, 24f, 400f, 824f), height, 1f)
            assertEquals(RectPx(20f, 660f, 380f, 816f), frame.bounds)
        }
    }

    @Test fun shortInitialMeasurementCannotCollapseWholeHostTargetControls() {
        val host = RectPx(0f, 24f, 400f, 824f)
        val frame = foregroundFrame(host, 1f, 1f, host)
        assertEquals(RectPx(20f, 32f, 380f, 188f), frame.bounds)
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
