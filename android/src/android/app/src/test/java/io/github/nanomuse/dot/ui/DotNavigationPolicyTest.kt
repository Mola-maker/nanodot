package io.github.nanomuse.dot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DotNavigationPolicyTest {
    @Test fun paneWidthDeterminesExactBreakpoints() {
        for (scale in listOf(1f, 1.5f, 2f)) {
            assertEquals(DotLayout.Compact, DotNavigationPolicy.layout(599, scale))
            assertEquals(DotLayout.Medium, DotNavigationPolicy.layout(600, scale))
            assertEquals(DotLayout.Medium, DotNavigationPolicy.layout(839, scale))
            assertEquals(DotLayout.Expanded, DotNavigationPolicy.layout(840, scale))
        }
    }
    @Test fun enlargedTextCanCollapseOnlyTheConversationList() {
        assertEquals(false, DotNavigationPolicy.showConversationList(840, 2f))
        assertEquals(true, DotNavigationPolicy.showConversationList(1200, 2f))
        assertEquals(true, DotNavigationPolicy.showConversationList(840, 1f))
        assertEquals(false, DotNavigationPolicy.showConversationList(839, 1f))
    }
}
