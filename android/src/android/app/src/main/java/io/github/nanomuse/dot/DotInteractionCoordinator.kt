package io.github.nanomuse.dot

import android.widget.Toast
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
class DotInteractionCoordinator(private val service: MinisAccessibilityService) {
    private val app get() = service.application as MinisApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter by lazy { DotChatAdapter.create(app).also { chat ->
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
    private var opening: Job? = null
    private var request = 0L
    private var careWindow: CareOverlayWindow? = null
    private val carePlanner by lazy { ConfiguredCarePlanner(app) }
    val careSession by lazy {
        CareSession(CareController(scope,
            { generation -> service.dotScreen.capture(CapturePurpose.Care,generation) },
            { service.dotScreen.currentSceneKey() }, carePlanner::next),
            { careWindow?.clearFocus() }, { careWindow?.stopSpeech() })
    }
    fun connect() { CareOperationGate.observe(careSession::automatic) }
    fun pauseCare(hide: Boolean = false) {
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
        if (changed) onHostGeometryChanged()
        else careWindow?.refreshGeometry()
    }
    private fun openCare() {
        close()
        if (careWindow == null) careWindow = CareOverlayWindow(service,scope,careSession,
            { service.dotScreen.hostViewport() }, { service.dotScreen.currentSceneKey() })
        careSession.enter()
    }

    /** Explicit main-app session entry switches admission; opening our panel alone never does. */
    fun onChatEntered(sessionId: String) {
        service.dotOverlay.cancelCaptureScan()
        pauseCare(hide=true)
        ++request
        opening?.cancel()
        if (service.dotOverlay.panelView != null) adapter.bind(sessionId)
    }

    fun handle(intent: DotIntent) {
        service.dotOverlay.cancelCaptureScan()
        if (intent == DotIntent.Triple) { openCare(); return }
        if (intent != DotIntent.Single && intent != DotIntent.Double) {
            Toast.makeText(service,R.string.nm_dot_action_unavailable,Toast.LENGTH_SHORT).show()
            return
        }
        pauseCare(hide=true)
        opening?.cancel()
        // The old panel's collapse callback invalidates its admission before the new ticket exists.
        service.dotOverlay.collapsePanel()
        val ticket = ++request
        opening = scope.launch {
            val id = com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId ?: MainChat.sessionId.value ?: MainChat.resolve(app,app.chatRepository)
            if (ticket != request || !service.dotOverlay.isConnected) return@launch
            val binding = adapter.bind(id)
            if (intent == DotIntent.Double) {
                service.dotOverlay.beginCaptureScan(ticket)
                try {
                when (val result = service.dotScreen.capture(CapturePurpose.Chat,binding.generation)) {
                    is CaptureOutcome.Success -> adapter.attach(binding,result.snapshot)
                    is CaptureOutcome.Unavailable -> {
                        // A fresh read is required: failed screenshot nodes are never reused.
                        when (val fallback = service.dotScreen.captureNodes(binding.generation)) {
                            is CaptureOutcome.Success -> adapter.attach(binding,fallback.snapshot)
                            is CaptureOutcome.Unavailable -> adapter.captureError.value = true
                        }
                    }
                }
                if(ticket==request && adapter.accepts(binding)) service.dotOverlay.finishCaptureScan(ticket,adapter.preview.value!=null)
                else service.dotOverlay.cancelCaptureScan(ticket)
                } catch(cancelled: CancellationException) {
                    service.dotOverlay.cancelCaptureScan(ticket)
                    throw cancelled
                } catch(failure: Exception) {
                    if(ticket==request && adapter.accepts(binding)) {
                        adapter.captureError.value=true
                        service.dotOverlay.finishCaptureScan(ticket,false)
                    } else service.dotOverlay.cancelCaptureScan(ticket)
                }
            }
            if (ticket != request || !adapter.accepts(binding)) return@launch
            service.dotOverlay.showPanel(adapter) { close() }
        }
    }

    fun close() {
        service.dotOverlay.cancelCaptureScan()
        ++request
        opening?.cancel()
        adapter.binding.value?.let(adapter::cancel)
        service.dotOverlay.collapsePanel()
    }

    fun disconnect() {
        CareOperationGate.observe(null)
        careSession.disconnect(); careWindow?.close(); careWindow=null
        close()
    }
}
