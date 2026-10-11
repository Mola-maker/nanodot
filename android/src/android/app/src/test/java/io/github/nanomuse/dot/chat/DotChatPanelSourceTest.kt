package io.github.nanomuse.dot.chat

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Adapter integration fences, separate from native touch and window tests. */
class DotChatPanelSourceTest {
    @Test fun nativeControllerOwnsFocusablePanelAndBothOwnedWindows() {
        val source = File("src/main/java/io/github/nanomuse/dot/overlay/DotOverlayController.kt").readText()
        assertTrue("controller must provide an actual panel window",source.contains("fun showPanel("))
        assertTrue("panel and decorative window identities must be excluded along with orb",source.contains("listOfNotNull(orbView, panelView, trailView, captureScanner.view)"))
        assertTrue("native Back must distinguish keyboard from panel",source.contains("dispatchKeyEventPreIme"))
    }
    @Test fun coordinatorCapturesBeforeOpeningAndKeepsPendingCapabilitiesHonest() {
        val file = File("src/main/java/io/github/nanomuse/dot/DotInteractionCoordinator.kt")
        assertTrue("coordinator missing",file.exists())
        val source = file.readText()
        assertFalse("conversation cannot substitute an Activity",source.contains("startActivity"))
        assertTrue(source.indexOf("capture(CapturePurpose.Chat") < source.indexOf("showPanel(adapter)"))
        assertTrue(source.contains("nm_dot_action_unavailable"))
    }
}
