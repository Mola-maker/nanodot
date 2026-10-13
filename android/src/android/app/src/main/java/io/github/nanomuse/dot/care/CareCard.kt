package io.github.nanomuse.dot.care

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import io.github.nanomuse.dot.ui.LocalDotMotionEnabled
import io.github.nanomuse.dot.chat.DotForegroundChat
import io.github.nanomuse.dot.chat.DotMessageBubble
import io.github.nanomuse.dot.chat.estimateDotMessageTextHeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import com.openminis.app.R
import io.github.nanomuse.dot.ui.DotTokens

@Composable
fun CareCard(proposal: CareProposal, onCopy: () -> Unit, onPause: () -> Unit,
    onReobserve: () -> Unit, onEnd: () -> Unit, onRepeat: () -> Unit = {}) {
    var more by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
        Text(proposal.instruction, fontSize = DotTokens.Type.Care)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (proposal.copyText != null) CareButton(R.string.nm_care_copy, onCopy)
            Box {
                IconButton({ more = true }, Modifier.size(DotTokens.TouchTarget)) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.nm_more))
                }
                DropdownMenu(more, { more = false }) {
                    for ((label, action) in listOf(R.string.nm_care_repeat to onRepeat,
                        R.string.nm_care_pause to onPause, R.string.nm_care_reobserve to onReobserve,
                        R.string.nm_care_end to onEnd)) {
                        DropdownMenuItem(text = { Text(stringResource(label)) },
                            onClick = { more = false; action() }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
                    }
                }
            }
        }
    }
}

@Composable
private fun CareButton(label: Int, action: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick=action,enabled=enabled,modifier=Modifier.heightIn(min=DotTokens.TouchTarget)) {
        Text(stringResource(label),fontSize=DotTokens.Type.Body)
    }
}

