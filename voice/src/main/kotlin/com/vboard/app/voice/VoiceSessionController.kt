package com.vboard.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.vboard.app.R
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.correct.ContentGuard
import com.vboard.core.session.AudioPipeline
import com.vboard.core.session.DictationStateMachine
import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.ErrorKind
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.session.FinalTranscriptPolicy
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.SpokenFormats
import com.vboard.core.text.CleanupResult
import com.vboard.core.text.FieldKind
import com.vboard.core.text.UtteranceCommand
import com.vboard.app.llm.LlmRefinerClient
import com.vboard.app.llm.RemoteRefiner
import com.vboard.app.llm.refinerClientOrNull
import com.vboard.app.settings.SettingsSnapshot
import com.vboard.core.session.RefinementJournal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Drives one dictation session: microphone -> streaming Zipformer partials ->
 * endpoint -> Parakeet final pass -> rules cleanup -> optional LLM refinement.
 * All state transitions flow through the core [DictationStateMachine]; this
 * class executes its effects on Android.
 *
 * Threading, because most of the ways this can go wrong are threading bugs:
 *  - the state machine and every [Host] callback are touched only on the main
 *    thread;
 *  - [AudioCapture] lifecycle calls (start/release) are serialized onto one
 *    dedicated thread so a record is never opened before the previous one has
 *    been freed;
 *  - exactly one reader coroutine exists at a time, and it is the only owner of
 *    the capture buffer;
 *  - the final ASR decode gets its own single thread: decodes cannot overlap,
 *    and a slow one cannot starve Dispatchers.Default (which the suggestion
 *    strip also runs on).
 *
 * Reading and decoding are deliberately separate coroutines with an
 * [AudioPipeline] between them (VB-106). The reader does nothing but read,
 * buffer and hand off; it never touches the main thread and never waits on the
 * decoder. That is what stops a slow decode — or a busy UI thread — from
 * silently overrunning the ~400ms AudioRecord buffer, which used to hand
 * Parakeet audio with holes in it and make the model look inaccurate.
 */
