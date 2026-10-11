package io.github.nanomuse.dot.voice

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
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

/** Lives inside the real accessibility overlay; text stays editable and Send remains explicit. */
@Composable
fun DotVoiceControls(adapter: DotChatAdapter) {
    val voice = adapter.voice ?: return
    val ui by voice.ui.collectAsState()
    val player by adapter.speaker.collectAsState()
    val speaking by (player?.isSpeaking ?: remember { MutableStateFlow(false) }).collectAsState()
    val failed by (player?.playbackFailed ?: remember { MutableStateFlow(false) }).collectAsState()
    val capture by SpeechRecognitionManager.ownedState.collectAsState()
    val context = LocalContext.current
    val session = adapter.session ?: return
    val messages by session.messages.collectAsState()
    val streaming by session.isStreaming.collectAsState()
    val reply = messages.lastOrNull { it.role == "assistant" && !it.isInternalBridge }?.content.orEmpty()
    val active = ui.phase in setOf(DotVoiceController.Phase.PERMISSION, DotVoiceController.Phase.STARTING,
        DotVoiceController.Phase.RECORDING, DotVoiceController.Phase.FINISHING)
    val status = when (ui.phase) {
        DotVoiceController.Phase.PERMISSION -> R.string.nm_dot_voice_permission
        DotVoiceController.Phase.STARTING -> R.string.nm_dot_voice_starting
        DotVoiceController.Phase.RECORDING -> R.string.nm_dot_voice_recording
        DotVoiceController.Phase.FINISHING -> R.string.nm_dot_voice_finishing
        DotVoiceController.Phase.DENIED -> R.string.nm_dot_voice_denied
        DotVoiceController.Phase.BUSY -> R.string.nm_dot_voice_busy
        DotVoiceController.Phase.ERROR -> R.string.nm_dot_voice_error
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S4)) {
        status?.let { Text(stringResource(it), fontSize = DotTokens.Type.Body) }
        ui.error?.let { Text(it.name, fontSize = DotTokens.Type.Body) }
        ui.detail?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = DotTokens.Type.Body) }
        Row(horizontalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
            if (!active) TextButton(onClick = { adapter.stopReply(); voice.start() }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                Text(stringResource(R.string.nm_dot_voice_start), fontSize = DotTokens.Type.Body)
            }
            if (ui.phase == DotVoiceController.Phase.RECORDING) TextButton(onClick = voice::stop, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                Text(stringResource(R.string.nm_dot_voice_finish), fontSize = DotTokens.Type.Body)
            }
            if (active) TextButton(onClick = voice::cancel, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                Text(stringResource(R.string.nm_dot_voice_cancel), fontSize = DotTokens.Type.Body)
            }
            if (ui.phase == DotVoiceController.Phase.DENIED || ui.phase == DotVoiceController.Phase.ERROR) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                Text(stringResource(R.string.nm_refusal_open_settings), fontSize = DotTokens.Type.Body)
            }
        }
        if (reply.isNotBlank() || speaking) TextButton(onClick = {
            if (speaking) adapter.stopReply() else adapter.readReply(context, reply)
        }, enabled = speaking || (!streaming && !active && capture.state == RecognitionState.IDLE),
            modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
            Text(stringResource(if (speaking) R.string.nm_dot_chat_stop else R.string.nm_dot_voice_read), fontSize = DotTokens.Type.Body)
        }
        if (speaking) Text(stringResource(R.string.nm_dot_voice_speaking), fontSize = DotTokens.Type.Body)
        if (failed) Text(stringResource(R.string.nm_dot_voice_read_error), fontSize = DotTokens.Type.Body)
    }
}
