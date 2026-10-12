package io.github.nanomuse.dot.screen

import org.junit.Assert.assertEquals
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
        assertEquals(ScreenCaptureMode.Display, screenCaptureMode(35, CapturePurpose.Selection, true))
        for (purpose in CapturePurpose.entries) {
            assertEquals(ScreenCaptureMode.Nodes, screenCaptureMode(35, purpose, false))
        }
    }
}
