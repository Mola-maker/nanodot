package io.github.nanomuse.dot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.dot.DotForegroundMode
import io.github.nanomuse.dot.DotForegroundOperation
import io.github.nanomuse.dot.DotForegroundState

/** Presentation of current owner evidence; no animation or color implies completion/readiness. */
@Composable
fun DotForegroundStatus(state: DotForegroundState, onStop: () -> Unit, modifier: Modifier = Modifier,
    fallbackDescription: String? = null) {
    val description = if (foregroundUsesFallback(state) && fallbackDescription != null) fallbackDescription
        else stringResource(foregroundStatusResource(state))
    Row(modifier.fillMaxWidth().heightIn(min = DotTokens.TouchTarget).testTag("dot_foreground_status"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(description, modifier = Modifier.weight(1f).semantics {
            stateDescription = description; liveRegion = LiveRegionMode.Polite
        }, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onStop, enabled = canStopForeground(state),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = DotTokens.TouchTarget).testTag("dot_foreground_stop")) {
            Text(stringResource(R.string.nm_dot_chat_stop), fontSize = DotTokens.Type.Body,
                lineHeight = 20.sp, maxLines = 1, softWrap = false)
        }
    }
}

internal fun foregroundStatusResource(state: DotForegroundState): Int = when {
        !state.connected -> R.string.nm_dot_foreground_disconnected
        state.operation == DotForegroundOperation.Capturing -> R.string.nm_dot_foreground_capturing
        state.operation == DotForegroundOperation.Replying -> R.string.nm_status_replying
        state.operation == DotForegroundOperation.Listening -> R.string.nm_dot_voice_recording
        state.operation == DotForegroundOperation.Error -> R.string.nm_dot_foreground_error
        else -> R.string.nm_status_waiting
}

internal fun canStopForeground(state: DotForegroundState): Boolean =
    state.connected && state.mode != DotForegroundMode.Idle

/** Task status is used only when no higher-priority owner operation is present. */
internal fun foregroundUsesFallback(state: DotForegroundState): Boolean =
    state.connected && state.operation in setOf(DotForegroundOperation.Idle, DotForegroundOperation.CareWaiting)
