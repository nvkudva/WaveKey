package com.vboard.core.model

/**
 * Private mode: the one decision that moves dictation and text cleanup onto WaveKey's own
 * models, and the pure logic behind it.
 *
 * The product default is the platform's speech recognizer plus the device's own text AI, so
 * a fresh install works with nothing downloaded. Private mode is the opposite end state, and
 * it costs a bundle download. Everything a screen has to say about it — which packs the
 * bundle contains on this device, how far along it is, which backend is actually running,
 * whether an upgrading install may adopt the new default — is decided here, in one pure
 * place, because the alternative is a `when` in a Compose callback that the tests cannot
 * reach.
 *
 * Nothing in this file knows about Android, WorkManager or SharedPreferences. The Android
 * layer reports facts (pack states, scheduled work, free space, probe results) and applies
 * decisions; it never re-derives one.
 */

/** Why private mode cannot be offered at all on this device. */
enum class PrivateModeBlocker {
    /** No on-device recognizer covers the language the user is typing in. */
    LANGUAGE,

    /** 32-bit install: the refiner runtime has no build for it. */
    ABI,

    /** Not enough free space for even the smallest thing the bundle still needs. */
    STORAGE,
}

/**
 * The packs "private mode" means, on this device, in this language.
 *
 * Derived, never hardcoded: a 32-bit device's bundle is the recognizer alone and every figure
 * that follows shrinks with it, and a language with no on-device recognizer cannot offer
 * private dictation rather than quietly downloading an English model for French speech.
 */
data class PrivateModeBundle(
    val speechPacks: List<ModelPack>,
    val refinerPack: ModelPack?,
    /** Set when the bundle is empty, naming which device fact emptied it. */
    val blocker: PrivateModeBlocker?,
) {
    val packs: List<ModelPack> get() = speechPacks + listOfNotNull(refinerPack)

    val isEmpty: Boolean get() = packs.isEmpty()

    fun installedPacks(installedPackIds: Set<String>): List<ModelPack> =
        packs.filter { it.id in installedPackIds }

    fun missingPacks(installedPackIds: Set<String>): List<ModelPack> =
        packs.filter { it.id !in installedPackIds }

    /** What pressing the button costs right now: the sum of what is still missing. */
    fun downloadBytes(installedPackIds: Set<String>): Long =
        missingPacks(installedPackIds).sumOf { it.totalBytes }

    /**
     * Peak disk the remaining installs need. Higher than [downloadBytes] because an archive
     * coexists with its extracted contents; this is the figure a storage pre-check uses, and
     * the one the copy quotes second.
     */
    fun storageBytes(installedPackIds: Set<String>): Long =
        missingPacks(installedPackIds).sumOf { it.installFootprintBytes }

    /** Bytes the bundle amounts to in total, installed or not — the denominator of progress. */
    val totalBytes: Long get() = packs.sumOf { it.totalBytes }

    /** True when every pack in the bundle is present. */
    fun isComplete(installedPackIds: Set<String>): Boolean =
        !isEmpty && missingPacks(installedPackIds).isEmpty()

    /** True when an on-device recognizer for this language is installed. */
    fun hasLocalSpeech(installedPackIds: Set<String>): Boolean =
        speechPacks.any { it.id in installedPackIds }

    fun hasLocalRefiner(installedPackIds: Set<String>): Boolean =
        refinerPack?.id in installedPackIds

    companion object {
        /**
         * The bundle for [language] on a device where the refiner runtime is
         * [refinerAbiSupported].
         *
         * The recognizer side comes from [ModelCatalog.asrPacksFor], so language coverage
         * stays a catalog fact and this is not a second place that decides what English is.
         */
        fun of(
            language: String,
            refinerAbiSupported: Boolean,
            catalog: List<ModelPack> = ModelCatalog.packs,
        ): PrivateModeBundle {
            val speech = catalog.filter { it.kind == ModelKind.FINAL_ASR && it.covers(language) }
            val refiner =
                if (refinerAbiSupported) catalog.firstOrNull { it.kind == ModelKind.REFINER_LLM }
                else null
            val blocker = when {
                speech.isNotEmpty() || refiner != null -> null
                // No recognizer for this language is the headline reason; a 32-bit device
                // whose language is covered still has a bundle, so ABI only surfaces when
                // it is the thing that emptied it.
                catalog.none { it.kind == ModelKind.FINAL_ASR && it.covers(language) } ->
                    PrivateModeBlocker.LANGUAGE
                else -> PrivateModeBlocker.ABI
            }
            return PrivateModeBundle(speech, refiner, blocker)
        }
    }
}

