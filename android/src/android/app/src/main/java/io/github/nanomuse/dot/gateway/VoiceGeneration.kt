package io.github.nanomuse.dot.gateway

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Invalidates in-flight utterances when playback is stopped or paused. */
class VoiceGeneration {
    private val current = AtomicLong()
    fun ticket(): Long = current.get()
    fun invalidate() { current.incrementAndGet() }
    fun requireCurrent(ticket: Long) {
        if (current.get() != ticket) throw CancellationException("Voice utterance superseded")
    }
    fun isCurrent(ticket: Long): Boolean = current.get() == ticket
}
