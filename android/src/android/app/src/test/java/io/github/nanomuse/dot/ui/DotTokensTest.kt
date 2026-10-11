package io.github.nanomuse.dot.ui

import io.github.nanomuse.dot.DotColors
import org.junit.Assert.assertEquals
import org.junit.Test

class DotTokensTest {
    @Test
    fun materialOnRolesFollowTheSelectedPalette() {
        assertEquals(DotColors.LightOnPrimary, DotColors.lightScheme.onPrimary)
        assertEquals(DotColors.DarkOnPrimary, DotColors.darkScheme.onPrimary)
        assertEquals(DotColors.DarkOnPrimary, DotColors.darkScheme.onSecondary)
        assertEquals(DotColors.DarkOnPrimary, DotColors.darkScheme.onTertiary)
        assertEquals(DotColors.LightText, DotColors.lightScheme.onSurface)
        assertEquals(DotColors.DarkText, DotColors.darkScheme.onSurface)
    }

    @Test
    fun approvedNativeDimensionsAndDurationsAreAvailable() {
        assertEquals(16f, DotTokens.Type.Body.value)
        assertEquals(18f, DotTokens.Type.Care.value)
        assertEquals(48f, DotTokens.TouchTarget.value)
        assertEquals(180, DotTokens.Motion.Page)
        assertEquals(100, DotTokens.Motion.Press)
        assertEquals(200, DotTokens.Motion.Focus)
    }
}