class VoiceSessionController(
    private val service: Context,
    private val app: VoiceRuntime,
    private val host: Host,
) {
    interface Host {
        fun precedingText(): String
        fun fieldKind(): FieldKind
        fun updatePartial(text: String)
        fun commitUtterance(index: Int, text: String)
        fun replaceUtterance(index: Int, newText: String)
        fun deleteLastUtterance()
        fun onSessionEnded()
        fun showError(message: String, action: VoiceErrorAction)
        /**
         * The engines are still loading, so nothing said right now is heard.
         * Only called when they were not already resident — a warm press goes
         * straight to [showListening].
         */
        fun showPreparing()
        fun showListening()
        fun showFinalizing()
        fun showRefining()
        fun onAmplitude(rms: Float)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Deliberately outlives [destroy]: releasing the microphone requires joining
     * a reader that may be parked in a blocking native read, and that join must
     * still happen when the session scope is being torn down.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var machine = DictationStateMachine()
    private var settings: SettingsSnapshot = SettingsSnapshot()
    private var fieldKind: FieldKind = FieldKind.TEXT

    private val capture = AudioCapture()
    private var audioJob: Job? = null
    private var monitorJob: Job? = null
    private var prepareJob: Job? = null
    private var finalizeJob: Job? = null

    /**
     * Refinement outlives the utterance that started it, so it needs cancelling
     * on every teardown path: untracked, it kept an engine claim open and could
     * rewrite text in an editor the session had already left.
     */
    private var refineJob: Job? = null
    private var audioTeardownJob: Job? = null

    /**
     * Focus is requested when the session enters Listening and abandoned from
     * [stopAudio], i.e. on every path out of it. The callback lands on the main
     * thread, which is where the state machine lives.
     */
    private val focus = AudioFocusGuard(service) { reason ->
        Log.w(TAG, "microphone interrupted: $reason")
        scope.launch { dispatch(Event.AudioFocusLost) }
    }

    /** True between a stop request and the reader actually exiting. */
    @Volatile
    private var audioStopRequested = false

    /**
     * Buffers the in-flight utterance for the Parakeet re-pass and hands chunks
     * to the streaming decoder. Recreated per session; see [AudioPipeline] for
     * why the two sides have different loss guarantees.
     */
    private var pipeline: AudioPipeline? = null

    /**
     * Latest chunk level, published by the reader and read by the monitor tick.
     * The reader used to hop to the main thread once per 100ms chunk to deliver
     * this, which meant a busy UI thread could stall the microphone read loop —
     * the exact stall that overruns the capture buffer.
     */
    @Volatile
    private var latestAmplitude = 0f

    private var lastSpeechAt = 0L

    /**
     * Whether anything has been said since the last endpoint. Without it a quiet
     * room would finalize an empty utterance once a second, forever.
     */
    private var speechSinceEndpoint = false

    @Volatile
    private var lastChunkAt = 0L

    // ------------------------------------------------------------ public API

    /**
     * W6.5: silence does not end an utterance while the user is holding the mic.
     *
     * The re-scope W6.5 asked for: with hold-to-talk (W6.3) the finger *is* the
     * endpoint signal, and it is a better one than any silence threshold — a
     * user pausing mid-sentence with the key held is thinking, not finished.
     * Endpointing stays exactly as it was for tap-to-toggle sessions, where
     * there is no such signal.
     */
    fun setEndpointingEnabled(enabled: Boolean) {
        endpointingEnabled = enabled
    }

    private var endpointingEnabled = true

    fun startSession(fieldKind: FieldKind, settings: SettingsSnapshot) {
        // A rapid second tap must not leave the first session's preparation or
        // microphone running: that produced two live AudioRecords feeding two
        // reader loops through one shared buffer.
        prepareJob?.cancel()
        prepareJob = null
        finalizeJob?.cancel()
        finalizeJob = null
        refineJob?.cancel()
        refineJob = null
        stopAudio()

        VoiceEngines.cancelIdleRelease()
        this.fieldKind = fieldKind
        this.settings = settings
        machine = DictationStateMachine(
            DictationStateMachine.Config(refineEnabled = settings.llmRefineEnabled),
        )
        dispatch(Event.MicPressed)
        prepare()
    }

    /**
     * Orb tap: finalize what's been said, then end.
     *
     * The mic is cut first so the utterance buffer stops growing under the
     * snapshot. Whether the machine is still Listening or has already moved to
     * Finalizing (the 0.8s endpoint usually beats the tap, because users are
     * taught to stop talking first), the stop is deferred until the commit lands.
     */
    fun stopAndFinalize() {
        stopAudio()
        if (machine.state is DictationStateMachine.State.Listening) {
            dispatch(Event.EndpointDetected)
        }
        dispatch(Event.StopRequested)
    }

    fun cancelSession() {
        refineJob?.cancel()
        refineJob = null
        stopAudio()
        dispatch(Event.StopRequested)
    }

    /**
     * The editor is going away but anything already spoken must still land in the
     * field (VB-107), so this runs the same deferred-stop path as an orb tap
     * rather than discarding the buffered audio.
     *
     * The commit is asynchronous, so the input connection can still die before
     * the final pass returns. That no longer loses the utterance: the host holds
     * it and replays it into the next editor of the same app (W0.2, see
     * VBoardImeService.replayPendingVoiceCommit).
     */
    fun finishSession() {
        stopAndFinalize()
    }

    /** Teardown without UI callbacks (view is going away). */
    fun cancelSessionSilently() {
        prepareJob?.cancel()
        prepareJob = null
        finalizeJob?.cancel()
        finalizeJob = null
        refineJob?.cancel()
        refineJob = null
        stopAudio()
        machine.reset()
        VoiceEngines.scheduleIdleRelease()
    }

    fun destroy() {
        prepareJob?.cancel()
        finalizeJob?.cancel()
        refineJob?.cancel()
        stopAudio()
        scope.cancel()
        // stopAudio() just queued the last microphone release on teardownScope;
        // let that finish before the scope goes, or the release is the thing we
        // cancel and the native handle leaks instead.
        val release = audioTeardownJob
        teardownScope.launch {
            release?.join()
            teardownScope.cancel()
        }
        VoiceEngines.scheduleIdleRelease()
    }

    // ----------------------------------------------------------- preparation

    private fun prepare() {
        val granted = service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            dispatch(Event.PermissionDenied)
            return
        }
        // Say so rather than showing the listening UI over a mic that is not
        // recording yet: a cold press takes seconds, and the old bar spent them
        // claiming to listen.
        if (!VoiceEngines.isLoaded) host.showPreparing()
        // VB-131: the microphone opens now, not when the models land. A cold
        // press used to spend its first seconds with no record open at all, so
        // whatever was said into it was never captured, only missed. The reader
        // fills the utterance buffer while the load runs; the decode side stays
        // shut until [beginListening].
        openMic()
        prepareJob = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    // Extract archives if a download just finished.
                    ModelCatalog.packs
                        .filter { it.kind != ModelKind.REFINER_LLM }
                        .forEach { app.modelStore.ensureExtracted(app.packInstaller, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A pack that will not extract has already had its installed
                    // marker cleared, so the error screen's Download action leads
                    // somewhere that can actually repair it.
                    Log.e(TAG, "model extraction failed", e)
                    return@withContext VoiceEngines.LoadResult.BROKEN
                }
                VoiceEngines.load(app)
            }
            when (outcome) {
                VoiceEngines.LoadResult.READY -> {
                    if (settings.llmRefineEnabled) {
                        // Warm the refiner here, outside refine()'s own 3s budget:
                        // paying multi-second model init inside that budget made
                        // the first refinement time out every single time.
                        scope.launch(Dispatchers.IO) {
                            runCatching { VoiceEngines.loadRefiner(service, app)?.preload() }
                                .onFailure { Log.w(TAG, "refiner preload failed", it) }
                                // A refiner that answered "no" warmed nothing, and the
                                // first refinement will pay the model init inside its
                                // own budget — which is the timeout this call exists to
                                // avoid. Silence made that look like a slow model.
                                .onSuccess { if (it == false) Log.w(TAG, "refiner did not warm up") }
                        }
                    }
                    dispatch(Event.ModelsReady)
                }
                // The error states do not emit StopAudio (nothing used to be
                // recording this early), so the mic opened above is closed here.
                VoiceEngines.LoadResult.MISSING -> {
                    stopAudio()
                    dispatch(Event.ModelsMissing)
                }
                VoiceEngines.LoadResult.BROKEN -> {
                    stopAudio()
                    dispatch(Event.ModelsUnusable)
                }
            }
        }
    }

    // ------------------------------------------------------------ audio loop

    /**
     * Opens the record and starts the reader. Called before the models are
     * loaded, so it must not touch the engines: everything read here lands in
     * the pipeline's utterance buffer and waits for [beginListening].
     */
    private fun openMic() {
        val pipe = AudioPipeline()
        pipeline = pipe
        val now = System.currentTimeMillis()
        lastSpeechAt = now
        lastChunkAt = now
        latestAmplitude = 0f
        audioStopRequested = false

        // Never leave a previous reader running against a new record.
        audioJob?.cancel()
        val pendingTeardown = audioTeardownJob
        audioJob = scope.launch {
            // The previous record must be freed before we open the next one.
            pendingTeardown?.join()
            // VB-123: hold focus for the length of the session, so other apps
            // stop playing into the microphone and the platform tells us when
            // something more important (a call) needs the audio path.
            focus.request()
            val started = withContext(audioDispatcher) {
                try {
                    capture.start()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The AudioRecord constructor throws IllegalArgumentException for
                    // an unsupported config and SecurityException without the
                    // permission; either one used to crash the IME process under the
                    // user's finger, because only IllegalStateException was caught.
                    Log.e(TAG, "microphone start failed", e)
                    false
                }
            }
            if (!started) {
                focus.abandon()
                dispatch(Event.AudioError)
                return@launch
            }
            lastChunkAt = System.currentTimeMillis()
            // Claim the engines for the reader's lifetime: freeing a recognizer
            // out from under a decode is a native crash, not a dropped result.
            VoiceEngines.beginUse()
            try {
                coroutineScope {
                    val consumer = launch(streamDispatcher) { decodeLoop(pipe) }
                    withContext(Dispatchers.IO) { readLoop(pipe) }
                    // The reader has exited, so nothing more will be offered;
                    // let the decoder finish what is already queued rather than
                    // cutting a partial off mid-word.
                    pipe.close()
                    consumer.join()
                }
            } finally {
                VoiceEngines.endUse()
            }
        }

    }

    /**
     * The models are loaded and the mic has been live since [openMic]. Start the
     * tick that endpoints, times out and reports, i.e. everything that decides
     * what to do with audio rather than merely capturing it.
     */
    private fun beginListening() {
        if (VoiceEngines.finalPass == null) {
            stopAudio()
            dispatch(Event.ModelsMissing)
            return
        }
        // A session that somehow reached here without a record (no prepare, or a
        // mic that failed and was torn down) still needs one.
        if (audioJob == null) openMic()
        val pipe = pipeline ?: return
        // The silence clock runs from here, not from the mic press: a slow load
        // must not spend the user's timeout for them. Speech heard during the
        // load keeps its timestamp, so it endpoints as soon as it should.
        if (!speechSinceEndpoint) lastSpeechAt = System.currentTimeMillis()
        monitorJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(MONITOR_TICK_MS)
                tick++
                // Coalesced UI work: one main-thread update per tick regardless
                // of how many chunks arrived, and the reader never waits for it.
                host.onAmplitude(latestAmplitude)
                val dropped = pipe.drainDroppedSamples()
                if (dropped > 0) dispatch(Event.AudioOverrun(dropped))

                // W: a pause ends an utterance, it does not end the session. The
                // sentence just spoken is transcribed and typed while the mic
                // stays live, so a long dictation lands in pieces as it is
                // spoken instead of all at once when the user finally stops.
                // The session still ends only on the close key (or the much
                // longer silence timeout below).
                if (endpointingEnabled && speechSinceEndpoint &&
                    machine.state is DictationStateMachine.State.Listening &&
                    System.currentTimeMillis() - lastSpeechAt > ENDPOINT_SILENCE_MS
                ) {
                    speechSinceEndpoint = false
                    dispatch(Event.EndpointDetected)
                }

                if (tick % WATCHDOG_EVERY_N_TICKS != 0) continue
                // A call can take the microphone without a focus callback ever
                // arriving (or before it does), and a silent AudioRecord looks
                // exactly like a quiet room. Poll for it.
                if (focus.callStarted()) {
                    focus.reportCallActive()
                    break
                }
                val elapsed = System.currentTimeMillis()
                if (elapsed - lastChunkAt > AUDIO_STALL_TIMEOUT_MS) {
                    // Chunks arrive every ~100ms. Two seconds of nothing means the
                    // mic is gone, however cheerful the bar looks.
                    Log.w(TAG, "no audio for ${elapsed - lastChunkAt}ms; treating as an audio error")
                    dispatch(Event.AudioError)
                    break
                }
                val silenceLimit = settings.silenceTimeout.millis
                if (silenceLimit != null && elapsed - lastSpeechAt > silenceLimit) {
                    dispatch(Event.SilenceTimeout)
                    break
                }
            }
        }
    }

    /**
     * Producer. Runs on Dispatchers.IO, is the sole owner of [AudioCapture.read],
     * and does nothing that can block on another thread: every chunk is buffered
     * for the final pass and offered to the decoder, and that is all. Anything
     * slower than the microphone in here is a driver overrun.
     */
    private suspend fun readLoop(pipe: AudioPipeline) {
        var failure: Int? = null
        while (coroutineContext.isActive) {
            when (val chunk = capture.read()) {
                is AudioCapture.Read.Failed -> {
                    failure = chunk.code
                    break
                }
                AudioCapture.Read.Stopped -> break
                is AudioCapture.Read.Chunk -> {
                    val samples = chunk.samples
                    val now = System.currentTimeMillis()
                    lastChunkAt = now
                    val level = samples.rmsLevel()
                    latestAmplitude = level
                    // The streaming decoder used to say when speech happened. With
                    // it gone the level meter is the only speech signal left, so
                    // the silence timeout reads it directly.
                    if (level > SPEECH_LEVEL) {
                        lastSpeechAt = now
                        speechSinceEndpoint = true
                    }
                    // Never lost for the final pass; may be dropped (and counted)
                    // for the streaming decoder if it has fallen behind.
                    pipe.offer(samples)
                }
            }
        }
        if (failure != null && !audioStopRequested) {
            Log.e(TAG, "audio read failed with code $failure")
            withContext(Dispatchers.Main.immediate) { dispatch(Event.AudioError) }
        } else if (failure == null && !audioStopRequested) {
            // The record stopped without us asking. Silent in the old code; the
            // user simply spoke into nothing until the silence timeout.
            Log.w(TAG, "audio capture ended unexpectedly")
            withContext(Dispatchers.Main.immediate) { dispatch(Event.AudioError) }
        }
    }

    /**
     * Consumer. Drains the decode queue so the pipeline's decoded position keeps
     * up with the reader; there is no live recognizer behind it any more.
     *
     * The queue still exists because it is what tells the pipeline how much audio
     * has been accounted for, and because dropping from it — rather than from the
     * buffer the final pass reads — is what keeps a slow moment from costing words.
     */
    private suspend fun decodeLoop(pipe: AudioPipeline) {
        while (true) {
            pipe.take() ?: break
        }
    }

    /**
     * Stops capture cooperatively. Safe on the main thread and non-blocking: the
     * record is stopped (which unblocks a parked read), the reader is cancelled,
     * and the join-then-release runs on [teardownScope]. Releasing without that
     * join is a native use-after-free, not a dropped chunk.
     */
    private fun stopAudio() {
        // Only the first stop of a live session logs, so the two teardown paths
        // (StopAudio then HideVoiceBar) do not report the same session twice.
        if (monitorJob != null) logDropSummary()
        monitorJob?.cancel()
        monitorJob = null
        val reader = audioJob
        audioJob = null
        audioStopRequested = true
        // Well inside the 500ms budget for giving focus back (VB-123): this runs
        // on every path out of the listening state, before the microphone
        // teardown that has to be joined off the main thread.
        focus.abandon()
        capture.requestStop()
        reader?.cancel()
        val previous = audioTeardownJob
        audioTeardownJob = teardownScope.launch {
            previous?.join()
            if (reader != null) {
                val joined = withTimeoutOrNull(AUDIO_JOIN_TIMEOUT_MS) { reader.join() }
                if (joined == null) {
                    Log.w(TAG, "audio reader did not exit within ${AUDIO_JOIN_TIMEOUT_MS}ms")
                }
            }
            withContext(audioDispatcher) { capture.release() }
        }
    }

    /**
     * One line per session when the streaming decoder lost audio, so a session
     * too short to have ticked a report still leaves a trace. Counts only.
     */
    private fun logDropSummary() {
        val pipe = pipeline ?: return
        if (pipe.droppedSamples <= 0L && pipe.evictedSamples <= 0L) return
        val ms = pipe.droppedSamples * 1_000L / AudioCapture.SAMPLE_RATE
        Log.w(
            TAG,
            "session captured ${pipe.producedSamples} samples; the streaming decoder " +
                "missed ${pipe.droppedSamples} of them (~${ms}ms in ${pipe.droppedChunkCount} " +
                "chunks) and the final pass missed ${pipe.evictedSamples}",
        )
    }

    // ---------------------------------------------------- utterance buffer

    /**
     * The audio the final pass should re-transcribe for the utterance ending now.
     *
     * Two cases, and the difference matters:
     *  - the microphone is still live, so this is an endpoint in a continuing
     *    session: take only up to the decoder's position, because anything the
     *    reader captured after that belongs to the next utterance;
     *  - the microphone has been cut (user stop, editor gone, or an interruption
     *    under VB-123): nothing more is coming, so take everything buffered —
     *    including audio the decoder never caught up with. Leaving it behind is
     *    exactly the "my last sentence vanished" bug.
     */
    private fun takeUtteranceAudio(): FloatArray {
        val pipe = pipeline ?: return FloatArray(0)
        return if (audioStopRequested) {
            pipe.takeAllUtterance()
        } else {
            pipe.takeUtteranceThrough(pipe.decodedPosition)
        }
    }

    // ------------------------------------------------------------ finalizing

    /**
     * Runs the final pass for one utterance. Re-entry is refused twice over: the
     * state machine only emits BeginFinalize on the Listening -> Finalizing edge,
     * and an in-flight [finalizeJob] is turned away here.
     */
    private fun beginFinalize(partial: String, utteranceIndex: Int) {
        if (finalizeJob?.isActive == true) {
            Log.w(TAG, "finalize already in flight for utterance $utteranceIndex")
            return
        }
        val samples = takeUtteranceAudio()
        // Counts only, never text (PLAN.md §3.4). This is the line that says
        // whether a session that heard something had anything to transcribe.
        // Counts only, never audio and never text (PLAN.md §3.4): how much was
        // captured, how loud it was, and whether there was an engine to decode
        // it. "It listened and nothing was typed" is otherwise indistinguishable
        // from a quiet room, which cost a whole debugging session to learn.
        // Off unless asked for: adb shell setprop log.tag.VBoardVoice DEBUG
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            Log.d(TAG, "finalize: samples=${samples.size} peak=$peak engine=${VoiceEngines.finalPass != null}")
        }
        host.showFinalizing()

        finalizeJob = scope.launch {
            // Latency: the accurate model takes about a second on the utterance
            // that just ended, and the user spends it looking at an empty field.
            // The live model's text is already good enough to read, so commit it
            // now and replace it when the accurate answer lands. What ends up in
            // the field is still the accurate model's text — this only decides
            // whether the wait happens with an empty field or a provisional one.
            var provisional: String? = null
            if (settings.provisionalCommit && partial.isNotBlank()) {
                val early = cleanTranscript(partial)
                if (early.command == UtteranceCommand.NONE && early.text.isNotBlank()) {
                    host.commitUtterance(utteranceIndex, early.text)
                    provisional = early.text
                }
            }
            val decoded = if (samples.isEmpty()) {
                null
            } else {
                VoiceEngines.beginUse()
                try {
                    withTimeoutOrNull(FINALIZE_WATCHDOG_MS) {
                        withContext(decodeDispatcher) {
                            runCatching { VoiceEngines.finalPass?.transcribe(samples) }
                                .onFailure { Log.e(TAG, "final ASR pass failed", it) }
                                .getOrNull()
                        }
                    }
                } finally {
                    VoiceEngines.endUse()
                }
            }
            // A blank final result is not a valid transcription of speech the user
            // watched the partial spell out: falling through with "" deleted the
            // sentence silently. Blank counts as failure, same as a timeout.
            val finalText = FinalTranscriptPolicy.choose(decoded, partial)

            val cleaned = cleanTranscript(finalText)
            if (Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "finalize: decoded=${decoded?.length ?: -1} committed=${cleaned.text.length}")
            // With a provisional commit already in the field, the machine must
            // not commit a second copy: it is told the utterance is finished
            // (blank), and the text in the field is corrected in place instead.
            val alreadyInField = provisional
            if (alreadyInField != null && cleaned.command != UtteranceCommand.NONE) {
                // The utterance turned out to be a command, so the provisional
                // text was never text: take it back before acting on it.
                host.replaceUtterance(utteranceIndex, "")
            }
            when (cleaned.command) {
                UtteranceCommand.SCRATCH_THAT -> {
                    dispatch(Event.FinalTranscript(""))
                    dispatch(Event.ScratchThat)
                    showListeningIfListening()
                }
                UtteranceCommand.STOP_LISTENING -> {
                    dispatch(Event.FinalTranscript(""))
                    dispatch(Event.StopRequested)
                }
                UtteranceCommand.NONE -> {
                    if (alreadyInField != null) {
                        if (cleaned.text != alreadyInField) {
                            host.replaceUtterance(utteranceIndex, cleaned.text)
                        }
                        // The machine's commit effect is what normally schedules
                        // refinement, and it is not running on this path.
                        if (settings.llmRefineEnabled && cleaned.text.isNotBlank()) {
                            refineAsync(cleaned.text, utteranceIndex)
                        }
                        dispatch(Event.FinalTranscript(""))
                    } else {
                        dispatch(Event.FinalTranscript(cleaned.text))
                    }
                    showListeningIfListening()
                }
            }
        }
    }

    private fun showListeningIfListening() {
        if (machine.state is DictationStateMachine.State.Listening) host.showListening()
    }

    /**
     * Rules cleanup for one utterance, with the expensive half off the main
     * thread.
     *
     * The 500-line tokenizer pipeline used to run on the main dispatcher at the
     * exact frame the commit animation had to draw. It is pure computation over
     * a string, so it moves to Default with no threading questions attached.
     *
     * [Host.precedingText] deliberately stays on the main thread. It is an IPC
     * into the host app, but a bounded one (a fixed, small character count), and
     * every InputConnection call site in this keyboard — like AOSP LatinIME — is
     * main-thread. Cross-thread InputConnection use is not a documented
     * guarantee, and trading a ~1ms bounded IPC for an untestable threading
     * change is the wrong side of that bargain.
     */
    private suspend fun cleanTranscript(raw: String): CleanupResult {
        // WaveKey (W4.2): shield before the cleaner, restore after.
        //
        // The tokenizer was built for prose and drops every symbol it does not
        // recognize, so a recognizer that writes "$5.99", "3.14" or an email
        // address — and modern ones do — would have it mangled on the way to the
        // input connection. ContentGuard swaps those spans for placeholders the
        // tokenizer treats as ordinary words.
        // W7.2: spoken formats become written ones first — "five dollars fifty"
        // has to be "$5.50" *before* the guard shields it, or the shield has
        // nothing to protect and the tokenizer eats the symbols that follow.
        // Raw mode is exempt: it is the verbatim escape hatch.
        val spoken = if (settings.rawTranscriptMode) raw else SpokenFormats.apply(raw)
        val shield = ContentGuard.shield(spoken)
        val precedingText = host.precedingText()
        val request = CleanupRequest(
            transcript = shield.masked,
            precedingText = precedingText,
            fieldKind = fieldKind,
            options = settings.cleanupOptions(),
            // Never staple a period onto a URL that happens to end the utterance.
            ensureTerminalPunctuation = settings.autoPunctuate &&
                fieldKind == FieldKind.TEXT &&
                !shield.endsWithShieldedSpan,
        )
        val cleaned = withContext(Dispatchers.Default) { app.cleaner.clean(request) }
        return cleaned.copy(text = shield.restore(cleaned.text))
    }

    // ------------------------------------------------------------ refinement

    private fun refineAsync(text: String, utteranceIndex: Int) {
        refineJob?.cancel()
        refineJob = scope.launch {
            val refiner = withContext(Dispatchers.IO) {
                runCatching { VoiceEngines.loadRefiner(service, app) }
                    .onFailure { Log.w(TAG, "refiner unavailable", it) }
                    .getOrNull()
            } ?: return@launch
            host.showRefining()
            VoiceEngines.beginUse()
            val startedAt = System.currentTimeMillis()
            val refined = try {
                refiner.refine(text)
            } finally {
                VoiceEngines.endUse()
            }
            recordRefinement(text, refined, System.currentTimeMillis() - startedAt)
            showListeningIfListening()
            if (refined != null && refined != text) {
                host.replaceUtterance(utteranceIndex, refined)
            }
        }
    }

    /**
     * Journals the pair when the user asked for it. The rejection reason lives
     * in the refiner process and does not cross the binder, so a refinement the
     * validator turned down is recorded as rejected with no reason attached.
     */
    private fun recordRefinement(spoken: String, refined: String?, elapsedMs: Long) {
        if (!settings.refinementJournalEnabled) return
        app.refinementJournal.record(
            RefinementJournal.Entry(
                atMillis = System.currentTimeMillis(),
                spoken = spoken,
                refined = refined,
                accepted = refined != null,
                reason = null,
                elapsedMs = elapsedMs,
            ),
        )
    }

    // ---------------------------------------------------------------- effects

    private fun dispatch(event: Event) {
        for (effect in machine.onEvent(event)) {
            execute(effect)
        }
    }

    private fun execute(effect: Effect) {
        when (effect) {
            Effect.ShowVoiceBar -> host.showListening()
            Effect.HideVoiceBar -> {
                stopAudio()
                // ~1.2GB of native memory should not stay pinned in the keyboard
                // process because someone dictated once this morning.
                VoiceEngines.scheduleIdleRelease()
                host.onSessionEnded()
            }
            Effect.StartAudio -> {
                host.showListening()
                beginListening()
            }
            Effect.StopAudio -> stopAudio()
            is Effect.UpdatePartial -> host.updatePartial(effect.text)
            is Effect.BeginFinalize -> beginFinalize(effect.partial, effect.utteranceIndex)
            is Effect.CommitUtterance -> {
                host.commitUtterance(effect.utteranceIndex, effect.text)
                if (effect.refine) refineAsync(effect.text, effect.utteranceIndex)
            }
            Effect.DeleteLastUtterance -> host.deleteLastUtterance()
            is Effect.NoteAudioOverrun -> {
                // Counts only: never audio, never transcript text. This is the
                // line that turns "the model is inaccurate" into a diagnosable
                // defect (VB-106).
                val ms = effect.droppedSamples * 1_000L / AudioCapture.SAMPLE_RATE
                Log.w(
                    TAG,
                    "streaming decoder fell behind: dropped ${effect.droppedSamples} samples " +
                        "(~${ms}ms), ${effect.sessionTotalSamples} this session; " +
                        "the final pass is unaffected",
                )
            }
            is Effect.SignalError -> showError(effect.kind)
        }
    }

    private fun showError(kind: ErrorKind) {
        val (message, action) = when (kind) {
            ErrorKind.MIC_PERMISSION_DENIED ->
                service.getString(R.string.voice_error_no_permission) to
                    VoiceErrorAction.OPEN_PERMISSION
            ErrorKind.MODEL_MISSING ->
                service.getString(R.string.voice_error_no_model) to
                    VoiceErrorAction.OPEN_DOWNLOAD
            ErrorKind.MODEL_CORRUPT ->
                service.getString(R.string.voice_error_model_corrupt) to
                    VoiceErrorAction.OPEN_DOWNLOAD
            ErrorKind.AUDIO_UNAVAILABLE ->
                service.getString(R.string.voice_error_mic_busy) to
                    VoiceErrorAction.DISMISS
            ErrorKind.INTERNAL ->
                service.getString(R.string.voice_error_generic) to
                    VoiceErrorAction.DISMISS
        }
        host.showError(message, action)
    }

    companion object {
        private const val TAG = "VBoardVoice"

        /**
         * Budget for the final pass.
         *
         * Honest limitation: the decode is a single blocking JNI call with no
         * suspension points, so [withTimeoutOrNull] cannot actually abandon it —
         * structured concurrency waits for the native call to return whatever the
         * timer says. What the dedicated [decodeDispatcher] does guarantee is that
         * a slow decode cannot overlap the next one and cannot starve
         * Dispatchers.Default. Genuinely bounding this needs cancellation support
         * inside the recognizer wrapper (a decode running on a thread we can
         * abandon, with the native handle owned by that thread).
         */
        private const val FINALIZE_WATCHDOG_MS = 5_000L

        private const val AUDIO_STALL_TIMEOUT_MS = 2_000L
        private const val AUDIO_JOIN_TIMEOUT_MS = 1_500L

        /** UI/overrun tick; matches the ~100ms capture cadence. */
        private const val MONITOR_TICK_MS = 100L

        /**
         * Level above which a chunk counts as speech for the silence timeout.
         * [rmsLevel] scales speech into roughly 0.16..1, and a quiet room sits
         * an order of magnitude below that, so this sits between the two.
         */
        private const val SPEECH_LEVEL = 0.08f

        /** Mic-health, call and silence checks run every 500ms. */
        private const val WATCHDOG_EVERY_N_TICKS = 5

        /**
         * How long the room has to stay quiet before the utterance is treated as
         * finished. Short enough that a sentence lands while the user is drawing
         * breath for the next one; long enough not to cut them off mid-thought.
         */
        private const val ENDPOINT_SILENCE_MS = 1_000L

        /** Serializes AudioRecord construction and release; see [AudioCapture]. */
        private val audioDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "vboard-audio") }
                .asCoroutineDispatcher()

        /** One decode at a time, and never on a dispatcher the UI shares. */
        private val decodeDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "vboard-asr-decode") }
                .asCoroutineDispatcher()

        /**
         * The streaming decoder's own thread. Separate from [decodeDispatcher]
         * on purpose: serializing the live stream behind a multi-second final
         * pass is precisely the stall that used to overrun the capture buffer.
         */
        private val streamDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "vboard-asr-stream") }
                .asCoroutineDispatcher()
    }
}

