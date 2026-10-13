package io.github.nanomuse.dot.chat

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.dot.ui.*
import kotlin.math.ceil

internal val LocalDotForegroundTight = staticCompositionLocalOf { false }

/** Reserves text space at the bubble's 296dp content width; the viewport still owns clipping/scrolling. */
internal fun estimateDotMessageTextHeight(text: String, fontSize: Float, fontScale: Float): Float {
    val lines = text.lineSequence().sumOf { line ->
        val ems = line.sumOf { if (it.code < 0x100) .55 else 1.0 }
        ceil(ems * fontSize * fontScale / 296.0).toInt().coerceAtLeast(1)
    }
    // DotForegroundChat applies fontScale to the final request, including this line height.
    return lines * fontSize * 1.45f
}

/** Shared chrome inside a bounded native window; only the transcript scrolls. */
@Composable
fun DotForegroundChat(status: String? = null, onCollapse: () -> Unit,
    history: @Composable () -> Unit, controls: @Composable () -> Unit,
    placementCompact: Boolean = false, onExpand: () -> Unit = {},
    onAdjust: () -> Unit = {}, onDrag: (Float, Float) -> Unit = { _, _ -> },
    capsule: (@Composable () -> Unit)? = null,
    menu: @Composable (() -> Unit) -> Unit = {},
    historyHeight: Float = 96f, controlsHeight: Float = 56f,
    onContentHeightChanged: (Int) -> Unit = {},
    onMenuDismissHandler: ((() -> Boolean)?) -> Unit = {}) {
    val density = LocalDensity.current
    // Estimate intrinsic content, never feed the constrained outer height back into its window.
    val requested = with(density) { (64f + historyHeight * fontScale + controlsHeight * fontScale).dp.roundToPx() }
    LaunchedEffect(requested) { onContentHeightChanged(requested) }
    var more by remember { mutableStateOf(false) }
    SideEffect { onMenuDismissHandler { if (more) { more = false; true } else false } }
    DisposableEffect(Unit) { onDispose { onMenuDismissHandler(null) } }
    val collapse = stringResource(R.string.nm_dot_chat_collapse)
    val adjust = stringResource(R.string.nm_dot_foreground_adjust)
    DotGlassSurface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val tight = maxHeight < 220.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = if (tight) 0.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (tight) 0.dp else 2.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = DotTokens.TouchTarget), verticalAlignment = Alignment.CenterVertically) {
                DotGlassPlate(Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Box(Modifier.fillMaxWidth().pointerInput(onDrag) {
                    detectDragGestures { change, amount -> change.consume(); onDrag(amount.x, amount.y) }
                }.semantics { contentDescription = adjust }.testTag("dot_foreground_drag")) {
                    if (capsule != null) capsule() else Text(status ?: "Dot",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("dot_foreground_status").semantics {
                            stateDescription = status ?: "Dot"; liveRegion = LiveRegionMode.Polite
                        })
                }
                }
                DotGlassPlate(shape = RoundedCornerShape(24.dp)) {
                IconButton(onCollapse, Modifier.size(DotTokens.TouchTarget)) {
                    Icon(Icons.Default.KeyboardArrowDown, collapse)
                }
                }
                DotGlassPlate(shape = RoundedCornerShape(24.dp)) {
                    IconButton({ more = !more }, Modifier.size(DotTokens.TouchTarget).testTag("dot_foreground_more")) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.nm_more))
                    }

                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("dot_foreground_history")) {
                // A naturally short card can still have ample history space. Compress message
                // padding only when this viewport cannot fit a scaled Care line plus its 16dp inset.
                val messageTight = maxHeight < with(density) { (DotTokens.Type.Care * 1.45f).toDp() } + 16.dp
                CompositionLocalProvider(LocalDotForegroundTight provides messageTight,
                    LocalTextStyle provides LocalTextStyle.current.copy(background = dotGlassPlateColor())) {
                    Box(Modifier.fillMaxSize().then(if (more) Modifier.clearAndSetSemantics {} else Modifier)) { history() }
                }
                if (more) DotGlassPlate(Modifier.fillMaxSize().testTag("dot_foreground_menu"), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val dismiss = { more = false }
                        menu(dismiss)
                        if (placementCompact) DropdownMenuItem(
                            text = { Text(stringResource(R.string.nm_dot_foreground_expand)) },
                            onClick = { more = false; onExpand() },
                            modifier = Modifier.heightIn(min = DotTokens.TouchTarget).testTag("dot_foreground_expand"))
                        DropdownMenuItem(text = { Text(adjust) }, onClick = { more = false; onAdjust() },
                            modifier = Modifier.heightIn(min = DotTokens.TouchTarget).testTag("dot_foreground_adjust"))
                    }
                }
            }
            CompositionLocalProvider(LocalDotForegroundTight provides tight) {
                Box(Modifier.fillMaxWidth().testTag("dot_foreground_controls")) { controls() }
            }
        }
        }
    }
}

@Composable
fun DotMessageBubble(user: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val sender = stringResource(if (user) R.string.nm_dot_bubble_you else R.string.nm_dot_bubble_assistant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        DotGlassPlate(modifier.widthIn(max = 320.dp).semantics { contentDescription = sender },
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (user) 18.dp else 4.dp, bottomEnd = if (user) 4.dp else 18.dp), user = user, message = true) {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = DotTokens.Type.Body * 1.45f)) {
                // Only a constrained history viewport drops this inset to keep its line visible.
                Column(Modifier.padding(horizontal = 12.dp, vertical = if (LocalDotForegroundTight.current) 0.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
            }
        }
    }
}
