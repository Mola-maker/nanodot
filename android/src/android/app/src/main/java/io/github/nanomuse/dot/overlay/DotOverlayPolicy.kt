package io.github.nanomuse.dot.overlay

enum class OverlayKind(val touchable: Boolean) {
    Orb(true), Panel(true), Trail(false), Focus(false),
}

object OverlayPolicy {
    fun windows(connected: Boolean, locked: Boolean, enabled: Boolean): Set<OverlayKind> =
        if (connected && !locked && enabled) setOf(OverlayKind.Orb) else emptySet()
}

/** The registry owns each add/remove exactly once, including future auxiliary windows. */
internal class DotWindowRegistry(
    private val add: (OverlayKind) -> Unit,
    private val remove: (OverlayKind) -> Unit,
) {
    private val attached = mutableSetOf<OverlayKind>()
    val size get() = attached.size
    fun sync(wanted: Set<OverlayKind>) {
        (attached - wanted).forEach { remove(it); attached.remove(it) }
        (wanted - attached).forEach { add(it); attached.add(it) }
    }
}
