// SPDX-License-Identifier: GPL-3.0-only
//
// WaveKey. New file: the opt-in Google dictation backend.
//
// This is the one path in the fork where speech leaves the device, so it is
// kept separate from the on-device session rather than hidden behind a flag
// inside it: nothing here runs unless PrivacyBreakingSettings.PREF_GOOGLE_VOICE
// is on. The audio never passes through this process — SpeechRecognizer records
// in Google's own process and hands back text.
package helium314.keyboard.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.vboard.core.model.SystemRecognizer
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log

class GoogleVoiceSession(
    private val context: Context,
    private val host: Host,
) {
    /** What the controller has to be able to do for a session to be usable. */
    interface Host {
        fun onGooglePartial(text: String)
        fun onGoogleFinal(text: String)
        fun onGoogleAmplitude(rms: Float)
        fun onGoogleListening()
        fun onGooglePreparing()
        fun onGoogleFinalizing()
        fun onGoogleError(message: String, kind: ErrorKind)
        fun onGoogleEnded()
    }

    /**
     * WaveKey: which recovery an error deserves. The recognizer reports
     * a flat int and the host has no way to tell "grant the mic" from "this
     * engine will never work here" from "say it again" without it.
     */
    enum class ErrorKind { PERMISSION, TRANSIENT, ENGINE_UNUSABLE }

    private var recognizer: SpeechRecognizer? = null

    /** True between start() and the session ending, whichever way it ends. */
    var isRunning = false
        private set

    /** Set while stopping, so a late error callback is not shown to the user. */
    private var stopping = false

    fun isAvailable() = onDeviceAvailable() || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * True when the platform can recognize without sending audio anywhere. Added
     * in API 31 and backed by the system's own offline packs, which the user
     * installs from system settings rather than from us.
     */
    fun onDeviceAvailable(): Boolean = Companion.onDeviceAvailable(context)

    /**
     * WaveKey: [onDeviceOnly] binds the session to the platform's
     * offline recognizer — it errors out rather than reaching for the network
     * one. Asking [onDeviceAvailable] before calling would not do it: the
     * decision is made here, so the guarantee has to be made here too.
     */
    fun start(onDeviceOnly: Boolean = false) {
        if (isRunning) return
        val onDevice = onDeviceAvailable()
        val usable = if (onDeviceOnly) onDevice else isAvailable()
        if (!usable) {
            host.onGoogleError(
                context.getString(R.string.voice_google_unavailable), ErrorKind.ENGINE_UNUSABLE
            )
            host.onGoogleEnded()
            return
        }
        isRunning = true
        stopping = false
        host.onGooglePreparing()
        // On-device first: it is the same recognizer without the round trip, so
        // preferring it is both faster and the difference between audio that
        // leaves the phone and audio that does not.
        val recognizer = (
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            ).also { this.recognizer = it }
        Log.i(TAG, if (onDevice) "system recognizer: on-device" else "system recognizer: network")
        recognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                Log.w(TAG, "Google recognizer refused to start", it)
                host.onGoogleError(
                    context.getString(R.string.voice_google_unavailable), ErrorKind.ENGINE_UNUSABLE
                )
                release()
            }
    }

    /** End the utterance and take whatever Google has: the "done" control. */
    fun stopAndFinalize() {
        if (!isRunning) return
        stopping = true
        host.onGoogleFinalizing()
        runCatching { recognizer?.stopListening() }
    }

    /** Drop the session and whatever it heard. */
    fun cancel() {
        if (!isRunning) return
        stopping = true
        runCatching { recognizer?.cancel() }
        release()
    }

    private fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (isRunning) {
            isRunning = false
            host.onGoogleEnded()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = host.onGoogleListening()
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            // SpeechRecognizer reports roughly -2..10 dB; the strip wants 0..1.
            host.onGoogleAmplitude(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = host.onGoogleFinalizing()

        override fun onError(error: Int) {
            if (!stopping || error != SpeechRecognizer.ERROR_NO_MATCH) {
                host.onGoogleError(context.getString(messageFor(error)), kindFor(error))
            }
            release()
        }

        override fun onResults(results: Bundle?) {
            firstResult(results)?.let { host.onGoogleFinal(it) }
            release()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { host.onGooglePartial(it) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String? = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }

    private fun messageFor(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> R.string.voice_google_error_audio
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_google_error_permission
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_google_error_network
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_google_error_no_match
        else -> R.string.voice_google_error_generic
    }

    /**
     * WaveKey: classified by exclusion. Only constants that exist on
     * API 21 are named, so nothing here needs a version guard; everything
     * newer falls through to ENGINE_UNUSABLE — including
     * ERROR_LANGUAGE_UNAVAILABLE, which is exactly what a device that has the
     * on-device recognizer but no language pack installed returns, and the one
     * kind that offers the user a way out. ERROR_SERVER and ERROR_CLIENT land
     * there too: neither clears by saying it again.
     */
    private fun kindFor(error: Int) = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorKind.PERMISSION
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ErrorKind.TRANSIENT
        else -> ErrorKind.ENGINE_UNUSABLE
    }

    companion object {
        private const val TAG = "SVBGoogleVoice"

        /**
         * True when the platform can recognize without sending audio anywhere.
         * Static so settings can ask without opening a session.
         *
         * `isOnDeviceRecognitionAvailable` answers from a device configuration value and
         * keeps answering yes when the service that configuration names has been disabled or
         * uninstalled — which is how a phone with no working recognizer at all was told that
         * nothing it says is sent to Google, and then failed to bind at the first mic press.
         * So the framework's answer is kept, and a bindable service is required with it.
         */
        fun onDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) &&
                recognitionServices(context).any { it.preinstalled }

        /**
         * WaveKey: which recognizer this device actually has, probed rather than inferred
         * from an API level.
         *
         * The distinction between the two available cases is the whole privacy story — one
         * keeps audio on the phone and the other does not — so it is answered in one place
         * and handed to `:core` as a fact. The third case is real too: a device can have no
         * recognizer at all, and a claim of privacy resting on a service that cannot be
         * bound is the same lie as a claim of privacy resting on nothing.
         */
        fun systemRecognizer(context: Context): SystemRecognizer = when {
            recognitionServices(context).isEmpty() -> SystemRecognizer.NONE
            onDeviceAvailable(context) -> SystemRecognizer.ON_DEVICE
            SpeechRecognizer.isRecognitionAvailable(context) -> SystemRecognizer.NETWORK_ONLY
            else -> SystemRecognizer.NONE
        }

        /** A `RecognitionService` this device can actually bind to. */
        private data class Recognizer(val packageName: String, val preinstalled: Boolean)

        /**
         * The recognition services the package manager will resolve, which is the only
         * evidence that any of this can run. Disabled and uninstalled components do not
         * resolve, which is exactly the case the framework's availability flags miss.
         *
         * The on-device recognizer is part of the system image on every device that has one,
         * so [Recognizer.preinstalled] is what separates "this phone recognizes offline" from
         * "some app I installed can recognize".
         */
        private fun recognitionServices(context: Context): List<Recognizer> = runCatching {
            context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.applicationInfo }
                .map {
                    Recognizer(
                        packageName = it.packageName,
                        preinstalled = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
        }.getOrElse {
            // A package manager query that throws is not evidence of absence, so the
            // framework's own answer stands rather than the mode chooser going blank.
            Log.w(TAG, "could not enumerate recognition services", it)
            listOf(Recognizer(context.packageName, preinstalled = true))
        }
    }
}
