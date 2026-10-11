package io.github.nanomuse.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.github.nanomuse.dot.ui.DotDestination
import io.github.nanomuse.dot.ui.DotHomeRoute
import io.github.nanomuse.dot.ui.DotTokens

/** Visible native destination labels reflow into two rows at enlarged system font sizes. */
@Composable
fun MuseBottomBar(selected: DotHomeRoute, onSelect: (DotHomeRoute) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup().padding(DotTokens.Space.S4)) {
            val columns = if (LocalDensity.current.fontScale >= 1.5f) 2 else 4
            DotHomeRoute.entries.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DotTokens.Space.S4)) {
                    row.forEach { route ->
                        DotDestination(route, route == selected, { onSelect(route) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
