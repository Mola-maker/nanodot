package io.github.nanomuse.dot.overlay

import org.junit.Assert.*
import org.junit.Test

class DotCrystalMotionTest {
    @Test fun integerFrameGridCoversEveryPixelWithoutOverlap() {
        val frames = (0..15).map { CrystalFrames.rect(1254, 1254, it) }
        assertEquals(1254 * 1254, frames.sumOf { (it.right - it.left) * (it.bottom - it.top) })
        assertEquals(CrystalRect(940, 940, 1254, 1254), frames.last())
        assertEquals(frames[0].right, frames[1].left)
        assertEquals(frames[0].bottom, frames[4].top)
    }
    @Test fun breathingWrapsAt3200AndStopsAtFrameZero() {
        assertEquals(0, CrystalFrames.index(199, true, true))
        assertEquals(1, CrystalFrames.index(200, true, true))
        assertEquals(15, CrystalFrames.index(3199, true, true))
        assertEquals(0, CrystalFrames.index(3200, true, true))
        assertEquals(0, CrystalFrames.index(3000, false, true))
        assertEquals(0, CrystalFrames.index(3000, true, false))
    }
    @Test fun trailUsesRawPointsCapsSamplesAndFadesWithTheGestureClock() {
        val trail = CrystalTrail()
        repeat(50) { trail.add(it.toFloat(), (it * 2).toFloat(), it.toLong(), true) }
        val live = trail.visible(50)
        assertEquals(36, live.size)
        assertEquals(14f, live.first().x)
        assertEquals(98f, live.last().y)
        assertTrue(live.first().alpha < live.last().alpha)
        assertTrue(trail.visible(699).isEmpty())
    }
    @Test fun stationaryPointsDoNotExtendTheRefreshDeadline() {
        val trail = CrystalTrail()
        trail.add(12f, 25f, 100, true)
        trail.add(12f, 25f, 600, true)
        assertTrue(trail.visible(750).isEmpty())
        trail.add(12f, 25f, 800, true)
        assertTrue("Stationary events after expiry must not restart the tail", trail.visible(800).isEmpty())
    }
    @Test fun cancelAndReducedMotionClearAllPendingSamples() {
        val trail = CrystalTrail()
        trail.add(2f, 3f, 0, true)
        trail.clear()
        assertTrue(trail.visible(1).isEmpty())
        trail.add(4f, 5f, 2, true)
        trail.add(6f, 7f, 3, false)
        assertTrue(trail.visible(3).isEmpty())
        trail.add(8f, 9f, 4, true)
        assertEquals(1, trail.visible(4).size)
    }
}
