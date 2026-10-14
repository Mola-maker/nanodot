package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight // nanoMuse
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// nanoMuse: shared generated M3 roles preserve the brand on-colors in both themes.
private val LightColorScheme = io.github.nanomuse.dot.DotColors.lightScheme
private val DarkColorScheme = io.github.nanomuse.dot.DotColors.darkScheme

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(12.dp),        // Chip, TextField
    medium = RoundedCornerShape(20.dp),       // Card, Snackbar
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = scaledTypography(fontScale)
    val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        // nanoMuse: every custom transition follows the actual native reduced-motion setting.
        io.github.nanomuse.dot.ui.DotMotionHost {
            CompositionLocalProvider(LocalChatPalette provides chatPalette, content = content)
        }
    }
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

// nanoMuse: Muse's type, on the system face. Material's scale carries tracking
// (0.1–0.5sp) and small labels (11–12sp) that read as "Android"; Muse sets
// everything at zero tracking, runs body at 16 and its captions at 13, and
// weights titles SemiBold. The face stays the device's default so the app
// matches Muse on the same phone (MiSans, Roboto, …) and Chinese and Latin
// never come from two fonts.
private fun museTypography(): Typography {
    val base = Typography()
    fun TextStyle.muse(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        copy(fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp, fontWeight = weight)
    return Typography(
        displayLarge = base.displayLarge.muse(56, 64, FontWeight.SemiBold),
        displayMedium = base.displayMedium.muse(44, 52, FontWeight.SemiBold),
        displaySmall = base.displaySmall.muse(36, 44, FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.muse(32, 40, FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.muse(28, 36, FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.muse(24, 32, FontWeight.SemiBold),
        titleLarge = base.titleLarge.muse(22, 28, FontWeight.SemiBold),
        titleMedium = base.titleMedium.muse(17, 22, FontWeight.SemiBold),
        titleSmall = base.titleSmall.muse(15, 20, FontWeight.Medium),
        // nanoMuse: system-font body size comes from the UI token authority.
        bodyLarge = base.bodyLarge.copy(
            fontSize = io.github.nanomuse.dot.ui.DotTokens.Type.Body,
            lineHeight = 24.sp, letterSpacing = 0.sp,
        ),
        bodyMedium = base.bodyMedium.muse(14, 20),
        bodySmall = base.bodySmall.muse(13, 18),
        labelLarge = base.labelLarge.muse(15, 20, FontWeight.Medium),
        labelMedium = base.labelMedium.muse(13, 18, FontWeight.Medium),
        labelSmall = base.labelSmall.muse(12, 16, FontWeight.Medium),
    )
}

private fun scaledTypography(factor: Float): Typography {
    val base = museTypography() // nanoMuse: was Material's default scale
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}
