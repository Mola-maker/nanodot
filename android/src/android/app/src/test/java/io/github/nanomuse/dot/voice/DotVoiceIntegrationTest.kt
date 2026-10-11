package io.github.nanomuse.dot.voice

import com.openminis.app.speech.*
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.StreamingDelta
import io.github.nanomuse.dot.chat.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual manager, adapter, binding and overlay controller; only platform launch and audio hardware are controlled. */
@OptIn(ExperimentalCoroutinesApi::class)
class DotVoiceIntegrationTest {
    private class Session : DotChatSession {
        override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        override val inputText = MutableStateFlow("")
        override val isStreaming = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val configured = MutableStateFlow<String?>("fixture")
        override val deltas = MutableStateFlow<Map<String,StreamingDelta>>(emptyMap())
        override val consumedAttachments = MutableStateFlow<Map<String,String>>(emptyMap())
        var sends = 0
        override fun input(text: String) { inputText.value = text }
        override fun stage(file: File): DotOwnedAttachment? = null
        override fun remove(id: String) {}
        override fun send(text: String) { sends++ }
        override fun cancel() {}
        override fun retry() {}
    }
    private class Engine : SpeechRecognitionEngine {
        override val id = "system"; override val displayName = id
        override val isAvailable = true; override val supportedLocales = emptyList<Locale>()
        override val supportsPartialResults = true
        lateinit var listener: SpeechRecognitionEngine.Listener
        var starts = 0; var closes = 0; var stops = 0
        var continuous: Boolean? = true; var locale: Locale? = null
        var cleanup: CompletableDeferred<Unit>? = null
        override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) = startOwned(locale,null,listener)
        override fun startOwned(locale: Locale, continuous: Boolean?, listener: SpeechRecognitionEngine.Listener) {
            starts++; this.listener = listener; this.continuous = continuous; this.locale = locale; listener.onReadyForSpeech()
        }
        override fun stop() { stops++ }
        override fun cancel() {}
        override suspend fun cancelAndJoin() { closes++; cleanup?.await() }
    }
    private inner class Fixture(val scope: CoroutineScope) {
        val engine = Engine()
        val sessions = mutableMapOf<String,Session>()
        val aliases = mutableMapOf<String,String>()
        val adapter = DotChatAdapter({ aliases[it] ?: it }, { sessions.getOrPut(it) { Session() } }, { error("No screenshot") })
        val gate = VoiceStartGate()
        lateinit var pending: VoiceStartGate.Request
        var foregroundEnds = 0
        val voice = DotVoiceController(adapter,gate,{VoiceSelection("system",Locale.CHINA,false)},
            { r, _ -> pending = r },scope=scope)
        init {
            val f = SpeechRecognitionManager::class.java.getDeclaredField("engines").also { it.isAccessible=true }
            @Suppress("UNCHECKED_CAST")
            (f.get(null) as MutableList<SpeechRecognitionEngine>).apply { clear(); add(engine) }
            adapter.voice=voice; adapter.bind("A")
        }
        fun admit(): VoiceStartGate.Request {
            voice.start(); val r=pending; gate.visible(r,true); check(gate.begin(r,true))
            check(gate.admit(r.id,true) === r); check(voice.foreground(r) { foregroundEnds++ }); return r
        }
    }
    @Test fun pendingCancellationAndPermissionDenialLeaveKeyboardUsable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f=Fixture(backgroundScope)
            f.voice.start(); val old=f.pending; f.voice.cancel(); f.gate.visible(old,true)
            assertFalse(f.gate.begin(old,true)); assertNull(f.gate.admit(old.id,true)); assertEquals(0,f.engine.starts)
            f.voice.start(); f.voice.denied(f.pending)
            assertEquals(DotVoiceController.Phase.DENIED,f.voice.ui.value.phase)
            f.adapter.editInput("keyboard"); assertEquals("keyboard",f.sessions.getValue("A").inputText.value)
            assertEquals(0,f.engine.starts)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun notificationStopWaitsCleanupAndOldStopCannotTouchNextBinding() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f=Fixture(backgroundScope)
        try {
            val old=f.admit(); runCurrent(); val oldListener=f.engine.listener
            f.engine.cleanup=CompletableDeferred(); f.voice.cancel(old); runCurrent()
            assertEquals(1,f.engine.closes); assertEquals(0,f.foregroundEnds)
            assertTrue(SpeechRecognitionManager.occupied()); assertNull(f.gate.request { true })
            oldListener.onFinal("late"); oldListener.onError(RecognitionError.NETWORK); runCurrent()
            f.engine.cleanup!!.complete(Unit); runCurrent(); assertEquals(1,f.foregroundEnds)
            f.adapter.bind("B"); f.admit(); runCurrent()
            f.voice.cancel(old); oldListener.onFinal("wrong B"); oldListener.onError(RecognitionError.NETWORK); runCurrent()
            assertEquals(DotVoiceController.Phase.RECORDING,f.voice.ui.value.phase)
            assertEquals(2,f.engine.starts); assertEquals(1,f.engine.closes)
            assertEquals("",f.sessions.getValue("B").inputText.value)
        } finally { f.engine.cleanup?.complete(Unit); f.voice.cancel(); SpeechRecognitionManager.ownedState.value.ticket?.let(SpeechRecognitionManager::cancelOwned); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun finalIsEditableUnsentAndAliasPromotionPreservesBinding() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f=Fixture(backgroundScope)
        try {
            f.adapter.editInput("draft"); f.admit(); runCurrent()
            assertEquals(false,f.engine.continuous); assertEquals(Locale.CHINA,f.engine.locale)
            assertEquals(RecognitionState.IDLE,SpeechRecognitionManager.mainState.value)
            f.aliases["A"]="canonical"; f.adapter.bind("canonical")
            f.engine.listener.onPartial("interim"); runCurrent(); assertEquals("draft",f.sessions.getValue("A").inputText.value)
            f.engine.listener.onFinal("你好"); runCurrent()
            assertEquals("draft 你好",f.sessions.getValue("A").inputText.value)
            assertEquals(0,f.sessions.getValue("A").sends); assertEquals(1,f.engine.starts)
            assertEquals(1,f.foregroundEnds); assertFalse(SpeechRecognitionManager.occupied())
        } finally { f.voice.cancel(); SpeechRecognitionManager.ownedState.value.ticket?.let(SpeechRecognitionManager::cancelOwned); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun mainOwnerCannotReadStopOrReconfigureDotAndOldMainCannotWriteNewDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f=Fixture(backgroundScope)
        try {
            f.admit(); runCurrent()
            val main=MainVoiceInput(); var busy: RecognitionError?=null
            main.start(main.pending(),VoiceSelection("provider",Locale.JAPAN,true),{_,_->fail("Dot text leaked")},{e,_->busy=e})
            assertEquals(RecognitionError.RECOGNIZER_BUSY,busy); main.cancel(); runCurrent()
            assertEquals(0,f.engine.closes); assertEquals(Locale.CHINA,f.engine.locale)
            f.voice.cancel(); runCurrent()
            var text=""
            main.start(main.pending(),VoiceSelection("system",Locale.US,false),{t,_->text=t},{_,_->}); runCurrent()
            val listener=f.engine.listener; main.close(); runCurrent()
            val next=MainVoiceInput()
            next.start(next.pending(),VoiceSelection("system",Locale.JAPAN,false),{t,_->text=t},{_,_->}); runCurrent()
            main.cancel(); listener.onFinal("stale"); runCurrent(); assertEquals("",text)
            assertNull(f.engine.continuous); assertEquals(Locale.JAPAN,f.engine.locale)
            next.close(); runCurrent()
        } finally { f.voice.cancel(); SpeechRecognitionManager.ownedState.value.ticket?.let(SpeechRecognitionManager::cancelOwned); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun repeatedTapKeepsStopVisibleAndServiceFailureDoesNotArmAudio() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f=Fixture(backgroundScope)
            f.voice.start(); val request=f.pending; f.voice.start()
            assertEquals(DotVoiceController.Phase.PERMISSION,f.voice.ui.value.phase)
            f.gate.visible(request,true); f.gate.begin(request,true)
            f.voice.failed(request)
            assertNull(f.gate.admit(request.id,true)); assertEquals(0,f.engine.starts)
            assertEquals(DotVoiceController.Phase.ERROR,f.voice.ui.value.phase)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun switchingAndKeyboardEditRevokeCaptureUntilActualCleanup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f=Fixture(backgroundScope)
        try {
            f.admit(); runCurrent(); val old=f.engine.listener
            f.engine.cleanup=CompletableDeferred(); f.adapter.bind("B"); runCurrent()
            assertEquals(1,f.engine.closes); assertEquals(0,f.foregroundEnds)
            f.adapter.editInput("B keyboard"); old.onFinal("late A"); old.onError(RecognitionError.NETWORK); runCurrent()
            assertEquals("B keyboard",f.sessions.getValue("B").inputText.value)
            f.engine.cleanup!!.complete(Unit); runCurrent(); assertEquals(1,f.foregroundEnds)
            f.admit(); runCurrent(); f.adapter.editInput("edited B"); runCurrent()
            assertEquals(2,f.engine.closes); assertEquals("edited B",f.sessions.getValue("B").inputText.value)
        } finally { f.engine.cleanup?.complete(Unit); f.voice.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun mainPendingPermissionCannotRearmAfterClose() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f=Fixture(backgroundScope); val main=MainVoiceInput(); val pending=main.pending()
            main.close()
            main.start(pending,VoiceSelection("system",Locale.US,false),{_,_->fail("stale main")},{_,_->fail("stale error")})
            runCurrent(); assertEquals(0,f.engine.starts)
        } finally { Dispatchers.resetMain() }
    }

}
