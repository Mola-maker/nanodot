package io.github.nanomuse.dot.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.openminis.app.R

val LocalDotMotionEnabled = staticCompositionLocalOf { true }

/** Observe the native preference, including changes while the Activity stays mounted. */
@Composable
fun DotMotionHost(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motion by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motion = enabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalDotMotionEnabled provides motion, content = content)
}

/** The real ChatViewModel supplies state and handlers. There is no second agent or draft. */
@Composable
fun DotChatChrome(isStreaming: Boolean, hasContent: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val stop = isStreaming && !hasContent
    val label = stringResource(if (stop) R.string.nm_coding_stop else R.string.nm_coding_send)
    val working = stringResource(R.string.nm_status_replying)
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val target = when { pressed -> colors.primaryContainer; focused -> colors.secondaryContainer; else -> colors.primary }
    val background by animateColorAsState(target, tween(if (!LocalDotMotionEnabled.current) 0 else if (focused && !pressed) DotTokens.Motion.Focus else DotTokens.Motion.Press), label = "dotChatAction")
    IconButton(
        onClick = if (stop) onStop else onSend,
        enabled = stop || hasContent,
        interactionSource = interactions,
        modifier = Modifier.size(DotTokens.TouchTarget)
            .background(if (stop || hasContent) background else colors.surfaceVariant, CircleShape)
            .semantics { if (isStreaming) stateDescription = working },
        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onPrimary, disabledContentColor = colors.onSurfaceVariant),
    ) {
        Icon(if (stop) Icons.Default.Stop else Icons.Default.ArrowUpward, label)
    }
}
