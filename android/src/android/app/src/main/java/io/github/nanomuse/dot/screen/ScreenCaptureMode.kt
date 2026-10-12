package io.github.nanomuse.dot.screen

internal enum class ScreenCaptureMode { Nodes, Window, Display }

/** A visible foreground Stop must not be hidden for an asynchronous display capture.
 * API34 window capture excludes accessibility overlays. Earlier foreground Chat uses fresh
 * host nodes; explicit Care/selection pixel paths retain their existing capture capability.
 */
internal fun screenCaptureMode(sdk: Int, purpose: CapturePurpose, pixelsRequested: Boolean): ScreenCaptureMode = when {
    !pixelsRequested -> ScreenCaptureMode.Nodes
    sdk >= 34 && purpose in setOf(CapturePurpose.Chat, CapturePurpose.Care) -> ScreenCaptureMode.Window
    purpose == CapturePurpose.Chat -> ScreenCaptureMode.Nodes
    else -> ScreenCaptureMode.Display
}
