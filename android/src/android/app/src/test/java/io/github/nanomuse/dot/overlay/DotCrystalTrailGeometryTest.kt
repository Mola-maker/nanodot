package io.github.nanomuse.dot.overlay

import org.junit.Assert.*
import org.junit.Test

class DotCrystalTrailGeometryTest {
    @Test fun sparseGhostsStayBehindTheTipWithoutDependingOnInputEventFrequency() {
        // Removing distance sampling would pile a ghost onto every pointer sample.
        val sparse = listOf(CrystalTrailPoint(0f, 0f, .2f), CrystalTrailPoint(200f, 0f, .6f))
        val dense = (0..20).map { CrystalTrailPoint(it * 10f, 0f, .2f + it * .02f) }
        for (points in listOf(sparse, dense)) {
            val ghosts = crystalTrailGhosts(points, 1f)
            assertEquals(3, ghosts.size)
            ghosts.zip(listOf(88f, 128f, 168f)).forEach { (ghost, x) -> assertEquals(x, ghost.x, .001f) }
            assertTrue(ghosts.all { it.y == 0f && it.alpha > 0f && it.alpha < .6f })
            assertTrue(ghosts.zipWithNext().all { (older, newer) -> older.alpha < newer.alpha })
        }
    }

    @Test fun anchorsFollowThePolylineAroundACornerAtDisplayDensity() {
        // Chord interpolation would cut across the corner instead of following the drag.
        val ghosts = crystalTrailGhosts(listOf(
            CrystalTrailPoint(0f, 0f, .2f), CrystalTrailPoint(120f, 0f, .4f),
            CrystalTrailPoint(120f, 160f, .6f)), 2f)
        assertEquals(3, ghosts.size)
        ghosts.zip(listOf(56f to 0f, 120f to 16f, 120f to 96f)).forEach { (ghost, expected) ->
            assertEquals(expected.first, ghost.x, .001f)
            assertEquals(expected.second, ghost.y, .001f)
        }
    }

    @Test fun aShortOrStationaryDragDoesNotCreateASpuriousSecondOrb() {
        assertTrue(crystalTrailGhosts(listOf(CrystalTrailPoint(0f, 0f, .6f)), 1f).isEmpty())
        assertTrue(crystalTrailGhosts(List(36) { CrystalTrailPoint(10f, 10f, .6f) }, 1f).isEmpty())
        assertTrue(crystalTrailGhosts(listOf(CrystalTrailPoint(0f, 0f, .2f),
            CrystalTrailPoint(31f, 0f, .6f)), 1f).isEmpty())
    }

    @Test fun tightLoopsDoNotStackHistoricalOrbsOverTheSamePixels() {
        // Arc length alone is not enough when a finger circles within a small area.
        val corners = listOf(0f to 0f, 10f to 0f, 10f to 10f, 0f to 10f)
        val loop = (0 until 36).map { index ->
            val point = corners[index % corners.size]
            CrystalTrailPoint(point.first, point.second, .5f)
        }
        assertEquals(1, crystalTrailGhosts(loop, 1f).size)
    }

    @Test fun expiredOrInvalidVisualSamplesNeverProduceGhosts() {
        val expired = listOf(CrystalTrailPoint(0f, 0f, 0f), CrystalTrailPoint(200f, 0f, 0f))
        assertTrue(crystalTrailGhosts(expired, 1f).isEmpty())
        assertTrue(crystalTrailGhosts(expired, 0f).isEmpty())
        assertTrue(crystalTrailGhosts(expired, Float.NaN).isEmpty())
        assertTrue(crystalTrailGhosts(listOf(CrystalTrailPoint(Float.NaN, 0f, .3f),
            CrystalTrailPoint(200f, 0f, .6f)), 1f).isEmpty())
    }
}
