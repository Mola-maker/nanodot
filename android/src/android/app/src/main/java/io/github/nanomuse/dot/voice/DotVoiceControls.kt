package io.github.nanomuse.dot.voice

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.ui.DotTokens
import kotlinx.coroutines.flow.MutableStateFlow

/** These slots observe the existing voice owners. Dictation only edits the canonical draft. */
@Composable
fun DotVoiceControls(adapter: DotChatAdapter) {
    val voice = adapter.voice ?: return
    val ui by voice.ui.collectAsState()
    val player by adapter.speaker.collectAsState()
    val speaking by (player?.isSpeaking ?: remember { MutableStateFlow(false) }).collectAsState()
    val active = ui.phase in activeVoicePhases
    IconButton(onClick = voice::primaryAction, modifier = Modifier.size(DotTokens.TouchTarget)) {
        Icon(when {
            ui.phase == DotVoiceController.Phase.RECORDING -> Icons.Default.Done
            active -> Icons.Default.Close
            else -> Icons.Default.Mic
        }, stringResource(when {
            ui.phase == DotVoiceController.Phase.RECORDING -> R.string.nm_dot_voice_finish
            active -> R.string.nm_dot_voice_cancel
            else -> R.string.nm_dot_voice_start
        }))
    }
    if (ui.phase == DotVoiceController.Phase.RECORDING) IconButton(voice::cancel, Modifier.size(DotTokens.TouchTarget)) {
        Icon(Icons.Default.Close, stringResource(R.string.nm_dot_voice_cancel))
    }
    if (speaking) IconButton(adapter::stopReply, Modifier.size(DotTokens.TouchTarget)) {
        Icon(Icons.Default.Stop, stringResource(R.string.nm_dot_chat_stop))
    }
}

private val activeVoicePhases = setOf(DotVoiceController.Phase.PERMISSION, DotVoiceController.Phase.STARTING,
    DotVoiceController.Phase.RECORDING, DotVoiceController.Phase.FINISHING)

/** Voice detail belongs to the transcript, so input actions never become a button column. */
@Composable
fun DotVoiceEvidence(adapter: DotChatAdapter) {
    val voice = adapter.voice ?: return
    val ui by voice.ui.collectAsState()
    val player by adapter.speaker.collectAsState()
    val failed by (player?.playbackFailed ?: remember { MutableStateFlow(false) }).collectAsState()
    val status = when (ui.phase) {
        DotVoiceController.Phase.PERMISSION -> R.string.nm_dot_voice_permission
        DotVoiceController.Phase.STARTING -> R.string.nm_dot_voice_starting
        DotVoiceController.Phase.FINISHING -> R.string.nm_dot_voice_finishing
        DotVoiceController.Phase.DENIED -> R.string.nm_dot_voice_denied
        DotVoiceController.Phase.BUSY -> R.string.nm_dot_voice_busy
        DotVoiceController.Phase.ERROR -> R.string.nm_dot_voice_error
        else -> null
    }
    if (status != null || ui.error != null || !ui.detail.isNullOrBlank() || failed) {
        io.github.nanomuse.dot.chat.DotMessageBubble(false) {
            status?.let { Text(stringResource(it), fontSize = DotTokens.Type.Body) }
            ui.error?.let { Text(it.name, fontSize = DotTokens.Type.Body) }
            ui.detail?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = DotTokens.Type.Body) }
            if (failed) Text(stringResource(R.string.nm_dot_voice_read_error), fontSize = DotTokens.Type.Body)
        }
    }
}

@Composable
fun DotVoiceMenu(adapter: DotChatAdapter, dismiss: () -> Unit) {
    val voice = adapter.voice ?: return
    val ui by voice.ui.collectAsState()
    val player by adapter.speaker.collectAsState()
    val speaking by (player?.isSpeaking ?: remember { MutableStateFlow(false) }).collectAsState()
    val capture by SpeechRecognitionManager.ownedState.collectAsState()
    val context = LocalContext.current
    val session = adapter.session ?: return
    val messages by session.messages.collectAsState()
    val streaming by session.isStreaming.collectAsState()
    val reply = messages.lastOrNull { it.role == "assistant" && !it.isInternalBridge }?.content.orEmpty()
    val active = ui.phase in activeVoicePhases
    if (active) DropdownMenuItem(text = { Text(stringResource(R.string.nm_dot_voice_cancel)) },
        onClick = { dismiss(); voice.cancel() }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
    if (ui.phase == DotVoiceController.Phase.DENIED || ui.phase == DotVoiceController.Phase.ERROR) {
        DropdownMenuItem(text = { Text(stringResource(R.string.nm_refusal_open_settings)) }, onClick = {
            dismiss()
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
    }
    if (reply.isNotBlank() || speaking) DropdownMenuItem(
        text = { Text(stringResource(if (speaking) R.string.nm_dot_chat_stop else R.string.nm_dot_voice_read)) },
        onClick = { dismiss(); if (speaking) adapter.stopReply() else adapter.readReply(context, reply) },
        enabled = speaking || (!streaming && !active && capture.state == RecognitionState.IDLE),
        modifier = Modifier.heightIn(min = DotTokens.TouchTarget))
}
