package io.github.nanomuse.dot.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.dot.ui.DotTokens

/** Shared chrome inside a bounded native window; no transparent full-screen touch surface. */
@Composable
fun DotForegroundChat(status: String? = null, onCollapse: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp),
        color = colors.surface.copy(alpha = .97f), contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = .35f)), tonalElevation = 4.dp) {
        Column(Modifier.fillMaxSize().padding(DotTokens.Space.S12), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dot", fontSize = DotTokens.Type.Body)
                    status?.let { Text(it, fontSize = DotTokens.Type.Body, modifier = Modifier.semantics {
                        stateDescription = it; liveRegion = LiveRegionMode.Polite
                    }) }
                }
                TextButton(onClick = onCollapse, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                    Text(stringResource(R.string.nm_dot_chat_collapse), fontSize = DotTokens.Type.Body)
                }
            }
            content()
        }
    }
}

@Composable
fun DotMessageBubble(user: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sender = stringResource(if (user) R.string.nm_dot_bubble_you else R.string.nm_dot_bubble_assistant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(modifier.fillMaxWidth(.9f).semantics { contentDescription = sender },
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (user) 18.dp else 4.dp, bottomEnd = if (user) 4.dp else 18.dp),
            color = if (user) colors.primary.copy(alpha = .14f) else colors.surfaceVariant,
            contentColor = colors.onSurface) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
