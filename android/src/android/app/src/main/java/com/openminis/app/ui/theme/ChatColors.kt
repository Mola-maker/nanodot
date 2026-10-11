package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic chat colors mirroring iOS ChatColors (AIChatView.swift).
// Resolved from LocalChatPalette, which is provided by MinisTheme.
//
// iOS reference:
//   systemBackground        -> background
//   secondarySystemBackground -> secondaryBg
//   tertiarySystemFill      -> userBubble
//   tertiarySystemGroupedBackground -> toolBg
//   label                   -> primaryText
//   secondaryLabel          -> secondaryText
//   tertiaryLabel           -> tertiaryText
//   quaternaryLabel         -> sendButtonDisabled
//   separator               -> border
//   systemGray6             -> inlineCodeBg / toolCapsuleBg
@Immutable
data class ChatPalette(
    val isDark: Boolean,
    val background: Color,
    val secondaryBg: Color,
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,
    val userBubble: Color,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val separator: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val thinking: Color,
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val toastBg: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
)

val LightChatPalette = ChatPalette(
    isDark = false,
    background = Color(0xFFF4F8FF),
    secondaryBg = Color(0xFFFFFFFF),
    inputBg = Color(0xFFFFFFFF),
    inputIconBg = Color(0xFFFFFFFF),
    inputIconBorder = Color.Transparent,
    inputBorder = Color(0x4D3C3C43),
    primaryText = Color(0xFF1C3552),
    secondaryText = Color(0xFF1C3552),
    tertiaryText = Color(0xFF1C3552),
    disabledText = Color(0xFF61758F),
    userBubble = Color(0xFFFFFFFF),
    toolBg = Color(0xFFFFFFFF),
    toolBorder = Color(0x14000000),
    toolCapsuleBg = Color(0xFFFFFFFF),
    separator = Color(0x4D3C3C43),
    sendButton = Color(0xFF2D6396),
    sendButtonDisabled = Color(0x2E3C3C43),
    codeBlockBg = Color(0xFFFFFFFF),
    codeBlockText = Color(0xFF1C3552),
    inlineCodeBg = Color(0xFFFFFFFF),
    inlineCodeText = Color(0xFF2D6396),
    link = Color(0xFF2D6396),
    blockquoteBar = Color(0x802D6396),
    thinking = Color(0xFF2D6396),
    warningBg = Color(0x14FF9500),
    warningText = Color(0xFF1C3552),
    tableBorder = Color(0x1F000000),
    inputShadow = Color.Transparent,
    toastBg = Color(0x2E2D6396),
    thumbnailBorder = Color(0x33808080),
    sheetHeaderBg = Color(0xFFFFFFFF),
    sheetHeaderBorder = Color(0x1A000000),
    fabAccent = Color(0xFF2D6396),
)

// T153: Android-specific dark palette tweaks. iOS borrows the system
// palette (#1C1C1E / #2C2C2E etc.) which reads as "layered dark grey"
// on a 1000+ nit display, but on a typical Android phone (Pixel 6 ≈
// 500 nits, mid-range OEMs even less) those layers crush together
// into a single near-black wash and the user can't tell tool capsules
// from background or input from message list. Lift the non-background
// layers ~6-10% so the contrast survives the brightness gap; the pure
// `background` itself stays #000 because every other color is keyed
// to "darker than this".
val DarkChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF101D30),
    secondaryBg = Color(0xFF172B43),
    inputBg = Color(0xFF172B43),
    inputIconBg = Color(0xFF172B43),
    inputIconBorder = Color(0xFF595959),
    inputBorder = Color(0x40545458),
    primaryText = Color(0xFFE8F0FD),
    secondaryText = Color(0xFFE8F0FD),
    tertiaryText = Color(0xFFE8F0FD),
    disabledText = Color(0xFF9DB5D2),
    // [T-android-user-bubble-dark-contrast] The old 0x247676D7 was a 14%-alpha
    // translucent blue-grey that washed out to near-invisible on the #000 chat
    // background on real (≈500-nit) displays — exactly the "crush to a near-black
    // wash" failure the palette header warns about. Use an OPAQUE cool slate-blue
    // so the user's own messages read as a distinct accent; white primaryText
    // stays legible on it.
    userBubble = Color(0xFF172B43),
    toolBg = Color(0xFF172B43),
    toolBorder = Color(0x40545458),
    toolCapsuleBg = Color(0xFF172B43),
    separator = Color(0x99545458),
    sendButton = Color(0xFFA9D2F5),
    sendButtonDisabled = Color(0x2EEBEBF5),
    codeBlockBg = Color(0xFF172B43),
    codeBlockText = Color(0xFFE8F0FD),
    // [T-inline-code-dark-bg-android] Lifted into the T153 ramp — the old
    // #1C1C1E chip was invisible on the #000 chat background (the comment
    // block above explains why non-background layers need the ~6-10% lift;
    // this one was missed). #34343A sits between inputBg (#2C2C30) and
    // toolBg (#3A3A3F), clearly above codeBlockBg (#262626) so small inline
    // chips read against both the wash and fenced blocks.
    inlineCodeBg = Color(0xFF172B43),
    inlineCodeText = Color(0xFFA9D2F5),
    link = Color(0xFFA9D2F5),
    blockquoteBar = Color(0x80A9D2F5),
    thinking = Color(0xFFA9D2F5),
    warningBg = Color(0x14FF9F0A),
    warningText = Color(0xFFE8F0FD),
    tableBorder = Color(0xFF38383A),
    inputShadow = Color(0x80000000),
    toastBg = Color(0x2EA9D2F5),
    thumbnailBorder = Color(0x20545458),
    sheetHeaderBg = Color(0xFF172B43),
    sheetHeaderBorder = Color(0x33FFFFFF),
    fabAccent = Color(0xFFA9D2F5),
)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// Short accessor: ChatColors.primaryText instead of LocalChatPalette.current.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
