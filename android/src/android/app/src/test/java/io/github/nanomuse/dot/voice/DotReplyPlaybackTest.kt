package io.github.nanomuse.dot.voice

import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.speech.VoiceTextSanitizer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*

/** Executes the existing player's actual enqueue policy; does not synthesize or claim audible output. */
@OptIn(ExperimentalCoroutinesApi::class)
class DotReplyPlaybackTest {
    // The real singleton manager owns a main-thread scope even when this test only reads its state.
    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }
    private fun player(queue: Channel<String>): ReadAloudPlayer {
        val type = Class.forName("sun.misc.Unsafe")
        val unsafe = type.getDeclaredField("theUnsafe").also { it.isAccessible=true }.get(null)
        val player = type.getMethod("allocateInstance",Class::class.java).invoke(unsafe,ReadAloudPlayer::class.java) as ReadAloudPlayer
        fun set(name: String, value: Any) { ReadAloudPlayer::class.java.getDeclaredField(name).also { it.isAccessible=true }.set(player,value) }
        set("queue",queue); set("pending",AtomicInteger()); set("_isSpeaking",MutableStateFlow(false))
        set("linkPhrases\$delegate",lazy { VoiceTextSanitizer.LinkPhrases("link to %s","link") })
        return player
    }
    private fun enqueue(player: ReadAloudPlayer, explicit: Boolean) {
        ReadAloudPlayer::class.java.getDeclaredMethod("enqueue",String::class.java,Boolean::class.javaPrimitiveType)
            .also { it.isAccessible=true }.invoke(player,"你好，世界",explicit)
    }
    @Test fun explicitReplyWorksWithAutoRepliesOffWithoutChangingPreference() {
        VoiceOutputState.setEnabled(false)
        val queue=Channel<String>(Channel.UNLIMITED); val player=player(queue)
        enqueue(player,false); assertTrue(queue.tryReceive().isFailure)
        enqueue(player,true); assertEquals("你好，世界",queue.tryReceive().getOrNull())
        assertFalse(VoiceOutputState.canPlay)
    }
    @Test fun explicitReplyStillCannotBypassCaptureSuppression() {
        VoiceOutputState.setEnabled(false)
        val field=VoiceOutputState::class.java.getDeclaredField("captureSuppressed").also { it.isAccessible=true }
        field.setBoolean(null,true)
        try {
            val queue=Channel<String>(Channel.UNLIMITED)
            enqueue(player(queue),true); assertTrue(queue.tryReceive().isFailure)
        } finally { field.setBoolean(null,false) }
    }
}
