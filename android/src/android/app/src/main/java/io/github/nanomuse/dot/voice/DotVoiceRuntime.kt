package io.github.nanomuse.dot.voice

import android.content.Context
import android.content.Intent

/** Capabilities never survive process death and are never reconstructed from Intent extras. */
object DotVoiceRuntime {
    const val EXTRA_REQUEST = "dot_voice_request"
    val gate = VoiceStartGate()
    data class Entry(val request: VoiceStartGate.Request, val controller: DotVoiceController)
    private var entry: Entry? = null
    fun launch(context: Context, request: VoiceStartGate.Request, controller: DotVoiceController) {
        entry = Entry(request, controller)
        context.startActivity(Intent(context, DotVoicePermissionActivity::class.java)
            .putExtra(EXTRA_REQUEST, request.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }
    fun find(id: String?): Entry? = entry?.takeIf { it.request === gate.find(id) }
    fun release(request: VoiceStartGate.Request) { if (entry?.request === request) entry = null }
}
