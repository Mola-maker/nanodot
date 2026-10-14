package io.github.nanomuse.dot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.openminis.app.R

@Composable
fun DotStatusSurface(state: DotPageState, onRetry: () -> Unit, content: @Composable () -> Unit) {
    if (state == DotPageState.Ready) {
        content()
        return
    }
    val message = when (state) {
        DotPageState.Loading -> stringResource(R.string.nm_dot_loading)
        is DotPageState.Empty -> state.message
        is DotPageState.Error -> state.message
        DotPageState.Ready -> ""
    }
    Column(
        Modifier.fillMaxWidth().padding(DotTokens.Space.S16)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DotTokens.Space.S12)) {
            when (state) {
                DotPageState.Loading -> if (LocalDotMotionEnabled.current) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.HourglassEmpty, null,
                        Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is DotPageState.Error -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Outlined.Inbox, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
        if (state is DotPageState.Error && state.retryable) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                Text(stringResource(R.string.nm_avatar_retry))
            }
        }
    }
}

/** Only the owner of a cancellable operation supplies onCancel. Ready never fabricates success. */
@Composable
fun DotOperationStatus(state: DotPageState, onRetry: () -> Unit, onCancel: (() -> Unit)? = null) {
    DotStatusSurface(state, onRetry) {}
    if (state == DotPageState.Loading && onCancel != null) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.padding(horizontal = DotTokens.Space.S16).heightIn(min = DotTokens.TouchTarget)) {
            Text(stringResource(R.string.nm_cancel))
        }
    }
}

/** Result text, icon and announcement all describe the actual result. */
@Composable
fun DotResultSurface(message: String, success: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(DotTokens.Space.S16).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DotTokens.Space.S12)) {
        Icon(if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
            tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
    }
}
