package io.github.nanomuse.dot.care

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.graphicsLayer
import io.github.nanomuse.dot.ui.LocalDotMotionEnabled
import io.github.nanomuse.dot.chat.DotForegroundChat
import io.github.nanomuse.dot.chat.DotMessageBubble
import io.github.nanomuse.dot.screen.RectPx
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
internal fun CarePanel(session: CareSession, onCopy: () -> Unit, onRepeat: (String) -> Unit,
    onInstructionBounds: (String?, RectPx?) -> Unit = { _, _ -> },
    capsule: (@Composable () -> Unit)? = null, placementCompact: Boolean = false,
    onExpand: () -> Unit = {}, onAdjust: () -> Unit = {}, onDrag: (Float, Float) -> Unit = { _, _ -> }) {
    val state by session.controller.state.collectAsState()
    val editing by session.editingGoal.collectAsState()
    val history by session.conversation.state.collectAsState()
    var goal by remember(editing,state.goal,state.question) { mutableStateOf(state.goal) }
    val status = stringResource(careStatusLabel(if (editing) CareState.Idle else state.state))
    val list = rememberLazyListState()
    val current = if (state.proposal != null) session.instructionKey() else null
    val motion=LocalDotMotionEnabled.current
    var measured by remember(current) { mutableStateOf(false) }
    val cue = rememberCareInstructionReveal(current, motion, measured)
    LaunchedEffect(history.size, current) {
        if (list.layoutInfo.totalItemsCount > 0) {
            if(motion) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
            else list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
        if (current == null) onInstructionBounds(null, null)
    }
    DisposableEffect(Unit) { onDispose { onInstructionBounds(null, null) } }
    DotForegroundChat(status, { session.pause(hide = true) }, capsule = capsule,
        placementCompact = placementCompact, onExpand = onExpand, onAdjust = onAdjust, onDrag = onDrag, history = {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
            if (history.isEmpty()) item { DotMessageBubble(false) {
                Text(stringResource(R.string.nm_care_intro),fontSize=DotTokens.Type.Care)
            } }
            items(history, key = { it.id }) { row ->
                if(row.id==current) DisposableEffect(row.id) { onDispose { onInstructionBounds(row.id,null) } }
                val anchor = if (row.id == current) Modifier.graphicsLayer { alpha=careCueProgress((cue*980).toLong(),motion).bubble }.onGloballyPositioned { coordinates ->
                    val r = coordinates.boundsInWindow()
                    measured = r.width > 0 && r.height > 0
                    onInstructionBounds(row.id, RectPx(r.left,r.top,r.right,r.bottom))
                } else Modifier
                DotMessageBubble(row.user, anchor) {
                    if (row.verified) Text(stringResource(R.string.nm_care_step_confirmed),fontSize=DotTokens.Type.Body)
                    Text(row.text,fontSize=DotTokens.Type.Care)
                }
            }
            if (state.errorCode != null && state.pendingInstruction == null) item { DotMessageBubble(false) {
                Text(stringResource(if(state.errorCode=="provider_not_configured") R.string.nm_care_provider_missing
                    else R.string.nm_care_unavailable),fontSize=DotTokens.Type.Care)
            } }
            if (state.pendingInstruction != null && state.proposal == null) item { DotMessageBubble(false) {
                Text(state.pendingInstruction!!,fontSize=DotTokens.Type.Care)
            } }
        }
    }, controls = {
        Column(Modifier.fillMaxWidth(),
            verticalArrangement=Arrangement.spacedBy(DotTokens.Space.S8)) {
            if (editing || state.state==CareState.Clarifying) {
                OutlinedTextField(value=goal,onValueChange={ if(it.length<=2048) goal=it },
                    label={ Text(stringResource(R.string.nm_care_goal)) },modifier=Modifier.fillMaxWidth(),maxLines=4)
                CareButton(R.string.nm_care_start,{session.submitGoal(goal)},goal.isNotBlank())
            }
            CareActions {
                state.proposal?.let { proposal ->
                    if(proposal.copyText != null) CareButton(R.string.nm_care_copy,onCopy)
                    CareButton(R.string.nm_care_repeat,{ onRepeat(proposal.instruction) })
                }
                if(state.pendingInstruction != null) CareButton(R.string.nm_care_confirm,session.controller::confirmStepCompleted)
                if(state.state==CareState.Paused) CareButton(R.string.nm_care_resume,session::resume)
                else if(!editing) {
                    CareButton(R.string.nm_care_pause,{session.pause()})
                    CareButton(R.string.nm_care_reobserve,session::reobserve)
                }
                if(!editing) CareButton(R.string.nm_care_change_goal,session::changeGoal)
                CareButton(R.string.nm_care_end,session::end)
            }
        }
    })
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
