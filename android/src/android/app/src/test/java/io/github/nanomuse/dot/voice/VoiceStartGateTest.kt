package io.github.nanomuse.dot.voice
import org.junit.Assert.*
import org.junit.Test
class VoiceStartGateTest {
    @Test fun rejectsForgedMissingAndRestoredRequests() {
        val g = VoiceStartGate(); g.request { true }
        assertNull(g.find(null)); assertNull(g.admit("forged", true)); assertNull(VoiceStartGate().admit("restored", true))
    }
    @Test fun permissionAndVisibleActivityRequiredAtBothBoundaries() {
        val g = VoiceStartGate(); val r = g.request { true }!!
        assertFalse(g.begin(r, true)); g.visible(r, true)
        assertFalse(g.begin(r, false)); assertTrue(g.begin(r, true))
        g.visible(r, false); assertNull(g.admit(r.id, true))
    }
    @Test fun bindingRevocationAndPendingCancelCannotAdmit() {
        val g = VoiceStartGate(); var valid = true; val r = g.request { valid }!!
        g.visible(r, true); assertTrue(g.begin(r, true)); valid = false
        assertNull(g.admit(r.id, true)); g.revoke(r); assertNull(g.admit(r.id, true))
    }
    @Test fun oneShotCleanupAndOldStopCannotRevokeNew() {
        val g = VoiceStartGate(); val a = g.request { true }!!
        g.visible(a, true); g.begin(a, true); assertSame(a, g.admit(a.id, true))
        assertNull(g.admit(a.id, true)); g.revoke(a); assertNull(g.request { true })
        g.complete(a); val b = g.request { true }!!
        g.revoke(a); g.complete(a); assertSame(b, g.find(b.id))
    }
}
