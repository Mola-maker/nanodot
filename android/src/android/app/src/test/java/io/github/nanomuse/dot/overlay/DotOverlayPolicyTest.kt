package io.github.nanomuse.dot.overlay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DotOverlayPolicyTest {
    @Test fun onlyConnectedUnlockedEnabledHasOrb() {
        for (connected in listOf(false, true)) for (locked in listOf(false, true))
            for (enabled in listOf(false, true)) {
                assertEquals(if (connected && !locked && enabled) setOf(OverlayKind.Orb) else emptySet(),
                    OverlayPolicy.windows(connected, locked, enabled))
            }
    }

    @Test fun reconnectAddsOnceAndDisconnectRemovesEveryWindowOnce() {
        val added = mutableListOf<OverlayKind>()
        val removed = mutableListOf<OverlayKind>()
        val registry = DotWindowRegistry(added::add, removed::add)
        registry.sync(setOf(OverlayKind.Orb))
        registry.sync(setOf(OverlayKind.Orb))
        assertEquals(listOf(OverlayKind.Orb), added)
        registry.sync(OverlayKind.entries.toSet())
        registry.sync(emptySet())
        registry.sync(emptySet())
        assertEquals(OverlayKind.entries.toSet(), removed.toSet())
        assertEquals(4, removed.size)
    }

    @Test fun overlayHasNoScreenshotRecordingOrModelCalls() {
        // Architectural privacy fence, separate from real-window instrumentation.
        val directory = File("src/main/java/io/github/nanomuse/dot/overlay")
        assertTrue("Gradle app working directory required", directory.isDirectory)
        val forbidden = Regex("\\b(takeScreenshot|takeScreenshotOfWindow|AudioRecord|MediaRecorder|MediaProjection|SpeechRecognizer|ChatRepository|LLMClient)\\b")
        val calls = directory.listFiles()!!.filter { it.extension == "kt" }
            .flatMap { forbidden.findAll(it.readText()).map { match -> match.value }.toList() }
        assertEquals("default overlay must not call capture, recording or models", emptyList<String>(), calls)
    }

    @Test fun nonInteractiveWindowsNeverConsumeTouch() {
        assertTrue(OverlayKind.Orb.touchable)
        assertTrue(OverlayKind.Panel.touchable)
        assertFalse(OverlayKind.Trail.touchable)
        assertFalse(OverlayKind.Focus.touchable)
    }
}
