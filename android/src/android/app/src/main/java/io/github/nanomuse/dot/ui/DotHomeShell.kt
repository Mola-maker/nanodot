package io.github.nanomuse.dot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nanomuse.ui.home.MuseBottomBar

/** Public shell contract for hosts without a session-list pane. */
@Composable
fun DotHomeShell(route: DotHomeRoute, onRoute: (DotHomeRoute) -> Unit, content: @Composable () -> Unit) {
    DotHomeShell(route, onRoute, null, content)
}

/** Adapts to the allocated window pane, keeping the content at one composition location. */
@Composable
fun DotHomeShell(
    route: DotHomeRoute,
    onRoute: (DotHomeRoute) -> Unit,
    conversationList: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val scale = LocalDensity.current.fontScale
        val layout = DotNavigationPolicy.layout(maxWidth.value.toInt(), scale)
        val compact = layout == DotLayout.Compact
        val shortWindow = maxHeight < 480.dp
        val showList = route == DotHomeRoute.Chat && conversationList != null &&
            DotNavigationPolicy.showConversationList(maxWidth.value.toInt(), scale)
        Row(Modifier.fillMaxSize()) {
            // Empty slots stay mounted during resizing; content never moves between branches.
            Box(Modifier.width(if (compact) 0.dp else DotNavigationPolicy.railWidth(scale).dp).fillMaxHeight()) {
                if (!compact) {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                        Column(
                            // Keep destinations available while the real chat IME consumes its own pane.
                            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical))
                                .verticalScroll(rememberScrollState()).selectableGroup().padding(DotTokens.Space.S8),
                            verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S8),
                        ) {
                            DotHomeRoute.entries.forEach { destination ->
                                DotDestination(destination, destination == route, { onRoute(destination) }, Modifier.fillMaxWidth(), horizontal = shortWindow, showIcon = !shortWindow || scale < 2f)
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(if (showList) 320.dp else 0.dp).fillMaxHeight()) {
                if (showList) conversationList?.invoke()
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                if (compact) MuseBottomBar(selected = route, onSelect = onRoute)
            }
        }
    }
}

@Composable
internal fun DotDestination(route: DotHomeRoute, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, horizontal: Boolean = false, showIcon: Boolean = true) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.selectable(selected, role = Role.Tab, onClick = onClick),
    ) {
        val itemModifier = Modifier.defaultMinSize(minHeight = DotTokens.TouchTarget).padding(DotTokens.Space.S8)
        val label: @Composable () -> Unit = {
            Text(stringResource(route.label), style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (horizontal) {
            Row(itemModifier, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
                if (showIcon) Icon(route.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Box(Modifier.weight(1f)) { label() }
            }
        } else {
            Column(itemModifier, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S4)) {
                if (showIcon) Icon(route.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                label()
            }
        }
    }
}
