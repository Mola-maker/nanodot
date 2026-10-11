package io.github.nanomuse.dot

import android.widget.Toast
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
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
    private val adapter by lazy { DotChatAdapter.create(app) }
    private var opening: Job? = null
    private var request = 0L

    /** Explicit main-app session entry switches admission; opening our panel alone never does. */
    fun onChatEntered(sessionId: String) {
        ++request
        opening?.cancel()
        if (service.dotOverlay.panelView != null) adapter.bind(sessionId)
    }

    fun handle(intent: DotIntent) {
        if (intent != DotIntent.Single && intent != DotIntent.Double) {
            Toast.makeText(service,R.string.nm_dot_action_unavailable,Toast.LENGTH_SHORT).show()
            return
        }
        opening?.cancel()
        // The old panel's collapse callback invalidates its admission before the new ticket exists.
        service.dotOverlay.collapsePanel()
        val ticket = ++request
        opening = scope.launch {
            val id = com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId ?: MainChat.sessionId.value ?: MainChat.resolve(app,app.chatRepository)
            if (ticket != request || !service.dotOverlay.isConnected) return@launch
            val binding = adapter.bind(id)
            if (intent == DotIntent.Double) {
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
            }
            if (ticket != request || !adapter.accepts(binding)) return@launch
            service.dotOverlay.showPanel(adapter) { close() }
        }
    }

    fun close() {
        ++request
        opening?.cancel()
        adapter.binding.value?.let(adapter::cancel)
        service.dotOverlay.collapsePanel()
    }

    fun disconnect() { close() }
}
