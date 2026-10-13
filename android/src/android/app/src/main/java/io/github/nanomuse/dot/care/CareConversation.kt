package io.github.nanomuse.dot.care

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation history only. It never verifies, advances, captures, or sends a request. */
data class CareBubble(val id: String, val text: String, val user: Boolean = false, val verified: Boolean = false)
class CareConversation {
    private val mutable = MutableStateFlow<List<CareBubble>>(emptyList())
    val state = mutable.asStateFlow()
    val rows: List<CareBubble> get() = mutable.value
    private var userSequence = 0L
    fun userGoal(text: String) {
        if (text.isBlank()) return
        mutable.value = rows + CareBubble("user:${++userSequence}", text, user = true)
    }
    fun assistant(id: String, text: String, verified: Boolean = false) {
        if (text.isBlank() || rows.any { it.id == id }) return
        mutable.value = rows + CareBubble(id, text, verified = verified)
    }
}
