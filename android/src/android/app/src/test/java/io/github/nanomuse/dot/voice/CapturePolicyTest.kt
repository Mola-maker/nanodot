package io.github.nanomuse.dot.voice
import org.junit.Assert.*
import org.junit.Test
class CapturePolicyTest {
    @Test fun dotFalseDoesNotRearmAtFinalOrNoMatch() { assertFalse(CapturePolicy.continuous(true, false)) }
    @Test fun mainRetainsFeedingAudioSegmentation() { assertTrue(CapturePolicy.continuous(true, null)); assertFalse(CapturePolicy.continuous(false, null)) }
}
