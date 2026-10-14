package io.github.nanomuse.dot.chat

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Adapter integration fences, separate from native touch and window tests. */
class DotChatPanelSourceTest {
    @Test fun nativeControllerKeepsPanelAndPreImeBackEntryPoints() {
        val source = File("src/main/java/io/github/nanomuse/dot/overlay/DotOverlayController.kt").readText()
        assertTrue("controller must provide an actual panel window",source.contains("fun showPanel("))
        // Actual orb/panel/menu identities and capture exclusion are checked by native window tests.
        // A literal listOfNotNull source fragment cannot establish runtime ownership.
        assertTrue("native Back must distinguish keyboard from panel",source.contains("dispatchKeyEventPreIme"))
    }
    @Test fun coordinatorCapturesBeforeOpeningAndKeepsPendingCapabilitiesHonest() {
        val file = File("src/main/java/io/github/nanomuse/dot/DotInteractionCoordinator.kt")
        assertTrue("coordinator missing",file.exists())
        val source = file.readText()
        assertFalse("conversation cannot substitute an Activity",source.contains("startActivity"))
        assertTrue(source.indexOf("capture(CapturePurpose.Chat") < source.indexOf("showPanel(adapter,"))
        assertTrue(source.contains("nm_dot_action_unavailable"))
    }
}
