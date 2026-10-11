package io.github.nanomuse.dot.ui

enum class DotLayout { Compact, Medium, Expanded }

object DotNavigationPolicy {
    fun layout(widthDp: Int, fontScale: Float): DotLayout {
        require(widthDp >= 0 && fontScale.isFinite() && fontScale > 0)
        return when {
            widthDp < 600 -> DotLayout.Compact
            widthDp < 840 -> DotLayout.Medium
            else -> DotLayout.Expanded
        }
    }

    fun railWidth(fontScale: Float): Int = if (fontScale >= 1.5f) 160 else 120

    /** Keep a usable conversation pane at large type; the modal list remains available. */
    fun showConversationList(widthDp: Int, fontScale: Float): Boolean =
        layout(widthDp, fontScale) == DotLayout.Expanded &&
            widthDp >= railWidth(fontScale) + 320 + (360 * fontScale).toInt()
}
