package io.github.nanomuse.dot.care

/** Pure presentation, never changes the controller's admission or verification state. */
internal enum class CarePrimary { None, Submit, Confirm, Resume, Reobserve }
internal fun carePrimary(state: CareUiState, editing: Boolean): CarePrimary = when {
    editing || state.state == CareState.Clarifying -> CarePrimary.Submit
    state.state == CareState.Paused -> CarePrimary.Resume
    state.state in setOf(CareState.AwaitingUser, CareState.Recovering) &&
        state.pendingInstruction != null && state.errorCode == "needs_user_confirmation" -> CarePrimary.Confirm
    state.state == CareState.AwaitingUser -> CarePrimary.Reobserve
    state.state == CareState.Recovering && state.goal.isNotBlank() &&
        state.errorCode != "provider_not_configured" -> CarePrimary.Reobserve
    else -> CarePrimary.None
}
