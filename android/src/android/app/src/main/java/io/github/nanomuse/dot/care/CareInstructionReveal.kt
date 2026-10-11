package io.github.nanomuse.dot.care

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*

/** Current-instruction animation; actual measurement admits the visual sequence. */
@Composable
internal fun rememberCareInstructionReveal(current: String?, motion: Boolean, measured: Boolean): Float {
    val cue = remember(current, motion) { Animatable(if (current == null || !motion) 1f else 0f) }
    LaunchedEffect(cue, measured) {
        if (current != null && motion && measured) cue.animateTo(1f, tween(980, easing = LinearEasing))
    }
    return cue.value
}
