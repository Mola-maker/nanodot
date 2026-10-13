package io.github.nanomuse.dot.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.dot.ui.DotTokens
import java.text.DateFormat
import java.util.Date

/** The service's focusable accessibility window renders the same VM state as the main chat. */
@Composable
fun DotChatPanel(adapter: DotChatAdapter, onCollapse: () -> Unit, onStop: (SessionBinding) -> Unit,
    foreground: io.github.nanomuse.dot.DotForegroundState? = null,
    placement: io.github.nanomuse.dot.overlay.ForegroundPlacement = io.github.nanomuse.dot.overlay.ForegroundPlacement.Bottom,
    onExpand: () -> Unit = {}, onAdjust: () -> Unit = {}, onDrag: (Float, Float) -> Unit = { _, _ -> },
    onTranscriptState: (androidx.compose.foundation.lazy.LazyListState) -> Unit = {},
    onContentHeightChanged: (Int) -> Unit = {},
    onMenuDismissHandler: ((() -> Boolean)?) -> Unit = {},
    onClearRegion: () -> Unit = {}, onReselectRegion: () -> Unit = {}) {
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
    val composerLabel = stringResource(R.string.nm_dot_chat_message)
    val listState = rememberLazyListState()
    SideEffect { onTranscriptState(listState) }
    val motion=io.github.nanomuse.dot.ui.LocalDotMotionEnabled.current
    LaunchedEffect(messages.size, preview) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            if(motion) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
            else listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }
    val visibleMessages = messages.filterNot { it.isInternalBridge }
    val fontScale = LocalDensity.current.fontScale
    fun textHeight(value: String) = estimateDotMessageTextHeight(value, DotTokens.Type.Body.value, fontScale)
    val emptyText = stringResource(R.string.nm_dot_chat_empty)
    val captureFailureText = stringResource(R.string.nm_dot_chat_capture_failed)
    val missingModelText = stringResource(R.string.nm_chat_no_model)
    val previewTextOnly = stringResource(R.string.nm_dot_chat_text_only)
    val regionSelectedText = stringResource(R.string.nm_dot_region_selected)
    val regionTextMode = stringResource(R.string.nm_dot_region_text_mode)
    val previewCaption = preview?.let {
        stringResource(R.string.nm_dot_chat_historical, DateFormat.getDateTimeInstance().format(Date(it.capturedAtMs)))
    }
    val historyItemHeights = buildList {
        if (visibleMessages.isEmpty()) add(textHeight(emptyText))
        visibleMessages.forEach { message ->
            val parts = listOf(textHeight(deltas[message.id]?.content ?: message.content)) +
                message.imageUris.map { 160f } + message.attachmentNames.map(::textHeight)
            add(16f + parts.sum() + (parts.size - 1) * 6f)
        }
        val extraParts = buildList {
            preview?.let {
                if (!it.textOnly) add(160f)
                add(textHeight(previewCaption.orEmpty()))
                if (it.textOnly) add(textHeight(previewTextOnly))
                if (it.regionText != null) {
                    add(textHeight(regionSelectedText))
                    if (!session.supportsImages()) add(textHeight(regionTextMode))
                    add(DotTokens.TouchTarget.value)
                }
                add(DotTokens.TouchTarget.value)
            }
            if (captureError) add(textHeight(captureFailureText))
            if (configured.isNullOrBlank()) add(textHeight(missingModelText))
            error?.let { add(textHeight(it)); add(DotTokens.TouchTarget.value) }
            if (compact) add(DotTokens.TouchTarget.value)
        }
        if (extraParts.isNotEmpty()) add(16f + extraParts.sum() + (extraParts.size - 1) * 6f)
    }
    // The final voice-evidence item keeps one list gap even when it has no visible content.
    val historyBudget = (historyItemHeights.sum() + historyItemHeights.size * 12f)
        .coerceIn(80f, 180f)
    DotForegroundChat(placementCompact = placement == io.github.nanomuse.dot.overlay.ForegroundPlacement.Compact,
        onExpand = onExpand, onAdjust = onAdjust, onDrag = onDrag,
        onContentHeightChanged = onContentHeightChanged, onMenuDismissHandler = onMenuDismissHandler,
        historyHeight = historyBudget,
        menu = { dismiss -> io.github.nanomuse.dot.voice.DotVoiceMenu(adapter, dismiss) },
        capsule = foreground?.let { state -> { io.github.nanomuse.dot.ui.DotForegroundStatus(state, { binding?.let(onStop) }) } },
        status = stringResource(if (busy) R.string.nm_status_replying else R.string.nm_status_waiting), onCollapse = onCollapse, history = {
            LazyColumn(Modifier.fillMaxSize().testTag("dot_transcript"), state = listState, verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S12, Alignment.Bottom)) {
                if (visibleMessages.isEmpty()) item { Text(emptyText, fontSize = DotTokens.Type.Body, lineHeight = DotTokens.Type.Body * 1.45f) }
                items(visibleMessages, key = { it.id }) { message ->
                    DotMessageBubble(message.role == "user") {
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
                if (preview != null || captureError || configured.isNullOrBlank() || error != null || compact) item {
                    DotMessageBubble(false) {
                        preview?.let { local ->
                            val bitmap = remember(local.file) { if (local.textOnly) null else BitmapFactory.decodeFile(local.file.path) }
                            DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
                            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                            Text(previewCaption.orEmpty(), fontSize = DotTokens.Type.Body)
                            if (local.textOnly) Text(previewTextOnly, fontSize = DotTokens.Type.Body)
                            if (local.regionText != null) {
                                Text(regionSelectedText, fontSize=DotTokens.Type.Body)
                                if (!session.supportsImages()) Text(regionTextMode, fontSize=DotTokens.Type.Body)
                                TextButton(onClick=onReselectRegion,modifier=Modifier.heightIn(min=DotTokens.TouchTarget)) {
                                    Text(stringResource(R.string.nm_dot_region_reselect),fontSize=DotTokens.Type.Body)
                                }
                            }
                            TextButton(onClick = { adapter.removePreview(); onClearRegion() }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                                Text(stringResource(R.string.nm_dot_chat_remove), fontSize = DotTokens.Type.Body)
                            }
                        }
                        if (captureError) Text(captureFailureText, fontSize = DotTokens.Type.Body)
                        if (configured.isNullOrBlank()) Text(missingModelText, fontSize = DotTokens.Type.Body)
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
                item { io.github.nanomuse.dot.voice.DotVoiceEvidence(adapter) }
            }
    }, controls = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DotDraftField(text, adapter::editInput, composerLabel,
                    Modifier.weight(1f).testTag("dot_composer"),
                    compact = placement == io.github.nanomuse.dot.overlay.ForegroundPlacement.Compact)
                io.github.nanomuse.dot.voice.DotVoiceControls(adapter)
                IconButton(onClick = { binding?.let { if(adapter.send(it, text)) onClearRegion() } },
                    enabled = binding != null && !busy && !configured.isNullOrBlank() && (text.isNotBlank() || preview != null),
                    modifier = Modifier.size(DotTokens.TouchTarget).testTag("dot_composer_send")) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.nm_dot_chat_send))
                }
            }
    })
}
