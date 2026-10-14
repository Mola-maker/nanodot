package io.github.nanomuse.dot.screen

import io.github.nanomuse.dot.care.CareAction
import io.github.nanomuse.dot.care.CareExpectation
import io.github.nanomuse.dot.care.CareProposal
import io.github.nanomuse.dot.care.CareProposalValidator
import io.github.nanomuse.dot.gesture.PointPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureModeTest {
    @Test fun foregroundChatUsesOverlayFreeWindowOrFreshNodesWhileStopIsVisible() {
        assertEquals(ScreenCaptureMode.Window, screenCaptureMode(34, CapturePurpose.Chat, true))
        assertEquals(ScreenCaptureMode.Window, screenCaptureMode(35, CapturePurpose.Chat, true))
        for (sdk in listOf(26, 29, 30, 33)) {
            assertEquals("Visible Stop cannot coexist with an overlay-free display shot on SDK $sdk",
                ScreenCaptureMode.Nodes, screenCaptureMode(sdk, CapturePurpose.Chat, true))
        }
    }
    @Test fun explicitNonForegroundPixelFlowsRetainTheirExistingCaptureCapability() {
        assertEquals(ScreenCaptureMode.Window, screenCaptureMode(34, CapturePurpose.Care, true))
        assertEquals(ScreenCaptureMode.Display, screenCaptureMode(33, CapturePurpose.Care, true))
        assertEquals(ScreenCaptureMode.Display, screenCaptureMode(26, CapturePurpose.Care, true))
        for (purpose in CapturePurpose.entries) {
            assertEquals(ScreenCaptureMode.Nodes, screenCaptureMode(35, purpose, false))
        }
    }
    @Test fun selectionKeepsStopVisibleDuringAcquisition() {
        for (sdk in listOf(34, 35)) {
            assertEquals(ScreenCaptureMode.Window, screenCaptureMode(sdk, CapturePurpose.Selection, true))
        }
        for (sdk in listOf(26, 29, 30, 33)) {
            assertEquals(ScreenCaptureMode.Nodes, screenCaptureMode(sdk, CapturePurpose.Selection, true))
        }
    }

    @Test fun pixelCaptureBoundsRetainTheirActualCoordinateSpace() {
        val host = RectPx(320f, 120f, 900f, 720f)
        val display = RectPx(0f, 0f, 1200f, 800f)
        assertEquals(host, captureBounds(ScreenCaptureMode.Window, host, display))
        assertEquals(display, captureBounds(ScreenCaptureMode.Display, host, display))
    }

    @Test fun nodeCapturePreservesNonzeroHostOriginForRegionFallback() {
        // A full-display node frame silently changes Care admission in split-screen.
        val host = RectPx(320f, 120f, 900f, 720f)
        val bounds = captureBounds(ScreenCaptureMode.Nodes, host, RectPx(0f, 0f, 1200f, 800f))
        assertEquals(host, bounds)
        val transform = ScreenTransform(bounds, bounds.width.toInt(), bounds.height.toInt())
        assertEquals(PointPx(0f, 0f), transform.screenToImage(PointPx(320f, 120f)))
        assertEquals(PointPx(580f, 600f), transform.screenToImage(PointPx(900f, 720f)))
        assertEquals(PointPx(400f, 200f), transform.imageToScreen(PointPx(80f, 80f)))
    }

    @Test fun nodeOnlyCareRejectsTargetsInsideDisplayButOutsideHost() {
        val host = RectPx(320f, 120f, 900f, 720f)
        val bounds = captureBounds(ScreenCaptureMode.Nodes, host, RectPx(0f, 0f, 1200f, 800f))
        val scene = SceneKey("fixture.app", 7, 0, 3)
        val inside = NodeDto("snapshot:inside", RectPx(400f, 200f, 500f, 250f),
            "Inside", null, "fixture/inside", "Button", false, true, false, true, false)
        val outside = inside.copy(ref = "snapshot:outside", bounds = RectPx(40f, 200f, 140f, 250f),
            text = "Outside", viewId = "fixture/outside")
        val crossing = inside.copy(ref = "snapshot:crossing", bounds = RectPx(290f, 200f, 390f, 250f),
            text = "Crossing", viewId = "fixture/crossing")
        val snapshot = ScreenSnapshot("snapshot", 9, 1000, scene, bounds, 0,
            RectPx(320f, 120f, 300f, 80f), null, listOf(inside, outside, crossing), null,
            ScreenTransform(bounds, bounds.width.toInt(), bounds.height.toInt()))
        snapshot.use {
            val proposal = CareProposal(it.id, scene, inside.ref, CareAction.Open,
                "Open the item", null, CareExpectation.PageContains("Details"), .9f)
            val validator = CareProposalValidator()
            assertTrue("An admitted host target remains usable", validator.validate(proposal, it))
            assertFalse("An off-host node must not become a Care target merely because it fits the display",
                validator.validate(proposal.copy(targetRef = outside.ref), it))
            assertFalse("A node crossing the host edge must remain inadmissible",
                validator.validate(proposal.copy(targetRef = crossing.ref), it))
        }
    }
}
