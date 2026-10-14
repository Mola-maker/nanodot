package io.github.nanomuse.dot.voice

/** In-memory capability shared by the permission Activity and microphone service. Main-thread only. */
class VoiceStartGate {
    class Request internal constructor(val id: String, val valid: () -> Boolean) {
        internal var visible = false
        internal var starting = false
        internal var admitted = false
        internal var revoked = false
    }
    private var active: Request? = null
    fun request(valid: () -> Boolean): Request? {
        if (active != null || !valid()) return null
        return Request(java.util.UUID.randomUUID().toString(), valid).also { active = it }
    }
    fun find(id: String?): Request? = active?.takeIf { it.id == id && !it.revoked && it.valid() }
    fun visible(request: Request, visible: Boolean) {
        if (active === request) request.visible = visible
    }
    fun begin(request: Request, permitted: Boolean): Boolean {
        if (find(request.id) !== request || !request.visible || !permitted || request.starting || request.admitted) return false
        request.starting = true
        return true
    }
    fun admit(id: String?, permitted: Boolean): Request? = find(id)?.takeIf {
        it.visible && it.starting && !it.admitted && permitted
    }?.also { it.admitted = true }
    /** An admitted request remains occupied until its resource completion, even after cancellation. */
    fun revoke(request: Request) { if (active === request) request.revoked = true }
    fun complete(request: Request) { if (active === request) active = null }
}
