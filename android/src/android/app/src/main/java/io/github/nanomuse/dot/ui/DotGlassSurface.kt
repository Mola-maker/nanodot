package io.github.nanomuse.dot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Transmits the actual host pixels unchanged in position; never blurs or captures the host. */
@Composable
fun DotGlassSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // Paired colors apply only inside the foreground window, including its menus and composer.
    val colors = MaterialTheme.colorScheme.copy(
        primary = Color(0xFF0754BD), onPrimary = Color.White,
        primaryContainer = Color(0xFF003C91), onPrimaryContainer = Color.White,
        secondary = Color(0xFF0754BD), onSecondary = Color.White,
        surface = Color(0xFF003270), onSurface = Color.White,
        surfaceVariant = Color(0xFF003C91), onSurfaceVariant = Color(0xFFDDEBFF),
        outline = Color(0xFF8BBCFF), error = Color(0xFFFFD4CD)
    )
    val shape = RoundedCornerShape(24.dp)
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Box(modifier.clip(shape)
                .background(Color(0xFF0047AB).copy(alpha = .22f))
                .drawWithCache {
                    val wash = Brush.linearGradient(listOf(
                        Color(0xFF9BD7FF).copy(alpha = .025f), Color.Transparent,
                        Color(0xFF307BE1).copy(alpha = .025f)
                    ), end = Offset(size.width, size.height))
                    // A continuous cap supports white toolbar text over any host. Empty history
                    // below it keeps the actual host contrast; no host capture or blur is involved.
                    val cap = Brush.verticalGradient(
                        0f to Color(0xFF003270).copy(alpha = .88f),
                        .60f to Color(0xFF003270).copy(alpha = .86f),
                        1f to Color.Transparent,
                        endY = 96.dp.toPx()
                    )
                    val rim = Brush.linearGradient(listOf(
                        Color(0xFFE5F6FF).copy(alpha = .90f),
                        Color(0xFF7ABBFF).copy(alpha = .36f),
                        Color(0xFF2869BC).copy(alpha = .65f),
                        Color(0xFFC1E7FF).copy(alpha = .68f)
                    ), end = Offset(size.width, size.height))
                    val innerRim = Brush.linearGradient(listOf(
                        Color(0xFFB9E6FF).copy(alpha = .50f),
                        Color(0xFF175CC3).copy(alpha = .12f),
                        Color(0xFFE2F3FF).copy(alpha = .42f)
                    ), start = Offset(size.width, 0f), end = Offset(0f, size.height))
                    val glint = Brush.horizontalGradient(listOf(Color.Transparent,
                        Color.White.copy(alpha = .72f), Color.Transparent),
                        startX = 24.dp.toPx(), endX = size.width * .58f)
                    onDrawBehind {
                        drawRect(wash)
                        drawRect(cap)
                        // Optical depth stays inside the 4dp rim; it does not cast a gray shadow
                        // or place a bright reflection behind the toolbar's text.
                        val depthInset = 2.dp.toPx()
                        drawRoundRect(Color(0xFF003270).copy(alpha = .56f),
                            topLeft = Offset(depthInset, depthInset),
                            size = Size((size.width - depthInset * 2).coerceAtLeast(0f),
                                (size.height - depthInset * 2).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(22.dp.toPx()), style = Stroke(3.dp.toPx()))
                        val outerInset = .5.dp.toPx()
                        drawRoundRect(rim, topLeft = Offset(outerInset, outerInset),
                            size = Size((size.width - outerInset * 2).coerceAtLeast(0f),
                                (size.height - outerInset * 2).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(23.5.dp.toPx()), style = Stroke(1.dp.toPx()))
                        val innerInset = 3.dp.toPx()
                        drawRoundRect(innerRim, topLeft = Offset(innerInset, innerInset),
                            size = Size((size.width - innerInset * 2).coerceAtLeast(0f),
                                (size.height - innerInset * 2).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(21.dp.toPx()), style = Stroke(.75.dp.toPx()))
                        drawLine(glint, Offset(24.dp.toPx(), 2.dp.toPx()),
                            Offset(size.width * .58f, 2.dp.toPx()),
                            strokeWidth = .75.dp.toPx(), cap = StrokeCap.Round)
                    }
                }, content = content)
        }
    }
}

/** A local reading substrate, restricted to actual text/controls rather than empty history. */
@Composable
fun DotGlassPlate(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp),
    user: Boolean = false, message: Boolean = false, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val plateColor = if (message) Color(0xFF0047AB).copy(alpha = if (user) .86f else .80f)
        else dotGlassPlateColor(user)
    Surface(modifier, shape = shape, color = plateColor,
        contentColor = Color.White,
        border = BorderStroke(.75.dp, Brush.linearGradient(listOf(
            Color.White.copy(alpha = .62f), colors.primary.copy(alpha = .20f),
            Color.White.copy(alpha = .16f)
        )))) {
        // A bare empty-state line can inherit a substrate from history; a bubble already has one.
        val plateContent: @Composable () -> Unit = {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(background = Color.Unspecified)) {
                content()
            }
        }
        MaterialTheme(colorScheme = colors.copy(
            primary = if (message) Color.White else colors.primary,
            onPrimary = if (message) Color(0xFF003C91) else colors.onPrimary,
            onSurface = Color.White, onSurfaceVariant = Color(0xFFDDEBFF)
        )) {
            plateContent()
        }
    }
}

@Composable
fun dotGlassPlateColor(user: Boolean = false): Color {
    return Color(0xFF003C91).copy(alpha = if (user) .94f else .90f)
}