/**
 * Process-wide engine cache: models stay loaded across dictation sessions so
 * the second mic press starts in tens of milliseconds — but not forever, see
 * [scheduleIdleRelease].
 */
object VoiceEngines {

    private const val TAG = "VBoardEngines"

    /**
     * How long the engines stay resident after the keyboard goes away.
     *
     * Was 90s, which is shorter than the gap between two messages in the same
     * conversation, so almost every mic press paid the full multi-second load
     * again. The timer is only armed once the keyboard is hidden, so an open
     * keyboard now keeps the engines for as long as it is open.
     */
    private const val IDLE_RELEASE_MS = 600_000L

    enum class LoadResult {
        READY,

        /** No installed pack to load from — the user has not downloaded them. */
        MISSING,

        /**
         * Files are present but unusable: corrupt payload, native load failure,
         * or out of memory. Distinct from [MISSING] so the UI can offer a repair
         * instead of pointing at a screen that says the pack is installed.
         */
        BROKEN,
    }

    @Volatile var finalPass: FinalAsr? = null
        private set

    /**
     * The refiner is a *connection* now, not an engine: the model itself lives in
     * the `:llm` process (V2_PLAN Wave 0.5), so what this object holds is a
     * binder that costs nothing to keep and everything to forget at the wrong
     * moment. Releasing it is what lets that process — and the half-gigabyte
     * model in it — be reclaimed.
     */
    @Volatile private var refiner: LlmRefinerClient? = null

    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Guards [idleJob] only. Deliberately not the object monitor: the idle timer
     * is armed and disarmed from the main thread, and a model load can hold the
     * object monitor for seconds — sharing one lock would turn a mic press into
     * an ANR.
     */
    private val idleLock = Any()
    private var idleJob: Job? = null
    private var warmJob: Job? = null