/**
 * The one state the control renders. Per-pack [PackState] values are inputs to this and are
 * never the headline: the user made one decision and is owed one answer about it.
 */
sealed interface PrivateModeState {

    /** Nothing in the bundle can be installed here. Present, disabled, and says why. */
    data class Unavailable(val reason: PrivateModeBlocker) : PrivateModeState

    /** No bundle pack installed and nothing in flight. */
    data object Off : PrivateModeState

    /** Enqueued but held: offline, or waiting for an unmetered link. No progress bar. */
    data class Queued(val waitingForNetwork: Boolean) : PrivateModeState

    /**
     * One bar for the whole bundle. [bytesDone] counts already-installed packs as complete,
     * so the number never goes backwards when the bundle is resumed after a cancel.
     */
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : PrivateModeState {
        val fraction: Double
            get() =
                if (bytesTotal <= 0L) 0.0
                else (bytesDone.toDouble() / bytesTotal.toDouble()).coerceIn(0.0, 1.0)
    }

    /** Verifying and extracting: a step with no meaningful percentage. */
    data object Installing : PrivateModeState

    /**
     * A bundle pack failed for a reason other than cancellation. Packs that finished stay
     * finished, which is why [installedPacks] is part of the state rather than lost with it.
     */
    data class Failed(
        val error: InstallError,
        val installedPacks: List<ModelPack>,
        val missingPacks: List<ModelPack>,
    ) : PrivateModeState

    /**
     * Some but not all of the bundle is installed, and nothing is running. A first-class end
     * state: it is where a cancelled bundle lands and where someone who only wants local
     * dictation can legitimately stop.
     */
    data class PartlyOn(
        val installedPacks: List<ModelPack>,
        val missingPacks: List<ModelPack>,
    ) : PrivateModeState

    /** Every bundle pack installed. */
    data object On : PrivateModeState
}

/** What the device can actually do, as probed — never inferred from an API level. */
enum class SystemRecognizer {
    /** The platform recognizes without sending audio anywhere (API 31+ with a system pack). */
    ON_DEVICE,

    /** A recognizer exists but only the network one; audio leaves the phone. */
    NETWORK_ONLY,

    /** No system recognizer at all. */
    NONE,
}

/** Gemini Nano through AICore, as its own feature-status query reports it. */
enum class DeviceAi {
    /** Usable right now. */
    AVAILABLE,

    /** Supported, but the system still has to fetch its weights. Those bytes are not ours. */
    DOWNLOADABLE,

    /** The system is fetching them. */
    PREPARING,

    /** Not supported on this device, or no client to ask. */
    UNAVAILABLE,
}

data class DeviceCapabilities(
    val recognizer: SystemRecognizer,
    val deviceAi: DeviceAi,
    val refinerAbiSupported: Boolean,
)

/** What is transcribing speech right now. */
enum class DictationBackend {
    /** WaveKey's own recognizer, on the device. */
    WAVEKEY,

    /** The platform recognizer, on the device. */
    SYSTEM_ON_DEVICE,

    /** The platform recognizer, over the network. Audio leaves the phone. */
    SYSTEM_NETWORK,

    /** Nothing can dictate. The voice strip owes the user a sentence saying so. */
    NONE,
}

/** What is rewriting dictated text right now. */
enum class RefinementBackend {
    /** WaveKey's own refiner. */
    WAVEKEY,

    /** The device's own text AI (Gemini Nano through AICore). */
    DEVICE_AI,

    /** The deterministic cleanup pipeline only. Always available, never downloaded. */
    DETERMINISTIC,
}

