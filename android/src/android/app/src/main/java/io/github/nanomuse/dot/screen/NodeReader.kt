package io.github.nanomuse.dot.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Copies only read-only DTOs; every obtained root/child is recycled before returning. */
internal object NodeReader {
    @Suppress("DEPRECATION")
    fun read(root: AccessibilityNodeInfo, snapshotId: String): List<NodeDto> {
        val result = ArrayList<NodeDto>()
        val windowId = root.windowId
        // Public ID lookup includes the host content viewport even when tree traversal omits
        // unimportant layout containers. No service-wide accessibility flags are changed.
        val content = try { root.findAccessibilityNodeInfosByViewId("android:id/content") }
        catch (error: Throwable) { root.recycle(); throw error }
        fun copy(node: AccessibilityNodeInfo, path: String): NodeDto {
            val rect = Rect(); node.getBoundsInScreen(rect)
            val password = node.isPassword
            return NodeDto("$snapshotId:$path", rect.toPx(),
                if (password) null else node.text?.toString(),
                if (password) null else node.contentDescription?.toString(),
                node.viewIdResourceName, node.className?.toString() ?: "unknown", node.isFocused,
                node.isClickable, node.isEditable, node.isEnabled, password)
        }
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            try {
                if (depth > 64 || result.size >= 2000) return
                result.add(copy(node,path))
                for (index in 0 until node.childCount) {
                    if (result.size >= 2000) break
                    node.getChild(index)?.let { visit(it, "$path.$index", depth + 1) }
                }
            } finally { node.recycle() }
        }
        try {
            visit(root, "0", 0)
            content.forEachIndexed { index, node ->
                if (node.windowId == windowId && result.size < 2000) {
                    val dto = copy(node,"content.$index")
                    if (result.none { it.viewId == dto.viewId && it.bounds == dto.bounds }) result.add(dto)
                }
            }
            return result
        } finally { content.forEach { it.recycle() } }
    }
}
internal fun Rect.toPx() = RectPx(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
