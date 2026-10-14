package io.github.nanomuse.dot.overlay

import org.junit.Assert.*
import org.junit.Test

class DotWindowGeometryTest {
    @Test fun legacyCoordinatesAreRelativeToUsableParentAndPersistWithoutOriginDrift() {
        val geometry = DotWindowGeometry(80, 36, 1080, 1932, parentRelative = true)
        val position = geometry.position(72, 0.25f, 0.75f)
        assertEquals(232 to 1368, position)
        assertEquals(312 to 1404, geometry.screenPosition(position.first, position.second))
        assertEquals(0.25f to 0.75f, geometry.fractions(72, position.first, position.second))
        // Reconnect/rotation uses the new native parent, retaining the saved proportions.
        val rotated = DotWindowGeometry(96, 24, 1920, 1080, parentRelative = true)
        assertEquals(438 to 738, rotated.position(72, 0.25f, 0.75f))
    }

    @Test fun modernCoordinatesRetainScreenOriginExactlyOnce() {
        val geometry = DotWindowGeometry(80, 36, 1080, 1932, parentRelative = false)
        val position = geometry.position(72, 0.25f, 0.75f)
        assertEquals(312 to 1404, position)
        assertEquals(position, geometry.screenPosition(position.first, position.second))
        assertEquals(0.25f to 0.75f, geometry.fractions(72, position.first, position.second))
    }

    @Test fun edgeSnapUsesRawScreenMidpointWithLeftNavigationOrCutoutInset() {
        val geometry = DotWindowGeometry(300, 24, 800, 1056, parentRelative = true)
        assertEquals(0, geometry.edgeX(72, 500f))
        assertEquals(428, geometry.edgeX(72, 600f))
        assertEquals(0 to 960, geometry.clamp(72, -100, 2000))
        assertEquals(428 to 0, geometry.clamp(72, 2000, -100))
    }

    @Test fun topBottomAndSideInsetsRemainOutsideTheEntireTouchTarget() {
        for (relative in listOf(true, false)) {
            val geometry = DotWindowGeometry(96, 48, 1800, 1008, relative)
            for (x in listOf(0f, 0.5f, 1f)) for (y in listOf(0f, 0.5f, 1f)) {
                val p = geometry.position(72, x, y)
                val screen = geometry.screenPosition(p.first, p.second)
                assertTrue(screen.first >= 96 && screen.second >= 48)
                assertTrue(screen.first + 72 <= 1800 && screen.second + 72 <= 1008)
            }
        }
    }

    @Test fun undersizedParentHasNoNegativeTravelOrPersistenceDivision() {
        val geometry = DotWindowGeometry(20, 30, 60, 70, parentRelative = true)
        assertEquals(0 to 0, geometry.position(72, 1f, 1f))
        assertEquals(0 to 0, geometry.clamp(72, 100, -100))
        assertEquals(0, geometry.edgeX(72, 100f))
        assertEquals(0f to 0f, geometry.fractions(72, 0, 0))
    }
}
