package io.github.nanomuse.dot.voice

import android.media.MediaPlayer
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.TextToSpeechManager
import io.github.nanomuse.dot.gateway.VoiceGeneration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** Controlled SDK resource; all stop/pause/registry/ownership code under test is production. */
class ControlledReplyMediaPlayer : MediaPlayer() {
    var failStop=false; var failRelease=false; var stops=0; var releases=0
    override fun isPlaying() = true
    override fun stop() { stops++; if (failStop) error("controlled stop failure") }
    override fun release() { releases++; if (failRelease) error("controlled release failure") }
}
object ReplyCleanupFixture {
    fun player(media: MediaPlayer, utterance: Job): ReadAloudPlayer {
        val type=Class.forName("sun.misc.Unsafe")
        val unsafe=type.getDeclaredField("theUnsafe").also { it.isAccessible=true }.get(null)
        val p=type.getMethod("allocateInstance",Class::class.java).invoke(unsafe,ReadAloudPlayer::class.java) as ReadAloudPlayer
        fun set(name: String,value: Any) { ReadAloudPlayer::class.java.getDeclaredField(name).also { it.isAccessible=true }.set(p,value) }
        set("player",media); set("utteranceJob",utterance); set("generation",VoiceGeneration())
        set("system",TextToSpeechManager()); set("queue",Channel<String>(Channel.UNLIMITED))
        set("pending",AtomicInteger()); set("_isSpeaking",MutableStateFlow(false)); set("playbackFailed",MutableStateFlow(false))
        set("heldForCapture",mutableListOf<String>()); set("sentenceBuffer",StringBuilder())
        set("scope",CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate))
        return p
    }
}
