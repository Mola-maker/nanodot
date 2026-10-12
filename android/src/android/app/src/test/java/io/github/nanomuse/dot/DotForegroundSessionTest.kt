package io.github.nanomuse.dot

import org.junit.Assert.*
import org.junit.Test
import io.github.nanomuse.dot.chat.SessionBinding

class DotForegroundSessionTest {
    @Test fun staleTicketRejectedAfterStopAccountAndReconnect() {
        val owner = DotForegroundSession()
        assertFalse(owner.accepts(owner.ticket()))
        owner.connect()
        val first = owner.ticket()
        assertTrue(owner.accepts(first))
        owner.invalidate()
        assertFalse(owner.accepts(first))
        val stopped = owner.ticket()
        owner.accountChanged()
        assertFalse(owner.accepts(stopped))
        val account = owner.ticket()
        owner.disconnect(); owner.connect()
        assertFalse(owner.accepts(account))
        assertTrue(owner.accepts(owner.ticket()))
    }
    @Test fun disconnectIsIdempotent() {
        val owner = DotForegroundSession(); owner.connect()
        owner.update(DotForegroundMode.Chat, SessionBinding("local", 1), DotForegroundOperation.Replying)
        owner.disconnect()
        val disconnected = owner.state.value
        owner.disconnect()
        assertEquals(disconnected, owner.state.value)
        assertNull(disconnected.binding)
        assertEquals(DotForegroundOperation.Idle, disconnected.operation)
    }
    @Test fun presentationDoesNotInvalidateAdmission() {
        val owner = DotForegroundSession(); owner.connect()
        val ticket = owner.ticket()
        owner.update(DotForegroundMode.Screen, SessionBinding("local", 1), DotForegroundOperation.Capturing)
        assertTrue(owner.accepts(ticket))
        owner.update(DotForegroundMode.Chat, SessionBinding("local", 1), DotForegroundOperation.Replying)
        assertTrue(owner.accepts(ticket))
    }
}
