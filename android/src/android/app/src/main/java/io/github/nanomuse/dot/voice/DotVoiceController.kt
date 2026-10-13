package io.github.nanomuse.dot.voice

import io.github.nanomuse.dot.voice.proof.VoiceProofTap

import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.chat.SessionBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Actual overlay lifetime. Platform components supply visibility/promotion, never capture authorization. */
class DotVoiceController(
    private val adapter: DotChatAdapter,
    val gate: VoiceStartGate,
    private val selection: () -> VoiceSelection,
    private val launchPermission: (VoiceStartGate.Request, DotVoiceController) -> Unit,
    private val permissionWindow: (Boolean) -> Unit = {},
    private val released: (VoiceStartGate.Request) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    enum class Phase { IDLE, PERMISSION, STARTING, RECORDING, FINISHING, DENIED, BUSY, ERROR }
    data class Ui(val phase: Phase = Phase.IDLE, val error: RecognitionError? = null, val detail: String? = null)
    val ui = MutableStateFlow(Ui())
    private var request: VoiceStartGate.Request? = null
    private var binding: SessionBinding? = null
    private var pinned: VoiceSelection? = null
    private var capture: VoiceLease.Ticket? = null
    private var session: DotVoiceSession? = null
    private var deadline: Job? = null
    private var endForeground: (() -> Unit)? = null
    private var finish: Ui = Ui()

    /** Shared by the composer and explicit orb microphone action; never by permission return. */
    fun primaryAction() {
        when (ui.value.phase) {
            Phase.RECORDING -> stop()
            Phase.PERMISSION, Phase.STARTING, Phase.FINISHING -> cancel()
            else -> { adapter.stopReply(); start() }
        }
    }

    /** Called only by an explicit microphone action; permission return cannot create a request. */
    fun start() {
        if (request != null) return
        if (SpeechRecognitionManager.occupied()) { ui.value = Ui(Phase.BUSY); return }
        val target = adapter.binding.value ?: return
        val choice = try { selection() } catch (_: Exception) { ui.value = Ui(Phase.ERROR); return }
        val next = gate.request { adapter.accepts(target) } ?: run { ui.value = Ui(Phase.BUSY); return }
        binding = target; pinned = choice; request = next; finish = Ui()
        ui.value = Ui(Phase.PERMISSION)
        permissionWindow(true)
        try { launchPermission(next, this) }
        catch (_: Exception) { fail(next, Ui(Phase.ERROR)) }
        if (request === next) deadline = scope.launch {
            delay(30_000)
            if (request === next && capture == null) fail(next, Ui(Phase.ERROR))
        }
    }

    /** Service invokes only after real startForeground succeeded, with the consumed in-process request. */
    fun foreground(next: VoiceStartGate.Request, end: () -> Unit): Boolean {
        if (request !== next || !next.admitted || next.revoked || !next.valid()) return false
        deadline?.cancel(); endForeground = end
        val target = binding ?: return false
        ui.value = Ui(Phase.STARTING)
        val ownedSession = DotVoiceSession(object : DotVoiceSession.Port {
            override fun start(continuous: Boolean, text: (String, Boolean) -> Unit,
                error: (RecognitionError, String?) -> Unit): VoiceLease.Ticket? {
                if (VoiceProofTap.active)
                    VoiceProofTap.captureRequest(adapter, target, next, this@DotVoiceController)
                return SpeechRecognitionManager.startOwned("dot", continuous, text, error, pinned,
                    onTicket = { ticket -> if (VoiceProofTap.active)
                        VoiceProofTap.captureTicket(adapter, target, next, ticket) },
                    retrySelectedEngine = true)
            }
            override fun stop(ticket: VoiceLease.Ticket) = SpeechRecognitionManager.stopOwned(ticket)
            override fun cancel(ticket: VoiceLease.Ticket) = SpeechRecognitionManager.cancelOwned(ticket)
        }, adapter::accepts, { adapter.voiceInput(it) }, adapter::writeVoiceInput,
            { kind, detail -> if (request === next) {
                finish = Ui(if (kind == RecognitionError.PERMISSION_DENIED) Phase.DENIED else if (kind == RecognitionError.RECOGNIZER_BUSY) Phase.BUSY else Phase.ERROR, kind, detail)
            } })
        session = ownedSession
        ownedSession.start(target)
        capture = ownedSession.currentTicket()
        permissionWindow(false)
        val ticket = capture
        if (ticket == null) { complete(next); return false }
        scope.launch {
            val observe = launch {
                SpeechRecognitionManager.ownedState.collect { state ->
                    if (request === next && state.ticket === ticket && state.state != RecognitionState.IDLE) {
                        ui.value = Ui(when (state.state) {
                            RecognitionState.STARTING -> Phase.STARTING
                            RecognitionState.RECORDING -> Phase.RECORDING
                            else -> Phase.FINISHING
                        })
                    }
                }
            }
            ticket.awaitReleased()
            observe.cancel()
            complete(next)
        }
        return true
    }
    fun hasCapture(next: VoiceStartGate.Request) = request === next && capture != null
    fun stop() { session?.stop() }
    fun cancel() { request?.let(::cancel) }
    fun cancel(next: VoiceStartGate.Request) {
        if (request !== next) return
        gate.revoke(next)
        session?.cancel()
        finish = Ui()
        if (capture == null) complete(next) else ui.value = Ui(Phase.FINISHING)
    }
    fun denied(next: VoiceStartGate.Request) = fail(next, Ui(Phase.DENIED))
    fun failed(next: VoiceStartGate.Request) = fail(next, Ui(Phase.ERROR))
    private fun fail(next: VoiceStartGate.Request, result: Ui) {
        if (request !== next) return
        finish = result
        gate.revoke(next)
        session?.cancel()
        if (capture == null) complete(next) else ui.value = Ui(Phase.FINISHING)
    }
    private fun complete(next: VoiceStartGate.Request) {
        if (request !== next) return
        deadline?.cancel(); deadline = null
        // Resource release, not an IDLE publication or posted teardown, ends service ownership.
        val end = endForeground; endForeground = null
        try { end?.invoke() } catch (_: Exception) { finish = Ui(Phase.ERROR) }
        gate.complete(next)
        released(next)
        request = null; capture = null; session = null; binding = null; pinned = null
        permissionWindow(false)
        ui.value = finish
    }
}
