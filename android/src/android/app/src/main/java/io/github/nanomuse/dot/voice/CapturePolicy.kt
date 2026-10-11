package io.github.nanomuse.dot.voice

/** null preserves the main window's established segmented dictation policy. */
object CapturePolicy {
    fun continuous(feedingAudio: Boolean, requested: Boolean?): Boolean = feedingAudio && requested != false
}