    /**
     * Outstanding claims on the native handles (a live reader loop, a decode, a
     * refinement). Memory pressure can arrive at any moment, and freeing a
     * recognizer while one of those is running is a segfault rather than a lost
     * result, so a release is refused while anything holds a claim.
     */
    private val claims = AtomicInteger(0)

    val isLoaded: Boolean get() = finalPass != null

    fun beginUse() {
        claims.incrementAndGet()
    }

    fun endUse() {
        claims.decrementAndGet()
    }

    @Synchronized
    fun load(app: VoiceRuntime): LoadResult {
        if (isLoaded) return LoadResult.READY
        val parakeetPaths = app.modelStore.parakeetPaths(app.packInstaller)
            ?: return LoadResult.MISSING

        var finalAsr: FinalAsr? = null
        return try {
            finalAsr = FinalAsr(parakeetPaths)
            finalPass = finalAsr
            LoadResult.READY
        } catch (e: Throwable) {
            runCatching { finalAsr?.release() }
            Log.e(TAG, "ASR engine load failed", e)
            LoadResult.BROKEN
        }
    }

    @Synchronized
    fun loadRefiner(context: Context, app: VoiceRuntime): RemoteRefiner? {
        refiner?.let { return it }
        return refinerClientOrNull(context, app)?.also { refiner = it }
    }

