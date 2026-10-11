package io.github.nanomuse.dot.care

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import com.openminis.app.R
import io.github.nanomuse.dot.ui.DotTokens

@Composable
fun CareCard(proposal: CareProposal, onCopy: () -> Unit, onPause: () -> Unit,
    onReobserve: () -> Unit, onEnd: () -> Unit, onRepeat: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
        Text(proposal.instruction, fontSize = DotTokens.Type.Care)
        CareActions {
            if (proposal.copyText != null) CareButton(R.string.nm_care_copy,onCopy)
            CareButton(R.string.nm_care_repeat,onRepeat)
            CareButton(R.string.nm_care_pause,onPause)
            CareButton(R.string.nm_care_reobserve,onReobserve)
            CareButton(R.string.nm_care_end,onEnd)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CareActions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DotTokens.Space.S8), content = { content() })
}
@Composable
private fun CareButton(label: Int, action: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick=action,enabled=enabled,modifier=Modifier.heightIn(min=DotTokens.TouchTarget)) {
        Text(stringResource(label),fontSize=DotTokens.Type.Body)
    }
}

@Composable
internal fun CarePanel(session: CareSession, onCopy: () -> Unit, onRepeat: (String) -> Unit) {
    val state by session.controller.state.collectAsState()
    val editing by session.editingGoal.collectAsState()
    var goal by remember(editing,state.goal,state.question) { mutableStateOf(state.goal) }
    val status = stringResource(careStatusLabel(if (editing) CareState.Idle else state.state))
    Surface(shape=MaterialTheme.shapes.medium,tonalElevation=DotTokens.Space.S4) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(DotTokens.Space.S16),
            verticalArrangement=Arrangement.spacedBy(DotTokens.Space.S8)) {
            Text(status,fontSize=DotTokens.Type.Body,color=MaterialTheme.colorScheme.tertiary,
                modifier=Modifier.semantics { stateDescription=status; liveRegion=LiveRegionMode.Polite })
            if (editing || state.state==CareState.Clarifying) {
                state.question?.let { Text(it,fontSize=DotTokens.Type.Care) }
                OutlinedTextField(value=goal,onValueChange={ if(it.length<=2048) goal=it },
                    label={ Text(stringResource(R.string.nm_care_goal)) },modifier=Modifier.fillMaxWidth(),maxLines=4)
                CareButton(R.string.nm_care_start,{session.submitGoal(goal)},goal.isNotBlank())
            }
            state.proposal?.let { proposal ->
                CareCard(proposal,onCopy,{session.pause()},session::reobserve,session::end,
                    { onRepeat(proposal.instruction) })
            }
            if (state.errorCode != null && state.pendingInstruction == null) {
                Text(stringResource(if(state.errorCode=="provider_not_configured") R.string.nm_care_provider_missing
                    else R.string.nm_care_unavailable),fontSize=DotTokens.Type.Care)
            }
            state.pendingInstruction?.let { pending ->
                if (state.proposal==null) Text(pending,fontSize=DotTokens.Type.Care)
                CareButton(R.string.nm_care_confirm,session.controller::confirmStepCompleted)
            }
            CareActions {
                if (state.state==CareState.Paused) CareButton(R.string.nm_care_resume,session::resume)
                if (state.proposal==null && !editing) {
                    if(state.state!=CareState.Paused) {
                        CareButton(R.string.nm_care_reobserve,session::reobserve)
                        CareButton(R.string.nm_care_pause,{session.pause()})
                    }
                }
                if (!editing) CareButton(R.string.nm_care_change_goal,session::changeGoal)
                if (state.proposal==null) CareButton(R.string.nm_care_end,session::end)
            }
        }
    }
}

internal fun careStatusLabel(state: CareState): Int = when(state) {
    CareState.Idle -> R.string.nm_care_intro
    CareState.Observing -> R.string.nm_care_observing
    CareState.Clarifying -> R.string.nm_care_clarifying
    CareState.Guiding, CareState.AwaitingUser -> R.string.nm_care_awaiting
    CareState.Verifying -> R.string.nm_care_verifying
    CareState.Completed -> R.string.nm_care_completed
    CareState.Recovering -> R.string.nm_care_recovering
    CareState.Paused -> R.string.nm_care_paused
}
