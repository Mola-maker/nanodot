package io.github.nanomuse.dot.overlay

import androidx.compose.ui.graphics.BlendMode

/** The render recipe and actual Compose operators are shared with the pixel regression backend. */

internal interface CrystalBlendCanvas {
    fun isolatedLayer(draw: () -> Unit)
    fun frame(index: Int, weight: Float, mode: BlendMode)
}

internal object CrystalBlend {
    fun draw(current: Int, fraction: Float, canvas: CrystalBlendCanvas) {
        val t = fraction.coerceIn(0f, 1f)
        // ADD in a transparent isolated layer interpolates both premultiplied RGB and alpha.
        // Source-over would retain old-frame opacity, even with two complementary weights.
        canvas.isolatedLayer {
            canvas.frame(current, 1f - t, BlendMode.Plus)
            canvas.frame((current + 1) % 16, t, BlendMode.Plus)
        }
    }
}