    @Synchronized
    fun releaseRefiner() {
        if (claims.get() > 0) {
            // Refusing is not the same as never releasing: re-arm, or the claim
            // that was in flight at the deadline keeps the model resident for
            // the life of the process.
            Log.w(TAG, "refiner release refused: engines in use")
            scheduleIdleRelease()
            return
        }
        refiner?.let { runCatching { it.disconnect() } }
        refiner = null
    }

    @Synchronized
    fun releaseAll() {
        if (claims.get() > 0) {
            Log.w(TAG, "engine release refused: engines in use")
            scheduleIdleRelease()
            return
        }
        runCatching { finalPass?.release() }
        runCatching { refiner?.disconnect() }
        finalPass = null
        refiner = null
    }

    /**
     * Loads the engines before the user asks for them, off the main thread.
     *
     * Called when the keyboard becomes visible: the load is the whole of the
     * delay between the mic press and the first word being heard, and doing it
     * here means the press usually finds them already resident. Idempotent, and
     * safe to race with a session — [load] is synchronized and returns early
     * when the engines are up.
     */
    fun warmUp(app: VoiceRuntime) {
        cancelIdleRelease()
        if (isLoaded) return
        synchronized(idleLock) {
            if (warmJob?.isActive == true) return
            warmJob = idleScope.launch(Dispatchers.IO) {
                val result = runCatching { load(app) }.getOrNull()
                if (result != LoadResult.READY) Log.i(TAG, "warm-up did not load engines: $result")
            }
        }
    }

    /** Cancels a pending idle release; call when a session is starting. */
    fun cancelIdleRelease() {
        synchronized(idleLock) {
            idleJob?.cancel()
            idleJob = null
        }
    }

    /**
     * Frees the engines once dictation has been idle for a while. Without this the
     * keyboard process holds roughly 1.2GB of native memory from the first mic
     * press until the process dies, which on a mid-range phone means it dies.
     */
    fun scheduleIdleRelease() {
        synchronized(idleLock) {
            idleJob?.cancel()
            idleJob = idleScope.launch {
                delay(IDLE_RELEASE_MS)
                releaseAll()
            }
        }
    }
}
