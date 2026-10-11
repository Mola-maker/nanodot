package io.github.nanomuse.dot.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.dot.ui.DotTokens

/** Shared chrome inside a bounded native window; no transparent full-screen touch surface. */
@Composable
fun DotForegroundChat(status: String? = null, onCollapse: () -> Unit,
    history: @Composable () -> Unit, controls: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp),
        color = colors.surface.copy(alpha = .97f), contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = .35f)), tonalElevation = 4.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 280.dp * LocalDensity.current.fontScale
            val padding = if (compact) 4.dp else DotTokens.Space.S12
            val controlsHeight = if (compact) 48.dp else minOf(160.dp, maxHeight * .42f)
            val collapse = stringResource(R.string.nm_dot_chat_collapse)
            CompositionLocalProvider(LocalTextStyle provides if (compact)
                MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp) else LocalTextStyle.current) {
            Column(Modifier.fillMaxSize().padding(padding).padding(end = if (compact) 52.dp else 0.dp),
                // Reserve rounding headroom for the 48dp history in short hosts at enlarged fonts.
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp)) {
            if (compact) {
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics {
                        stateDescription = it; liveRegion = LiveRegionMode.Polite
                    }) }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dot", fontSize = DotTokens.Type.Body)
                    status?.let { Text(it, fontSize = DotTokens.Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics {
                        stateDescription = it; liveRegion = LiveRegionMode.Polite
                    }) }
                }
                TextButton(onClick = onCollapse, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                    Text(collapse, fontSize = DotTokens.Type.Body)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("dot_foreground_history")) { history() }
            Column(Modifier.height(controlsHeight).fillMaxWidth().testTag("dot_foreground_controls")
                .verticalScroll(rememberScrollState())) { controls() }
            }
            if (compact) IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = collapse)
            }
            }
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
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
