package com.vboard.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The private mode state machine, its bundle derivation, the backend resolution the status
 * line reads from, and the upgrade migration.
 *
 * The migration cases are the ones worth the most: an existing install that already owns the
 * models must never be moved onto the network recognizer by a default change.
 */
class PrivateModeTest {

    private val speech = ModelCatalog.byKind(ModelKind.FINAL_ASR).single()
    private val refiner = ModelCatalog.byKind(ModelKind.REFINER_LLM).single()

    private fun bundle(language: String = "en", abi: Boolean = true) =
        PrivateModeBundle.of(language, refinerAbiSupported = abi)

    private fun installed(vararg packs: ModelPack): Map<String, PackState> =
        packs.associate { it.id to PackState.Installed }

    // ------------------------------------------------------------------ bundle

    @Test
    fun `the bundle is the language's recognizers plus the refiner`() {
        assertEquals(listOf(speech.id, refiner.id), bundle().packs.map { it.id })
        assertNull(bundle().blocker)
    }

    @Test
    fun `the download figure is the sum of the catalog packs, not a constant`() {
        val expected = speech.totalBytes + refiner.totalBytes
        assertEquals(expected, bundle().downloadBytes(emptySet()))
        assertEquals("980 MB", ByteSize.format(bundle().downloadBytes(emptySet())))
    }

    @Test
    fun `the figure shrinks to what is still missing`() {
        assertEquals(refiner.totalBytes, bundle().downloadBytes(setOf(speech.id)))
    }

    @Test
    fun `storage needed is the install footprint, above the download size`() {
        val b = bundle()
        assertTrue(b.storageBytes(emptySet()) > b.downloadBytes(emptySet()))
    }

    @Test
    fun `a 32-bit device's bundle is the recognizer alone, and its figures follow`() {
        val b = bundle(abi = false)
        assertEquals(listOf(speech.id), b.packs.map { it.id })
        assertEquals("482 MB", ByteSize.format(b.downloadBytes(emptySet())))
        assertEquals(PrivateModeState.On, PrivateMode.resolve(b, installed(speech)))
    }

    @Test
    fun `a language with no on-device recognizer cannot offer private dictation`() {
        val b = bundle(language = "fr", abi = false)
        assertEquals(PrivateModeBlocker.LANGUAGE, b.blocker)
        assertEquals(
            PrivateModeState.Unavailable(PrivateModeBlocker.LANGUAGE),
            PrivateMode.resolve(b, emptyMap()),
        )
    }

    @Test
    fun `a language with no recognizer still offers the refiner where it can run`() {
        val b = bundle(language = "fr", abi = true)
        assertEquals(listOf(refiner.id), b.packs.map { it.id })
        assertNull(b.blocker)
    }

    // ------------------------------------------------------------------- states

    @Test
    fun `nothing installed and nothing scheduled is Off`() {
        assertEquals(PrivateModeState.Off, PrivateMode.resolve(bundle(), emptyMap()))
    }

    @Test
    fun `everything installed is On`() {
        assertEquals(PrivateModeState.On, PrivateMode.resolve(bundle(), installed(speech, refiner)))
    }

    @Test
    fun `enqueued but held is Queued with no progress`() {
        val state = PrivateMode.resolve(
            bundle(),
            emptyMap(),
            scheduledPackIds = setOf(speech.id),
            waitingForNetworkPackIds = setOf(speech.id),
        )
        assertEquals(PrivateModeState.Queued(waitingForNetwork = true), state)
    }

    @Test
    fun `one bar counts installed packs as complete`() {
        val state = PrivateMode.resolve(
            bundle(),
            installed(speech) + (refiner.id to PackState.Downloading(0L, refiner.totalBytes)),
            scheduledPackIds = setOf(refiner.id),
        )
        assertIs<PrivateModeState.Downloading>(state)
        assertEquals(speech.totalBytes, state.bytesDone)
        assertEquals(speech.totalBytes + refiner.totalBytes, state.bytesTotal)
        assertTrue(state.fraction > 0.4 && state.fraction < 0.6)
    }

