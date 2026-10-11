package io.github.nanomuse.dot.chat

data class SessionBinding(val sessionId: String, val generation: Long)

/** Admission boundary for a panel; canonical aliases are resolved at comparison time. */
class DotSessionBinding(private val canonical: (String) -> String) {
    private var generation = 0L
    var current: SessionBinding? = null
        private set

    fun bind(sessionId: String): SessionBinding {
        current?.let { if (canonical(it.sessionId) == canonical(sessionId)) return it }
        return SessionBinding(sessionId, ++generation).also { current = it }
    }

    fun accepts(binding: SessionBinding): Boolean = current?.let {
        it.generation == binding.generation && canonical(it.sessionId) == canonical(binding.sessionId)
    } == true

    fun cancel(binding: SessionBinding) {
        if (accepts(binding)) current = null
    }
}
