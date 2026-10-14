package io.github.nanomuse.dot.overlay

import io.github.nanomuse.dot.screen.RectPx
import org.junit.Assert.*
import org.junit.Test

class DotOrbMenuGeometryTest {
    private fun overlaps(a: RectPx, b: RectPx) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    private fun contains(outer: RectPx, inner: RectPx) =
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom
    private fun assertUsable(frame: DotOrbMenuFrame, safe: RectPx, orb: RectPx,
        density: Float, avoid: List<RectPx> = emptyList()) {
        assertTrue(contains(safe, frame.bounds))
        assertFalse(overlaps(frame.bounds, orb))
        avoid.forEach { assertFalse("Menu must leave every protected rectangle usable", overlaps(frame.bounds, it)) }
        assertEquals(4, frame.buttons.size)
        frame.buttons.forEachIndexed { i, button ->
            assertTrue(contains(frame.bounds, button))
            assertTrue(button.width >= 48f * density)
            assertTrue(button.height >= 48f * density)
            frame.buttons.drop(i + 1).forEach { assertFalse(overlaps(button, it)) }
        }
    }

    @Test fun everyCornerKeepsFourFullTargetsInsideOffsetDisplay() {
        for (density in listOf(1f, 2.625f, 3f)) {
            val safe = RectPx(37f, 63f, 37f + 360f * density, 63f + 720f * density)
            val size = 48f * density
            for (x in listOf(safe.left, safe.right - size)) for (y in listOf(safe.top, safe.bottom - size)) {
                val orb = RectPx(x, y, x + size, y + size)
                val frame = requireNotNull(orbMenuFrame(safe, orb, density))
                assertUsable(frame, safe, orb, density)
            }
        }
    }

    @Test fun shortImeHostUsesLegalCompactLayoutWithoutCoveringOrb() {
        val safe = RectPx(20f, 40f, 380f, 160f)
        val orb = RectPx(332f, 66f, 380f, 114f)
        val frame = requireNotNull(orbMenuFrame(safe, orb, 1f))
        assertUsable(frame, safe, orb, 1f)
        assertTrue(frame.bounds.height <= 120f)
    }

    @Test fun menuCannotCoverChatStopOrCurrentCareTarget() {
        val safe = RectPx(0f, 24f, 360f, 740f)
        val orb = RectPx(312f, 528f, 360f, 576f)
        val card = RectPx(0f, 480f, 360f, 740f)
        val target = RectPx(16f, 300f, 280f, 390f)
        val frame = requireNotNull(orbMenuFrame(safe, orb, 1f, listOf(card, target)))
        assertUsable(frame, safe, orb, 1f, listOf(card, target))
    }

    @Test fun onlyOneRowFitsInShortLandscapeHost() {
        val safe = RectPx(30f, 20f, 750f, 76f)
        val orb = RectPx(702f, 20f, 750f, 68f)
        val frame = requireNotNull(orbMenuFrame(safe, orb, 1f))
        assertUsable(frame, safe, orb, 1f)
        assertEquals(48f, frame.bounds.height, .01f)
    }

    @Test fun insufficientOrInvalidSpaceNeverClipsOrOverlapsTargets() {
        val orb = RectPx(0f, 0f, 48f, 48f)
        assertNull(orbMenuFrame(RectPx(0f, 0f, 100f, 80f), orb, 1f))
        assertNull(orbMenuFrame(RectPx(0f, 0f, 400f, 800f), orb, 0f))
        assertNull(orbMenuFrame(RectPx(0f, 0f, Float.NaN, 800f), orb, 1f))
        val host = RectPx(0f, 0f, 400f, 800f)
        assertNull(orbMenuFrame(host, orb, 1f, listOf(host)))
    }
}
