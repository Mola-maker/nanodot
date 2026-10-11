package io.github.nanomuse.dot.gesture

data class PointPx(val x: Float, val y: Float)

data class GestureLimits(
    val doubleTapMs: Long,
    val longPressMs: Long,
    val touchSlopPx: Float,
    val doubleTapSlopPx: Float,
)

sealed interface GestureEvent {
    data class Down(val point: PointPx, val atMs: Long, val pointerId: Int) : GestureEvent
    data class Move(val point: PointPx, val atMs: Long, val pointerId: Int) : GestureEvent
    data class Up(val point: PointPx, val atMs: Long, val pointerId: Int) : GestureEvent
    data class Tick(val atMs: Long) : GestureEvent
    data object Cancel : GestureEvent
}

sealed interface DotIntent {
    data object Single : DotIntent
    data object Double : DotIntent
    data object Triple : DotIntent
    data class Drag(val points: List<PointPx>) : DotIntent
}

data class GestureResult(val intents: List<DotIntent>, val deadlineMs: Long?)

/**
 * Pure arbitration for a single orb. Call on one thread with monotonic event times
 * (MotionEvent.eventTime / SystemClock.uptimeMillis). Inject ViewConfiguration limits.
 *
 * Schedule Tick at deadlineMs, replacing the previous scheduled tick after every event.
 * A stale tick is harmless: it examines current candidates, never a captured callback.
 * Send Cancel for rotation, service disconnect, or any interrupted touch stream.
 * Press feedback belongs to the view; only completed business gestures are emitted here.
 */
class DotGestureReducer(private val limits: GestureLimits) {
    private data class TapSequence(val count: Int, val down: PointPx, val upMs: Long)

    private class Press(val down: GestureEvent.Down, val previousTaps: Int) {
        val points = mutableListOf(down.point)
        var dragging = false
        var suppressed = false
    }

    private val pending = mutableListOf<TapSequence>()
    private val pointers = mutableSetOf<Int>()
    private var press: Press? = null
    private var blocked = false

    fun accept(event: GestureEvent): GestureResult {
        if (event == GestureEvent.Cancel) {
            clearCandidates()
            pointers.clear()
            blocked = false
            return result(emptyList())
        }

        // Cancel before advancing timers: a second pointer must never release an old tap.
        val pointerId = when (event) {
            is GestureEvent.Down -> event.pointerId
            is GestureEvent.Move -> event.pointerId
            is GestureEvent.Up -> event.pointerId
            else -> null
        }
        if (pointers.isNotEmpty() && pointerId != null &&
            (event is GestureEvent.Down || pointerId !in pointers)) {
            if (event !is GestureEvent.Up) pointers.add(pointerId)
            clearCandidates()
            blocked = true
        }
        if (blocked) {
            when (event) {
                is GestureEvent.Down -> pointers.add(event.pointerId)
                is GestureEvent.Up -> pointers.remove(event.pointerId)
                else -> Unit
            }
            if (pointers.isEmpty()) blocked = false
            return result(emptyList())
        }

        val atMs = when (event) {
            is GestureEvent.Down -> event.atMs
            is GestureEvent.Move -> event.atMs
            is GestureEvent.Up -> event.atMs
            is GestureEvent.Tick -> event.atMs
            GestureEvent.Cancel -> error("Cancel already handled")
        }
        val intents = mutableListOf<DotIntent>()
        expireCompletedTaps(atMs, intents)
        press?.let {
            if (!it.dragging && atMs - it.down.atMs >= limits.longPressMs) {
                it.suppressed = true
            }
        }

        when (event) {
            is GestureEvent.Down -> {
                val previous = pending.lastOrNull()?.takeIf {
                    event.atMs - it.upMs < limits.doubleTapMs &&
                        withinSlop(it.down, event.point, limits.doubleTapSlopPx)
                }
                if (previous != null) pending.removeAt(pending.lastIndex)
                press = Press(event, previous?.count ?: 0)
                pointers.add(event.pointerId)
            }
            is GestureEvent.Move -> updatePoint(event.point, event.pointerId)
            is GestureEvent.Up -> {
                updatePoint(event.point, event.pointerId)
                val current = press
                if (current != null && event.pointerId == current.down.pointerId) {
                    when {
                        current.suppressed -> Unit
                        current.dragging -> intents.add(DotIntent.Drag(current.points.toList()))
                        current.previousTaps == 2 -> intents.add(DotIntent.Triple)
                        else -> pending.add(TapSequence(current.previousTaps + 1, current.down.point, event.atMs))
                    }
                    press = null
                }
                pointers.remove(event.pointerId)
            }
            is GestureEvent.Tick -> Unit
            GestureEvent.Cancel -> Unit
        }
        return result(intents.toList())
    }

    private fun updatePoint(point: PointPx, pointerId: Int) {
        val current = press ?: return
        if (current.down.pointerId != pointerId || current.suppressed) return
        if (current.points.last() != point) current.points.add(point)
        if (!current.dragging && !withinSlop(current.down.point, point, limits.touchSlopPx)) {
            current.dragging = true
            pending.clear()
        }
    }

    private fun expireCompletedTaps(atMs: Long, intents: MutableList<DotIntent>) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (atMs - candidate.upMs >= limits.doubleTapMs) {
                intents.add(if (candidate.count == 1) DotIntent.Single else DotIntent.Double)
                iterator.remove()
            }
        }
    }

    private fun clearCandidates() {
        pending.clear()
        press = null
    }

    private fun result(intents: List<DotIntent>): GestureResult {
        val tapDeadline = pending.minOfOrNull { it.upMs + limits.doubleTapMs }
        val pressDeadline = press?.takeUnless { it.dragging || it.suppressed }
            ?.let { it.down.atMs + limits.longPressMs }
        val deadline = when {
            tapDeadline == null -> pressDeadline
            pressDeadline == null -> tapDeadline
            else -> minOf(tapDeadline, pressDeadline)
        }
        return GestureResult(intents, deadline)
    }

    private fun withinSlop(first: PointPx, second: PointPx, slop: Float): Boolean {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return dx * dx + dy * dy <= slop * slop
    }
}
