package io.github.nanomuse.dot.screen

import io.github.nanomuse.dot.gesture.PointPx
import org.junit.Assert.*
import org.junit.Test

class ScreenTransformTest {
    @Test fun rotationAndInsetsRoundTrip() {
        // A cropped host origin must be subtracted; rotation is already applied by Android.
        for (rotation in 0..3) {
            val bounds = if (rotation % 2 == 0) RectPx(12f, 24f, 612f, 824f) else RectPx(24f, 12f, 824f, 612f)
            val transform = ScreenTransform(bounds, 300, 400)
            assertEquals(PointPx(0f, 0f), transform.screenToImage(PointPx(bounds.left, bounds.top)))
            assertEquals(PointPx(300f, 400f), transform.screenToImage(PointPx(bounds.right, bounds.bottom)))
            for (p in listOf(PointPx(bounds.left, bounds.top), PointPx(bounds.right, bounds.top),
                PointPx(bounds.left, bounds.bottom), PointPx(bounds.right, bounds.bottom))) {
                val back = transform.imageToScreen(transform.screenToImage(p))
                assertEquals(p.x, back.x, 1f); assertEquals(p.y, back.y, 1f)
            }
            assertEquals(PointPx(150f, 200f), transform.screenToImage(PointPx((bounds.left + bounds.right)/2, (bounds.top + bounds.bottom)/2)))
        }
    }
    @Test fun selectedRootIsTheOnlyCropAuthority() {
        // Actual API35 split regression: AX region width598, root/surface width597.
        val root=RectPx(603f,0f,1200f,800f)
        val selected=requireNotNull(hostFrameBounds(1089,1089,root))
        val transform=ScreenTransform(selected,597,800)
        assertEquals(PointPx(0f,0f),transform.screenToImage(PointPx(603f,0f)))
        assertEquals(PointPx(597f,800f),transform.screenToImage(PointPx(1200f,800f)))
        assertNull("A different root cannot supply this window's crop",hostFrameBounds(1089,1090,root))
        assertNull(hostFrameBounds(1089,1089,RectPx(603f,0f,603f,800f)))
        assertNull(hostFrameBounds(1089,1089,RectPx(Float.NaN,0f,1200f,800f)))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyBoundsRefused() {
        ScreenTransform(RectPx(0f, 0f, 0f, 100f), 100, 100)
    }
}
