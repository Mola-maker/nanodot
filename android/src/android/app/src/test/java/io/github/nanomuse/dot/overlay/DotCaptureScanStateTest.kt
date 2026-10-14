package io.github.nanomuse.dot.overlay

import org.junit.Assert.*
import org.junit.Test

class DotCaptureScanStateTest {
    @Test fun acceptedGenerationOwnsTheScanUntilItsActualOutcome() {
        val ticket = CaptureScanTicket()
        assertEquals(4L, ticket.begin(4L))
        assertEquals(4L, ticket.activeGeneration)
        assertNull(ticket.success)
        assertNull(ticket.begin(4L))
        assertNull(ticket.begin(5L))
        assertEquals(4L, ticket.activeGeneration)
    }

    @Test fun onlyTheActiveGenerationCanFinishAScan() {
        val ticket = CaptureScanTicket()
        ticket.begin(7L)
        assertFalse(ticket.finish(6L, true))
        assertEquals(7L, ticket.activeGeneration)
        assertNull(ticket.success)
        assertTrue(ticket.finish(7L, true))
        assertNull(ticket.activeGeneration)
        assertEquals(true, ticket.success)
    }

    @Test fun completedGenerationsCannotBeReadmittedAsFreshScans() {
        val ticket = CaptureScanTicket()
        ticket.begin(7L)
        ticket.finish(7L, true)
        assertNull(ticket.begin(7L))
        assertNull(ticket.begin(6L))
        assertEquals(8L, ticket.begin(8L))
        assertEquals(8L, ticket.activeGeneration)
        assertNull(ticket.success)
    }

    @Test fun failedOutcomeAllowsANewRetryGeneration() {
        val ticket = CaptureScanTicket()
        ticket.begin(7L)
        assertTrue(ticket.finish(7L, false))
        assertEquals(false, ticket.success)
        assertNull(ticket.activeGeneration)
        assertNull(ticket.begin(7L))
        assertEquals(8L, ticket.begin(8L))
    }

    @Test fun canceledCompletionCannotFinishANewerScan() {
        val ticket = CaptureScanTicket()
        ticket.begin(3L)
        ticket.cancel()
        assertNull(ticket.activeGeneration)
        assertNull(ticket.success)
        assertNull(ticket.begin(3L))
        assertEquals(4L, ticket.begin(4L))
        assertFalse(ticket.finish(3L, true))
        assertEquals(4L, ticket.activeGeneration)
        assertNull(ticket.success)
        assertTrue(ticket.finish(4L, false))
        assertEquals(false, ticket.success)
    }

    @Test fun duplicateCompletionCannotOverwriteTheRecordedOutcome() {
        val ticket = CaptureScanTicket()
        ticket.begin(1L)
        ticket.finish(1L, true)
        assertFalse(ticket.finish(1L, false))
        assertEquals(true, ticket.success)
    }

    @Test fun cancellationClearsRecordedOutcomeWithoutReusingItsGeneration() {
        val ticket = CaptureScanTicket()
        ticket.begin(10L)
        ticket.finish(10L, true)
        ticket.cancel()
        assertNull(ticket.success)
        assertNull(ticket.begin(10L))
        assertEquals(11L, ticket.begin(11L))
    }

    @Test fun monotonicAdmissionDoesNotIncrementOrWrapExternalGenerations() {
        val ticket = CaptureScanTicket()
        assertEquals(Long.MIN_VALUE, ticket.begin(Long.MIN_VALUE))
        ticket.cancel()
        assertEquals(Long.MAX_VALUE, ticket.begin(Long.MAX_VALUE))
        ticket.cancel()
        assertNull(ticket.begin(Long.MIN_VALUE))
    }
}