/**
 * The backends a mic press would actually use. The status line under the control says this,
 * rather than restating which preference is set: a preference is not a promise.
 */
data class ActiveBackends(
    val dictation: DictationBackend,
    val refinement: RefinementBackend,
) {
    /** The single fact the disclosure exists for. */
    val audioLeavesDevice: Boolean get() = dictation == DictationBackend.SYSTEM_NETWORK
}

object PrivateMode {

    /**
     * The one state, from per-pack states and what the scheduler is holding.
     *
     * @param packStates state per bundle pack id, disk merged with anything live.
     * @param scheduledPackIds bundle packs WorkManager has enqueued or running.
     * @param waitingForNetworkPackIds the subset whose network constraint is not met yet.
     * @param usableSpaceBytes free space on the model volume, or null when it is not known.
     */
    fun resolve(
        bundle: PrivateModeBundle,
        packStates: Map<String, PackState>,
        scheduledPackIds: Set<String> = emptySet(),
        waitingForNetworkPackIds: Set<String> = emptySet(),
        usableSpaceBytes: Long? = null,
    ): PrivateModeState {
        bundle.blocker?.let { return PrivateModeState.Unavailable(it) }
        if (bundle.isEmpty) return PrivateModeState.Unavailable(PrivateModeBlocker.LANGUAGE)

        val installedIds = bundle.packs
            .filter { packStates[it.id] == PackState.Installed }
            .mapTo(mutableSetOf()) { it.id }
        val installed = bundle.installedPacks(installedIds)
        val missing = bundle.missingPacks(installedIds)

        // Out of space for everything still missing, and nothing installed to show for it:
        // offering a download that cannot finish is worse than saying so.
        if (usableSpaceBytes != null && installed.isEmpty() && missing.isNotEmpty() &&
            missing.minOf { it.installFootprintBytes } > usableSpaceBytes
        ) {
            return PrivateModeState.Unavailable(PrivateModeBlocker.STORAGE)
        }

        val downloading = bundle.packs.mapNotNull { packStates[it.id] as? PackState.Downloading }
        if (downloading.isNotEmpty()) {
            return PrivateModeState.Downloading(
                // Installed packs count as complete: the bar is about the bundle, and a bar
                // that restarts at zero for the second pack teaches the user it is lying.
                bytesDone = installed.sumOf { it.totalBytes } + downloading.sumOf { it.bytesDone },
                bytesTotal = bundle.totalBytes,
            )
        }
        if (bundle.packs.any { packStates[it.id] == PackState.Verifying }) {
            return PrivateModeState.Installing
        }
        val scheduled = bundle.packs.filter { it.id in scheduledPackIds }
        if (scheduled.isNotEmpty()) {
            return PrivateModeState.Queued(
                waitingForNetwork = scheduled.any { it.id in waitingForNetworkPackIds },
            )
        }
        if (missing.isEmpty()) return PrivateModeState.On

        // Cancellation is not a failure: it lands in Off or Partly on, which is what the
        // user asked for. Anything else has a cause the user is owed.
        val failure = bundle.packs
            .mapNotNull { packStates[it.id] as? PackState.Failed }
            .firstOrNull { it.error != InstallError.CANCELLED }
        if (failure != null) {
            return PrivateModeState.Failed(failure.error, installed, missing)
        }
        if (installed.isNotEmpty()) return PrivateModeState.PartlyOn(installed, missing)
        return PrivateModeState.Off
    }

