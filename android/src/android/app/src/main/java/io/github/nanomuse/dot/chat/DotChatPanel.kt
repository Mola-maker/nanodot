package io.github.nanomuse.dot.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.dot.ui.DotTokens
import java.text.DateFormat
import java.util.Date

/** The service's focusable accessibility window renders the same VM state as the main chat. */
@Composable
fun DotChatPanel(adapter: DotChatAdapter, onCollapse: () -> Unit) {
    val binding by adapter.binding.collectAsState()
    val preview by adapter.preview.collectAsState()
    val captureError by adapter.captureError.collectAsState()
    val session = adapter.session ?: return
    val messages by session.messages.collectAsState()
    val text by session.inputText.collectAsState()
    val streaming by session.isStreaming.collectAsState()
    val error by session.error.collectAsState()
    val configured by session.configured.collectAsState()
    val deltas by session.deltas.collectAsState()
    val vm = adapter.businessViewModel
    val attachments by (vm?.attachments ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.openminis.app.ui.chat.InputAttachment>()) }).collectAsState()
    val compacting by (vm?.isCompacting ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val pendingCompactSend by (vm?.pendingCompactSend ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val busy = streaming || compacting || pendingCompactSend
    val consumed by session.consumedAttachments.collectAsState()
    val compact by (vm?.showCompactBeforeSendPrompt ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    LaunchedEffect(messages, error, streaming, attachments, consumed) { adapter.releaseConsumedFiles() }
    Surface(Modifier.fillMaxSize(), tonalElevation = 4.dp) {
        Column(Modifier.fillMaxSize().padding(DotTokens.Space.S16), verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Dot", fontSize = DotTokens.Type.Body, modifier = Modifier.padding(top = DotTokens.Space.S12))
                TextButton(onClick = onCollapse, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                    Text(stringResource(R.string.nm_dot_chat_collapse), fontSize = DotTokens.Type.Body)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S12)) {
                if (messages.isEmpty()) item { Text(stringResource(R.string.nm_dot_chat_empty), fontSize = DotTokens.Type.Body) }
                items(messages.filterNot { it.isInternalBridge }, key = { it.id }) { message ->
                    Column {
                        Text(deltas[message.id]?.content ?: message.content, fontSize = DotTokens.Type.Body)
                        // Persisted images keep their VM-owned media URIs after the preview is dismissed.
                        message.imageUris.forEach { uri ->
                            val bitmap = remember(uri) { uri.path?.let { BitmapFactory.decodeFile(it) } }
                            DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
                            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                        }
                        message.attachmentNames.forEach { Text(it, fontSize = DotTokens.Type.Body) }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
                        preview?.let { local ->
                            val bitmap = remember(local.file) { if (local.textOnly) null else BitmapFactory.decodeFile(local.file.path) }
                            DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
                            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                            Text(stringResource(R.string.nm_dot_chat_historical, DateFormat.getDateTimeInstance().format(Date(local.capturedAtMs))), fontSize = DotTokens.Type.Body)
                            if (local.textOnly) Text(stringResource(R.string.nm_dot_chat_text_only), fontSize = DotTokens.Type.Body)
                            TextButton(onClick = adapter::removePreview, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                                Text(stringResource(R.string.nm_dot_chat_remove), fontSize = DotTokens.Type.Body)
                            }
                        }
                        if (captureError) Text(stringResource(R.string.nm_dot_chat_capture_failed), fontSize = DotTokens.Type.Body)
                        if (configured.isNullOrBlank()) Text(stringResource(R.string.nm_chat_no_model), fontSize = DotTokens.Type.Body)
                        error?.let {
                            Text(it, fontSize = DotTokens.Type.Body)
                            TextButton(onClick = session::retry, enabled = !busy && !configured.isNullOrBlank(), modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                                Text(stringResource(R.string.nm_dot_chat_retry), fontSize = DotTokens.Type.Body)
                            }
                        }
                        if (compact) Row {
                            TextButton(onClick = { vm?.compactAndSendPending() }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) { Text(stringResource(R.string.nm_dot_chat_compact), fontSize = DotTokens.Type.Body) }
                            TextButton(onClick = { vm?.cancelCompactBeforeSend() }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) { Text(stringResource(R.string.nm_dot_chat_cancel), fontSize = DotTokens.Type.Body) }
                        }
                    }
                }
            }
            io.github.nanomuse.dot.voice.DotVoiceControls(adapter)
            OutlinedTextField(text, adapter::editInput, Modifier.fillMaxWidth().heightIn(min = DotTokens.TouchTarget).testTag("dot_composer"),
                label = { Text(stringResource(R.string.nm_dot_chat_message), fontSize = DotTokens.Type.Body) },
                textStyle = LocalTextStyle.current.copy(fontSize = DotTokens.Type.Body), maxLines = 4)
            Button(onClick = { binding?.let { if (busy) { adapter.cancel(it); adapter.bind(it.sessionId) } else adapter.send(it,text) } },
                enabled = binding != null && (busy || !configured.isNullOrBlank() && (text.isNotBlank() || preview != null)),
                modifier = Modifier.fillMaxWidth().heightIn(min = DotTokens.TouchTarget)) {
                Text(stringResource(if (busy) R.string.nm_dot_chat_stop else R.string.nm_dot_chat_send), fontSize = DotTokens.Type.Body)
            }
        }
    }
}