    /**
     * The regression behind the second QA round's first blocker: the control sat in Queued for
     * the whole of two complete 980 MB downloads. The resolver was never the broken half — the
     * progress simply never reached it — so this pins the combination the screen actually
     * feeds it, a pack that is both scheduled and reporting bytes.
     */
    @Test
    fun `a scheduled pack that is reporting bytes is Downloading, never Queued`() {
        val state = PrivateMode.resolve(
            bundle(),
            mapOf(speech.id to PackState.Downloading(speech.totalBytes / 2, speech.totalBytes)),
            scheduledPackIds = setOf(speech.id, refiner.id),
            waitingForNetworkPackIds = setOf(refiner.id),
        )
        assertIs<PrivateModeState.Downloading>(state)
        assertEquals(speech.totalBytes / 2, state.bytesDone)
    }

    /**
     * One download, one set of numbers. The notification and the progress bar are the same
     * download reported twice, so they run the same arithmetic over the same states; two
     * surfaces each summing their own share is how a notification came to read 21% beside a
     * screen showing no percentage at all.
     */
    @Test
    fun `the combined figure counts what is installed, what is verifying and what is in flight`() {
        val states = mapOf(
            speech.id to PackState.Verifying,
            refiner.id to PackState.Downloading(1_000L, refiner.totalBytes),
        )
        assertEquals(
            speech.totalBytes + 1_000L,
            PrivateMode.downloadedBytes(bundle().packs, states),
        )
        assertEquals(0L, PrivateMode.downloadedBytes(bundle().packs, emptyMap()))
    }

    @Test
    fun `a pack past downloading no longer drags the bar backwards`() {
        val state = PrivateMode.resolve(
            bundle(),
            mapOf(
                speech.id to PackState.Verifying,
                refiner.id to PackState.Downloading(0L, refiner.totalBytes),
            ),
        )
        assertIs<PrivateModeState.Downloading>(state)
        assertEquals(speech.totalBytes, state.bytesDone)
    }

    @Test
    fun `verifying is Installing, not a percentage`() {
        assertEquals(
            PrivateModeState.Installing,
            PrivateMode.resolve(bundle(), mapOf(speech.id to PackState.Verifying)),
        )
    }

    // ----------------------------------------------------------- engine adoption

    /**
     * The second QA round's other blocker: once the user had turned private mode off keeping
     * the models, "Go private" could never turn it on again. Both packs downloaded, both
     * installed, and the engine stayed on Google — because the sync that moves the engine
     * refuses to overrule a standing choice of the platform recognizer, and nothing on the
     * download path withdrew that choice.
     */
    @Test
    fun `a standing choice of the platform recognizer is never overruled by an install`() {
        assertFalse(
            PrivateMode.adoptsLocalEngine(
                googleVoicePreferred = true,
                platformRecognizerChosen = true,
                localSpeechInstalled = true,
            ),
        )
    }

    @Test
    fun `withdrawing that choice is what lets the installed pack take over`() {
        assertTrue(
            PrivateMode.adoptsLocalEngine(
                googleVoicePreferred = true,
                platformRecognizerChosen = false,
                localSpeechInstalled = true,
            ),
        )
    }

    @Test
    fun `nothing is adopted before the recognizer pack exists`() {
        assertFalse(
            PrivateMode.adoptsLocalEngine(
                googleVoicePreferred = true,
                platformRecognizerChosen = false,
                localSpeechInstalled = false,
            ),
        )
    }

    // AC 16: installed is not the same as selected, and the one place that used to conflate
    // them reported On for a mode the user had just turned off — with no action left on the
    // row, so no way back.

    @Test
    fun `a bundle the user has switched away from is not On`() {
        val state = PrivateMode.resolve(
            bundle(),
            installed(speech, refiner),
            googleVoicePreferred = true,
        )
        assertEquals(PrivateModeState.OffWithModels(listOf(speech, refiner), emptyList()), state)
    }

    @Test
    fun `turning it off keeps every pack, so coming back costs nothing`() {
        val state = PrivateMode.resolve(
            bundle(),
            installed(speech, refiner),
            googleVoicePreferred = true,
        )
        assertIs<PrivateModeState.OffWithModels>(state)
        assertTrue(state.missingPacks.isEmpty(), "nothing is left to download")
        assertEquals(bundle().packs, state.installedPacks)
    }

