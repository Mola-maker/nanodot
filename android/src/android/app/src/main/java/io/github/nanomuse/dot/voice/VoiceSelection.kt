package io.github.nanomuse.dot.voice

import com.openminis.app.speech.RecognitionState
import java.util.Locale

/** Immutable UI choice; engine configuration is applied only inside successful manager admission. */
data class VoiceSelection(val engineId: String, val locale: Locale, val preferOffline: Boolean)
data class OwnedVoiceState(val ticket: VoiceLease.Ticket? = null, val state: RecognitionState = RecognitionState.IDLE)
