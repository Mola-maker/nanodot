package io.github.nanomuse.dot.voice

import com.openminis.app.speech.*
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real singleton manager and production ownership core; only hardware engines are fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechManagerOwnershipTest {
    private class Engine(override val id: String) : SpeechRecognitionEngine {
        override val displayName = id
        override val isAvailable = true
        override val supportedLocales = emptyList<Locale>()
        override val supportsPartialResults = true
        lateinit var listener: SpeechRecognitionEngine.Listener
        var starts = 0; var stops = 0; var closes = 0; var policy: Boolean? = true
        var gate: CompletableDeferred<Unit>? = null
        val locales = mutableListOf<Locale>()
        override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) = startOwned(locale, null, listener)
        override fun startOwned(locale: Locale, continuous: Boolean?, listener: SpeechRecognitionEngine.Listener) {
            this.listener = listener; policy = continuous; starts++; locales.add(locale); listener.onReadyForSpeech()
        }
        override fun stop() { stops++ }
        override fun cancel() {}
        override suspend fun cancelAndJoin() { closes++; gate?.await() }
    }
    @Suppress("UNCHECKED_CAST")
    private fun install(vararg engines: Engine) {
        val field = SpeechRecognitionManager::class.java.getDeclaredField("engines").also { it.isAccessible = true }
        (field.get(null) as MutableList<SpeechRecognitionEngine>).apply { clear(); addAll(engines) }
        select(engines.first().id)
    }
    @Suppress("UNCHECKED_CAST")
    private fun select(id: String) {
        val field = SpeechRecognitionManager::class.java.getDeclaredField("_selectedEngineId").also { it.isAccessible = true }
        (field.get(null) as MutableStateFlow<String>).value = id
    }
    @Test fun actualManagerPinsEngineAndMainCancelCannotStopDot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val a = Engine("system"); val b = Engine("provider"); install(a, b)
            val ticket = SpeechRecognitionManager.startOwned("dot", false, { _, _ -> }, { _, _ -> })!!; runCurrent()
            assertEquals(RecognitionState.RECORDING, SpeechRecognitionManager.state.value)
            assertEquals(false, a.policy); select("provider")
            SpeechRecognitionManager.cancelRecording(); SpeechRecognitionManager.stopRecording(); runCurrent()
            assertEquals(0, a.closes); assertEquals(0, a.stops)
            SpeechRecognitionManager.stopOwned(ticket); runCurrent(); assertEquals(1, a.stops); assertEquals(0, b.stops)
            SpeechRecognitionManager.cancelOwned(ticket); runCurrent(); assertEquals(1, a.closes); assertEquals(0, b.closes)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun actualManagerWaitsCleanupAndDropsOldCallbacks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val a = Engine("system"); install(a); val text = mutableListOf<String>()
            val old = SpeechRecognitionManager.startOwned("dot", false, { t, _ -> text.add(t) }, { _, _ -> })!!; runCurrent()
            a.gate = CompletableDeferred(); SpeechRecognitionManager.cancelOwned(old); runCurrent()
            assertEquals(RecognitionState.FINISHING, SpeechRecognitionManager.state.value)
            assertTrue(VoiceOutputState.captureSuppressed)
            var busy: RecognitionError? = null
            assertNull(SpeechRecognitionManager.startOwned("main", null, { _, _ -> }, { e, _ -> busy = e }))
            assertEquals(RecognitionError.RECOGNIZER_BUSY, busy)
            a.gate!!.complete(Unit); runCurrent(); assertFalse(VoiceOutputState.captureSuppressed)
            val oldListener = a.listener; val b = Engine("provider"); install(b)
            val next = SpeechRecognitionManager.startOwned("main", null, { t, _ -> text.add(t) }, { _, _ -> })!!; runCurrent()
            assertNull(b.policy); oldListener.onFinal("stale"); oldListener.onError(RecognitionError.NETWORK); runCurrent()
            assertTrue(text.isEmpty()); assertEquals(RecognitionState.RECORDING, SpeechRecognitionManager.state.value)
            SpeechRecognitionManager.cancelOwned(old); runCurrent(); assertEquals(0, b.closes)
            SpeechRecognitionManager.cancelOwned(next); runCurrent()
        } finally { Dispatchers.resetMain() }
    }
    private fun installPreferences(): MutableMap<String, String> {
        val saved = mutableMapOf<String, String>()
        val editor = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { saved[args!![0] as String] = args[1] as String; proxy }
                "apply" -> null
                else -> error("Unexpected preference edit: ${method.name}")
            }
        }
        val prefs = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, _ ->
            check(method.name == "edit"); editor
        }
        SpeechRecognitionManager::class.java.getDeclaredField("prefs").also {
            it.isAccessible = true; it.set(null, prefs)
        }
        return saved
    }
    @Test fun latestLocaleDuringCleanupIsUsedByActualManagerRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val a = Engine("system"); install(a); val saved = installPreferences()
        try {
            SpeechRecognitionManager.selectLocale(Locale.US)
            SpeechRecognitionManager.startOwned("main", null, { _, _ -> }, { _, _ -> })!!; runCurrent()
            a.gate = CompletableDeferred()
            SpeechRecognitionManager.selectLocale(Locale.FRANCE); runCurrent()
            SpeechRecognitionManager.selectLocale(Locale.JAPAN); runCurrent()
            assertEquals(1, a.starts)
            a.gate!!.complete(Unit); runCurrent()
            assertEquals(Locale.JAPAN, SpeechRecognitionManager.locale.value)
            assertEquals("ja-JP", saved["locale"])
            assertEquals(listOf(Locale.US, Locale.JAPAN), a.locales)
        } finally {
            a.gate?.complete(Unit); SpeechRecognitionManager.cancelRecording(); runCurrent(); Dispatchers.resetMain()
        }
    }
    @Test fun cancellationRevokesLocaleRestartEvenIfAnotherSelectionArrives() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val a = Engine("system"); install(a); installPreferences()
        try {
            SpeechRecognitionManager.selectLocale(Locale.US)
            SpeechRecognitionManager.startOwned("main", null, { _, _ -> }, { _, _ -> })!!; runCurrent()
            a.gate = CompletableDeferred()
            SpeechRecognitionManager.selectLocale(Locale.FRANCE); runCurrent()
            SpeechRecognitionManager.cancelRecording()
            SpeechRecognitionManager.selectLocale(Locale.JAPAN)
            a.gate!!.complete(Unit); runCurrent()
            assertEquals(1, a.starts)
            assertEquals(RecognitionState.IDLE, SpeechRecognitionManager.state.value)
        } finally {
            a.gate?.complete(Unit); SpeechRecognitionManager.cancelRecording(); runCurrent(); Dispatchers.resetMain()
        }
    }
    @Test fun localeChangeCannotReauthorizeDot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val a = Engine("system"); install(a); installPreferences()
        var ticket: VoiceLease.Ticket? = null
        try {
            SpeechRecognitionManager.selectLocale(Locale.US)
            ticket = SpeechRecognitionManager.startOwned("dot", false, { _, _ -> }, { _, _ -> })!!; runCurrent()
            a.gate = CompletableDeferred(); SpeechRecognitionManager.cancelOwned(ticket); runCurrent()
            SpeechRecognitionManager.selectLocale(Locale.JAPAN)
            a.gate!!.complete(Unit); runCurrent()
            assertEquals(Locale.US, SpeechRecognitionManager.locale.value)
            assertEquals(listOf(Locale.US), a.locales)
        } finally {
            a.gate?.complete(Unit); ticket?.let(SpeechRecognitionManager::cancelOwned); runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test fun mainPanelTracksRestartTicketAndOldPanelCannotStopReplacement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = Engine("system"); install(engine); installPreferences()
        val panel = MainVoiceInput()
        try {
            SpeechRecognitionManager.selectLocale(Locale.US)
            panel.start(panel.pending(),VoiceSelection("system",Locale.US,false),{_,_->},{_,_->}); runCurrent()
            val old = panel.ticket.value!!
            engine.gate = CompletableDeferred()
            SpeechRecognitionManager.selectLocale(Locale.FRANCE); runCurrent()
            assertSame(old,panel.ticket.value)
            assertFalse(old.completion.isCompleted)
            engine.gate!!.complete(Unit); runCurrent()
            assertNotSame(old,panel.ticket.value); assertTrue(old.completion.isCompleted)
            panel.stop(); runCurrent(); assertEquals(1,engine.stops)
            panel.close(); runCurrent()
            val replacement = MainVoiceInput()
            replacement.start(replacement.pending(),VoiceSelection("system",Locale.JAPAN,false),{_,_->},{_,_->}); runCurrent()
            val closes = engine.closes; panel.cancel(); runCurrent(); assertEquals(closes,engine.closes)
            replacement.close(); runCurrent()
        } finally { engine.gate?.complete(Unit); panel.close(); SpeechRecognitionManager.cancelRecording(); runCurrent(); Dispatchers.resetMain() }
    }

}
