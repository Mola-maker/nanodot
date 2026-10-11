package io.github.nanomuse.dot.voice

/** Process-local exclusive capture admission. Async work must retain its ticket, not an owner string. */
class VoiceLease {
    class Ticket internal constructor(val owner: String, val generation: Long)
    private var generation = 0L
    private var active: Ticket? = null
    private var compatibility: Ticket? = null

    @Synchronized fun acquire(owner: String): Boolean {
        val ticket = acquireTicket(owner) ?: return false
        compatibility = ticket
        return true
    }
    /** Compatibility release cannot release a ticket acquired through the asynchronous API. */
    @Synchronized fun release(owner: String) {
        compatibility?.takeIf { it.owner == owner }?.let { release(it) }
    }
    @Synchronized fun acquireTicket(owner: String): Ticket? {
        require(owner.isNotBlank())
        if (active != null) return null
        return Ticket(owner, ++generation).also { active = it }
    }
    @Synchronized fun release(ticket: Ticket) {
        if (active !== ticket) return
        active = null
        if (compatibility === ticket) compatibility = null
    }
    @Synchronized fun owns(ticket: Ticket): Boolean = active === ticket
}
