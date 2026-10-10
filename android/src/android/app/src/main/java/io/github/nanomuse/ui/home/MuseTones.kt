package io.github.nanomuse.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.theme.ChatColors

/**
 * The few neutrals Muse's pages are built from. OpenMinis' Material scheme uses iOS grouped
 * colours (`surface` is the grey page, `surfaceVariant` the white card), which is the opposite
 * of what a Muse page needs, so the shell names its own.
 */
object MuseTones {
    private val dark: Boolean
        @Composable @ReadOnlyComposable get() = ChatColors.background.luminance() < 0.5f

    /** True in the dark theme (the chat palette's, which follows the in-app override). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = dark

    /** Cards, pills, round buttons, sheets, the drawer. */
    val surface: Color
        @Composable @ReadOnlyComposable get() = if (dark) io.github.nanomuse.dot.DotColors.DarkSurface else io.github.nanomuse.dot.DotColors.LightSurface

    /** A selected row, the active segment, the disc under the face. */
    val fill: Color
        @Composable @ReadOnlyComposable get() = if (dark) io.github.nanomuse.dot.DotColors.DarkSurface else io.github.nanomuse.dot.DotColors.LightSurface

    /** The agent's message bubble and the composer pill (Muse: #E9EAEC on #FCFCFC). */
    val bubble: Color
        @Composable @ReadOnlyComposable get() = if (dark) io.github.nanomuse.dot.DotColors.DarkSurface else io.github.nanomuse.dot.DotColors.LightSurface

    /** The warm disc under the face (Muse's is a shade warmer than its greys). */
    val disc: Color
        @Composable @ReadOnlyComposable get() = if (dark) io.github.nanomuse.dot.DotColors.DarkBackground else io.github.nanomuse.dot.DotColors.LightBackground

    /** Hairlines and row separators. */
    val hairline: Color
        @Composable @ReadOnlyComposable get() = if (dark) io.github.nanomuse.dot.DotColors.DarkOutline else io.github.nanomuse.dot.DotColors.LightOutline

    /** The grey canvas a page of white cards sits on (the feed). */
    val canvas: Color
        @Composable @ReadOnlyComposable get() = if (dark) io.github.nanomuse.dot.DotColors.DarkBackground else io.github.nanomuse.dot.DotColors.LightBackground

    /** Muse's action blue (the "Let's go" button). */
    val action: Color
        @Composable @ReadOnlyComposable get() = if (dark) io.github.nanomuse.dot.DotColors.DarkPrimary else io.github.nanomuse.dot.DotColors.LightPrimary

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
