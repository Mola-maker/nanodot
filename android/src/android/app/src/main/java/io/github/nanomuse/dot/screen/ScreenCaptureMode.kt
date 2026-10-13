package io.github.nanomuse.dot.screen

internal enum class ScreenCaptureMode { Nodes, Window, Display }

/** A visible foreground Stop must not be hidden for an asynchronous display capture.
 * API34 window capture excludes accessibility overlays. Earlier foreground Chat/Selection use
 * fresh host nodes; explicit Care pixel paths retain their existing capture capability.
 */
internal fun screenCaptureMode(sdk: Int, purpose: CapturePurpose, pixelsRequested: Boolean): ScreenCaptureMode = when {
    !pixelsRequested -> ScreenCaptureMode.Nodes
    sdk >= 34 -> ScreenCaptureMode.Window
    purpose in setOf(CapturePurpose.Chat, CapturePurpose.Selection) -> ScreenCaptureMode.Nodes
    else -> ScreenCaptureMode.Display
}
