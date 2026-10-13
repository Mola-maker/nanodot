package io.github.nanomuse.dot

import android.widget.Toast
import android.content.Context
import android.content.SharedPreferences
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.dot.chat.SessionBinding
import io.github.nanomuse.dot.voice.DotVoiceController
import kotlinx.coroutines.flow.*
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.care.*
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.gesture.DotIntent
import io.github.nanomuse.dot.screen.CaptureOutcome
import io.github.nanomuse.dot.screen.CapturePurpose
import io.github.nanomuse.home.MainChat
import kotlinx.coroutines.*

/** Service-scoped admission only. ChatViewModel remains the sole conversation/stream owner. */
class DotInteractionCoordinator internal constructor(
    private val service: MinisAccessibilityService,
    private val injectedAdapter: DotChatAdapter? = null,
    private val screenCapture: suspend (CapturePurpose, Long) -> CaptureOutcome = { purpose, generation ->
        service.dotScreen.capture(purpose, generation)
    },
    private val nodeCapture: suspend (Long) -> CaptureOutcome = { generation -> service.dotScreen.captureNodes(generation) },
) {
    private val app get() = service.application as MinisApp
    private var scope = newScope()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val foreground = DotForegroundSession()
    private var projection: Job? = null
    private val capturing = MutableStateFlow(false)
    private var accountIdentity: Pair<Boolean?, String?>? = null
    private val metadata by lazy { service.getSharedPreferences("nanomuse", Context.MODE_PRIVATE) }
    private val accountListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("cloud.account_id", "cloud.hint", "cloud.instance_id")) {
            scope.launch { if (foreground.state.value.connected) refreshAccount() }
        }
    }
    private val adapter by lazy { injectedAdapter ?: DotChatAdapter.create(app).also { chat ->
        chat.voice = io.github.nanomuse.dot.voice.DotVoiceController(chat,
            io.github.nanomuse.dot.voice.DotVoiceRuntime.gate,
            {
                val choice = app.providerRepository.resolveVoiceInputChoice()
                io.github.nanomuse.dot.voice.VoiceSelection(if (choice.isSystem) "system" else "provider",
                    com.openminis.app.speech.SpeechRecognitionManager.locale.value, choice.systemPreferOffline == true)
            },
            { request, controller -> io.github.nanomuse.dot.voice.DotVoiceRuntime.launch(service, request, controller) },
            service.dotOverlay::setVoicePermissionWindow,
            io.github.nanomuse.dot.voice.DotVoiceRuntime::release)
    } }
    private val idleVoiceUi = MutableStateFlow(DotVoiceController.Ui())
    /** Projection of the existing microphone owner, never a second recording state machine. */
    val voiceUi: StateFlow<DotVoiceController.Ui> get() = adapter.voice?.ui ?: idleVoiceUi
    private var opening: Job? = null
    private var careWindow: CareOverlayWindow? = null
    private val carePlanner by lazy { ConfiguredCarePlanner(app) }
    private var careOwner: CareSession? = null
    val careSession: CareSession get() = careOwner ?: CareSession(CareController(scope,
        { generation ->
            refreshAccount()
            val ticket = foreground.ticket()
            val outcome = service.dotScreen.capture(CapturePurpose.Care, generation)
            if (!admitted(ticket)) {
                (outcome as? CaptureOutcome.Success)?.snapshot?.close()
                throw CancellationException("Foreground admission ended")
            }
            outcome
        }, { service.dotScreen.currentSceneKey() }, { goal, snapshot, history ->
            val ticket = foreground.ticket()
            val result = carePlanner.next(goal, snapshot, history)
            if (!admitted(ticket)) throw CancellationException("Foreground admission ended")
            result
        }),
        { careWindow?.clearFocus() }, { careWindow?.stopSpeech() }).also { careOwner = it }

    fun connect() {
        if (foreground.state.value.connected) return
        if (!scope.isActive) scope = newScope()
        foreground.connect()
        accountIdentity = identity()
        metadata.registerOnSharedPreferenceChangeListener(accountListener)
        scope.launch { NanoMuseCloud.signedIn(service).collect { refreshAccount() } }
        CareOperationGate.observe(careSession::automatic)
    }
    private fun identity() = NanoMuseCloud.signedIn(service).value to NanoMuseCloud.account(service)?.accountId
    private fun refreshAccount() {
        val next = identity()
        if (accountIdentity != null && next != accountIdentity) {
            accountIdentity = next
            foreground.accountChanged()
            close()
            careOwner?.disconnect()
        } else accountIdentity = next
    }
    private fun admitted(ticket: DotForegroundTicket, binding: SessionBinding? = null): Boolean {
        refreshAccount()
        return foreground.accepts(ticket) && (binding == null || adapter.accepts(binding))
    }
    /** Display priority only; every independent producer retains its own lifecycle. */
    private fun project() {
        projection?.cancel()
        val ticket = foreground.ticket()
        val binding = adapter.binding.value
        val mode = foreground.state.value.mode
        projection = scope.launch {
            combine(capturing, adapter.session?.isStreaming ?: flowOf(false),
                adapter.voice?.ui ?: flowOf(DotVoiceController.Ui()), careSession.controller.state,
                adapter.captureError) { capture, busy, voice, care, error ->
                when {
                    capture -> DotForegroundOperation.Capturing
                    voice.phase in setOf(DotVoiceController.Phase.PERMISSION, DotVoiceController.Phase.STARTING,
                        DotVoiceController.Phase.RECORDING, DotVoiceController.Phase.FINISHING) -> DotForegroundOperation.Listening
                    busy && binding != null -> DotForegroundOperation.Replying
                    (error && mode in setOf(DotForegroundMode.Chat, DotForegroundMode.Screen) && binding != null) ||
                        (mode == DotForegroundMode.Care && care.errorCode != null) -> DotForegroundOperation.Error
                    mode == DotForegroundMode.Care && care.state !in setOf(CareState.Idle, CareState.Completed) -> DotForegroundOperation.CareWaiting
                    else -> DotForegroundOperation.Idle
                }
            }.collect { operation ->
                if (admitted(ticket, binding)) foreground.update(mode, binding, operation)
            }
        }
    }
    fun pauseCare(hide: Boolean = false) {
        service.dotOverlay.dismissOrbMenu()
        adapter.voice?.cancel(); adapter.stopReply()
        careSession.pause(hide)
        if (hide) careWindow?.hide()
    }
    fun onHostGeometryChanged() {
        careWindow?.clearFocus(); careWindow?.stopSpeech()
        careSession.controller.onSceneChanged()
        careWindow?.refreshGeometry()
    }
    fun onSceneChanged(changed: Boolean) {
        if (!foreground.state.value.connected) return
        if (changed) onHostGeometryChanged()
        else careWindow?.refreshGeometry()
    }
    private fun openCare() {
        close()
        if (careWindow == null) careWindow = CareOverlayWindow(service,scope,careSession,
            { service.dotScreen.hostViewport() }, { service.dotScreen.currentSceneKey() },
            foreground = foreground.state, onStop = { pauseCare(hide = true) })
        careSession.enter()
        foreground.update(DotForegroundMode.Care, null, DotForegroundOperation.Idle)
        project()
    }

    /** Explicit main-app session entry switches admission; opening our panel alone never does. */
    fun onChatEntered(sessionId: String) {
        if (!foreground.state.value.connected) return
        service.dotOverlay.dismissOrbMenu()
        service.dotOverlay.cancelCaptureScan()
        pauseCare(hide=true)
        refreshAccount()
        foreground.invalidate()
        capturing.value = false
        projection?.cancel()
        opening?.cancel()
        if (service.dotOverlay.panelView != null) {
            val binding = adapter.bind(sessionId)
            foreground.update(DotForegroundMode.Chat, binding, DotForegroundOperation.Idle)
            project()
        }
    }

    fun handle(intent: DotIntent) {
        if (!foreground.state.value.connected) return
        if (intent == DotIntent.LongPress) return // The overlay owns this presentation-only action.
        service.dotOverlay.dismissOrbMenu()
        refreshAccount()
        if (intent == DotIntent.Voice) { handleVoiceAction(); return }
        service.dotOverlay.cancelCaptureScan()
        if (intent == DotIntent.Triple) { openCare(); return }
        if (intent != DotIntent.Single && intent != DotIntent.Double) {
            Toast.makeText(service,R.string.nm_dot_action_unavailable,Toast.LENGTH_SHORT).show()
            return
        }
        openConversation(intent)
    }

    private fun handleVoiceAction() {
        val voice = adapter.voice
        if (voice == null) {
            Toast.makeText(service, R.string.nm_dot_action_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val binding = adapter.binding.value
        val state = foreground.state.value
        if (!capturing.value && binding != null && adapter.accepts(binding) && state.binding == binding &&
            state.mode in setOf(DotForegroundMode.Chat, DotForegroundMode.Screen) && service.dotOverlay.panelView != null) {
            voice.primaryAction() // Finish/Cancel retains the current permission request and draft.
        } else {
            service.dotOverlay.cancelCaptureScan()
            openConversation(DotIntent.Single, startVoice = true)
        }
    }

    private fun openConversation(intent: DotIntent, startVoice: Boolean = false) {
        pauseCare(hide=true)
        opening?.cancel()
        // The old panel's collapse callback invalidates its admission before the new ticket exists.
        service.dotOverlay.collapsePanel()
        foreground.invalidate()
        capturing.value = false
        val ticket = foreground.ticket()
        opening = scope.launch {
            val id = com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId ?: MainChat.sessionId.value ?: MainChat.resolve(app,app.chatRepository)
            if (!admitted(ticket) || !service.dotOverlay.isConnected) return@launch
            val binding = adapter.bind(id)
            foreground.update(if (intent == DotIntent.Double) DotForegroundMode.Screen else DotForegroundMode.Chat,
                binding, DotForegroundOperation.Idle)
            if (intent == DotIntent.Double) {
                capturing.value = true
                foreground.update(DotForegroundMode.Screen, binding, DotForegroundOperation.Capturing)
            }
            project()
            // Keep the owned cancellation surface mounted throughout capture and fallback.
            // The repository uses host-window pixels or fresh nodes while these controls are visible.
            service.dotOverlay.showPanel(adapter, onStop = ::stopChatReply, foreground = foreground.state) { close() }
            if (!admitted(ticket, binding)) return@launch
            if (startVoice && service.dotOverlay.panelView != null) adapter.voice?.primaryAction()
            if (intent == DotIntent.Double) {
                service.dotOverlay.beginCaptureScan(ticket.epoch)
                try {
                when (val result = screenCapture(CapturePurpose.Chat,binding.generation)) {
                    is CaptureOutcome.Success -> if (admitted(ticket, binding)) adapter.attach(binding,result.snapshot) else result.snapshot.close()
                    is CaptureOutcome.Unavailable -> {
                        if (!admitted(ticket, binding)) return@launch
                        // A fresh read is required: failed screenshot nodes are never reused.
                        when (val fallback = nodeCapture(binding.generation)) {
                            is CaptureOutcome.Success -> if (admitted(ticket, binding)) adapter.attach(binding,fallback.snapshot) else fallback.snapshot.close()
                            is CaptureOutcome.Unavailable -> if (admitted(ticket, binding)) adapter.captureError.value = true
                        }
                    }
                }
                if(admitted(ticket, binding)) service.dotOverlay.finishCaptureScan(ticket.epoch,adapter.preview.value!=null)
                else service.dotOverlay.cancelCaptureScan(ticket.epoch)
                } catch(cancelled: CancellationException) {
                    service.dotOverlay.cancelCaptureScan(ticket.epoch)
                    throw cancelled
                } catch(failure: Exception) {
                    if(admitted(ticket, binding)) {
                        adapter.captureError.value=true
                        service.dotOverlay.finishCaptureScan(ticket.epoch,false)
                    } else service.dotOverlay.cancelCaptureScan(ticket.epoch)
                } finally {
                    if (foreground.accepts(ticket)) capturing.value = false
                }
            }
        }
    }

    /** Stop the owned stream, retaining the mounted panel and canonical history. */
    fun stopChatReply(binding: SessionBinding) {
        if (!foreground.state.value.connected) return
        refreshAccount()
        if (!adapter.accepts(binding)) return
        service.dotOverlay.dismissOrbMenu()
        val mode = foreground.state.value.mode
        foreground.invalidate()
        service.dotOverlay.cancelCaptureScan()
        capturing.value = false
        opening?.cancel()
        projection?.cancel()
        adapter.cancel(binding)
        val fresh = adapter.bind(binding.sessionId)
        foreground.update(mode, fresh, DotForegroundOperation.Idle)
        project()
    }

    fun close() {
        service.dotOverlay.dismissOrbMenu()
        service.dotOverlay.cancelCaptureScan()
        foreground.invalidate()
        capturing.value = false
        projection?.cancel()
        opening?.cancel()
        adapter.voice?.cancel(); adapter.closeSpeech()
        careOwner?.pause(hide = true)
        careWindow?.hide()
        adapter.binding.value?.let(adapter::cancel)
        service.dotOverlay.collapsePanel()
    }

    fun disconnect() {
        if (!foreground.state.value.connected) return
        foreground.disconnect()
        metadata.unregisterOnSharedPreferenceChangeListener(accountListener)
        CareOperationGate.observe(null)
        close()
        careOwner?.disconnect(); careWindow?.close(); careWindow=null
        careOwner = null
        scope.cancel()
    }
}
