package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.SceneKey

internal data class CareInstructionIdentity(val sessionStep: String, val scene: SceneKey?)
internal data class CareInstructionFrame(val alpha: Float, val graphemes: Int, val finished: Boolean)

/** Finite presentation only: expiration never emits a controller action or completion. */
internal class CareInstructionTimeline(val count: Int, val motion: Boolean) {
    private val appear = if (motion) 120L else 0L
    private val reveal = if (motion) (count.toLong().coerceAtLeast(0) * 36L).coerceAtMost(1800L) else 0L
    private val hold = (count.toLong().coerceAtLeast(0) * 65L).coerceIn(2400L, 6000L)
    private val fade = if (motion) 180L else 0L
    val duration = appear + reveal + hold + fade
    fun frame(elapsed: Long): CareInstructionFrame {
        val time = elapsed.coerceAtLeast(0L)
        if (time >= duration) return CareInstructionFrame(0f, count, true)
        val alpha = when {
            time < appear -> time.toFloat() / appear
            time > duration - fade && fade > 0 -> (duration - time).toFloat() / fade
            else -> 1f
        }
        val visible = if (reveal == 0L) count else
            (((time - appear).coerceAtLeast(0L).toDouble() / reveal) * count).toInt().coerceIn(0, count)
        return CareInstructionFrame(alpha, visible, false)
    }
}

/** A measured cue is admitted once per current scene/session/step; replay is explicit. */
internal class CareInstructionPlayback {
    var identity: CareInstructionIdentity? = null
        private set
    var startedAt: Long? = null
        private set
    private var consumed = false
    fun bind(next: CareInstructionIdentity) {
        if (identity == next) return
        identity = next; startedAt = null; consumed = false
    }
    fun admit(key: CareInstructionIdentity, now: Long): Boolean {
        if (identity != key || consumed) return false
        startedAt = now; consumed = true; return true
    }
    fun replay(key: CareInstructionIdentity, now: Long): Boolean {
        if (identity != key) return false
        startedAt = now; consumed = true; return true
    }
    fun retire() { startedAt = null; consumed = true }
    fun clear() { identity = null; startedAt = null; consumed = false }
}
