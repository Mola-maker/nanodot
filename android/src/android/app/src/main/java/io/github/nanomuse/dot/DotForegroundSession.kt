package io.github.nanomuse.dot

import io.github.nanomuse.dot.chat.SessionBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DotForegroundMode { Idle, Chat, Screen, Care }
enum class DotForegroundOperation { Idle, Capturing, Replying, Listening, CareWaiting, Error }
data class DotForegroundTicket(val epoch: Long, val accountEpoch: Long)
data class DotForegroundState(
    val epoch: Long = 0,
    val connected: Boolean = false,
    val mode: DotForegroundMode = DotForegroundMode.Idle,
    val binding: SessionBinding? = null,
    val operation: DotForegroundOperation = DotForegroundOperation.Idle,
    val accountEpoch: Long = 0,
)

/** Main-thread admission and immutable presentation only; canonical work stays in its owners. */
class DotForegroundSession {
    private val mutableState = MutableStateFlow(DotForegroundState())
    val state: StateFlow<DotForegroundState> = mutableState.asStateFlow()
    fun ticket() = state.value.let { DotForegroundTicket(it.epoch, it.accountEpoch) }
    fun accepts(ticket: DotForegroundTicket) = state.value.let {
        it.connected && it.epoch == ticket.epoch && it.accountEpoch == ticket.accountEpoch
    }
    fun connect() {
        if (!state.value.connected) mutableState.value = state.value.copy(
            epoch = state.value.epoch + 1, connected = true)
    }
    fun invalidate() {
        mutableState.value = state.value.copy(epoch = state.value.epoch + 1,
            mode = DotForegroundMode.Idle, binding = null, operation = DotForegroundOperation.Idle)
    }
    fun update(mode: DotForegroundMode, binding: SessionBinding?, operation: DotForegroundOperation) {
        if (state.value.connected) mutableState.value = state.value.copy(
            mode = mode, binding = binding, operation = operation)
    }
    fun accountChanged() {
        invalidate()
        mutableState.value = state.value.copy(accountEpoch = state.value.accountEpoch + 1)
    }
    fun disconnect() {
        if (!state.value.connected) return
        invalidate()
        mutableState.value = state.value.copy(connected = false)
    }
}
