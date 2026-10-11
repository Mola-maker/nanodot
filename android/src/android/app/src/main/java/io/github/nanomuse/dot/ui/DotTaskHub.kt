package io.github.nanomuse.dot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import io.github.nanomuse.ui.home.HomeTab

/** The existing Feed, Ideas, Goals and Library screens retain their actions and state keys. */
@Composable
fun DotTaskHub(selected: HomeTab, onSelect: (HomeTab) -> Unit, header: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth < androidx.compose.ui.unit.Dp(600f) && LocalDensity.current.fontScale >= 1.5f) 2 else 4
        Column {
            header()
            Column(Modifier.fillMaxWidth().selectableGroup().padding(DotTokens.Space.S8),
                verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S4)) {
                HomeTab.taskEntries.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(DotTokens.Space.S4)) {
                        row.forEach { tab ->
                            Surface(
                                color = if (tab == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f).selectable(tab == selected, role = Role.Tab, onClick = { onSelect(tab) }),
                            ) {
                                Text(stringResource(tab.label), style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.defaultMinSize(minHeight = DotTokens.TouchTarget).padding(DotTokens.Space.S12))
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
