package io.github.nanomuse.dot.care

import org.junit.Assert.*
import org.junit.Test

class CarePresentationPolicyTest {
    @Test fun editingAndClarifyingChooseSubmitEvenWithAnOldInstruction() {
        assertEquals(CarePrimary.Submit, carePrimary(CareUiState(CareState.Paused, pendingInstruction = "old"), true))
        assertEquals(CarePrimary.Submit, carePrimary(CareUiState(CareState.Clarifying), false))
    }
    @Test fun onlyCurrentUserConfirmationCanChooseConfirm() {
        assertEquals(CarePrimary.Confirm, carePrimary(CareUiState(CareState.Recovering,
            errorCode = "needs_user_confirmation", pendingInstruction = "current"), false))
        assertEquals(CarePrimary.None, carePrimary(CareUiState(CareState.Verifying,
            pendingInstruction = "old"), false))
        assertEquals(CarePrimary.None, carePrimary(CareUiState(CareState.Completed,
            pendingInstruction = "old"), false))
    }
    @Test fun pauseAndRecoveryHaveOneSupportedActionWithoutCreditingAStep() {
        assertEquals(CarePrimary.Resume, carePrimary(CareUiState(CareState.Paused), false))
        assertEquals(CarePrimary.Reobserve, carePrimary(CareUiState(CareState.Recovering,
            goal = "find settings", errorCode = "host_unavailable"), false))
        assertEquals(CarePrimary.None, carePrimary(CareUiState(CareState.Recovering,
            goal = "find settings", errorCode = "provider_not_configured"), false))
        assertEquals(CarePrimary.None, carePrimary(CareUiState(CareState.Observing), false))
    }
}
