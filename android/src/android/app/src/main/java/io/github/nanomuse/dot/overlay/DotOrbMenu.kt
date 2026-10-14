package io.github.nanomuse.dot.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.dot.gesture.DotIntent
import io.github.nanomuse.dot.ui.LocalDotMotionEnabled
import io.github.nanomuse.dot.voice.DotVoiceController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Presentation only. Each composition is disposed with the one bounded native menu window. */
@Composable
internal fun DotOrbMenu(frame: DotOrbMenuFrame, voice: StateFlow<DotVoiceController.Ui>,
    onAction: (DotIntent) -> Unit) {
    val voiceUi by voice.collectAsState()
    val motion = LocalDotMotionEnabled.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val voiceActive = voiceUi.phase in setOf(DotVoiceController.Phase.PERMISSION,
        DotVoiceController.Phase.STARTING, DotVoiceController.Phase.FINISHING)
    val voiceRecording = voiceUi.phase == DotVoiceController.Phase.RECORDING
    val labels = listOf(R.string.nm_dot_open_conversation, R.string.nm_dot_view_screen,
        R.string.nm_dot_start_care, when {
            voiceRecording -> R.string.nm_dot_voice_finish
            voiceActive -> R.string.nm_dot_voice_cancel
            else -> R.string.nm_dot_voice_start
        })
    val icons = listOf(Icons.AutoMirrored.Filled.Chat, Icons.Default.CenterFocusStrong,
        Icons.Default.FavoriteBorder, when {
            voiceRecording -> Icons.Default.Done
            voiceActive -> Icons.Default.Close
            else -> Icons.Default.Mic
        })
    val intents = listOf(DotIntent.Single, DotIntent.Double, DotIntent.Triple, DotIntent.Voice)
    val title = stringResource(R.string.nm_more)
    Box(Modifier.fillMaxSize().semantics { paneTitle = title }.testTag("dot_orb_menu")) {
        frame.buttons.forEachIndexed { index, button ->
            val progress = remember { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(motion) {
                if (!motion) progress.snapTo(1f)
                else if (progress.value < 1f) {
                    delay(index * 24L)
                    progress.animateTo(1f, spring(dampingRatio = DotCrystalMotion.snapDampingRatio,
                        stiffness = DotCrystalMotion.snapStiffness))
                }
            }
            val p = progress.value.coerceIn(0f, 1f)
            val startX = frame.origin.x - button.width / 2f
            val startY = frame.origin.y - button.height / 2f
            val label = stringResource(labels[index])
            val enabled = !motion || p >= .98f
            val activate: () -> Unit = { onAction(intents[index]) }
            IconButton(onClick = activate, enabled = enabled,
                modifier = Modifier.offset { IntOffset(
                    (startX + (button.left - startX) * p - frame.bounds.left).roundToInt(),
                    (startY + (button.top - startY) * p - frame.bounds.top).roundToInt()) }
                    .size(with(density) { button.width.toDp() })
                    .testTag("dot_orb_menu_$index")
                    // One native AX button owns its label and action. Clear the Material/decorative
                    // descendant semantics so Android does not expose separate label/role children.
                    .clearAndSetSemantics {
                        contentDescription = label
                        role = Role.Button
                        if (enabled) onClick(label) { activate(); true } else disabled()
                    }) {
                // Scale decoration only. Native semantic/touch bounds remain a full 48dp.
                Surface(Modifier.fillMaxSize().graphicsLayer {
                    val scale = .65f + .35f * p
                    scaleX = scale; scaleY = scale; alpha = .3f + .7f * p
                }, shape = CircleShape,
                    color = lerp(colors.surface, colors.primary, .10f).copy(alpha = .82f),
                    contentColor = colors.primary,
                    border = BorderStroke(1.dp, Brush.linearGradient(listOf(
                        Color.White.copy(alpha = .85f), colors.primary.copy(alpha = .32f),
                        Color.White.copy(alpha = .48f))))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Icon(icons[index], contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
