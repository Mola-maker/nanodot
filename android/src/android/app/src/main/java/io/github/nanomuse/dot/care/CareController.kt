package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.CaptureOutcome
import io.github.nanomuse.dot.screen.NodeDto
import io.github.nanomuse.dot.screen.SceneKey
import io.github.nanomuse.dot.screen.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class CareState { Idle, Observing, Clarifying, Guiding, AwaitingUser, Verifying, Completed, Recovering, Paused }
data class CareUiState(val state: CareState = CareState.Idle, val proposal: CareProposal? = null,
    val goal: String = "", val question: String? = null, val errorCode: String? = null, val target: NodeDto? = null,
    val pendingInstruction: String? = null)
enum class VerificationOrigin { Observable, UserConfirmed }
data class VerifiedCareStep(val proposal: CareProposal, val afterSnapshotId: String,
    val afterScene: SceneKey, val origin: VerificationOrigin)

/** Service/UI-owner state machine. Invoke methods and [currentScene] on the supplied scope's
 * serial dispatcher (Android Main); sampler/planner may suspend, never mutate this owner.
 * The sampler transfers snapshot ownership. Planner results must describe the supplied snapshot.
 * No events/timeouts invoke confirmation, no action executor is admitted, and no bitmap enters UI.
 * Task9 supplies the real service owner, localized code-to-question mapping and controls.
 */