@Composable
internal fun CarePanel(session: CareSession, onCopy: () -> Unit, onRepeat: (String) -> Unit,
    capsule: (@Composable (String) -> Unit)? = null, placementCompact: Boolean = false,
    onExpand: () -> Unit = {}, onAdjust: () -> Unit = {}, onDrag: (Float, Float) -> Unit = { _, _ -> },
    onContentHeightChanged: (Int) -> Unit = {},
    onMenuDismissHandler: ((() -> Boolean)?) -> Unit = {}) {
    val state by session.controller.state.collectAsState()
    val editing by session.editingGoal.collectAsState()
    val history by session.conversation.state.collectAsState()
    var goal by remember(editing,state.goal,state.question) { mutableStateOf(state.goal) }
    val status = stringResource(careStatusLabel(if (editing) CareState.Idle else state.state))
    val goalLabel = stringResource(R.string.nm_care_goal)
    val list = rememberLazyListState()
    val current = if (state.proposal != null) session.instructionKey() else null
    val motion=LocalDotMotionEnabled.current
    LaunchedEffect(history.size, current) {
        if (list.layoutInfo.totalItemsCount > 0) {
            if(motion) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
            else list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    val primary = carePrimary(state, editing)
    val fontScale = LocalDensity.current.fontScale
    fun careTextHeight(value: String) = estimateDotMessageTextHeight(value, DotTokens.Type.Care.value, fontScale)
    fun bodyTextHeight(value: String) = estimateDotMessageTextHeight(value, DotTokens.Type.Body.value, fontScale)
    val introText = stringResource(R.string.nm_care_intro)
    val confirmedText = stringResource(R.string.nm_care_step_confirmed)
    val errorText = if (state.errorCode != null && state.pendingInstruction == null) {
        stringResource(if (state.errorCode == "provider_not_configured") R.string.nm_care_provider_missing else R.string.nm_care_unavailable)
    } else null
    val pendingText = state.pendingInstruction.takeIf { state.proposal == null }
    val historyItemHeights = buildList {
        if (history.isEmpty()) add(16f + careTextHeight(introText))
        history.forEach { row ->
            val parts = buildList {
                if (row.verified) add(bodyTextHeight(confirmedText))
                add(careTextHeight(row.text))
                if (row.id == current) state.proposal?.copyText?.let {
                    add(bodyTextHeight(it))
                    add(DotTokens.TouchTarget.value)
                }
            }
            add(16f + parts.sum() + (parts.size - 1) * 6f)
        }
        errorText?.let { add(16f + careTextHeight(it)) }
        pendingText?.let { add(16f + careTextHeight(it)) }
    }
    val historyBudget = (historyItemHeights.sum() + (historyItemHeights.size - 1).coerceAtLeast(0) * 12f)
        .coerceIn(80f, 180f)
    DotForegroundChat(status, { session.pause(hide = true) }, capsule = capsule?.let { render -> { render(status) } },
        onContentHeightChanged = onContentHeightChanged, onMenuDismissHandler = onMenuDismissHandler,
        historyHeight = historyBudget,
        controlsHeight = if (primary == CarePrimary.None) 0f else 64f,
        menu = { dismiss ->
            fun action(block: () -> Unit) { dismiss(); block() }
            state.proposal?.let { proposal ->
                if (proposal.copyText != null) DropdownMenuItem(text = { Text(stringResource(R.string.nm_care_copy)) },
                    onClick = { action(onCopy) }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
                DropdownMenuItem(text = { Text(stringResource(R.string.nm_care_repeat)) },
                    onClick = { action { onRepeat(proposal.instruction) } }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
            }
            if (!editing && state.state != CareState.Paused && state.state != CareState.Completed) {
                DropdownMenuItem(text = { Text(stringResource(R.string.nm_care_pause)) },
                    onClick = { action { session.pause() } }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
                DropdownMenuItem(text = { Text(stringResource(R.string.nm_care_reobserve)) },
                    onClick = { action(session::reobserve) }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
            }
            if (!editing) DropdownMenuItem(text = { Text(stringResource(R.string.nm_care_change_goal)) },
                onClick = { action(session::changeGoal) }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
            DropdownMenuItem(text = { Text(stringResource(R.string.nm_care_end)) },
                onClick = { action(session::end) }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
        },
        placementCompact = placementCompact, onExpand = onExpand, onAdjust = onAdjust, onDrag = onDrag, history = {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S12, Alignment.Bottom)) {
            if (history.isEmpty()) item { DotMessageBubble(false) {
                Text(introText, fontSize = DotTokens.Type.Care, lineHeight = DotTokens.Type.Care * 1.45f)
            } }
            items(history, key = { it.id }) { row ->
                DotMessageBubble(row.user) {
                    if (row.verified) Text(confirmedText, fontSize = DotTokens.Type.Body)
                    Text(row.text, fontSize = DotTokens.Type.Care, lineHeight = DotTokens.Type.Care * 1.45f)
                    if (row.id == current && state.proposal?.copyText != null) {
                        Text(state.proposal!!.copyText!!, fontSize = DotTokens.Type.Body)
                        TextButton(onCopy, Modifier.heightIn(min = DotTokens.TouchTarget)) { Text(stringResource(R.string.nm_care_copy)) }
                    }
                }
            }
            if (errorText != null) item { DotMessageBubble(false) {
                Text(errorText, fontSize = DotTokens.Type.Care, lineHeight = DotTokens.Type.Care * 1.45f)
            } }
            if (pendingText != null) item { DotMessageBubble(false) {
                Text(pendingText, fontSize = DotTokens.Type.Care, lineHeight = DotTokens.Type.Care * 1.45f)
            } }
        }
    }, controls = {
        when (primary) {
            CarePrimary.Submit -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                io.github.nanomuse.dot.chat.DotDraftField(goal, { if (it.length <= 2048) goal = it }, goalLabel,
                    Modifier.weight(1f), compact = placementCompact)
                IconButton({ session.submitGoal(goal) }, enabled = goal.isNotBlank(), modifier = Modifier.size(DotTokens.TouchTarget)) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.nm_care_start))
                }
            }
            CarePrimary.Confirm, CarePrimary.Resume, CarePrimary.Reobserve -> Button(onClick = {
                when (primary) {
                    CarePrimary.Confirm -> session.controller.confirmStepCompleted()
                    CarePrimary.Resume -> session.resume()
                    CarePrimary.Reobserve -> session.reobserve()
                    else -> Unit
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = DotTokens.TouchTarget).testTag("care_primary")) {
                Text(stringResource(when (primary) {
                    CarePrimary.Confirm -> R.string.nm_care_confirm
                    CarePrimary.Resume -> R.string.nm_care_resume
                    else -> R.string.nm_care_reobserve
                }), fontSize = DotTokens.Type.Body)
            }
            CarePrimary.None -> Unit
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
