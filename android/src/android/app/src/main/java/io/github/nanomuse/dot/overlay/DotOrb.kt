package io.github.nanomuse.dot.overlay

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.dot.DotColors
import io.github.nanomuse.dot.gesture.DotIntent
import io.github.nanomuse.dot.ui.LocalDotMotionEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** The packaged imagegen atlas is decorative; only the existing button exposes semantics. */
@Composable
fun DotOrb(
    onIntent: (DotIntent) -> Unit,
    careActive: Boolean = false,
    active: Boolean = true,
    pressed: Boolean = false,
    dragging: Boolean = false,
    onMotionPreference: (Boolean) -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val motion = LocalDotMotionEnabled.current
    SideEffect { onMotionPreference(motion) }
    val atlas = ImageBitmap.imageResource(R.drawable.nm_crystal_orb_atlas)
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(motion, active) {
        elapsed = 0L
        if (motion && active) {
            val start = SystemClock.uptimeMillis()
            while (isActive) { delay(50); elapsed = SystemClock.uptimeMillis() - start }
        }
    }
    val open = stringResource(R.string.nm_dot_open_conversation)
    val screen = stringResource(R.string.nm_dot_view_screen)
    val care = stringResource(R.string.nm_dot_start_care)
    val description = stringResource(if (careActive) R.string.nm_care_awaiting else R.string.nm_dot_orb_description)
    Canvas(Modifier.size(48.dp).semantics {
        role = Role.Button
        contentDescription = description
        onClick(open) { onIntent(DotIntent.Single); true }
        customActions = listOf(
            CustomAccessibilityAction(open) { onIntent(DotIntent.Single); true },
            CustomAccessibilityAction(screen) { onIntent(DotIntent.Double); true },
            CustomAccessibilityAction(care) { onIntent(DotIntent.Triple); true },
        )
    }) {
        fun frame(index: Int, alpha: Float) {
            val cell = CrystalFrames.rect(atlas.width, atlas.height, index)
            // Fixed crop removes only outer padding: ~40dp visible crystal in a 48dp hitbox.
            // Both source edges remain inside this cell, including odd-sized final cells.
            val insetX = (cell.right - cell.left) / 10
            val insetY = (cell.bottom - cell.top) / 10
            drawImage(atlas, srcOffset = IntOffset(cell.left + insetX, cell.top + insetY),
                srcSize = IntSize(cell.right - cell.left - insetX * 2, cell.bottom - cell.top - insetY * 2),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = alpha)
        }
        val current = CrystalFrames.index(elapsed, motion, active)
        frame(current, 1f)
        if (motion && active) frame((current + 1) % 16, (elapsed % 200) / 200f)
        if (pressed || dragging) {
            drawCircle(Color(0xFFCFE8FF).copy(alpha = if (dragging) .18f else .10f), radius = size.minDimension * .40f)
            drawCircle(Color(0xFF6E9FC9), radius = size.minDimension * .44f, style = Stroke(1.dp.toPx()))
        }
        if (careActive) drawCircle(if (dark) DotColors.DarkCare else DotColors.LightCare,
            radius = size.minDimension * .46f, style = Stroke(2.dp.toPx()))
    }
}
