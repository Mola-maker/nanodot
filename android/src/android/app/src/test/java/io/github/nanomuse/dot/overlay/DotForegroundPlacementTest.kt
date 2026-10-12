package io.github.nanomuse.dot.overlay

import io.github.nanomuse.dot.screen.RectPx
import org.junit.Assert.*
import org.junit.Test

class DotForegroundPlacementTest {
    @Test fun bottomAboveAndCompactAreExplicitAndBounded() {
        val host = RectPx(120f, 70f, 720f, 870f)
        assertEquals(ForegroundPlacement.Bottom, foregroundFrame(host,320f,1f).placement)
        val above = foregroundFrame(host,320f,1f,RectPx(120f,650f,720f,870f))
        assertEquals(ForegroundPlacement.AboveTarget,above.placement)
        assertTrue(above.bounds.bottom < 650f)
        val compact = foregroundFrame(host,320f,1f,host)
        assertEquals(ForegroundPlacement.Compact,compact.placement)
        assertTrue(compact.bounds.top >= host.top && compact.bounds.bottom <= host.bottom)
        assertTrue(compact.bounds.width < host.width)
    }
    @Test fun invalidTargetsAndLandscapeImeOriginsRetainBoundedPlacement() {
        for(host in listOf(RectPx(420f,24f,1020f,280f),RectPx(0f,24f,840f,300f))) {
            val ordinary=foregroundFrame(host,320f,2f)
            assertEquals(ordinary,foregroundFrame(host,320f,2f,RectPx(Float.NaN,0f,100f,100f)))
            val compact=foregroundFrame(host,320f,2f,host)
            assertEquals(ForegroundPlacement.Compact,compact.placement)
            assertTrue(compact.bounds.left>=host.left && compact.bounds.right<=host.right)
            assertTrue(compact.bounds.top>=host.top && compact.bounds.bottom<=host.bottom)
        }
    }
    @Test fun statusUsesOwnerEvidenceAndDoesNotInventReadiness() {
        val idle = io.github.nanomuse.dot.DotForegroundState(connected = true, mode = io.github.nanomuse.dot.DotForegroundMode.Chat)
        assertEquals(com.openminis.app.R.string.nm_status_waiting, io.github.nanomuse.dot.ui.foregroundStatusResource(idle))
        assertEquals(com.openminis.app.R.string.nm_dot_foreground_error, io.github.nanomuse.dot.ui.foregroundStatusResource(idle.copy(operation = io.github.nanomuse.dot.DotForegroundOperation.Error)))
        assertEquals(com.openminis.app.R.string.nm_status_replying, io.github.nanomuse.dot.ui.foregroundStatusResource(idle.copy(operation = io.github.nanomuse.dot.DotForegroundOperation.Replying)))
        for (operation in io.github.nanomuse.dot.DotForegroundOperation.entries) {
            val disconnected = idle.copy(connected = false, operation = operation)
            assertEquals(com.openminis.app.R.string.nm_dot_foreground_disconnected, io.github.nanomuse.dot.ui.foregroundStatusResource(disconnected))
            assertFalse(io.github.nanomuse.dot.ui.canStopForeground(disconnected))
        }
        assertTrue(io.github.nanomuse.dot.ui.canStopForeground(idle))
        assertFalse(io.github.nanomuse.dot.ui.canStopForeground(idle.copy(mode = io.github.nanomuse.dot.DotForegroundMode.Idle)))
    }
}
