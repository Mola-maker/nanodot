package io.github.nanomuse.dot.voice.proof

import java.security.MessageDigest
import java.io.ByteArrayOutputStream

/** Bounded in-memory evidence for one public, process-local manual proof owner. */
internal class OwnedVoiceEvidence(
    private val adapter: Any, private val session: Any, private val binding: Any,
    private val publicTextHash: String, private val valid: () -> Boolean,
    private val clock: () -> Long = System::nanoTime,
) {
    data class Event(val kind: String, val text: String? = null,
        val captureGeneration: Long = 0, val platformGeneration: Long = 0,
        val ticketGeneration: Long = 0, val ordinal: Int = 0,
        val timeNanos: Long = 0, val threadId: Long = 0, val numbers: List<Int> = emptyList(),
        val utteranceId: String? = null)
    data class Snapshot(val events: List<Event>, val pcm: ByteArray,
        val pcmComplete: Boolean, val incomplete: Boolean)
    private val started = clock()
    private var closed = false
    private var incomplete = false
    private var player: Any? = null
    private var manager: Any? = null
    private var client: Any? = null
    private var utterance: String? = null
    private var playbackCurrent: (() -> Boolean)? = null
    private var request: Any? = null
    private var captureCurrent: (() -> Boolean)? = null
    private var ticket: Any? = null
    private var ticketGeneration = 0L
    private var engine: Any? = null
    private var captureGeneration = 0L
    private var platformGeneration = 0L
    private var returnOk = false
    private var format: List<Int>? = null
    private var done = false
    private var audioThread: Long? = null
    private var textBytes = 0
    private var pcm = ByteArrayOutputStream()
    private val events = mutableListOf<Event>()

    private inline fun guarded(action: () -> Boolean): Boolean = try { action() }
        catch (_: Throwable) { incomplete = true; false }
    private fun alive(): Boolean = guarded {
        if (closed) false else if (!valid() || clock() - started > 30_000_000_000L) {
            // Completed evidence was captured while valid; cleanup cannot reopen collection.
            if (!pcmCompleted()) incomplete = true
            false
        } else true
    }
    private fun owned(a: Any, s: Any?, b: Any?) = a === adapter && s === session && b === binding
    private fun public(text: String) = text.length <= 4096 && hash(text) == publicTextHash
    private fun add(kind: String, text: String? = null, numbers: List<Int> = emptyList()): Boolean {
        if (text != null && text.length > 4096) { incomplete = true; return false }
        val cost = text?.toByteArray(Charsets.UTF_8)?.size ?: 0
        if (events.size >= 256 || cost + textBytes + pcm.size() > MAX_BYTES) {
            incomplete = true; return false
        }
        textBytes += cost
        events.add(Event(kind, text, captureGeneration, platformGeneration, ticketGeneration,
            events.size + 1, clock(), Thread.currentThread().id, numbers.toList(),
            if (kind.startsWith("tts_")) utterance else null))
        return true
    }
    @Synchronized fun reply(a: Any, s: Any?, b: Any?, p: Any, text: String) = guarded {
        if (!alive() || !owned(a, s, b) || !public(text)) false
        else { if (player != null && player !== p) incomplete = true; player = p; true }
    }
    @Synchronized fun ownsPlayer(p: Any) = alive() && p === player
    @Synchronized fun system(p: Any, m: Any, generation: Long, current: () -> Boolean, text: String) = guarded {
        if (!ownsPlayer(p) || !current() || !public(text)) false
        else { manager = m; playbackCurrent = current; ticketGeneration = generation; true }
    }
    @Synchronized fun beforeSpeak(m: Any, c: Any, id: String, queue: Int, text: String,
        voice: () -> String?) = guarded {
        if (!alive() || m !== manager || playbackCurrent?.invoke() != true || !public(text)) false
        else {
            // A second request is ambiguous for this one-utterance proof, never silently replace it.
            if (utterance != null) { incomplete = true; return@guarded false }
            client = c; utterance = id
            add("tts_submit", voice(), listOf(queue, System.identityHashCode(c),
                System.identityHashCode(player), System.identityHashCode(manager)))
        }
    }
    @Synchronized fun ownsTts(m: Any, c: Any, id: String?) = guarded {
        alive() && m === manager && c === client && id != null && id == utterance && playbackCurrent?.invoke() == true
    }
    @Synchronized fun speakReturn(m: Any, c: Any, id: String, rc: Int) {
        guarded {
            if (!ownsTts(m, c, id)) false
            else { returnOk = rc == 0; if (!returnOk) incomplete = true; add("tts_return", numbers = listOf(rc)) }
        }
    }
    @Synchronized fun tts(m: Any, c: Any, id: String?, kind: String, values: IntArray? = null) {
        guarded {
            if (!ownsTts(m, c, id)) false else {
                when (kind) {
                    "format" -> {
                        val next = values?.toList()
                        if (next == null || next.size != 3 || next[0] <= 0 || next[1] !in listOf(2, 3, 4) || next[2] !in 1..2 ||
                            (format != null && format != next) || pcm.size() > 0) incomplete = true
                        else format = next
                    }
                    "done" -> { if (done || format == null || pcm.size() == 0) incomplete = true; done = true }
                    "error", "stop" -> incomplete = true
                }
                add("tts_$kind", numbers = values?.toList().orEmpty())
            }
        }
    }
    @Synchronized fun audio(m: Any, c: Any, id: String?, bytes: ByteArray?) {
        guarded {
            if (!ownsTts(m, c, id)) false else if (format == null || done || bytes == null ||
                bytes.size.toLong() + pcm.size() + textBytes > MAX_BYTES) { incomplete = true; false }
            else {
                val thread = Thread.currentThread().id
                if (audioThread != null && audioThread != thread) incomplete = true
                audioThread = thread
                // write copies only this owner's real callback bytes; no microphone or output mix.
                pcm.write(bytes)
                add("tts_audio", numbers = listOf(bytes.size))
            }
        }
    }
    @Synchronized fun captureRequest(a: Any, s: Any?, b: Any?, r: Any, current: () -> Boolean) = guarded {
        if (!alive() || !owned(a, s, b) || !current() || request != null) false
        else { request = r; captureCurrent = current; true }
    }
    @Synchronized fun captureTicket(a: Any, b: Any?, t: Any, generation: Long) = guarded {
        if (!alive() || a !== adapter || b !== binding || request == null ||
            captureCurrent?.invoke() != true || ticket != null) false
        else { ticket = t; ticketGeneration = generation; true }
    }
    @Synchronized fun captureEngine(e: Any) = guarded {
        if (!alive() || ticket == null || captureCurrent?.invoke() != true) false
        else { if (engine != null && engine !== e) incomplete = true; engine = e; true }
    }
    @Synchronized fun captureBegin(e: Any, generation: Long) {
        guarded {
            if (!alive() || e !== engine || captureCurrent?.invoke() != true) false
            else { captureGeneration = generation; platformGeneration = 0; true }
        }
    }
    @Synchronized fun factory(e: Any, capture: Long, platform: Long, actualClient: Any, route: String) {
        guarded {
            if (!alive() || e !== engine || capture != captureGeneration || captureCurrent?.invoke() != true) false
            else { platformGeneration = platform; add("asr_factory", route, listOf(System.identityHashCode(actualClient))) }
        }
    }
    @Synchronized fun ownsCapture(e: Any, capture: Long, platform: Long) = guarded {
        alive() && e === engine && capture == captureGeneration && platform == platformGeneration &&
            platform != 0L && captureCurrent?.invoke() == true
    }
    @Synchronized fun asr(e: Any, capture: Long, platform: Long, kind: String, text: () -> String?) {
        guarded { if (!ownsCapture(e, capture, platform)) false else add(kind, text()) }
    }
    private fun pcmCompleted(): Boolean {
        val alignment = format?.let { (if (it[1] == 2) 2 else if (it[1] == 4) 4 else 1) * it[2] } ?: 0
        return !closed && !incomplete && returnOk && done && alignment > 0 &&
            pcm.size() > 0 && pcm.size() % alignment == 0
    }
    @Synchronized fun snapshot() = Snapshot(events.toList(), pcm.toByteArray(), pcmCompleted(), incomplete)
    @Synchronized fun close() {
        // Drop the owned backing allocation as well as its logical length after resource join.
        closed = true; pcm = ByteArrayOutputStream(0); events.clear()
        player = null; manager = null; client = null; utterance = null; playbackCurrent = null
        request = null; captureCurrent = null; ticket = null; engine = null
    }
    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
