package io.github.nanomuse.dot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Transmits the actual host pixels unchanged in position; never blurs or captures the host. */
@Composable
fun DotGlassSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val rim = Brush.linearGradient(listOf(
        Color.White.copy(alpha = .86f), colors.primary.copy(alpha = .34f),
        Color.White.copy(alpha = .48f), colors.primary.copy(alpha = .52f)
    ))
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Box(modifier.shadow(6.dp, shape, clip = false).clip(shape)
            .background(lerp(colors.surface, colors.primary, .08f).copy(alpha = .10f))
            .drawWithCache {
                val wash = Brush.linearGradient(listOf(
                    Color.White.copy(alpha = .06f), colors.primary.copy(alpha = .018f),
                    Color.White.copy(alpha = .035f)
                ), end = Offset(size.width, size.height))
                val glint = Brush.horizontalGradient(listOf(Color.Transparent,
                    Color.White.copy(alpha = .62f), Color.Transparent))
                onDrawBehind {
                    drawRect(wash)
                    drawLine(glint, Offset(24.dp.toPx(), 2.dp.toPx()),
                        Offset(size.width - 24.dp.toPx(), 2.dp.toPx()),
                        strokeWidth = .75.dp.toPx(), cap = StrokeCap.Round)
                }
            }.border(BorderStroke(1.dp, rim), shape), content = content)
    }
}

/** A local reading substrate, restricted to actual text/controls rather than empty history. */
@Composable
fun DotGlassPlate(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp),
    user: Boolean = false, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = shape, color = dotGlassPlateColor(user),
        contentColor = colors.onSurface,
        border = BorderStroke(.75.dp, Brush.linearGradient(listOf(
            Color.White.copy(alpha = .62f), colors.primary.copy(alpha = .20f),
            Color.White.copy(alpha = .16f)
        )))) {
        // A bare empty-state line can inherit a substrate from history; a bubble already has one.
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(background = Color.Unspecified)) {
            content()
        }
    }
}

@Composable
fun dotGlassPlateColor(user: Boolean = false): Color {
    val colors = MaterialTheme.colorScheme
    return lerp(colors.surface, colors.primary, if (user) .08f else .025f).copy(alpha = .96f)
}
