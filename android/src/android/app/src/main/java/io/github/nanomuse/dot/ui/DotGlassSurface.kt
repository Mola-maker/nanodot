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
                .background(Color(0xFF0047AB).copy(alpha = .28f))
                .drawWithCache {
                    val wash = Brush.linearGradient(listOf(
                        Color(0xFF9BD7FF).copy(alpha = .012f), Color.Transparent,
                        Color(0xFF307BE1).copy(alpha = .012f)
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
                        Color(0xFFE5F6FF).copy(alpha = .88f),
                        Color(0xFF448DE9).copy(alpha = .22f),
                        Color(0xFF003270).copy(alpha = .72f),
                        Color(0xFFC1E7FF).copy(alpha = .78f)
                    ), end = Offset(size.width, size.height))
                    val bevel = Brush.linearGradient(listOf(
                        Color(0xFF77BFFF).copy(alpha = .22f),
                        Color(0xFF0754BD).copy(alpha = .34f),
                        Color(0xFF002962).copy(alpha = .54f)
                    ), end = Offset(size.width * .72f, size.height))
                    val innerRim = Brush.linearGradient(listOf(
                        Color(0xFF002962).copy(alpha = .46f),
                        Color(0xFF175CC3).copy(alpha = .08f),
                        Color(0xFFB9E6FF).copy(alpha = .30f)
                    ), end = Offset(size.width, size.height))
                    val glint = Brush.horizontalGradient(listOf(Color.Transparent,
                        Color.White.copy(alpha = .72f), Color.Transparent),
                        startX = 24.dp.toPx(), endX = size.width * .58f)
                    onDrawWithContent {
                        drawRect(wash)
                        drawRect(cap)
                        drawContent()
                        // A broad inward bevel and opposing inner edge give the transparent
                        // body thickness. Fade toward the interior; keep the edge above the
                        // composer, whose tight layout can reach the bottom of this surface.
                        // Every radius shares the outer 24dp corner center.
                        val band = 7.dp.toPx().coerceAtMost(size.minDimension / 8f)
                        repeat(7) { layer ->
                            val depthInset = (layer + .5f) * band / 7f
                            drawRoundRect(bevel,
                                topLeft = Offset(depthInset, depthInset),
                                size = Size((size.width - depthInset * 2).coerceAtLeast(0f),
                                    (size.height - depthInset * 2).coerceAtLeast(0f)),
                                cornerRadius = CornerRadius((24.dp.toPx() - depthInset).coerceAtLeast(0f)),
                                alpha = 1f - layer / 8f, style = Stroke(band / 7f * 1.25f))
                        }
                        val outerInset = .5.dp.toPx()
                        drawRoundRect(rim, topLeft = Offset(outerInset, outerInset),
                            size = Size((size.width - outerInset * 2).coerceAtLeast(0f),
                                (size.height - outerInset * 2).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(23.5.dp.toPx()), style = Stroke(1.dp.toPx()))
                        val innerInset = band - .5.dp.toPx()
                        drawRoundRect(innerRim, topLeft = Offset(innerInset, innerInset),
                            size = Size((size.width - innerInset * 2).coerceAtLeast(0f),
                                (size.height - innerInset * 2).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius((24.dp.toPx() - innerInset).coerceAtLeast(0f)),
                            style = Stroke(.75.dp.toPx()))
                        // Short corner glints replace an equally bright border around the card.
                        // These are drawn highlights, not sampled/refraction of another app.
                        val arcInset = 1.5.dp.toPx()
                        val arcDiameter = 45.dp.toPx()
                        for ((origin, start) in listOf(Offset(arcInset, arcInset) to 188f,
                            Offset(size.width - arcDiameter - arcInset,
                                size.height - arcDiameter - arcInset) to 8f)) {
                            drawArc(Color(0xFFB9E6FF).copy(alpha = .20f), start, 68f, false,
                                origin, Size(arcDiameter, arcDiameter),
                                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                            drawArc(Color(0xFFE5F6FF).copy(alpha = .82f), start, 68f, false,
                                origin, Size(arcDiameter, arcDiameter),
                                style = Stroke(.8.dp.toPx(), cap = StrokeCap.Round))
                        }
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
            Color.White.copy(alpha = .32f), colors.primary.copy(alpha = .20f),
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