class CareController(
    private val scope: CoroutineScope,
    private val sample: suspend (Long) -> CaptureOutcome,
    private val currentScene: () -> SceneKey?,
    private val plan: suspend (String, ScreenSnapshot, List<CareProposal>) -> CarePlanResult,
    private val debounceMs: Long = 150,
) {
    private val mutableState = MutableStateFlow(CareUiState())
    val state: StateFlow<CareUiState> = mutableState.asStateFlow()
    private val history = MutableStateFlow<List<VerifiedCareStep>>(emptyList())
    val verifiedSteps: StateFlow<List<VerifiedCareStep>> = history.asStateFlow()
    var generation: Long = 0
        private set
    private var revision = 0L
    private var work: Job? = null
    private var initialInput: (suspend () -> Unit)? = null
    private var inputAdmission: Deferred<Result<Unit>>? = null
    private var automatic = false
    private var active = false
    private var waiting: Step? = null
    private var confirmation = false
    private val validator = CareProposalValidator()
    private data class Step(val proposal: CareProposal, val verifier: CareVerifier)

    init { require(debounceMs in 0..1000) }

    fun start(goal: String, beforeInitialSample: suspend () -> Unit = {}) {
        invalidate()
        cancelInputAdmission()
        initialInput = beforeInitialSample
        generation++
        history.value = emptyList()
        waiting = null
        confirmation = false
        active = goal.isNotBlank() && goal.length <= 2048
        mutableState.value = CareUiState(goal = goal)
        if (!active) { recover("invalid_goal"); return }
        if (automatic) publish(CareState.Paused) else observe()
    }

    fun pause() {
        if (!active) return
        invalidate(); cancelInputAdmission(); generation++
        waiting = null; confirmation = false
        publish(CareState.Paused)
    }

    fun resume() {
        if (!active || state.value.state != CareState.Paused || automatic) return
        generation++
        observe()
    }

    fun cancel() {
        invalidate(); cancelInputAdmission(); initialInput = null; generation++
        active = false; waiting = null; confirmation = false
        history.value = emptyList()
        mutableState.value = CareUiState()
    }

    /** Explicit user task ending; never fabricates a verified step or model Done result. */
    fun finish() {
        if (!active) return
        invalidate(); cancelInputAdmission(); initialInput = null; generation++
        active = false; waiting = null; confirmation = false
        publish(CareState.Completed)
    }

    fun onAutomaticOperation(active: Boolean) {
        automatic = active
        if (active) pause()
        // Stopping an operator never resumes care or credits its events.
    }

    fun onSceneChanged() {
        if (!active || automatic || state.value.state == CareState.Paused) return
        observe(debounce = true)
    }

    /** Explicit user admission for uncertainty; samples again before recording its distinct origin. */
    fun confirmStepCompleted() {
        if (!active || automatic || state.value.state == CareState.Paused || !confirmation || waiting == null) return
        observe(userConfirmed = true)
    }

    /** Retry/re-observe without crediting the pending step. Used by later localized recovery controls. */
    fun reobserve() {
        if (!active || automatic || state.value.state == CareState.Paused) return
        waiting = null; confirmation = false
        cancelInputAdmission() // Explicit retry may start a new bounded input handoff.
        observe()
    }

    private fun cancelInputAdmission() { inputAdmission?.cancel(); inputAdmission = null }

    private fun observe(debounce: Boolean = false, userConfirmed: Boolean = false) {
        invalidate()
        // Host/IME/geometry changes clear the visual target synchronously, before a new capture.
        publish(if (waiting == null) CareState.Observing else CareState.Verifying)
        val ticket = revision
        val task = generation
        val goal = state.value.goal
        // This owner survives scene-triggered work cancellation: IME/layout events cannot
        // bypass admission or keep extending its five-second deadline. Stop cancels it.
        val admission = initialInput?.let { input ->
            inputAdmission ?: scope.async {
                try {
                    withTimeout(5000) { input() }
                    Result.success(Unit)
                } catch (timeout: TimeoutCancellationException) {
                    Result.failure(timeout)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }.also { inputAdmission = it }
        }
        work = scope.launch {
            var owned: ScreenSnapshot? = null
            try {
                if (!accepts(ticket, task, goal)) return@launch
                admission?.await()?.getOrThrow()
                if (!accepts(ticket, task, goal)) return@launch
                if (debounce) delay(debounceMs) // Coalesces clues; never counts as completion evidence.
                if (!accepts(ticket, task, goal)) return@launch
                initialInput = null; inputAdmission = null
                val requestedScene = currentScene() ?: run { recover("host_unavailable"); return@launch }
                val outcome = withTimeout(5000) { sample(task).also { if (it is CaptureOutcome.Success) owned = it.snapshot } }
                if (!accepts(ticket, task, goal)) return@launch
                if (outcome is CaptureOutcome.Unavailable) { recover(outcome.code); return@launch }
                val snapshot = (outcome as CaptureOutcome.Success).snapshot
                if (snapshot.generation != task || snapshot.scene != requestedScene || currentScene() != snapshot.scene) {
                    recover("scene_changed"); return@launch
                }
                if (snapshot.nodes.isEmpty()) { recover("nodes_unavailable"); return@launch }
                val step = waiting
                if (step != null) {
                    if (!CareVerifier.sameHost(step.proposal.scene, snapshot.scene)) {
                        confirmation = false; recover("scene_changed"); return@launch
                    }
                    val result = step.verifier.evaluate(step.proposal, snapshot)
                    if (userConfirmed) {
                        if (snapshot.id == step.proposal.snapshotId) {
                            confirmation = false; recover("condition_not_observed"); return@launch
                        }
                        // Copy has no host event: same-version fresh sampling is honest, but its
                        // current target must still be unique and eligible before accepting admission.
                        if (step.proposal.action == CareAction.Copy && step.verifier.target(step.proposal, snapshot) == null) {
                            recover("needs_user_confirmation"); return@launch
                        }
                        if (result == VerificationResult.NotYet && step.proposal.action != CareAction.Copy) {
                            confirmation = false; recover("condition_not_observed"); return@launch
                        }
                        record(step, snapshot, VerificationOrigin.UserConfirmed)
                    } else when (result) {
                        VerificationResult.Confirmed -> record(step, snapshot, VerificationOrigin.Observable)
                        VerificationResult.NeedsUserConfirmation -> {
                            confirmation = true; recover("needs_user_confirmation"); return@launch
                        }
                        VerificationResult.NotYet -> {
                            val target = step.verifier.target(step.proposal, snapshot)
                            if (target == null) { confirmation = true; recover("needs_user_confirmation"); return@launch }
                            val proposal = rebound(step.proposal, snapshot, target)
                            confirmation = step.proposal.action == CareAction.Copy
                            publish(CareState.AwaitingUser, proposal = proposal, target = target,
                                code = if (confirmation) "needs_user_confirmation" else null)
                            return@launch
                        }
                    }
                }
                publish(CareState.Observing)
                val result = withTimeout(30_000) { plan(goal, snapshot, history.value.map { it.proposal }) }
                if (!accepts(ticket, task, goal) || currentScene() != snapshot.scene) return@launch
                when (result) {
                    is CarePlanResult.Step -> {
                        if (!validator.validate(result.proposal, snapshot)) { recover("recovering_invalid_proposal"); return@launch }
                        waiting = Step(result.proposal, CareVerifier(snapshot))
                        confirmation = result.proposal.action == CareAction.Copy
                        val target = snapshot.nodes.single { it.ref == result.proposal.targetRef }
                        publish(CareState.Guiding, result.proposal, target)
                        publish(CareState.AwaitingUser, result.proposal, target,
                            code = if (confirmation) "needs_user_confirmation" else null)
                    }
                    is CarePlanResult.Clarify -> {
                        if (validator.safeText(result.question)) publish(CareState.Clarifying, question = result.question)
                        else recover("recovering_invalid_proposal")
                    }
                    is CarePlanResult.Unavailable -> recover(result.code)
                }
            } catch (_: TimeoutCancellationException) {
                if (accepts(ticket, task, goal)) recover("care_timeout")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (accepts(ticket, task, goal)) recover("care_unavailable")
            } finally { owned?.close() }
        }
    }

    private fun record(step: Step, snapshot: ScreenSnapshot, origin: VerificationOrigin) {
        history.value = history.value + VerifiedCareStep(step.proposal, snapshot.id, snapshot.scene, origin)
        waiting = null; confirmation = false
    }
    private fun rebound(proposal: CareProposal, snapshot: ScreenSnapshot, target: NodeDto): CareProposal =
        proposal.copy(snapshotId = snapshot.id, scene = snapshot.scene, targetRef = target.ref,
            expectation = when (val expected = proposal.expectation) {
                is CareExpectation.Focused -> expected.copy(ref = target.ref)
                is CareExpectation.TextEquals -> expected.copy(ref = target.ref)
                else -> expected
            })
    private fun accepts(ticket: Long, task: Long, goal: String) = active && !automatic &&
        revision == ticket && generation == task && state.value.goal == goal && state.value.state != CareState.Paused
    private fun invalidate() { revision++; work?.cancel(); work = null }
    private fun recover(code: String) = publish(CareState.Recovering, code = code)
    private fun publish(next: CareState, proposal: CareProposal? = null, target: NodeDto? = null,
        question: String? = null, code: String? = null) {
        mutableState.value = CareUiState(next, proposal, state.value.goal, question, code, target,
            pendingInstruction = if (code == "needs_user_confirmation" && confirmation) waiting?.proposal?.instruction else null)
    }
}