    @Test
    fun `half a bundle with the platform recognizer selected is off, not partly on`() {
        val state = PrivateMode.resolve(
            bundle(),
            installed(speech),
            googleVoicePreferred = true,
        )
        assertEquals(PrivateModeState.OffWithModels(listOf(speech), listOf(refiner)), state)
    }

    @Test
    fun `the same packs with the local engine selected are On`() {
        assertEquals(
            PrivateModeState.On,
            PrivateMode.resolve(
                bundle(),
                installed(speech, refiner),
                googleVoicePreferred = false,
            ),
        )
    }

    @Test
    fun `the engine preference does not invent a state out of an empty disk`() {
        assertEquals(
            PrivateModeState.Off,
            PrivateMode.resolve(bundle(), emptyMap(), googleVoicePreferred = true),
        )
    }

    @Test
    fun `a cancelled pack lands in Off rather than Failed`() {
        assertEquals(
            PrivateModeState.Off,
            PrivateMode.resolve(
                bundle(),
                mapOf(speech.id to PackState.Failed(InstallError.CANCELLED)),
            ),
        )
    }

    @Test
    fun `cancelling after the recognizer installed lands in Partly on`() {
        val state = PrivateMode.resolve(
            bundle(),
            installed(speech) + (refiner.id to PackState.Failed(InstallError.CANCELLED)),
        )
        assertEquals(PrivateModeState.PartlyOn(listOf(speech), listOf(refiner)), state)
    }

    @Test
    fun `a real failure keeps the installed pack and names one cause`() {
        val state = PrivateMode.resolve(
            bundle(),
            installed(speech) + (refiner.id to PackState.Failed(InstallError.NETWORK)),
        )
        assertEquals(
            PrivateModeState.Failed(InstallError.NETWORK, listOf(speech), listOf(refiner)),
            state,
        )
    }

    @Test
    fun `no room for anything missing is Unavailable, not a download that cannot finish`() {
        assertEquals(
            PrivateModeState.Unavailable(PrivateModeBlocker.STORAGE),
            PrivateMode.resolve(bundle(), emptyMap(), usableSpaceBytes = 1_000_000L),
        )
    }

    @Test
    fun `packs already installed are never called Unavailable for space`() {
        val state = PrivateMode.resolve(
            bundle(),
            installed(speech),
            usableSpaceBytes = 1_000_000L,
        )
        assertIs<PrivateModeState.PartlyOn>(state)
    }

    // ------------------------------------------------------- capability resolution

    private fun caps(
        recognizer: SystemRecognizer,
        deviceAi: DeviceAi = DeviceAi.UNAVAILABLE,
        abi: Boolean = true,
    ) = DeviceCapabilities(recognizer, deviceAi, abi)

    @Test
    fun `the shipped default on a network-only device sends audio to the service`() {
        val backends = PrivateMode.backends(
            caps(SystemRecognizer.NETWORK_ONLY, DeviceAi.AVAILABLE),
            googleVoicePreferred = true,
            localSpeechInstalled = false,
            localRefinerInstalled = false,
        )
        assertEquals(DictationBackend.SYSTEM_NETWORK, backends.dictation)
        assertEquals(RefinementBackend.DEVICE_AI, backends.refinement)
        assertTrue(backends.audioLeavesDevice)
    }

    @Test
    fun `the shipped default on an offline-capable device sends nothing`() {
        val backends = PrivateMode.backends(
            caps(SystemRecognizer.ON_DEVICE),
            googleVoicePreferred = true,
            localSpeechInstalled = false,
            localRefinerInstalled = false,
        )
        assertEquals(DictationBackend.SYSTEM_ON_DEVICE, backends.dictation)
        assertEquals(RefinementBackend.DETERMINISTIC, backends.refinement)
        assertFalse(backends.audioLeavesDevice)
    }

