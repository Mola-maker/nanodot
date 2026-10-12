package io.github.nanomuse.dot.chat

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import io.github.nanomuse.dot.ui.DotGlassSurface
import io.github.nanomuse.dot.ui.DotGlassPlate
import io.github.nanomuse.dot.ui.dotGlassPlateColor

/** Shared chrome inside a bounded native window; no transparent full-screen touch surface. */
@Composable
fun DotForegroundChat(status: String? = null, onCollapse: () -> Unit,
    history: @Composable () -> Unit, controls: @Composable () -> Unit,
    placementCompact: Boolean = false, onExpand: () -> Unit = {},
    onAdjust: () -> Unit = {}, onDrag: (Float, Float) -> Unit = { _, _ -> },
    capsule: (@Composable () -> Unit)? = null) {
    DotGlassSurface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = placementCompact || maxHeight < 280.dp * LocalDensity.current.fontScale
            val padding = if (compact) 4.dp else DotTokens.Space.S12
            val controlsHeight = if (compact) 48.dp else minOf(160.dp, maxHeight * .42f)
            val collapse = stringResource(R.string.nm_dot_chat_collapse)
            CompositionLocalProvider(LocalTextStyle provides if (compact)
                MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp) else LocalTextStyle.current) {
            Column(Modifier.fillMaxSize().padding(padding).padding(end = if (compact) 52.dp else 0.dp),
                // Reserve rounding headroom for the 48dp history in short hosts at enlarged fonts.
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp)) {
            if (!compact && capsule != null) DotGlassPlate(Modifier.fillMaxWidth()) { capsule() }
            if (compact) {
                status?.let { DotGlassPlate(shape = RoundedCornerShape(4.dp)) { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp).semantics {
                        stateDescription = it; liveRegion = LiveRegionMode.Polite
                    }) } }
            } else DotGlassPlate(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("dot_foreground_history")) {
                // Protect bare empty-state text only; the unused transcript stays transparent.
                CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(background = dotGlassPlateColor())) {
                    history()
                }
            }
            DotGlassPlate(Modifier.height(controlsHeight).fillMaxWidth().testTag("dot_foreground_controls")) {
            Column(Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())) {
            if (compact) capsule?.invoke()
            if (placementCompact) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onExpand, Modifier.weight(1f).heightIn(min = DotTokens.TouchTarget).testTag("dot_foreground_expand")) {
                        Text(stringResource(R.string.nm_dot_foreground_expand))
                    }
                    TextButton(onAdjust, Modifier.weight(1f).heightIn(min = DotTokens.TouchTarget).testTag("dot_foreground_adjust")
                        .pointerInput(onDrag) { detectDragGestures { change, amount -> change.consume(); onDrag(amount.x, amount.y) } }) {
                        Text(stringResource(R.string.nm_dot_foreground_adjust))
                    }
                }
            }
                controls()
            }
            }
            }
            if (compact) DotGlassPlate(Modifier.align(Alignment.TopEnd).size(48.dp), shape = RoundedCornerShape(24.dp)) {
                IconButton(onClick = onCollapse, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = collapse)
                }
            }
            }
        }
    }
}

@Composable
fun DotMessageBubble(user: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val sender = stringResource(if (user) R.string.nm_dot_bubble_you else R.string.nm_dot_bubble_assistant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        DotGlassPlate(modifier.fillMaxWidth(.9f).semantics { contentDescription = sender },
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (user) 18.dp else 4.dp, bottomEnd = if (user) 4.dp else 18.dp),
            user = user) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
