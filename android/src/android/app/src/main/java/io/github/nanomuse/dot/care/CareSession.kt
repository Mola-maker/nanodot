package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Serial service owner; the controller owns evidence, this owner owns UI admission. */
class CareSession(val controller: CareController, private val clearFocus: () -> Unit, private val stopSpeech: () -> Unit) {
    val conversation = CareConversation()
    val visible = MutableStateFlow(false)
    val editingGoal = MutableStateFlow(false)
    fun enter() {
        visible.value = true
        editingGoal.value = controller.state.value.state in setOf(CareState.Idle, CareState.Completed)
    }
    fun submitGoal(goal: String, beforeInitialSample: suspend () -> Unit = {}) {
        clearFocus(); stopSpeech()
        conversation.userGoal(goal.trim())
        visible.value = true; editingGoal.value = false
        controller.start(goal.trim(), beforeInitialSample)
    }
    fun changeGoal() {
        clearFocus(); stopSpeech(); controller.cancel()
        visible.value = true; editingGoal.value = true
    }
    fun pause(hide: Boolean = false) {
        clearFocus(); stopSpeech(); controller.pause()
        if (hide) visible.value = false
    }
    fun end() {
        clearFocus(); stopSpeech(); controller.finish()
        visible.value = false; editingGoal.value = false
    }
    fun disconnect() {
        clearFocus(); stopSpeech(); controller.cancel()
        visible.value = false; editingGoal.value = false
    }
    fun sceneChanged(before: SceneKey?, after: SceneKey?) {
        if (before == after) return
        clearFocus(); stopSpeech(); controller.onSceneChanged()
    }
    fun automatic(active: Boolean) {
        if (active) { clearFocus(); stopSpeech() }
        controller.onAutomaticOperation(active)
    }
    fun resume() { clearFocus(); stopSpeech(); controller.resume() }
    fun reobserve() { clearFocus(); stopSpeech(); controller.reobserve() }
    fun focusBounds(current: SceneKey?): RectPx? {
        val state = controller.state.value
        if (!visible.value || editingGoal.value || state.state !in setOf(CareState.Guiding,CareState.AwaitingUser)) return null
        val proposal = state.proposal ?: return null
        val target = state.target ?: return null
        if (current == null || proposal.scene != current || target.ref != proposal.targetRef) return null
        return target.bounds.takeIf { it.width > 0 && it.height > 0 &&
            listOf(it.left,it.top,it.right,it.bottom).all(Float::isFinite) }
    }

    /** Reflect actual controller output; presentation has no permission to credit a step. */
    fun observeConversation() {
        val state = controller.state.value
        controller.verifiedSteps.value.forEach { step ->
            conversation.assistant("verified:${step.afterSnapshotId}", step.proposal.instruction, verified = true)
        }
        state.question?.let { conversation.assistant("question:${controller.generation}:$it", it) }
        state.proposal?.let { conversation.assistant(instructionKey(), it.instruction) }
    }
    fun instructionKey(): String {
        val state = controller.state.value
        return "instruction:${controller.generation}:${controller.verifiedSteps.value.size}:${state.proposal?.instruction}"
    }
}

/** Main-owned ref-counted action admission; stopping an owner is never care resume. */
class CareOperationOwners {
    private var count = 0
    private var listener: ((Boolean) -> Unit)? = null
    fun observe(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
        listener?.invoke(count > 0)
    }
    fun retainUntilIdle(scope: CoroutineScope, busy: Flow<Boolean>): Job {
        val lease = acquire()
        return scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { busy.first { !it } } finally { lease.close() }
        }
    }
    fun acquire(): AutoCloseable {
        count++
        listener?.invoke(true)
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true; count--
                if (count == 0) listener?.invoke(false)
            }
        }
    }
}