    @Test
    fun `the automatic fallback never reaches for the network recognizer`() {
        val backends = PrivateMode.backends(
            caps(SystemRecognizer.NETWORK_ONLY),
            googleVoicePreferred = false,
            localSpeechInstalled = false,
            localRefinerInstalled = false,
        )
        assertEquals(DictationBackend.NONE, backends.dictation)
        assertFalse(backends.audioLeavesDevice)
    }

    @Test
    fun `private mode complete reports WaveKey on both halves`() {
        val backends = PrivateMode.backends(
            caps(SystemRecognizer.NETWORK_ONLY, DeviceAi.AVAILABLE),
            googleVoicePreferred = false,
            localSpeechInstalled = true,
            localRefinerInstalled = true,
        )
        assertEquals(DictationBackend.WAVEKEY, backends.dictation)
        assertEquals(RefinementBackend.WAVEKEY, backends.refinement)
    }

    @Test
    fun `a 32-bit device never runs WaveKey's refiner even with the pack present`() {
        val backends = PrivateMode.backends(
            caps(SystemRecognizer.ON_DEVICE, DeviceAi.AVAILABLE, abi = false),
            googleVoicePreferred = true,
            localSpeechInstalled = false,
            localRefinerInstalled = true,
        )
        assertEquals(RefinementBackend.DEVICE_AI, backends.refinement)
    }

    @Test
    fun `device AI that still needs its own download is not a refiner yet`() {
        val backends = PrivateMode.backends(
            caps(SystemRecognizer.ON_DEVICE, DeviceAi.DOWNLOADABLE),
            googleVoicePreferred = true,
            localSpeechInstalled = false,
            localRefinerInstalled = false,
        )
        assertEquals(RefinementBackend.DETERMINISTIC, backends.refinement)
    }

    @Test
    fun `the disclosure is owed only while audio actually leaves the phone`() {
        val network = ActiveBackends(DictationBackend.SYSTEM_NETWORK, RefinementBackend.DETERMINISTIC)
        val onDevice = ActiveBackends(DictationBackend.SYSTEM_ON_DEVICE, RefinementBackend.DETERMINISTIC)
        assertTrue(PrivateMode.networkDisclosureOwed(network, consentRecorded = false))
        assertFalse(PrivateMode.networkDisclosureOwed(network, consentRecorded = true))
        assertFalse(PrivateMode.networkDisclosureOwed(onDevice, consentRecorded = false))
    }

    // ------------------------------------------------------------------ migration

    @Test
    fun `a read before the migration has decided answers with the old behaviour`() {
        assertFalse(googleVoiceDefault(shippedDefault = true, defaultDecided = false))
        assertTrue(googleVoiceDefault(shippedDefault = true, defaultDecided = true))
    }

    @Test
    fun `a fresh install adopts the shipped default`() {
        assertEquals(
            GoogleVoiceMigration.NOTHING,
            migrateGoogleVoiceDefault(
                InstallHistory(
                    choiceRecorded = false,
                    anyWaveKeyPackInstalled = false,
                    voiceSessionRecorded = false,
                ),
            ),
        )
    }

    @Test
    fun `an install with models keeps dictating on them`() {
        assertEquals(
            GoogleVoiceMigration.PIN_ON_DEVICE,
            migrateGoogleVoiceDefault(
                InstallHistory(
                    choiceRecorded = false,
                    anyWaveKeyPackInstalled = true,
                    voiceSessionRecorded = false,
                ),
            ),
        )
    }

    @Test
    fun `an install that has dictated before keeps its behaviour`() {
        assertEquals(
            GoogleVoiceMigration.PIN_ON_DEVICE,
            migrateGoogleVoiceDefault(
                InstallHistory(
                    choiceRecorded = false,
                    anyWaveKeyPackInstalled = false,
                    voiceSessionRecorded = true,
                ),
            ),
        )
    }

    @Test
    fun `an answer the user already gave is never overwritten`() {
        assertEquals(
            GoogleVoiceMigration.NOTHING,
            migrateGoogleVoiceDefault(
                InstallHistory(
                    choiceRecorded = true,
                    anyWaveKeyPackInstalled = true,
                    voiceSessionRecorded = true,
                ),
            ),
        )
    }
}
