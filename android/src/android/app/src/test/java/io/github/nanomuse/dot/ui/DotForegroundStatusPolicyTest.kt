package io.github.nanomuse.dot.ui

import io.github.nanomuse.dot.*
import org.junit.Assert.*
import org.junit.Test

class DotForegroundStatusPolicyTest {
    @Test fun careTaskStatusIsUsedOnlyWhenNoOwnerOperationNeedsAttention() {
        val care = DotForegroundState(connected = true, mode = DotForegroundMode.Care)
        assertTrue(foregroundUsesFallback(care))
        assertTrue(foregroundUsesFallback(care.copy(operation = DotForegroundOperation.CareWaiting)))
        for (operation in listOf(DotForegroundOperation.Capturing, DotForegroundOperation.Replying,
            DotForegroundOperation.Listening, DotForegroundOperation.Error)) {
            assertFalse(foregroundUsesFallback(care.copy(operation = operation)))
            assertTrue(canStopForeground(care.copy(operation = operation)))
        }
        assertFalse(foregroundUsesFallback(care.copy(connected = false)))
        assertFalse(canStopForeground(care.copy(connected = false)))
    }
    @Test fun idleConnectedChatRetainsExistingStopAdmissionRegardlessOfPresentation() {
        assertTrue(canStopForeground(DotForegroundState(connected = true, mode = DotForegroundMode.Chat)))
        assertFalse(canStopForeground(DotForegroundState(connected = true, mode = DotForegroundMode.Idle)))
    }
}
