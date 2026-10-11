package io.github.nanomuse.dot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.openminis.app.R

/** Compact task-page header. The saved agent identity stays intact. */
@Composable
fun DotHomeHeader(name: String, status: String?, onProfile: () -> Unit, onSessions: () -> Unit,
                  onNewChat: () -> Unit, onMore: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(DotTokens.Space.S4),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSessions) { Icon(Icons.Default.Menu, stringResource(R.string.nm_open_drawer)) }
            TextButton(onClick = onProfile, modifier = Modifier.weight(1f)) {
                Column {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    if (!status.isNullOrBlank()) Text(status, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            IconButton(onClick = onNewChat) { Icon(Icons.Default.Add, stringResource(R.string.nm_drawer_new_chat)) }
            if (onMore != null) IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreHoriz, stringResource(R.string.nm_more))
            }
        }
    }
}