    /**
     * Which backends a mic press would use right now.
     *
     * @param googleVoicePreferred the `PREF_GOOGLE_VOICE` preference as stored.
     *
     * The load-bearing rule is the last branch: when the local engine is selected but its
     * model is absent, the automatic fallback may only borrow the platform's *on-device*
     * recognizer. Automatic behaviour never starts sending audio off the device — only a
     * choice the user made can do that.
     */
    fun backends(
        capabilities: DeviceCapabilities,
        googleVoicePreferred: Boolean,
        localSpeechInstalled: Boolean,
        localRefinerInstalled: Boolean,
    ): ActiveBackends {
        val dictation = when {
            !googleVoicePreferred && localSpeechInstalled -> DictationBackend.WAVEKEY
            googleVoicePreferred -> when (capabilities.recognizer) {
                SystemRecognizer.ON_DEVICE -> DictationBackend.SYSTEM_ON_DEVICE
                SystemRecognizer.NETWORK_ONLY -> DictationBackend.SYSTEM_NETWORK
                // Asked for a recognizer this device does not have: ours, if we have it.
                SystemRecognizer.NONE ->
                    if (localSpeechInstalled) DictationBackend.WAVEKEY else DictationBackend.NONE
            }
            capabilities.recognizer == SystemRecognizer.ON_DEVICE ->
                DictationBackend.SYSTEM_ON_DEVICE
            else -> DictationBackend.NONE
        }
        val refinement = when {
            // Private mode's promise is WaveKey's own models, so ours wins where it can run.
            localRefinerInstalled && capabilities.refinerAbiSupported -> RefinementBackend.WAVEKEY
            capabilities.deviceAi == DeviceAi.AVAILABLE -> RefinementBackend.DEVICE_AI
            else -> RefinementBackend.DETERMINISTIC
        }
        return ActiveBackends(dictation, refinement)
    }

    /**
     * True when audio would go to the network recognizer and the user has not been told.
     *
     * The consent is per install, not per session, and is dropped again when private mode is
     * turned on — so turning it off later asks once more rather than trading on an answer
     * given to a different question.
     */
    fun networkDisclosureOwed(backends: ActiveBackends, consentRecorded: Boolean): Boolean =
        backends.audioLeavesDevice && !consentRecorded
}

/**
 * The dictation default a preference read should fall back to.
 *
 * [defaultDecided] is false until the one-time migration has looked at this install, and until
 * then the answer is the old behaviour whatever the shipped default is. That closes the window
 * between process start and the migration finishing: without it, a mic press in that window
 * would adopt the new default on an install that was never allowed to have it, which is the
 * one failure this whole migration exists to prevent.
 */
fun googleVoiceDefault(shippedDefault: Boolean, defaultDecided: Boolean): Boolean =
    shippedDefault && defaultDecided

/**
 * What an install's existing state says about whether it may adopt the new default.
 *
 * @property choiceRecorded the `PREF_GOOGLE_VOICE` key is already present, i.e. the user (or
 *   an earlier migration) has an answer of their own. Never overwritten.
 * @property anyWaveKeyPackInstalled a WaveKey model pack is on disk. Someone paid a
 *   gigabyte for on-device dictation; flipping them onto the network recognizer is not a
 *   default change, it is a privacy change made behind their back.
 * @property voiceSessionRecorded the install has dictated before. Recorded from this build
 *   onwards; an install upgrading from an earlier build is recognised by its packs.
 */
data class InstallHistory(
    val choiceRecorded: Boolean,
    val anyWaveKeyPackInstalled: Boolean,
    val voiceSessionRecorded: Boolean,
)

/** What the one-time migration should write. */
enum class GoogleVoiceMigration {
    /** Leave the key absent, so the shipped default applies. A fresh install. */
    NOTHING,

    /**
     * Write the old default (`false`) explicitly, pinning dictation to WaveKey's own models.
     */
    PIN_ON_DEVICE,
}

/**
 * Decides, once per install, whether flipping the shipped default to the platform recognizer
 * is allowed to reach this user.
 *
 * This is the sharp edge of the whole feature: the change is safe on a fresh install and is
 * a silent privacy regression on an existing one. So the rule is stated once, here, as a
 * pure function over facts, and the Android layer only reports the facts and writes the
 * answer.
 */
fun migrateGoogleVoiceDefault(history: InstallHistory): GoogleVoiceMigration = when {
    // Their own answer, whatever it is, outranks any default.
    history.choiceRecorded -> GoogleVoiceMigration.NOTHING
    history.anyWaveKeyPackInstalled || history.voiceSessionRecorded ->
        GoogleVoiceMigration.PIN_ON_DEVICE
    else -> GoogleVoiceMigration.NOTHING
}
