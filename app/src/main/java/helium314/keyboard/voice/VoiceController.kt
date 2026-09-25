// SPDX-License-Identifier: GPL-3.0-only
//
// WaveKey. New file: everything that binds the IME-agnostic :voice
// module to this particular keyboard lives here, so LatinIME's own edits stay
// down to lifecycle calls (PLAN.md §3.2).
package helium314.keyboard.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.widget.Toast
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.vboard.app.settings.SettingsRepository
import com.vboard.app.models.PrivateModeDownloads
import com.vboard.app.voice.VoiceEngines
import com.vboard.app.voice.VoiceErrorAction
import com.vboard.app.voice.VoiceRuntime
import com.vboard.app.voice.VoiceSessionController
import com.vboard.core.model.PrivateMode
import com.vboard.core.session.VoiceMetrics
import com.vboard.core.text.CommitPlanner
import com.vboard.core.text.FieldKind
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.screens.PrivacyBreakingSettings

/**
 * Owns the dictation session for one IME instance.
 *
 * The session controller is deliberately ignorant of views and of HeliBoard; it
 * calls back into [VoiceSessionController.Host], and this class is the only
 * place that turns those calls into input-connection edits and strip state.
 */
class VoiceController(
    private val ime: LatinIME,
    private val runtime: VoiceRuntime,
) : VoiceSessionController.Host, VoiceStripView.Listener, GoogleVoiceSession.Host {

    private val session = VoiceSessionController(ime, runtime, this)

    /**
     * The opt-in Google backend. Created lazily so a user who never turns the
     * setting on never has a SpeechRecognizer in their keyboard process.
     */
    private val googleSession by lazy { GoogleVoiceSession(ime, this) }

    /** Which backend owns the running session; decided at start() and kept. */
    private var googleForSession = false

    /**
     * WaveKey: true when this session is on the platform recognizer
     * because Parakeet is not downloaded yet, rather than because the user
     * asked for it. Only that case gets the download-shaped error recovery.
     */
    private var fallbackForSession = false

    /**
     * An error the strip is still showing after its session ended. The Google
     * backend releases itself the moment it errors, and the local path leaves
     * the bar up in that case (DictationStateMachine.State.Error emits no
     * HideVoiceBar); without this the message and its action key would be
     * cleared in the same frame they were set.
     */
    private var errorOnStrip = false

    private fun googleBackendEnabled() =
        PrivacyBreakingSettings.googleVoiceEnabled(ime.prefs())

    /** Set by the IME when the strip is inflated; null before the view exists. */
    var strip: VoiceStripView? = null
        set(value) {
            field = value
            value?.listener = this
        }

    /** What the IME shows instead of the voice strip when dictation ends. */
    var onSessionUiEnded: (() -> Unit)? = null
    var onSessionUiStarted: (() -> Unit)? = null

    @Volatile
    var isActive = false
        private set

    private var fieldKind: FieldKind = FieldKind.TEXT

    /** The app being dictated into, captured when the session starts (W4.3). */
    private var sessionPackage: String? = null

    /** True while a press-and-hold owns the session; release ends it (W6.3). */
    private var holdScoped = false

    /** Session-scoped raw dictation: no cleanup, no refinement (W6.4). */
    private var rawForSession = false

    /** True between the minimize key and the keyboard coming back. */
    private var minimizedForSession = false

    /** Held only while a running session has no window to keep awake. */
    private var screenLock: PowerManager.WakeLock? = null

    /**
     * W7.3: opt-in, content-free measurement. Held by the runtime — there is no
     * endpoint to send it to and no file to write it into; the user's own
     * settings screen is the only reader (PLAN.md R24).
     */
    private val metrics get() = runtime.metrics

    /** When the session that is currently committing started, for the mean. */
    private var sessionStartedAt = 0L

    /**
     * The last dictated commit, awaiting its verdict: it counts as send-ready
     * only once the user has moved on without editing it. Held as a duration,
     * not as text.
     */
    private var pendingElapsedMs: Long? = null

    /** Aggregates for the settings screen; empty when telemetry is off. */
    fun metricsSnapshot(): VoiceMetrics.Snapshot = metrics.snapshot()

    /**
     * An utterance whose input connection died before the final pass returned.
     * In memory only, replayed into the next editor of the same app and nowhere
     * else — see [PendingDictation].
     */
    private var pending: PendingDictation? = null

    /**
     * Committed utterances of this session, by index, so a later refinement can
     * replace exactly what it refined rather than guessing at the cursor.
     */
    private val commits = HashMap<Int, String>()

    // --------------------------------------------------------------- IME hooks

    fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean = false) {
        // Moving to another field settles whatever was awaiting a verdict — but
        // a restart of the same field is not a move, and one of the things that
        // restarts input is this class rewriting the field itself (refinement,
        // or a fix over dictated text). Settling there records "the user did not
        // edit it" about text they have not yet had the chance to edit.
        if (!restarting) settleTelemetry()
        // The keyboard is back, so the window can keep the screen awake again.
        minimizedForSession = false
        releaseScreenLock()
        fieldKind = fieldKindOf(editorInfo)
        // An error bar belongs to the field that produced it, not to the next
        // one: its session is already over, so nothing else would clear it.
        if (!isActive && errorOnStrip) endSessionUi()
        // A field that must never be dictated into ends any session that was
        // running when focus moved into it.
        if (!fieldKind.allowsVoice && isActive) cancel()
        replayPending(editorInfo?.packageName)
    }

    /**
     * W4.3 draft rescue: hand a held utterance to the editor that just opened,
     * if it is plausibly the same piece of work the user was speaking into.
     *
     * The rule is deliberately narrow — same app, within the TTL, field still
     * accepts voice — because replaying speech into the *next* app's field would
     * be a worse bug than the data loss it fixes.
     */
    private fun replayPending(editorPackage: String?) {
        val held = pending ?: return
        val verdict = held.verdictFor(editorPackage, fieldKind.allowsVoice, SystemClock.elapsedRealtime())
        if (verdict != ReplayVerdict.REPLAY) {
            // Never logs the text, its length, or anything derived from it.
            Log.i(TAG, "held utterance not replayed: $verdict")
            if (verdict == ReplayVerdict.EXPIRED) pending = null
            return
        }
        val ic = ime.currentInputConnection ?: return
        pending = null
        ic.commitText(CommitPlanner.joinForInsertion(precedingText(), held.text), 1)
        Log.i(TAG, "held utterance replayed into the reopened editor")
    }

    /** The editor is going away: finalize rather than discard what was said. */
    fun onFinishInputView() {
        // The minimize control hides the keyboard on purpose and the session is
        // meant to outlive it, so the editor going away is not the end of the
        // utterance here — it is the start of the eyes-free part of it.
        if (minimizedForSession) {
            acquireScreenLock()
            return
        }
        if (isActive) session.finishSession()
    }

    fun onDestroy() {
        keepScreenOn(false)
        releaseScreenLock()
        session.destroy()
        strip = null
    }

    /**
     * W4.4: a touch outside the keyboard ends the utterance rather than leaving
     * the mic open. The user has moved on — to another field, a send button, the
     * app's own UI — and anything already said should land, not keep recording.
     */
    fun onTouchOutsideKeyboard() {
        if (isActive) session.stopAndFinalize()
    }

    /** The mic key, and the VOICE toolbar key, both land here. */
    /**
     * Starts loading the models without starting a session.
     *
     * Called on the mic's touch-down and when the keyboard opens with models
     * installed: model load is seconds on a cold press, and doing it during the
     * gesture is time the user was going to spend anyway.
     */
    fun warmUp() {
        if (!fieldKind.allowsVoice) return
        // The Google backend has no local engines to warm.
        if (googleBackendEnabled()) return
        VoiceEngines.warmUp(runtime)
    }

    /** The keyboard is visible: do not release engines out from under it. */
    fun onKeyboardShown() {
        VoiceEngines.cancelIdleRelease()
    }

    /** The keyboard is gone: the engines may be reclaimed after the idle delay. */
    fun onKeyboardHidden() {
        // Not while an utterance is still being heard: a minimized session is
        // running on those engines.
        if (isActive) return
        VoiceEngines.scheduleIdleRelease()
    }

    fun toggle() {
        if (isActive) stopAndFinalize() else start()
    }

    /**
     * Flips which recognizer the next session uses: the system one, or Parakeet
     * on device. It writes the same preference the settings screen does, so the
     * toolbar key is a shortcut to that switch rather than a second setting.
     *
     * A session already running keeps the backend it started with — swapping
     * engines mid-utterance would lose what has been said so far — so the flip
     * takes effect on the next press of the mic.
     */
    fun toggleAsrEngine() {
        val prefs = ime.prefs()
        val toSystem = !PrivacyBreakingSettings.googleVoiceEnabled(prefs)
        prefs.edit().putBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE, toSystem).apply()
        val message = ime.getString(
            when {
                toSystem -> R.string.asr_engine_system
                // Saying "switched to the on-device model" with no model
                // downloaded is a lie the user finds out about at the next
                // mic press; name what will actually run.
                fallbackWouldRun() -> R.string.asr_engine_ondevice_fallback
                else -> R.string.asr_engine_ondevice
            }
        )
        Toast.makeText(ime, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * WaveKey: true when a mic press with the local engine selected
     * would land on the platform's on-device recognizer instead of Parakeet.
     *
     * Ordered so a device with Parakeet installed never touches the recognizer
     * lookup, and a device without the platform recognizer — pre-API-31, or a
     * ROM with no speech service — answers false and keeps the download prompt.
     */
    private fun fallbackWouldRun() =
        !runtime.modelStore.dictationReady(runtime.packInstaller) && googleSession.onDeviceAvailable()

    /**
     * WaveKey: true when this press would send audio to the device's speech
     * service and the user has not been told that yet.
     *
     * Ordered cheapest first: the preference, then the recorded answer, and only
     * then the probe and the disk read. On every device with an offline
     * recognizer — and in private mode — this returns on the first line.
     */
    private fun networkDisclosureOwed(): Boolean {
        val prefs = ime.prefs()
        if (!PrivacyBreakingSettings.googleVoiceEnabled(prefs)) return false
        if (PrivateModePrefs.networkConsentRecorded(prefs)) return false
        val backends = PrivateMode.backends(
            capabilities = PrivateModeDownloads.capabilities(
                ime,
                GoogleVoiceSession.systemRecognizer(ime),
            ),
            googleVoicePreferred = true,
            localSpeechInstalled = runtime.modelStore.dictationReady(runtime.packInstaller),
            localRefinerInstalled = runtime.modelStore.refinerModelPath(runtime.packInstaller) != null,
        )
        return PrivateMode.networkDisclosureOwed(backends, consentRecorded = false)
    }

    /** End the utterance on whichever backend is running it. */
    private fun stopAndFinalize() {
        if (googleForSession) googleSession.stopAndFinalize() else session.stopAndFinalize()
    }

    /**
     * W6.3: press-and-hold started. Release ends the utterance and sends it, so
     * the session is marked as hold-scoped; a tap-started session is not.
     *
     * W6.4: [raw] is the deeper hold — this session bypasses cleanup and
     * refinement entirely and types what was heard, verbatim. It is scoped to
     * this session only; the setting is not touched.
     */
    fun startHold(raw: Boolean) {
        if (raw) {
            // The hold escalated mid-session: keep listening, drop the cleanup.
            rawForSession = true
            if (isActive) return
        }
        holdScoped = true
        // W6.5: the finger is the endpoint while it is down.
        session.setEndpointingEnabled(false)
        start()
    }

    /** W6.3: the finger lifted — finalize and send what was said. */
    fun endHold() {
        if (!holdScoped) return
        holdScoped = false
        session.setEndpointingEnabled(true)
        if (isActive) stopAndFinalize()
    }

    fun start() {
        if (!fieldKind.allowsVoice) return
        // WaveKey: on a device whose only recognizer is the network one, the
        // user is told that audio leaves the phone before any is captured. The
        // disclosure owns the session: nothing starts until it is answered, and
        // declining it starts nothing at all.
        if (networkDisclosureOwed()) {
            NetworkVoiceConsentActivity.launch(ime)
            return
        }
        PrivateModePrefs.recordVoiceSession(ime.prefs())
        if (!holdScoped) session.setEndpointingEnabled(true)
        isActive = true
        sessionPackage = ime.currentInputEditorInfo?.packageName
        settleTelemetry()
        sessionStartedAt = SystemClock.elapsedRealtime()
        commits.clear()
        keepScreenOn(true)
        errorOnStrip = false
        strip?.reset()
        onSessionUiStarted?.invoke()
        strip?.announceSessionStarted()
        googleForSession = googleBackendEnabled()
        if (googleForSession) {
            googleSession.start()
            return
        }
        // WaveKey: a fresh install has no Parakeet and a 482 MB wait
        // before the mic key does anything. Where the platform has an offline
        // recognizer of its own, borrow it until ours is downloaded. Bound to
        // the on-device one — the fallback is automatic, so it must never be
        // the thing that starts sending audio off the device.
        if (fallbackWouldRun()) {
            fallbackForSession = true
            // Reuses the Google backend's stop/cancel/end routing wholesale.
            googleForSession = true
            if (!fallbackNoticeShown) {
                fallbackNoticeShown = true
                Toast.makeText(ime, R.string.voice_fallback_system_notice, Toast.LENGTH_LONG).show()
            }
            googleSession.start(onDeviceOnly = true)
            return
        }
        val settings = runtime.settings.snapshot().let {
            // W6.4: a raw hold overrides the settings for this session only.
            if (rawForSession) it.copy(rawTranscriptMode = true, llmRefineEnabled = false) else it
        }
        session.startSession(fieldKind, settings)
    }

    /**
     * Holds the display awake for the length of a dictation session, so the
     * screen timeout — and the lock that follows it — cannot cut a recording
     * short mid-sentence.
     *
     * The flag lives on the IME window only: no system setting is written, so
     * the user's own timeout applies again the moment the session ends, whether
     * it ended by send, cancel, error, or the IME being torn down.
     */
    private fun keepScreenOn(on: Boolean) {
        // A hidden window keeps no screen awake, so a minimized session holds a
        // wake lock instead; the flag is for the ordinary, visible case.
        val window = ime.window?.window ?: return
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Keeps the display awake for a session the user minimized the keyboard on.
     * The window flag cannot do it — the window is gone — and this is the only
     * window the IME has.
     *
     * The lock carries its own timeout so a session that somehow never reports
     * its end cannot hold the screen on indefinitely; dictation that runs past
     * it is longer than any utterance this keyboard is built for.
     */
    @Suppress("DEPRECATION") // The only way to keep the screen on with no window.
    private fun acquireScreenLock() {
        if (screenLock?.isHeld == true) return
        // getSystemService(Class) is API 23; this keyboard still runs on 21.
        val power = ime.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = screenLock ?: power.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK, SCREEN_LOCK_TAG
        ).also { it.setReferenceCounted(false); screenLock = it }
        runCatching { lock.acquire(SCREEN_LOCK_TIMEOUT_MS) }
            .onFailure { Log.w(TAG, "could not hold the screen awake", it) }
    }

    private fun releaseScreenLock() {
        val lock = screenLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    fun cancel() {
        if (!isActive) {
            // The bar an error left behind outlives its session, so dismissing
            // it is the one thing cancel still has to do once the session is
            // over — the back arrow is what the user reaches for either way.
            if (errorOnStrip) endSessionUi()
            return
        }
        if (googleForSession) googleSession.cancel() else session.cancelSession()
    }

    // ------------------------------------------------- VoiceStripView.Listener

    override fun onVoiceCancel() = cancel()

    override fun onVoiceDone() {
        if (isActive) stopAndFinalize()
    }

    override fun onVoiceMinimizeKeyboard() {
        // The session keeps running; only the keyboard window goes away. That is
        // the point of the control — dictating into a field you need to see.
        // The flag has to be set first: hiding the window synchronously calls
        // back into onFinishInputView, which would otherwise finalize.
        minimizedForSession = isActive
        ime.requestHideSelf(0)
    }

    override fun onVoiceErrorAction(action: VoiceErrorAction) {
        when (action) {
            VoiceErrorAction.OPEN_PERMISSION -> openAppSettings()
            VoiceErrorAction.OPEN_DOWNLOAD -> openVoiceSettings()
            VoiceErrorAction.DISMISS -> cancel()
        }
    }

    // ------------------------------------------ VoiceSessionController.Host

    override fun precedingText(): String =
        ime.currentInputConnection?.getTextBeforeCursor(PRECEDING_CHARS, 0)?.toString() ?: ""

    override fun fieldKind(): FieldKind = fieldKind

    override fun updatePartial(text: String) {
        strip?.showPartial(text)
    }

    override fun commitUtterance(index: Int, text: String) {
        // The field may have changed under an asynchronous final pass — an app
        // toggling password visibility mid-dictation does exactly that.
        if (!fieldKind.allowsVoice) return
        val ic = ime.currentInputConnection ?: run {
            // W4.3: the editor went away before the final pass returned. The
            // speech is held rather than dropped; the next editor of the same
            // app gets it.
            pending = PendingDictation.hold(
                pending, text, sessionPackage, SystemClock.elapsedRealtime(),
            )
            Log.w(TAG, "no input connection; dictated utterance $index held for replay")
            return
        }
        val joined = CommitPlanner.joinForInsertion(precedingText(), text)
        ic.commitText(joined, 1)
        commits[index] = joined
        ime.onDictationCommitted()
        // W7.3: the verdict is not known yet — the user may still edit this.
        settleTelemetry()
        pendingElapsedMs = SystemClock.elapsedRealtime() - sessionStartedAt
    }

    /**
     * W7.3: the user typed or deleted after a dictated commit, so that utterance
     * was not send-ready. Only the verdict and the duration are recorded.
     */
    fun onUserEditedDictation() {
        val elapsed = pendingElapsedMs ?: return
        pendingElapsedMs = null
        if (runtime.settings.snapshot().telemetryEnabled) {
            metrics.record(edited = true, elapsedMs = elapsed)
        }
    }

    /** The pending utterance survived unedited: count it and forget it. */
    private fun settleTelemetry() {
        val elapsed = pendingElapsedMs ?: return
        pendingElapsedMs = null
        if (runtime.settings.snapshot().telemetryEnabled) {
            metrics.record(edited = false, elapsedMs = elapsed)
        }
    }

    override fun replaceUtterance(index: Int, newText: String) {
        val previous = commits[index] ?: return
        val ic = ime.currentInputConnection ?: return
        val joined = CommitPlanner.joinForInsertion(
            ic.getTextBeforeCursor(PRECEDING_CHARS + previous.length, 0)
                ?.toString()?.dropLast(previous.length) ?: "",
            newText,
        )
        ic.beginBatchEdit()
        ic.deleteSurroundingText(previous.length, 0)
        ic.commitText(joined, 1)
        ic.endBatchEdit()
        commits[index] = joined
    }

    override fun deleteLastUtterance() {
        val index = commits.keys.maxOrNull() ?: return
        val text = commits.remove(index) ?: return
        ime.currentInputConnection?.deleteSurroundingText(text.length, 0)
    }

    override fun onSessionEnded() {
        isActive = false
        googleForSession = false
        fallbackForSession = false
        holdScoped = false
        rawForSession = false
        minimizedForSession = false
        keepScreenOn(false)
        releaseScreenLock()
        // An error the user has not answered yet keeps the bar: it is carrying
        // the only control that recovers from it.
        if (errorOnStrip) return
        endSessionUi()
    }

    private fun endSessionUi() {
        errorOnStrip = false
        strip?.announceSessionEnded()
        strip?.reset()
        onSessionUiEnded?.invoke()
    }

    override fun showError(message: String, action: VoiceErrorAction) {
        strip?.showError(message, action)
    }

    override fun showPreparing() {
        strip?.showPreparing()
    }

    override fun showListening() {
        strip?.showListening()
    }

    override fun showFinalizing() {
        strip?.showFinalizing()
    }

    override fun showRefining() {
        strip?.showRefining()
    }

    override fun onAmplitude(rms: Float) {
        strip?.onAmplitude(rms)
    }

    // ------------------------------------------- GoogleVoiceSession.Host

    // The Google backend returns finished text, so it joins the same commit and
    // strip paths as the on-device one and skips only the parts that describe
    // the local pipeline: no cleanup settings, no refinement, no utterance
    // indexing beyond the single result Google gives back.

    override fun onGooglePartial(text: String) = updatePartial(text)

    override fun onGoogleFinal(text: String) = commitUtterance(0, text)

    override fun onGoogleAmplitude(rms: Float) = onAmplitude(rms)

    override fun onGoogleListening() = showListening()

    override fun onGooglePreparing() = showPreparing()

    override fun onGoogleFinalizing() = showFinalizing()

    /**
     * WaveKey: the recognizer's own message, routed to the recovery it
     * actually needs. A missing microphone permission is the likeliest error on
     * the very first press — this path has no permission gate of its own, it
     * finds out from the recognizer — and answering that with "download 482 MB"
     * would be a dead end.
     */
    override fun onGoogleError(message: String, kind: GoogleVoiceSession.ErrorKind) {
        errorOnStrip = true
        when (kind) {
            GoogleVoiceSession.ErrorKind.PERMISSION ->
                showError(message, VoiceErrorAction.OPEN_PERMISSION)
            GoogleVoiceSession.ErrorKind.ENGINE_UNUSABLE ->
                // Only the automatic fallback has somewhere better to send the
                // user: on the opt-in path the model is not what failed.
                if (fallbackForSession) {
                    showError(
                        ime.getString(R.string.voice_fallback_failed),
                        VoiceErrorAction.OPEN_DOWNLOAD,
                    )
                } else {
                    showError(message, VoiceErrorAction.DISMISS)
                }
            GoogleVoiceSession.ErrorKind.TRANSIENT ->
                showError(message, VoiceErrorAction.DISMISS)
        }
    }

    override fun onGoogleEnded() = onSessionEnded()

    // ------------------------------------------------------------------ misc

    private fun openAppSettings() {
        // WaveKey: the system permission dialog, not the App info screen. The
        // activity is invisible and finishes with the answer; App info is its
        // own fallback for a permission that has been denied for good.
        val intent = Intent(ime, MicPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ime.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not ask for the microphone", it) }
        cancel()
    }

    private fun openVoiceSettings() {
        val intent = ime.packageManager.getLaunchIntentForPackage(ime.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) runCatching { ime.startActivity(intent) }
        cancel()
    }

    private fun fieldKindOf(editorInfo: EditorInfo?): FieldKind {
        val inputType = editorInfo?.inputType ?: return FieldKind.TEXT
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME ->
                FieldKind.NUMBER
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> FieldKind.PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                -> FieldKind.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> FieldKind.TEXT
                else -> if (editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH)
                    FieldKind.SEARCH else FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
    }

    companion object {
        private const val TAG = "SVBVoice"
        /** Enough context for spacing and capitalization decisions, no more. */
        private const val PRECEDING_CHARS = 16
        private const val SCREEN_LOCK_TAG = "WaveKey:dictation"
        /** Longer than any dictation, short enough to bound a leak. */
        private const val SCREEN_LOCK_TIMEOUT_MS = 10L * 60L * 1000L

        /**
         * WaveKey: the "we borrowed your phone's recognizer" toast is
         * shown once per keyboard process, not once per session — it explains a
         * state, and a state does not need re-explaining every time the mic
         * opens. Deliberately not a preference: nothing to migrate, and a fresh
         * process is a fair time to say it again.
         */
        private var fallbackNoticeShown = false
    }
}
