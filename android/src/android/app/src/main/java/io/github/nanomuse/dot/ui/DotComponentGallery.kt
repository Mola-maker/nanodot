package io.github.nanomuse.dot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.openminis.app.R

/** Reusable native component specimens. Local choices demonstrate states; no business work runs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotComponentGallery() {
    var input by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(DotTokens.Space.S16),
            verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S16)) {
            Text(stringResource(R.string.nm_dot_components), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { dialog = true }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) { Text(stringResource(R.string.nm_more)) }
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) { Text(stringResource(R.string.nm_coding_send)) }
            DotChatChrome(isStreaming = true, hasContent = false, onSend = {}, onStop = {})
            OutlinedTextField(input, { input = it }, label = { Text(stringResource(R.string.nm_composer_placeholder)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(input, {}, enabled = false, label = { Text(stringResource(R.string.nm_composer_placeholder)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(input, { input = it }, isError = true, label = { Text(stringResource(R.string.nm_composer_placeholder)) },
                supportingText = { Text(stringResource(R.string.nm_dot_load_failed)) }, modifier = Modifier.fillMaxWidth())
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(DotTokens.TouchTarget)) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.nm_more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.nm_media_ready)) }, onClick = { menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.nm_coding_send)) }, onClick = {}, enabled = false)
                }
            }
            val switchLabel = stringResource(R.string.nm_dot_components) + " · " + stringResource(R.string.nm_media_ready)
            val checkLabel = stringResource(R.string.nm_dot_components) + " · " + stringResource(R.string.nm_task_library)
            Row { Switch(checked, { checked = it }, modifier = Modifier.semantics { contentDescription = switchLabel })
                Checkbox(checked, { checked = it }, modifier = Modifier.semantics { contentDescription = checkLabel }) }
            Row { Switch(false, {}, enabled = false, modifier = Modifier.semantics { contentDescription = switchLabel })
                Checkbox(false, {}, enabled = false, modifier = Modifier.semantics { contentDescription = checkLabel }) }
            Card(Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(stringResource(R.string.nm_media_ready)) }) }
            DotStatusSurface(DotPageState.Loading, {}) {}
            DotStatusSurface(DotPageState.Empty(stringResource(R.string.nm_dot_empty)), {}) {}
            DotStatusSurface(DotPageState.Error(stringResource(R.string.nm_dot_load_failed), true), {}) {}
            DotStatusSurface(DotPageState.Error(stringResource(R.string.nm_dot_load_failed), false), {}) {}
            DotStatusSurface(DotPageState.Ready, {}) { DotResultSurface(stringResource(R.string.nm_media_ready), true) }
            OutlinedButton(onClick = { sheet = true }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) { Text(stringResource(R.string.nm_task_library)) }
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text(stringResource(R.string.nm_dot_components)) },
        text = { Text(stringResource(R.string.nm_dot_empty)) }, confirmButton = { TextButton(onClick = { dialog = false }) { Text(stringResource(R.string.nm_cancel)) } })
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        DotStatusSurface(DotPageState.Empty(stringResource(R.string.nm_dot_empty)), {}) {}
        OutlinedButton(onClick = { sheet = false }, modifier = Modifier.padding(DotTokens.Space.S16)) { Text(stringResource(R.string.nm_cancel)) }
    }
}
