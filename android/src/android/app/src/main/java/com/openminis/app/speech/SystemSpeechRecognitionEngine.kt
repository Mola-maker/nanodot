package com.openminis.app.speech

// nanoMuse: source-selected passive voice proof producer.
import io.github.nanomuse.dot.voice.proof.VoiceProofTap

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Wraps [android.speech.SpeechRecognizer]. Handles OEM fragmentation —
 * especially Chinese ROMs where [SpeechRecognizer.isRecognitionAvailable]
 * can return `true` yet [SpeechRecognizer.startListening] immediately fires
 * ERROR_INSUFFICIENT_PERMISSIONS or ERROR_CLIENT. A first-failure observation
 * marks the engine permanently degraded for the rest of the process so the
 * UI can hide the mic button.
 *
 * Must be constructed and used on the main thread because [SpeechRecognizer]
 * delivers callbacks on the thread that creates it.
 */
class SystemSpeechRecognitionEngine(
    private val appContext: Context,
    private val mainDispatch: ((() -> Unit) -> Unit)? = null,
) : SpeechRecognitionEngine {

    override val id: String = "system"
    override val displayName: String = "System recognizer"
    override val supportsPartialResults: Boolean = true

    /** Session-scoped degraded flag — set once a runtime error proves the service unusable. */
    @Volatile private var degraded: Boolean = false

    /** Cached supported locales (populated lazily on first request). */
    @Volatile private var cachedSupportedLocales: List<Locale>? = null

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechRecognitionEngine.Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // nanoMuse: one dispatch boundary also permits deterministic endpoint/cancel ordering tests.
    private fun onMain(action: () -> Unit) {
        mainDispatch?.let { it(action) } ?: mainHandler.post(action)
    }

    override val isAvailable: Boolean
        get() {
            if (degraded) return false
            // Two-layer probe. The first returns true on many Chinese ROMs
            // that actually ship no real service — the intent query is the
            // backstop. Both cheap.
            val systemSaysYes = try { SpeechRecognizer.isRecognitionAvailable(appContext) }
                catch (_: Throwable) { false }
            if (!systemSaysYes) return false
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            val services = try {
                appContext.packageManager.queryIntentServices(intent, 0)
            } catch (_: Throwable) { emptyList() }
            return services.isNotEmpty()
        }

    override val supportedLocales: List<Locale>
        get() = cachedSupportedLocales ?: fallbackLocales

    /**
     * Fire the [RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS] ordered broadcast
     * and cache the recognizer's reported supported languages. Safe to call
     * repeatedly — only the first call does work; subsequent calls short-circuit.
     *
     * The broadcast can take a few hundred ms and some OEM services never
     * respond, so [onResult] is guaranteed to fire exactly once — either with
     * the system's answer or with the curated fallback after a timeout.
     */
    fun fetchSupportedLocales(onResult: (List<Locale>) -> Unit) {
        cachedSupportedLocales?.let { onResult(it); return }

        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliver(locales: List<Locale>) {
            if (fired.compareAndSet(false, true)) {
                cachedSupportedLocales = locales
                onResult(locales)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val tags = getResultExtras(true)
                    ?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    .orEmpty()
                val locales = tags.mapNotNull { tag ->
                    runCatching { Locale.forLanguageTag(tag) }.getOrNull()
                        ?.takeIf { !it.language.isNullOrEmpty() }
                }.distinct()
                if (locales.isNotEmpty()) deliver(locales) else deliver(fallbackLocales)
            }
        }
        try {
            appContext.sendOrderedBroadcast(
                Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
                null, receiver, mainHandler, 0, null, null,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "ACTION_GET_LANGUAGE_DETAILS failed: ${e.message}")
            deliver(fallbackLocales)
            return
        }
        // Backstop in case no one responds.
        mainHandler.postDelayed({ deliver(fallbackLocales) }, 2500)
    }

    /**
     * Curated locale list used when the system refuses to enumerate. Covers
     * the major languages that Google and most OEM recognizers support.
     */
    private val fallbackLocales: List<Locale> by lazy {
        listOf(
            // [T-android-asr-script-subtag] Script-stripped: the raw default on
            // a Simplified-Chinese device is zh-Hans-CN, which the recognizer
            // rejects. Since this list is also what SpeechRecognitionManager
            // re-validates the saved locale against, leaving the raw default
            // here made the broken locale look "supported" and the fallback
            // never fired.
            Locale.forLanguageTag(recognizerLanguageTag(Locale.getDefault())),
            Locale.forLanguageTag("en-US"),
            Locale.forLanguageTag("zh-CN"),
            Locale.forLanguageTag("zh-TW"),
            Locale.forLanguageTag("ja-JP"),
            Locale.forLanguageTag("ko-KR"),
            Locale.forLanguageTag("es-ES"),
            Locale.forLanguageTag("fr-FR"),
            Locale.forLanguageTag("de-DE"),
            Locale.forLanguageTag("it-IT"),
            Locale.forLanguageTag("pt-BR"),
            Locale.forLanguageTag("ru-RU"),
            Locale.forLanguageTag("ar-SA"),
            Locale.forLanguageTag("hi-IN"),
            Locale.forLanguageTag("id-ID"),
            Locale.forLanguageTag("th-TH"),
            Locale.forLanguageTag("vi-VN"),
            Locale.forLanguageTag("tr-TR"),
        ).distinctBy { it.toLanguageTag() }
    }

    // nanoMuse: pin callbacks and cleanup to this capture and every platform restart.
    private var platformGeneration = 0L
    private var captureGeneration = 0L
    private var cleanupFailure: Throwable? = null
    private val captureResources = io.github.nanomuse.dot.voice.CaptureResources()
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) = startOwned(locale, null, listener)
    override fun startOwned(locale: Locale, continuous: Boolean?, listener: SpeechRecognitionEngine.Listener) {
        cleanupFailure = null
        ++captureGeneration // nanoMuse: invalidate delayed VAD work from a previous capture.
        // nanoMuse: bind the actual selected engine's capture generation, without starting a recognizer.
        if (VoiceProofTap.active)
            VoiceProofTap.captureBegin(this, captureGeneration)
        this.listener = listener
        bufferedPartial = null
        heardSpeech = false
        sessionCommitted = false
        heldSpokenSeconds = 0f
        holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        holdFlushRunnable = null

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(RecognitionError.PERMISSION_DENIED, "RECORD_AUDIO not granted")
            return
        }

        // [T-android-vad] Start OUR capture first when we can feed the
        // recogniser, so the pipe already has audio by the time it starts
        // reading. When we can't, the recogniser opens the mic itself and no
        // VAD runs — see startEndpointingVad.
        segmentTranscript.setLength(0)
        startEndpointingVad()
        // [T-android-continuous-dictation] Only when we own the capture can we
        // survive the platform's ~1.5 s cut: restarting the recogniser is
        // harmless because the mic never changes hands. If the recogniser owns
        // the mic, a restart would re-acquire it each time, so we leave that
        // path on single-shot behaviour.
        continuousSession = io.github.nanomuse.dot.voice.CapturePolicy.continuous(feedingAudio, continuous)
        continuousLocale = if (feedingAudio) locale else null
        startInternal(locale, allowOnDeviceRetry = true)
    }

    // ── [T-android-vad] Silero endpointing ────────────────────────────────

    /**
     * The VAD that decides when the user stopped talking, replacing the
     * platform recognizer's OEM-specific endpointing.
     *
     * It DOES capture the audio that gets recognised. An earlier attempt ran
     * the VAD on a second AudioRecord alongside the recogniser's own; that
     * does not work, because Android gives the real stream to one client and
     * starves the other. On a Pixel 6 it was the recogniser that lost: it
     * reported `onStartOfSpeech` and then `withSpeech: false`, having been
     * handed nothing to transcribe.
     *
     * So there is exactly ONE capture. Its frames feed the VAD and, through
     * `RecognizerIntent.EXTRA_AUDIO_SOURCE`, the recogniser as well. Ending
     * the session is then simply closing the pipe: "the recognition session
     * will end when and only when the audio is closed" — which stops the mic
     * and commits the transcript in one step, exactly like iOS's silence
     * auto-stop (VoiceInputPanel.swift:642-669).
     *
     * EXTRA_AUDIO_SOURCE is API 33+; below that, and whenever the pipe cannot
     * be set up, the recogniser opens the mic itself and no VAD runs.
     */
    @Volatile
    private var vad: VoiceActivityDetector? = null

    /** Wall-clock ms when the current VAD speech segment opened; 0 when idle. */
    @Volatile
    private var speechStartedAt: Long = 0L

    /**
     * [T-android-asr-silent-failure] Sticky per-dictation flag: the VAD saw at
     * least one voiced segment since start(). Unlike [speechStartedAt] it is
     * NOT cleared at segment end — it exists so the error paths can tell
     * "user never spoke" (NO_MATCH, stays quiet) from "user spoke and the
     * recognizer returned nothing" (TRANSCRIPTION_FAILED, must be visible).
     */
    @Volatile
    private var heardSpeech = false

    /**
     * [T-android-asr-rom-compat] True once this session has delivered its
     * terminal outcome (final text or error). The segmented-session contract
     * says onEndOfSegmentedSession REPLACES onResults, but that's Google's
     * recognizer — a nonconforming OEM implementation may fire both, or tack
     * an error on after the result. Without this guard the second callback
     * re-committed against an already-cleared transcript: a blank commit with
     * heardSpeech still true, i.e. a spurious TRANSCRIPTION_FAILED error
     * popping up right after a successful dictation.
     */
    @Volatile
    private var sessionCommitted = false

    /**
     * [T-android-vad-merge-segments] Speech seconds handed to the recognizer
     * across consecutive sub-threshold bursts. The 2 s floor applies to this
     * total, not to any single burst.
     */
    private var heldSpokenSeconds = 0f
    private var holdFlushRunnable: Runnable? = null

    // nanoMuse: every pipe remains owned through write failure and asynchronous retirement.
    private val audioPipes = io.github.nanomuse.dot.voice.CapturePipes<android.os.ParcelFileDescriptor>()

    /**
     * True when this session feeds the recogniser our own audio. False on
     * API < 33 (EXTRA_AUDIO_SOURCE did not exist) or when the pipe could not
     * be created — in which case the recogniser opens the mic itself and no
     * VAD runs, because a second AudioRecord would starve it.
     */
    @Volatile
    private var feedingAudio: Boolean = false

    /**
     * [T-android-continuous-dictation] True from the user's mic tap until the
     * user taps again. While set, a platform-initiated end of session is a
     * SEGMENT boundary, not the end of dictation, and we immediately open a
     * new recogniser over the same capture.
     *
     * Why: `SpeechRecognizer` runs its own endpointing on whatever audio it is
     * given — even audio we supply through EXTRA_AUDIO_SOURCE — and cuts after
     * roughly 1.5 s of silence. The silence extras that would widen that are
     * advisory and Google's recogniser ignores them, so a single session
     * cannot be held open across a natural pause. Restarting is the only way
     * to make "keep listening until I say stop" true on this engine.
     *
     * The mic is NOT re-acquired on restart: our AudioRecord runs for the whole
     * dictation and each new recogniser reads a fresh pipe fed from it. That is
     * what stops the restart from becoming a mic-grab fight.
     */
    @Volatile
    private var continuousSession: Boolean = false

    /** Locale of the running dictation, so a restart reuses it. */
    @Volatile
    private var continuousLocale: Locale? = null

    /**
     * Text committed by earlier segments of this dictation. Each restart makes
     * the platform emit its own independent result, so the caller would
     * otherwise see only the last fragment.
     */
    private val segmentTranscript = StringBuilder()

    private fun startEndpointingVad() {
        // nanoMuse: VAD callbacks are delivered on IO; marshal and reject retired captures.
        val capture = captureGeneration
        // EXTRA_AUDIO_SOURCE is API 33+. Below that the recogniser must open
        // the mic itself, so we cannot run a VAD at all: two AudioRecords on
        // one device leaves one of them silent.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "[vad] API ${Build.VERSION.SDK_INT} < 33 — platform endpointing")
            return
        }
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            Log.w(TAG, "[vad] pipe creation failed, platform endpointing: ${t.message}")
            return
        }
        audioPipes.install(io.github.nanomuse.dot.voice.CapturePipe(
            pipe[0], pipe[1], android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]),
        ))

        val det = VoiceActivityDetector(appContext, endpointCallbacks(capture))
        // ONE capture serves both: the VAD judges these samples and the very
        // same frames go down the pipe to the recogniser.
        det.rawAudioSink = ::writeAudioPipe
        // nanoMuse: retain the detector until the capture job's resource finally completes.
        captureResources.track({ det.cancel() }, { det.awaitStopped() })
        when (val err = det.start()) {
            null -> {
                vad = det
                feedingAudio = true
                Log.i(TAG, "[vad] capture started, feeding recogniser via EXTRA_AUDIO_SOURCE")
            }
            else -> {
                // Capture failed: tear the pipe down so the recogniser is never
                // handed a source that will stay empty forever, and let it open
                // the mic itself.
                Log.w(TAG, "[vad] could not start, platform endpointing applies: $err")
                closeAudioPipe()
            }
        }
    }

    private fun endpointCallbacks(capture: Long): VoiceActivityListener = object : VoiceActivityListener {
        override fun onVoiceStart() {
            onMain {
                if (capture != captureGeneration) return@onMain
                speechStartedAt = System.currentTimeMillis()
                heardSpeech = true
                Log.i(TAG, "[vad] speech start")
            }
        }

        override fun onLevel(level: Float) {
            onMain {
                if (capture != captureGeneration) return@onMain
                // Drive the waveform from the VAD's AGC-boosted tap rather
                // than onRmsChanged: it updates ~10x more often and uses
                // the same perceptual curve as iOS. Mapped back onto the
                // [0,12] scale SpeechRecognitionManager normalises from.
                listener?.onRmsDb(level * 12f)
            }
        }

        override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {
            onMain {
                if (capture != captureGeneration) return@onMain
                if (reason != SegmentEndReason.SILENCE_DETECTED) return@onMain
                speechStartedAt = 0L

                // iOS discards a silence-closed segment under 2.0 s
                // outright (VoiceInputPanel.swift:652-663) — a cough or a
                // door shouldn't commit text. Cancel rather than stop so
                // the recognizer produces no result at all.
                //
                // spokenSeconds is measured from the audio payload; a
                // wall-clock figure would include the ~5 s silence that
                // closed the segment and could never fall below 2 s.
                // [T-android-vad-merge-segments] Accumulate DURATION, don't
                // cancel.
                //
                // The Provider engine can merge WAV payloads, but this path
                // can't: the audio has ALREADY been streamed into the
                // recognizer's pipe by the time onVoiceEnd fires, so there
                // is nothing left to concatenate. The equivalent fix is to
                // keep the session open — a sub-2s burst just isn't a
                // reason to tear it down. iOS applies its 2 s floor to the
                // running total (VoiceInputPanel.swift:651); here the
                // recognizer holds the audio and we only track how much
                // speech it has been given.
                //
                // Without this, natural stop-start speech — "yes" (0.8 s),
                // pause, "send it" (0.9 s) — cancelled on the first burst
                // and could never be dictated.
                heldSpokenSeconds += spokenSeconds
                if (heldSpokenSeconds < MIN_SEGMENT_SECONDS) {
                    Log.i(
                        TAG,
                        "[vad] silence close, spoken=${"%.2f".format(spokenSeconds)}s " +
                            "(held ${"%.2f".format(heldSpokenSeconds)}s) — holding session open",
                    )
                    // Force-flush after HOLD_FLUSH_MS so an isolated cough
                    // still settles instead of pinning the mic open. The
                    // recognizer's own session cap still bounds the total.
                    holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
                    val flush = Runnable {
                        if (capture != captureGeneration) return@Runnable
                        val held = heldSpokenSeconds
                        Log.i(TAG, "[vad] hold expired at ${"%.2f".format(held)}s — settling")
                        listener?.onError(RecognitionError.NO_MATCH, "Too short — tap the mic and speak.")
                        cancel()
                    }
                    holdFlushRunnable = flush
                    mainHandler.postDelayed(flush, HOLD_FLUSH_MS)
                    return@onMain
                }
                holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
                holdFlushRunnable = null
                Log.i(TAG, "[vad] silence close, spoken=${"%.2f".format(spokenSeconds)}s — finalising")
                finaliseSession()
            }
        }

        override fun onSessionLimit(limit: SessionLimit) {
            onMain {
                if (capture != captureGeneration) return@onMain
                // Not a failure — the session ran out its allowance. Commit
                // whatever the recognizer has rather than discarding it, so
                // a long dictation that hits the 300 s ceiling is not lost.
                Log.i(TAG, "[vad] session limit $limit — finalising")
                finaliseSession()
            }
        }

        override fun onCaptureError(message: String) {
            onMain {
                if (capture != captureGeneration) return@onMain
                // nanoMuse: a dead audio feeder is terminal, not microphone-ready.
                Log.w(TAG, "[vad] endpointing unavailable: $message")
                listener?.onError(RecognitionError.AUDIO_ERROR, message)
                stopVad()
                closeAudioPipe()
            }
        }
    }

    private fun writeAudioPipe(bytes: ByteArray, len: Int) = audioPipes.write(bytes, len)

    /**
     * End the recognition session the way EXTRA_AUDIO_SOURCE defines it:
     * "the recognition session will end when and only when the audio is
     * closed". Closing the write end makes the recogniser finalise and deliver
     * onResults, so this both stops the mic and commits the transcript — the
     * single step iOS performs on a silence close.
     *
     * When we are NOT feeding audio (API < 33, or the pipe failed) there is no
     * stream to close and stopListening() is the only lever.
     */
    private fun finaliseSession() {
        // stop() already routes both cases correctly: it closes the pipe when
        // we own the audio and always forwards stopListening().
        stop()
    }

    /** Everything heard this dictation, including the segment just closed. */
    private fun joinedTranscript(lastSegment: String): String {
        if (segmentTranscript.isEmpty()) return lastSegment
        if (lastSegment.isEmpty()) return segmentTranscript.toString()
        return "$segmentTranscript $lastSegment"
    }

    /**
     * [T-android-continuous-dictation] Open a new recogniser for the next
     * segment, leaving the microphone capture untouched.
     *
     * Only the pipe is rebuilt: the previous recogniser consumed its read end
     * and will not read another byte, so a fresh pair is handed to the new one
     * and the VAD's sink is repointed at the new write end. The AudioRecord
     * behind it never stops, which is what keeps this from turning into the
     * mic-grab fight that a full stop/start would cause.
     */
    private fun restartRecogniserForNextSegment() {
        val locale = continuousLocale ?: run {
            Log.w(TAG, "[continuous] no locale to restart with — ending dictation")
            endContinuousSession()
            return
        }
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null

        // Retire the consumed pipe and hand the capture a fresh one.
        closeAudioPipe()
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            Log.w(TAG, "[continuous] could not re-open pipe — ending dictation: ${t.message}")
            endContinuousSession()
            return
        }
        audioPipes.install(io.github.nanomuse.dot.voice.CapturePipe(
            pipe[0], pipe[1], android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]),
        ))
        feedingAudio = true

        startInternal(locale, allowOnDeviceRetry = false)
    }

    /**
     * End a continuous dictation and deliver everything heard. Used when the
     * user stops, and when a restart cannot continue.
     */
    private fun endContinuousSession() {
        continuousSession = false
        continuousLocale = null
        val all = joinedTranscript(bufferedPartial.orEmpty())
        segmentTranscript.setLength(0)
        bufferedPartial = null
        // nanoMuse: continuous application commit is distinct from a platform final.
        if (VoiceProofTap.ownsCapture(this, captureGeneration, platformGeneration))
            VoiceProofTap.asr(this, captureGeneration, platformGeneration, "application_continuous_end") { all }
        if (all.isNotEmpty()) listener?.onFinal(all) else listener?.onFinal("")
        tearDown()
    }

    private fun closeAudioPipe() {
        audioPipes.retireCurrent()
        feedingAudio = false
    }

    private fun stopVad() {
        vad?.let {
            it.rawAudioSink = null
            runCatching { it.stop() }.onFailure { cleanupFailure = it }
        }
        vad = null
        speechStartedAt = 0L
    }

    /**
     * [T-android-vad] Told by [SpeechRecognitionManager] when the app moves
     * between foreground and background, so the detector can apply iOS's
     * 15 s backgrounded stop (VoiceInputPanel.swift:285-295). Recording
     * survives a brief switch away but not a real one.
     */
    fun setBackgrounded(backgrounded: Boolean) {
        vad?.isBackgrounded = backgrounded
    }

    /**
     * [T-android-asr-ondevice-fallback] Start a session.
     *
     * [allowOnDeviceRetry] is true for the first attempt; when the on-device
     * (SODA) recognizer answers ERROR_LANGUAGE_UNAVAILABLE we restart once with
     * it false, which forces the regular (typically cloud) recognizer.
     */
    private fun startInternal(locale: Locale, allowOnDeviceRetry: Boolean) {
        // nanoMuse: a cancelled/retired posted start cannot open a newer capture's recognizer.
        val generation = ++platformGeneration
        onMain {
            if (platformGeneration != generation) return@onMain
            try {
                // Only reach for the on-device recognizer when the user actually
                // asked for offline. It used to be created unconditionally on
                // S+, which silently overrode the panel's "System Recognition
                // (Online)" choice — and on a device with no on-device pack for
                // the locale (e.g. a zh-CN Pixel 4a) SODA fails every session
                // with "Failed to get language pack" / ERROR_LANGUAGE_
                // UNAVAILABLE(12), so the mic opened and closed instantly.
                val wantOnDevice = preferOffline &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    allowOnDeviceRetry
                val r = if (wantOnDevice) {
                    // nanoMuse: record the branch that really succeeded; keep usingOnDevice policy unchanged.
                    try { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also {
                        VoiceProofTap.factory(this, captureGeneration, generation, it, "on_device")
                    } }
                    catch (_: Throwable) { SpeechRecognizer.createSpeechRecognizer(appContext).also {
                        VoiceProofTap.factory(this, captureGeneration, generation, it, "regular_fallback")
                    } }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext).also {
                        VoiceProofTap.factory(this, captureGeneration, generation, it, "regular")
                    }
                }
                usingOnDevice = wantOnDevice
                pendingLocale = locale
                recognizer = r
                r.setRecognitionListener(callbacksFor(generation))
                r.startListening(buildIntent(locale))
            } catch (e: Throwable) {
                Log.w(TAG, "startListening threw: ${e.message}")
                this.listener?.onError(RecognitionError.UNKNOWN, e.message)
                markDegraded()
                tearDown()
            }
        }
    }

    /** True when the live session is using the on-device recognizer. */
    @Volatile private var usingOnDevice: Boolean = false

    /** Locale of the live session, so a fallback restart can reuse it. */
    @Volatile private var pendingLocale: Locale? = null

    override fun stop() {
        // nanoMuse: VAD/session-limit stop uses the same owner's timeout as a manual stop.
        listener?.onFinishing()
        // [T-android-vad] Order matters. stopListening() first, so the
        // recogniser finalises what it already has and delivers onResults;
        // only then close the pipe. Closing first (which segmented-session
        // mode required) meant the audio source vanished before the result was
        // routed, and onResults never reached us.
        //
        // Our capture is stopped here because nothing else will — the
        // recogniser only owns the pipe, not the AudioRecord behind it.
        // nanoMuse: queued stop belongs to this platform generation only.
        val generation = platformGeneration
        onMain {
            if (generation != platformGeneration) return@onMain
            // [T-android-continuous-dictation] Disarm BEFORE stopListening, so
            // the onResults it triggers ends the dictation instead of starting
            // another segment.
            continuousSession = false
            continuousLocale = null

            // Stop feeding first, THEN ask the recogniser to finalise.
            //
            // Closing the pipe is what tells the recogniser the audio has
            // ended; without it, stopListening() leaves it waiting on a stream
            // that is still open and it finalises on whatever it has buffered.
            // Doing this in the other order (stopListening, then close) meant
            // the recogniser evaluated a stream that our capture was still
            // writing to and repeatedly answered NO_SPEECH_DETECTED even though
            // the user had spoken — the "recording silently discarded" report.
            stopVad()
            closeAudioPipe()
            try { recognizer?.stopListening() }
            catch (e: Throwable) {
                Log.w(TAG, "stopListening: ${e.message}")
                listener?.onError(RecognitionError.UNKNOWN, e.message)
            }
        }
    }

    override fun cancel() {
        // nanoMuse: invalidate immediately, before the posted platform cleanup runs.
        ++platformGeneration
        ++captureGeneration
        listener = null
        val generation = platformGeneration
        onMain {
            if (generation != platformGeneration) return@onMain
            try { recognizer?.cancel() }
            catch (e: Throwable) { Log.w(TAG, "cancel: ${e.message}") }
            tearDown()
        }
    }

    // nanoMuse: the manager keeps admission until posted teardown AND VAD completion.
    override suspend fun cancelAndJoin() {
        var failure: Throwable? = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                ++platformGeneration
                ++captureGeneration
                listener = null
                try { recognizer?.cancel() } finally { tearDown() }
            }
        } catch (t: Throwable) { failure = t }
        try { captureResources.closeAndJoin() } catch (t: Throwable) { failure = t }
        try { audioPipes.closeAndJoin() } catch (t: Throwable) { failure = t }
        (failure ?: cleanupFailure)?.let { markDegraded(); throw it }
    }

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    private fun tearDown() {
        // nanoMuse: stale platform/VAD callbacks must not reach the next listener or restart it.
        ++platformGeneration
        ++captureGeneration
        // nanoMuse: cleanup errors remain observable after all other resources are attempted.
        try { recognizer?.destroy() } catch (t: Throwable) { cleanupFailure = t }
        recognizer = null
        // [T-android-vad] The VAD holds the mic; leaking it past a session
        // would keep recording after the user stopped. The pipe must go too —
        // a dangling write end keeps a stale reader alive.
        stopVad()
        closeAudioPipe()
        bufferedPartial = null
        // [T-android-continuous-dictation] A stale arm would make the NEXT
        // session restart on its first platform cut, before start() has set it.
        continuousSession = false
        continuousLocale = null
        segmentTranscript.setLength(0)
        // [T-android-vad-merge-segments] Drop the hold: a pending flush firing
        // into a torn-down session would stop a VAD that no longer exists and
        // emit a "too short" toast for audio the user already abandoned.
        holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        holdFlushRunnable = null
        heldSpokenSeconds = 0f
    }

    /**
     * [T-android-vad] Last interim hypothesis, held back from the UI. See
     * onPartialResults — this is the salvage path for a recognizer that never
     * emits a final, not a live preview.
     */
    @Volatile
    private var bufferedPartial: String? = null

    /**
     * [T-android-voice-panel] Prefer fully on-device recognition (maps the
     * panel's "System Recognition (Offline)" pick). Best-effort: the OS may
     * still fall back when no offline pack is installed for the locale.
     */
    @Volatile var preferOffline: Boolean = false

    /**
     * [T-android-asr-script-subtag] Strip the SCRIPT subtag from a language tag
     * before handing it to the recognizer.
     *
     * `Locale.getDefault()` on a Simplified-Chinese device is `zh-Hans-CN`, and
     * `toLanguageTag()` faithfully returns "zh-Hans-CN". Google's recognizer
     * matches its language packs on plain `language-REGION` tags, so the script
     * subtag makes it report "Locale not supported" / "Failed to get language
     * pack of required locale" and fail the session with ERROR_LANGUAGE_
     * UNAVAILABLE (12) — the mic opens and immediately closes. Observed on a
     * Pixel 4a whose system locale is zh-Hans-CN.
     *
     * Reducing to language(+region) keeps the meaningful part ("zh-CN") and is
     * safe for every other locale: tags without a script subtag are unchanged.
     */
    private fun recognizerLanguageTag(locale: Locale): String {
        val lang = locale.language
        var region = locale.country
        // A script-only locale carries no region ("zh-Hans"), and a bare "zh"
        // is just as unresolvable to the recognizer as "zh-Hans" — it needs a
        // concrete language pack. Derive the region from the script, which is
        // exactly the information the script subtag encodes.
        if (region.isNullOrEmpty() && lang == "zh") {
            region = if (locale.script == "Hant") "TW" else "CN"
        }
        // Last resort for any other script-only/region-less locale: ask ICU to
        // fill in the likely region (zh-Hans -> zh-Hans-CN, etc.).
        if (region.isNullOrEmpty()) {
            region = runCatching { Locale.Builder().setLocale(locale).build() }
                .getOrNull()?.country.orEmpty()
        }
        return if (region.isEmpty()) lang else "$lang-$region"
    }

    private fun buildIntent(locale: Locale): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLanguageTag(locale))
            // [T-android-vad] Partials are still REQUESTED but never surfaced —
            // see onPartialResults. iOS does exactly this: its
            // SFSpeechURLRecognitionRequest sets shouldReportPartialResults =
            // true purely as a salvage path when the recognizer never emits a
            // final (VoiceProvider+System.swift:139-143), and buffers the text
            // in a local rather than showing it.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // [T-android-vad] Endpointing now belongs to our Silero VAD, so ask
            // the platform to sit still and let it decide. These extras are
            // advisory (Google's recognizer has largely ignored them since
            // Android 4.x), which is precisely why we can no longer rely on
            // them: the same app segmented differently per OEM. Padding them
            // well past the VAD's ~5 s window keeps the platform from cutting
            // a segment before the VAD does on ROMs that DO honour them.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PLATFORM_SILENCE_GUARD_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PLATFORM_SILENCE_GUARD_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)

            // [T-android-vad] Feed OUR capture instead of letting the
            // recogniser open the mic. This is what makes a VAD possible at
            // all: two AudioRecords on one device leaves one silent, and it
            // was the recogniser that starved — it reported onStartOfSpeech
            // and then `withSpeech: false`, having been given no audio.
            //
            // With a single source the VAD judges the same samples the
            // recogniser transcribes, and closing the pipe ends the session:
            // "The recognition session will end when and only when the audio
            // is closed" (RecognizerIntent.EXTRA_AUDIO_SOURCE).
            audioPipes.read?.let { readEnd ->
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceActivityDetector.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                // [T-android-asr-silent-failure] EXTRA_SEGMENTED_SESSION is
                // back ON — and this time the segment callbacks below are
                // implemented, which is the piece the first attempt missed.
                //
                // History: the extra was originally set, results went to
                // onSegmentResults (unimplemented) and the panel spun forever,
                // so the "fix" was to drop the extra. That made things WORSE,
                // not better: Google's recognizer classifies an injected-audio
                // session as AMBIENT_CONTINUOUS with multi-utterance
                // endpointing REGARDLESS (its own logcat: `Initialize Soda
                // [applicationDomain: AMBIENT_CONTINUOUS]`, `multi = true`).
                // Per-utterance finals then had no compliant delivery path:
                // every terminal onResults arrived with an EMPTY
                // RESULTS_RECOGNITION (finalLen=0 on 100% of sessions in
                // minis-2026-08-16.log) while Google's own process logged
                // `#onResults withSpeech: true` two ms earlier — the text
                // existed and went nowhere. The engine then salvaged only the
                // LAST buffered partial, which resets at every internal
                // utterance boundary: a 30.72 s dictation committed 3 chars
                // (13:58:42), and when the tail utterance held no clean speech
                // the whole session collapsed to ERROR_NO_MATCH (the 13:58 /
                // 14:29 error-7 bursts) even though the VAD heard speech.
                //
                // The documented contract (RecognizerIntent.EXTRA_SEGMENTED_
                // SESSION): with the extra set to EXTRA_AUDIO_SOURCE, results
                // arrive per segment via onSegmentResults and the session
                // terminates with onEndOfSegmentedSession when the audio
                // closes. Both are implemented below; segment finals accumulate
                // in segmentTranscript, so nothing rides on the partial-salvage
                // path anymore.
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_AUDIO_SOURCE,
                )
            }
        }
    }

    // nanoMuse: each platform listener carries its own generation, including internal restarts.
    private fun callbacksFor(generation: Long): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (generation == platformGeneration) callbacks.onReadyForSpeech(params) }
        override fun onBeginningOfSpeech() { if (generation == platformGeneration) callbacks.onBeginningOfSpeech() }
        override fun onBufferReceived(buffer: ByteArray?) { if (generation == platformGeneration) callbacks.onBufferReceived(buffer) }
        override fun onEndOfSpeech() { if (generation == platformGeneration) callbacks.onEndOfSpeech() }
        override fun onEvent(eventType: Int, params: Bundle?) { if (generation == platformGeneration) callbacks.onEvent(eventType, params) }
        override fun onRmsChanged(rms: Float) { if (generation == platformGeneration) callbacks.onRmsChanged(rms) }
        // nanoMuse: passive platform entry is inside the original generation guard, before one forwarding.
        override fun onPartialResults(results: Bundle?) { if (generation == platformGeneration) {
            observePlatform(generation, "platform_partial", results)
            callbacks.onPartialResults(results)
        } }
        override fun onResults(results: Bundle?) { if (generation == platformGeneration) {
            observePlatform(generation, "platform_results", results)
            callbacks.onResults(results)
        } }
        override fun onSegmentResults(results: Bundle) { if (generation == platformGeneration) {
            observePlatform(generation, "platform_segment", results)
            callbacks.onSegmentResults(results)
        } }
        override fun onEndOfSegmentedSession() { if (generation == platformGeneration) {
            observePlatform(generation, "platform_segmented_end", null)
            callbacks.onEndOfSegmentedSession()
        } }
        override fun onError(error: Int) { if (generation == platformGeneration) {
            observePlatform(generation, "platform_error", null, error)
            callbacks.onError(error)
        } }
    }

    // nanoMuse: no Bundle read or payload copy outside the registered public capture owner.
    private fun observePlatform(generation: Long, kind: String, results: Bundle?, error: Int? = null) {
        if (VoiceProofTap.ownsCapture(this, captureGeneration, generation))
            VoiceProofTap.asr(this, captureGeneration, generation, kind) {
                if (error != null) error.toString()
                else results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { listener?.onReadyForSpeech() }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onRmsChanged(rms: Float) { listener?.onRmsDb(rms) }

        /**
         * [T-android-vad] Partials are BUFFERED, never forwarded.
         *
         * This is the behavioural change that removes live streaming: text no
         * longer appears word-by-word as you speak. It is held here and only
         * committed once the utterance is complete, matching iOS, where the
         * recognizer's partials likewise never reach the UI
         * (VoiceProvider+System.swift:173-187 keeps them in a local `latestText`).
         *
         * Kept rather than disabled because it is the salvage path: some
         * recognizers fail to emit a final result on the first request or two
         * after a cold start, and without this the whole utterance would be
         * lost. [flushBufferedPartial] delivers it if that happens.
         */
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isEmpty()) return
            // [T-android-asr-silent-failure] Fold an utterance-boundary reset
            // into the transcript instead of overwriting it. In multi-utterance
            // (AMBIENT_CONTINUOUS) sessions the hypothesis stream RESTARTS at
            // every internal endpoint: partials for utterance N+1 begin from
            // scratch, so plain `bufferedPartial = text` threw away everything
            // utterance N had said whenever its final went missing — the
            // observed "30.72 s of speech commits 3 chars". A reset is
            // recognised by the new hypothesis being much shorter than, and
            // not a prefix-refinement of, the buffered one; mid-utterance
            // rewrites keep roughly monotonic length. With onSegmentResults
            // implemented the finals normally land there first (which clears
            // the buffer), so this only fires when a segment final was
            // genuinely dropped — exactly the case worth salvaging.
            val prev = bufferedPartial
            if (prev != null && prev.length > text.length * 2 && !prev.startsWith(text)) {
                Log.i(TAG, "[partial] reset detected (${prev.length}→${text.length}) — folding previous into transcript")
                if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                segmentTranscript.append(prev)
                // nanoMuse: preserve partial folding as application provenance, not platform final.
                if (VoiceProofTap.ownsCapture(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration))
                    VoiceProofTap.asr(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration, "application_partial_fold") { prev }
            }
            bufferedPartial = text
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            // Prefer the final; fall back to the last partial when the
            // recognizer returned an empty final but did hear something.
            val committed = text.ifEmpty { bufferedPartial.orEmpty() }
            // nanoMuse: an empty platform result's buffered replacement remains explicitly application salvage.
            if (text.isEmpty() && committed.isNotEmpty() && VoiceProofTap.ownsCapture(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration))
                VoiceProofTap.asr(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration, "application_empty_final_salvage") { committed }
            Log.i(
                TAG,
                "onResults finalLen=${text.length} bufferedLen=${bufferedPartial?.length ?: 0} " +
                    "committedLen=${committed.length} hasListener=${listener != null}",
            )
            bufferedPartial = null

            // [T-android-continuous-dictation] The platform ended ITS session,
            // which after ~1.5 s of silence is a pause, not the end of what the
            // user is saying. Keep the transcript, open a new recogniser over
            // the same running capture, and only surface text when the user
            // actually stops.
            if (continuousSession) {
                if (committed.isNotEmpty()) {
                    if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                    segmentTranscript.append(committed)
                }
                Log.i(TAG, "[continuous] segment done (+${committed.length}), restarting recogniser")
                restartRecogniserForNextSegment()
                return
            }

            commitFinal(joinedTranscript(committed))
        }

        /**
         * [T-android-asr-silent-failure] Segmented-session delivery — the
         * documented result path for an EXTRA_AUDIO_SOURCE session (see
         * buildIntent). Each internal utterance's FINAL lands here; they
         * accumulate in segmentTranscript so the terminal commit carries the
         * whole dictation instead of whatever partial happened to be last.
         */
        override fun onSegmentResults(segmentResults: Bundle) {
            val text = segmentResults
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            Log.i(TAG, "[segmented] segment result len=${text.length}")
            if (text.isNotEmpty()) {
                if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                segmentTranscript.append(text)
                // The segment final supersedes its own partials; a stale one
                // must not be re-folded into the next segment's salvage.
                bufferedPartial = null
            }
        }

        /**
         * [T-android-asr-silent-failure] Terminal callback of a segmented
         * session — fires when the audio source closes (our VAD's silence
         * close / the user's stop tap). Commits everything accumulated plus
         * any trailing partial the last segment never finalised.
         */
        override fun onEndOfSegmentedSession() {
            val tail = bufferedPartial.orEmpty()
            // nanoMuse: a trailing partial does not become a platform segment final in evidence.
            if (tail.isNotEmpty() && VoiceProofTap.ownsCapture(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration))
                VoiceProofTap.asr(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration, "application_trailing_partial") { tail }
            bufferedPartial = null
            Log.i(
                TAG,
                "[segmented] session end segLen=${segmentTranscript.length} tailLen=${tail.length}",
            )
            continuousSession = false
            continuousLocale = null
            commitFinal(joinedTranscript(tail))
        }

        /**
         * [T-android-asr-silent-failure] Single terminal-commit path shared by
         * onResults and onEndOfSegmentedSession.
         *
         * A blank commit after the VAD heard speech is a REAL failure — the
         * user spoke and the recognizer returned nothing — and used to vanish
         * (onFinal("") is discarded by the panel's blank guard). Surface it as
         * TRANSCRIPTION_FAILED, which the panel renders, unlike NO_MATCH.
         */
        private fun commitFinal(text: String) {
            // [T-android-asr-rom-compat] One terminal outcome per session —
            // see sessionCommitted. Second/duplicate callbacks are dropped.
            if (sessionCommitted) {
                Log.i(TAG, "[commit] duplicate terminal callback ignored (len=${text.length})")
                return
            }
            sessionCommitted = true
            if (text.isBlank() && heardSpeech) {
                Log.w(TAG, "[commit] speech heard but zero text — surfacing TRANSCRIPTION_FAILED")
                // nanoMuse: record only the original application outcome, no synthesized platform result.
                if (VoiceProofTap.ownsCapture(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration))
                    VoiceProofTap.asr(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration, "application_commit_error") { "TRANSCRIPTION_FAILED" }
                listener?.onError(
                    RecognitionError.TRANSCRIPTION_FAILED,
                    "Speech detected but nothing was transcribed. If this keeps happening, " +
                        "install the offline language pack in system voice-input settings, " +
                        "or switch to a provider recognizer.",
                )
                tearDown()
                return
            }
            // nanoMuse: the original listener receives the original final exactly once.
            if (VoiceProofTap.ownsCapture(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration))
                VoiceProofTap.asr(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration, "application_commit") { text }
            listener?.onFinal(text)
            tearDown()
        }

        override fun onError(errorCode: Int) {
            val err = mapError(errorCode)
            val msg = errorMessage(errorCode)
            // [T-android-asr-ondevice-fallback] The on-device recognizer has no
            // language pack for this locale. That is a property of THIS
            // recognizer, not of the device's speech support — the cloud one
            // usually handles the same locale fine. Restart once against the
            // regular recognizer instead of surfacing a dead end to the user.
            val retryLocale = pendingLocale
            if (err == RecognitionError.LANGUAGE_UNSUPPORTED && usingOnDevice && retryLocale != null) {
                Log.i(TAG, "on-device recognizer lacks a pack for the locale — retrying via the default recognizer")
                usingOnDevice = false
                tearDown()
                startInternal(retryLocale, allowOnDeviceRetry = false)
                return
            }
            // First-class ROM-level failures poison the engine for the rest of
            // the process so we don't keep showing a button that doesn't work.
            if (err == RecognitionError.OEM_NO_SERVICE ||
                err == RecognitionError.PERMISSION_DENIED ||
                err == RecognitionError.AUDIO_ERROR
            ) {
                markDegraded()
            }
            // [T-android-continuous-dictation] A silent stretch inside a
            // dictation surfaces as NO_MATCH / SPEECH_TIMEOUT. That is the user
            // thinking, not a failure — restart the segment instead of ending
            // the session and showing an error. Anything else is real and ends
            // the dictation.
            // (ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT both map to NO_MATCH.)
            if (continuousSession && err == RecognitionError.NO_MATCH) {
                Log.i(TAG, "[continuous] pause (error $errorCode) — restarting recogniser")
                restartRecogniserForNextSegment()
                return
            }
            Log.w(TAG, "recognizer error $errorCode ($msg)")

            // [T-android-asr-rom-compat] A trailing error AFTER the session
            // already delivered its result (nonconforming ROMs can fire both)
            // must not surface — the user got their text; a late error would
            // read as the dictation failing retroactively.
            if (sessionCommitted) {
                Log.i(TAG, "[error] post-commit error $errorCode ignored")
                tearDown()
                return
            }
            sessionCommitted = true

            // [T-android-continuous-dictation] Salvage whatever this dictation
            // already produced, whether or not it is still armed.
            //
            // The arm flag is NOT the right condition here. stop() clears it
            // before calling stopListening(), so the error that arrives
            // moments later — very often NO_SPEECH_DETECTED, because the
            // recogniser sees only the tail of the audio after our capture
            // closes — lands with continuousSession already false. Gating the
            // salvage on the flag therefore threw the transcript away on the
            // single most common path: the user taps stop and gets nothing.
            //
            // Anything heard this session lives in segmentTranscript (previous
            // segments) and bufferedPartial (the current one), so both are
            // recoverable independently of the flag.
            continuousSession = false
            continuousLocale = null
            val salvaged = joinedTranscript(bufferedPartial.orEmpty())
            segmentTranscript.setLength(0)
            if (salvaged.isNotEmpty()) {
                Log.i(TAG, "[continuous] salvaging ${salvaged.length} chars from error $errorCode")
                bufferedPartial = null
                // nanoMuse: error salvage is an application result with a separate origin.
                if (VoiceProofTap.ownsCapture(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration))
                    VoiceProofTap.asr(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration, "application_error_salvage") { salvaged }
                listener?.onFinal(salvaged)
                tearDown()
                return
            }
            // [T-android-asr-silent-failure] NO_MATCH with nothing salvaged is
            // only "you didn't say anything" when the user actually didn't.
            // When the VAD registered speech this session, the honest report
            // is a transcription failure — the panel swallows NO_MATCH by
            // design, which is precisely how four consecutive error-7s at
            // 14:29 produced zero visible feedback. Root cause on this device:
            // the offline recognizer has no zh-CN language pack (its own
            // logcat: `Failed to get language pack of required locale:
            // error 12`) and the platform degrades that to ERROR_NO_MATCH, so
            // the actionable cause never reaches our error 11/12 mapping.
            val effErr = if (err == RecognitionError.NO_MATCH && heardSpeech) {
                Log.w(TAG, "[error] NO_MATCH but VAD heard speech — surfacing TRANSCRIPTION_FAILED")
                RecognitionError.TRANSCRIPTION_FAILED
            } else err
            val effMsg = if (effErr == RecognitionError.TRANSCRIPTION_FAILED) {
                "Speech detected but nothing was transcribed. If this keeps happening, " +
                    "install the offline language pack in system voice-input settings, " +
                    "or switch to a provider recognizer."
            } else msg
            // nanoMuse: retain application error identity without copying unrelated freeform error detail.
            if (VoiceProofTap.ownsCapture(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration))
                VoiceProofTap.asr(this@SystemSpeechRecognitionEngine, captureGeneration, platformGeneration, "application_error") { effErr.name }
            listener?.onError(effErr, effMsg)
            tearDown()
        }
    }

    companion object {
        private const val TAG = "SystemSTT"

        // ── [T-android-vad] ──
        /**
         * Silence-close minimum, matching iOS `minSegmentSeconds`
         * (VoiceInputPanel.swift:607). Shorter utterances are discarded.
         */
        private const val MIN_SEGMENT_SECONDS = 2.0f

        /** Below this we stay silent; above it the user is told why (iOS :661). */
        private const val TOO_SHORT_FLOOR_SECONDS = 0.3f

        /**
         * [T-android-vad-merge-segments] How long the session stays open
         * waiting for the user to top up a sub-2s utterance. Matches the iOS
         * force-flush timer (VoiceInputPanel.swift:609).
         */
        private const val HOLD_FLUSH_MS = 5_000L

        /**
         * What we ask the platform to wait before endpointing itself. Our VAD
         * closes at ~5 s, so this is padded well past that: on a ROM that DOES
         * honour the extra, the platform must not cut first.
         */
        private const val PLATFORM_SILENCE_GUARD_MS = 15_000

        private fun mapError(code: Int): RecognitionError = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.NO_MATCH
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionError.NETWORK
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.PERMISSION_DENIED
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionError.RECOGNIZER_BUSY
            SpeechRecognizer.ERROR_AUDIO -> RecognitionError.AUDIO_ERROR
            SpeechRecognizer.ERROR_CLIENT -> RecognitionError.OEM_NO_SERVICE
            SpeechRecognizer.ERROR_SERVER -> RecognitionError.NETWORK
            // Codes added in API 33; referenced by literal for compatibility.
            11 /* ERROR_LANGUAGE_NOT_SUPPORTED */,
            12 /* ERROR_LANGUAGE_UNAVAILABLE   */ -> RecognitionError.LANGUAGE_UNSUPPORTED
            else -> RecognitionError.UNKNOWN
        }

        private fun errorMessage(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error (no recognition service?)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO required"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            // [T-android-asr-script-subtag] mapError already classifies 11/12 as
            // LANGUAGE_UNSUPPORTED, but this function built the user-visible
            // string and had no case for them — so a language-pack problem
            // surfaced as the useless "Unknown error (12)". Name the actual
            // cause, and point at the fix the user can act on.
            11 /* ERROR_LANGUAGE_NOT_SUPPORTED */ ->
                "Language not supported by the recognizer"
            12 /* ERROR_LANGUAGE_UNAVAILABLE */ ->
                "Language pack unavailable — download it in the system speech settings"
            else -> "Unknown error ($code)"
        }
    }
}
