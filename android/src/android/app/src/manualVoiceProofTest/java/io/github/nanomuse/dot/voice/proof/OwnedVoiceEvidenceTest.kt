package io.github.nanomuse.dot.voice.proof

import org.junit.Assert.*
import org.junit.Test

/** Catches collection outside the owner, lost provenance, and unbounded/incomplete PCM. */
class OwnedVoiceEvidenceTest {
    private val adapter = Any()
    private val session = Any()
    private val binding = Any()
    private val player = Any()
    private val manager = Any()
    private val client = Any()
    private val engine = Any()
    private val ticket = Any()
    private val request = Any()
    private val text = "今天下午三点提醒我喝水。"
    private val hash = "037543f0d6ab9de2e1382f5ac2cf1784f267c11214603cd1f320e1cdb4753546"
    private var time = 0L
    private var valid = true
    private fun owner() = OwnedVoiceEvidence(adapter, session, binding,
        hash, { valid }, { time })
    private fun speak(e: OwnedVoiceEvidence) {
        assertTrue(e.reply(adapter, session, binding, player, text))
        assertTrue(e.system(player, manager, 7, { valid }, text))
        assertTrue(e.beforeSpeak(manager, client, "original-id", 0, text) { "actual-voice" })
        e.speakReturn(manager, client, "original-id", 0)
    }
    @Test fun otherOwnerDoesNotEvaluateVoiceOrRetainPayload() {
        val e = owner()
        var reads = 0
        assertFalse(e.reply(Any(), session, binding, player, text))
        assertFalse(e.beforeSpeak(manager, client, "unowned", 0, text) { reads++; "wrong" })
        assertEquals(0, reads)
        assertTrue(e.snapshot().events.isEmpty())
    }
    @Test fun sameClientOriginalIdCollectsOrderedCopiedPcmAndCompletes() {
        val e = owner(); speak(e)
        e.tts(manager, client, "original-id", "format", intArrayOf(16000, 2, 1))
        val bytes = byteArrayOf(1, 2, 3, 4)
        e.audio(manager, client, "original-id", bytes)
        bytes[0] = 99
        e.tts(manager, client, "original-id", "done")
        val s = e.snapshot()
        assertTrue(s.pcmComplete)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), s.pcm)
        assertEquals(listOf("tts_submit", "tts_return", "tts_format", "tts_audio", "tts_done"),
            s.events.map { it.kind })
        assertEquals("actual-voice", s.events.first().text)
    }
    @Test fun unboundIdAndClientCannotCompleteOwnedSpeak() {
        val e = owner(); speak(e)
        e.speakReturn(manager, Any(), "original-id", 0)
        e.tts(manager, client, "different-id", "format", intArrayOf(16000, 2, 1))
        e.audio(manager, client, "different-id", byteArrayOf(1, 2))
        e.tts(manager, client, "different-id", "done")
        assertFalse(e.snapshot().pcmComplete)
        assertEquals(2, e.snapshot().events.size)
    }
    @Test fun revocationAndDeadlineRejectLateCallbacksAndClearOnClose() {
        val e = owner(); speak(e)
        valid = false
        e.audio(manager, client, "original-id", byteArrayOf(1, 2))
        assertFalse(e.snapshot().pcmComplete)
        e.close()
        valid = true
        assertFalse(e.reply(adapter, session, binding, player, text))
        assertTrue(e.snapshot().events.isEmpty())
        val timed = owner(); speak(timed); time = 30_000_000_001L
        timed.audio(manager, client, "original-id", byteArrayOf(1, 2))
        assertTrue(timed.snapshot().incomplete)
        assertEquals(0, timed.snapshot().pcm.size)
    }
    @Test fun overflowAndMissingFormatRemainIncomplete() {
        val e = owner(); speak(e)
        e.audio(manager, client, "original-id", byteArrayOf(1, 2))
        e.tts(manager, client, "original-id", "done")
        assertTrue(e.snapshot().incomplete)
        val full = owner(); speak(full)
        full.tts(manager, client, "original-id", "format", intArrayOf(16000, 2, 1))
        full.audio(manager, client, "original-id", ByteArray(8 * 1024 * 1024 + 1))
        assertTrue(full.snapshot().incomplete)
        assertEquals(0, full.snapshot().pcm.size)
    }
    @Test fun metadataExceptionIsContainedAndMarksEvidenceIncomplete() {
        val e = owner()
        e.reply(adapter, session, binding, player, text)
        e.system(player, manager, 7, { valid }, text)
        e.beforeSpeak(manager, client, "original-id", 0, text) { error("observer only") }
        assertTrue(e.snapshot().incomplete)
    }
    @Test fun platformFinalAndApplicationSalvageKeepSeparateOriginsAndGenerations() {
        val e = owner()
        assertTrue(e.captureRequest(adapter, session, binding, request) { valid })
        assertTrue(e.captureTicket(adapter, binding, ticket, 12))
        assertTrue(e.captureEngine(engine))
        e.captureBegin(engine, 3)
        e.factory(engine, 3, 5, Any(), "regular_fallback")
        var reads = 0
        e.asr(engine, 3, 4, "platform_results") { reads++; "stale" }
        e.asr(engine, 3, 5, "platform_results") { reads++; "" }
        e.asr(engine, 3, 5, "application_error_salvage") { reads++; text }
        val s = e.snapshot()
        assertEquals(2, reads)
        assertEquals(listOf("asr_factory", "platform_results", "application_error_salvage"),
            s.events.map { it.kind })
        assertEquals("regular_fallback", s.events.first().text)
        assertEquals("", s.events[1].text)
        assertEquals(text, s.events[2].text)
        assertEquals(listOf(5L, 5L, 5L), s.events.map { it.platformGeneration })
        assertEquals(listOf(3L, 3L, 3L), s.events.map { it.captureGeneration })
    }
    @Test fun malformedFormatCannotEscapeOrProduceCompletePcm() {
        val e = owner(); speak(e)
        e.tts(manager, client, "original-id", "format", intArrayOf(0))
        assertTrue(e.snapshot().incomplete)
        assertFalse(e.snapshot().pcmComplete)
    }
    @Test fun callbacksFromDifferentPcmThreadsRemainIncomplete() {
        val e = owner(); speak(e)
        e.tts(manager, client, "original-id", "format", intArrayOf(16000, 2, 1))
        e.audio(manager, client, "original-id", byteArrayOf(1, 2))
        Thread { e.audio(manager, client, "original-id", byteArrayOf(3, 4)) }.also { it.start(); it.join() }
        e.tts(manager, client, "original-id", "done")
        assertTrue(e.snapshot().incomplete)
        assertFalse(e.snapshot().pcmComplete)
    }
    @Test fun sameUtteranceIdFromOtherActualClientIsNotCollected() {
        val e = owner(); speak(e)
        e.tts(manager, Any(), "original-id", "format", intArrayOf(16000, 2, 1))
        assertEquals(listOf("tts_submit", "tts_return"), e.snapshot().events.map { it.kind })
        assertFalse(e.snapshot().pcmComplete)
    }
    @Test fun completedOwnedPcmRemainsEvidenceDuringRevokedCleanupWithoutLateCollection() {
        val e = owner(); speak(e)
        e.tts(manager, client, "original-id", "format", intArrayOf(16000, 2, 1))
        e.audio(manager, client, "original-id", byteArrayOf(1, 2))
        e.tts(manager, client, "original-id", "done")
        valid = false; time = 40_000_000_000L
        e.audio(manager, client, "original-id", byteArrayOf(3, 4))
        val s = e.snapshot()
        assertTrue(s.pcmComplete)
        assertArrayEquals(byteArrayOf(1, 2), s.pcm)
        assertEquals(5, s.events.size)
        e.close()
        assertEquals(0, e.snapshot().pcm.size)
    }
}
