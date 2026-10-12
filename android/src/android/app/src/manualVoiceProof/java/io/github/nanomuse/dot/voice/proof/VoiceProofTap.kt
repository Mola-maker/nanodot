package io.github.nanomuse.dot.voice.proof

import android.app.Application
import android.app.Instrumentation
import android.os.Looper
import android.os.Process
import android.speech.tts.TextToSpeech
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.SpeechRecognitionEngine
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.chat.SessionBinding
import io.github.nanomuse.dot.voice.DotVoiceController
import io.github.nanomuse.dot.voice.VoiceLease
import io.github.nanomuse.dot.voice.VoiceStartGate

/** Exists only in an explicitly selected manual-proof debug build. No transport or recording API. */
internal object VoiceProofTap {
    internal class Owner internal constructor(
        val adapter: DotChatAdapter,
        val evidence: OwnedVoiceEvidence,
    ) {
        @Volatile var revoked = false
        @Volatile var request: VoiceStartGate.Request? = null
        @Volatile var ticket: VoiceLease.Ticket? = null
        @Volatile var player: ReadAloudPlayer? = null
    }
    @Volatile private var owner: Owner? = null
    val active: Boolean get() = owner?.revoked == false

    /** Fixture calls on main, with the real running instrumentation and frozen public session. */
    fun register(instrumentation: Instrumentation, adapter: DotChatAdapter,
        binding: SessionBinding, publicTextHash: String): Owner {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(generateSequence(instrumentation.javaClass as Class<*>?) { it.superclass }
            .any { it.name == "androidx.test.runner.AndroidJUnitRunner" })
        val context = instrumentation.targetContext.applicationContext
        check(context is Application && Application.getProcessName() == context.packageName &&
            context.applicationInfo.uid == Process.myUid())
        check(owner == null && adapter.binding.value === binding && adapter.accepts(binding))
        val session = checkNotNull(adapter.session)
        require(publicTextHash.matches(Regex("[0-9a-f]{64}")))
        lateinit var next: Owner
        val evidence = OwnedVoiceEvidence(adapter, session, binding, publicTextHash, {
            !next.revoked && adapter.binding.value === binding && adapter.session === session &&
                adapter.accepts(binding)
        })
        next = Owner(adapter, evidence)
        owner = next
        return next
    }
    fun invalidate(capability: Owner) {
        check(owner === capability)
        capability.revoked = true
    }
    fun snapshot(capability: Owner): OwnedVoiceEvidence.Snapshot {
        check(owner === capability)
        return capability.evidence.snapshot()
    }
    /** Invalidate, cancel/join actual resources, then detach; a released ticket cannot be reclaimed. */
    fun unregister(capability: Owner) {
        check(Looper.myLooper() == Looper.getMainLooper() && owner === capability && capability.revoked)
        check(capability.ticket?.completion?.isCompleted != false)
        check(capability.player?.hasPendingCaptureCleanup != true)
        owner = null
        capability.evidence.close()
        capability.request = null; capability.ticket = null; capability.player = null
    }
    fun reply(adapter: DotChatAdapter, player: ReadAloudPlayer, text: String) {
        val o = owner?.takeUnless { it.revoked } ?: return
        if (o.evidence.reply(adapter, adapter.session, adapter.binding.value, player, text)) o.player = player
    }
    fun ownsPlayer(player: Any) = owner?.takeUnless { it.revoked }?.evidence?.ownsPlayer(player) == true
    fun system(player: Any, manager: Any, generation: Long, current: () -> Boolean, text: String) {
        owner?.takeUnless { it.revoked }?.evidence?.system(player, manager, generation, current, text)
    }
    fun beforeSpeak(manager: Any, client: TextToSpeech, id: String, queue: Int, text: String) {
        owner?.takeUnless { it.revoked }?.evidence?.beforeSpeak(manager, client, id, queue, text) {
            client.voice?.let { v -> "${v.name}|${v.locale.toLanguageTag()}|${v.quality}|${v.latency}|${v.isNetworkConnectionRequired}" }
        }
    }
    fun speakReturn(manager: Any, client: TextToSpeech, id: String, rc: Int) {
        owner?.takeUnless { it.revoked }?.evidence?.speakReturn(manager, client, id, rc)
    }
    fun ownsTts(manager: Any, client: Any, id: String?) = owner?.takeUnless { it.revoked }?.evidence?.ownsTts(manager, client, id) == true
    fun tts(manager: Any, client: Any, id: String?, kind: String, values: IntArray? = null) {
        owner?.takeUnless { it.revoked }?.evidence?.tts(manager, client, id, kind, values)
    }
    fun audio(manager: Any, client: Any, id: String?, bytes: ByteArray?) {
        owner?.takeUnless { it.revoked }?.evidence?.audio(manager, client, id, bytes)
    }
    fun captureRequest(adapter: DotChatAdapter, binding: SessionBinding,
        request: VoiceStartGate.Request, controller: DotVoiceController) {
        val o = owner?.takeUnless { it.revoked } ?: return
        if (adapter.voice !== controller) return
        if (o.evidence.captureRequest(adapter, adapter.session, binding, request) {
                !request.revoked && request.admitted && request.valid() && adapter.voice === controller
            }) o.request = request
    }
    fun captureTicket(adapter: DotChatAdapter, binding: SessionBinding,
        request: VoiceStartGate.Request, ticket: VoiceLease.Ticket) {
        val o = owner?.takeUnless { it.revoked } ?: return
        if (o.request !== request || ticket.owner != "dot") return
        if (o.evidence.captureTicket(adapter, binding, ticket, ticket.generation)) o.ticket = ticket
    }
    fun captureEngine(captureOwner: String, engine: SpeechRecognitionEngine) {
        val o = owner?.takeUnless { it.revoked } ?: return
        if (captureOwner != "dot" || o.ticket == null || o.ticket?.completion?.isCompleted != false) return
        o.evidence.captureEngine(engine)
    }
    fun captureBegin(engine: Any, generation: Long) {
        owner?.takeUnless { it.revoked }?.evidence?.captureBegin(engine, generation)
    }
    fun factory(engine: Any, capture: Long, platform: Long, actualClient: Any, route: String) {
        owner?.takeUnless { it.revoked }?.evidence?.factory(engine, capture, platform, actualClient, route)
    }
    fun ownsCapture(engine: Any, capture: Long, platform: Long) = owner?.takeUnless { it.revoked }
        ?.let { it.ticket?.completion?.isCompleted == false && it.evidence.ownsCapture(engine, capture, platform) } == true
    fun asr(engine: Any, capture: Long, platform: Long, kind: String, text: () -> String?) {
        owner?.takeUnless { it.revoked }?.evidence?.asr(engine, capture, platform, kind, text)
    }
}
