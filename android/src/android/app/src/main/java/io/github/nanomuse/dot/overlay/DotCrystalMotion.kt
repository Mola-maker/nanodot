package io.github.nanomuse.dot.overlay

internal data class CrystalRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Integer boundaries include the remainder pixels of the original, unmodified atlas. */
internal object CrystalFrames {
    fun rect(width: Int, height: Int, frame: Int): CrystalRect {
        require(width >= 4 && height >= 4 && frame in 0..15)
        val column = frame % 4
        val row = frame / 4
        return CrystalRect(column * width / 4, row * height / 4,
            (column + 1) * width / 4, (row + 1) * height / 4)
    }
    fun index(elapsed: Long, motion: Boolean, active: Boolean): Int =
        if (motion && active) ((elapsed.coerceAtLeast(0) / 200) % 16).toInt() else 0
}

internal data class CrystalTrailPoint(val x: Float, val y: Float, val alpha: Float)

/** Uses MotionEvent/uptime time, not wall time. Stationary input cannot keep a tail alive. */
internal class CrystalTrail {
    private data class Sample(val x: Float, val y: Float, val at: Long)
    private val samples = ArrayDeque<Sample>()
    private var lastSample: Sample? = null
    fun add(x: Float, y: Float, at: Long, motion: Boolean) {
        if (!motion) { clear(); return }
        if (!x.isFinite() || !y.isFinite()) return
        val previous = lastSample
        if (previous != null && (at < previous.at || previous.x == x && previous.y == y)) return
        val sample = Sample(x, y, at)
        lastSample = sample
        samples.addLast(sample)
        while (samples.size > 36) samples.removeFirst()
        expire(at)
    }
    fun visible(now: Long): List<CrystalTrailPoint> {
        expire(now)
        return samples.map { CrystalTrailPoint(it.x, it.y, (1f - (now - it.at).coerceAtLeast(0) / 650f).coerceIn(0f, 1f)) }
    }
    private fun expire(now: Long) {
        while (samples.firstOrNull()?.let { now - it.at >= 650 } == true) samples.removeFirst()
    }
    fun clear() { samples.clear(); lastSample = null }
}
