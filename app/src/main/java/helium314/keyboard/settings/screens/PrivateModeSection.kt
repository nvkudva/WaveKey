// SPDX-License-Identifier: GPL-3.0-only
//
// WaveKey. New file: the private mode control — one decision, one combined
// size, one progress story, one cancel, one end state.
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vboard.app.models.ModelDownloadService
import com.vboard.app.models.PrivateModeDownloads
import com.vboard.app.voice.voiceRuntimeOrNull
import com.vboard.core.model.ActiveBackends
import com.vboard.core.model.ByteSize
import com.vboard.core.model.DictationBackend
import com.vboard.core.model.DownloadDecision
import com.vboard.core.model.DownloadPolicy
import com.vboard.core.model.InstallError
import com.vboard.core.model.ModelKind
import com.vboard.core.model.PackState
import com.vboard.core.model.PrivateMode
import com.vboard.core.model.PrivateModeBlocker
import com.vboard.core.model.PrivateModeState
import com.vboard.core.model.RefinementBackend
import com.vboard.core.model.SystemRecognizer
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.preferences.PreferenceGroupDivider
import helium314.keyboard.voice.GoogleVoiceSession
import helium314.keyboard.voice.PrivateModePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The mode chooser, the honest disclosure, and one action.
 *
 * The two rows are a radio group rather than a switch because the off state is a specific
 * thing — Google — that the user needs to hear named, and because the on transition costs
 * several hundred megabytes and a switch promises instant.
 *
 * The radio never lies: selecting Private while the models are missing does not move the
 * selection. It starts the download, and the selection moves when the recognizer lands,
 * because the selected row always names the engine that would run right now. The engine is a
 * consequence of what is installed, which is why this replaced the old separate engine radio
 * pair rather than sitting next to it — two controls that can contradict each other was the
 * bug.
 */
@Composable
fun PrivateModeSection() {
    val ctx = LocalContext.current
    val runtime = remember { voiceRuntimeOrNull(ctx) }
    // Preference writes are not Compose state. Without this the rows keep their old text
    // after the engine changes, which is what makes a live setting look broken.
    val changed = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((changed?.value ?: 0) < 0) return
    if (runtime == null) {
        Text(
            stringResource(R.string.voice_models_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    val prefs = ctx.prefs()
    val scope = rememberCoroutineScope()
    val language = remember { PrivateModePrefs.dictationLanguage() }
    val bundle = remember(language) { PrivateModePrefs.bundleFor(language) }
    val recognizer = remember { GoogleVoiceSession.systemRecognizer(ctx) }
    val capabilities = remember { PrivateModeDownloads.capabilities(ctx, recognizer) }

    // Remembered, not rebuilt: the flow polls, so handing `collectAsState` a new instance on
    // every recomposition would restart the poll on every frame it produces.
    val downloads by remember { ModelDownloadService.observeDownloads(ctx) }
        .collectAsState(initial = ModelDownloadService.Downloads())
    val scheduled = downloads.scheduled
    val liveStates by ModelDownloadService.states.collectAsState()
    var diskStates by remember { mutableStateOf<Map<String, PackState>>(emptyMap()) }
    var usableSpace by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(bundle, liveStates, scheduled) {
        diskStates = withContext(Dispatchers.IO) {
            PrivateModeDownloads.diskStates(runtime.packInstaller, bundle)
        }
        usableSpace = withContext(Dispatchers.IO) {
            PrivateModeDownloads.usableSpaceBytes(runtime.modelStore)
        }
    }

    val scheduledIds = scheduled.map { it.packId }.toSet()
    val waitingIds = scheduled.filter { it.waitingForNetwork }.map { it.packId }.toSet()
    val scheduledProgress = scheduled.mapNotNull { row -> row.progress?.let { row.packId to it } }
        .toMap()
    // Disk wins once nothing is scheduled: a worker's last published state is what it *did*,
    // and a pack installed where this process cannot read it must not keep reading Installed.
    //
    // While something *is* scheduled, the scheduler's own record of it comes first. The
    // download runs in `:ui` and this screen runs in the keyboard's process, so the in-memory
    // state flow below is a different map that the worker never reaches; reading progress from
    // it alone is why the control stayed in Queued for the whole of a 980 MB download while the
    // notification counted percentages. It is kept as the second source because in a single
    // process it is the fresher of the two.
    val packStates = bundle.packs.associate { pack ->
        val disk = diskStates[pack.id] ?: PackState.NotInstalled
        val failure = downloads.failures[pack.id]?.let { PackState.Failed(it) }
        pack.id to when {
            pack.id in scheduledIds -> scheduledProgress[pack.id] ?: liveStates[pack.id] ?: disk
            // A pack that is on disk is on disk, whatever an older run of it did.
            disk == PackState.Installed -> disk
            // Nothing is running and the last run of this pack failed for a reason the user is
            // owed. It stands until they retry or say to leave it, which is what §5.3.7 asks
            // for and what an unreadable in-memory publish could never deliver.
            failure != null -> failure
            // Otherwise disk is the durable answer, and the only one: a worker's last published
            // state is what it *did*, and it must not keep a removed pack reading Installed.
            else -> disk
        }
    }
    val installedIds = bundle.packs
        .filter { packStates[it.id] == PackState.Installed }
        .mapTo(mutableSetOf()) { it.id }
    val google = PrivacyBreakingSettings.googleVoiceEnabled(prefs)
    // Installed packs are not private mode on their own: the engine preference is half the
    // state, which is why it is an input here rather than something the rows re-derive.
    val state = PrivateMode.resolve(
        bundle = bundle,
        packStates = packStates,
        scheduledPackIds = scheduledIds,
        waitingForNetworkPackIds = waitingIds,
        usableSpaceBytes = usableSpace,
        googleVoicePreferred = google,
    )
    val localSpeech = bundle.hasLocalSpeech(installedIds)
    val onDeviceRecognizer = recognizer == SystemRecognizer.ON_DEVICE
    val backends = PrivateMode.backends(
        capabilities = capabilities,
        googleVoicePreferred = google,
        localSpeechInstalled = localSpeech,
        localRefinerInstalled = bundle.hasLocalRefiner(installedIds),
    )

    // §5.4: the engine is not a second decision. The moment the recognizer pack is installed,
    // dictation switches to it, unless the user asked for Google explicitly.
    LaunchedEffect(installedIds) {
        if (bundle.hasLocalSpeech(installedIds)) {
            withContext(Dispatchers.IO) {
                PrivateModePrefs.syncEngineToInstalledPacks(ctx, language)
            }
        }
    }

    // Null while hidden; true when this same dialog also has to ask about mobile data. One
    // decision gets one dialog: a second one after the user has already said yes reads as the
    // app not having listened.
    var confirmDownloadMetered by remember { mutableStateOf<Boolean?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmTurnOff by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val missingBytes = bundle.downloadBytes(installedIds)
    val missingText = ByteSize.format(missingBytes)
    val storageText = ByteSize.format(bundle.storageBytes(installedIds))
    val installedText = ByteSize.format(PrivateModeDownloads.installedBytes(bundle, installedIds))

    /** Whether the confirmation owes the mobile-data question too. DownloadPolicy decides. */
    fun meteredConsentOwed(): Boolean = DownloadPolicy.decide(
        network = ModelDownloadService.networkState(ctx),
        meteredConsent = false,
        bytes = missingBytes,
    ) is DownloadDecision.ConfirmMetered

    /** Enqueues what is missing, with the constraint the answered question allows. */
    fun enqueue(meteredConsent: Boolean) {
        val decision = DownloadPolicy.decide(
            network = ModelDownloadService.networkState(ctx),
            meteredConsent = meteredConsent,
            bytes = missingBytes,
        )
        // Asking for private mode is the choice, not the moment the download lands. The engine
        // itself cannot move yet — there is no recognizer to move it to — but the standing
        // choice of the platform recognizer has to be withdrawn here, because the sync that
        // runs when the pack arrives will not overrule it. Leaving it in place meant a user who
        // had once turned private mode off keeping the models could download both packs, watch
        // them install, and still be on Google with a Go private button in front of them.
        PrivateModePrefs.requestPrivateMode(prefs)
        PrivateModeDownloads.start(
            context = ctx,
            installer = runtime.packInstaller,
            bundle = bundle,
            installedPackIds = installedIds,
            allowMetered = (decision as? DownloadDecision.Enqueue)?.allowMetered ?: meteredConsent,
        )
    }

    fun goPrivate() {
        if (bundle.isComplete(installedIds)) {
            // Coming back with both packs present is instant: no download, no dialog. That is
            // the proof the choice is cheap, so it must not be dressed up as an event.
            PrivateModePrefs.setGoogleVoice(prefs, google = false, manual = false)
        } else {
            confirmDownloadMetered = meteredConsentOwed()
        }
    }

    /** Stops the bundle and throws away the bytes nothing will resume. */
    fun cancelDownload() {
        scope.launch {
            PrivateModeDownloads.cancel(ctx, runtime.packInstaller, bundle, installedIds)
            diskStates = withContext(Dispatchers.IO) {
                PrivateModeDownloads.diskStates(runtime.packInstaller, bundle)
            }
        }
    }

    Column(Modifier.selectableGroup()) {
        EngineOption(
            name = stringResource(R.string.wk_mode_google),
            description = stringResource(
                when {
                    recognizer == SystemRecognizer.NONE -> R.string.wk_mode_google_unavailable
                    !google -> R.string.wk_mode_google_off
                    recognizer == SystemRecognizer.ON_DEVICE -> R.string.wk_mode_google_local
                    else -> R.string.wk_mode_google_network
                },
            ),
            // Selection follows the backend that would actually run, not the preference:
            // with the local engine chosen and no model installed, the platform's own
            // recognizer is what a mic press reaches, and the row says so.
            selected = backends.dictation == DictationBackend.SYSTEM_ON_DEVICE ||
                backends.dictation == DictationBackend.SYSTEM_NETWORK,
            enabled = recognizer != SystemRecognizer.NONE,
            trailing = {
                PrivateModeOverflow(
                    canRemove = installedIds.isNotEmpty(),
                    canUseGoogle = recognizer != SystemRecognizer.NONE && localSpeech,
                    onRemove = { confirmRemove = true },
                    onUseGoogle = {
                        PrivateModePrefs.setGoogleVoice(prefs, google = true, manual = true)
                    },
                )
            },
        ) {
            if (localSpeech && !google) confirmTurnOff = true
            else PrivateModePrefs.setGoogleVoice(prefs, google = true, manual = true)
        }
        EngineOption(
            name = stringResource(R.string.wk_mode_private),
            description = privateRowDescription(
                state = state,
                hasLocalSpeech = localSpeech,
                missingText = missingText,
                installedText = installedText,
                storageText = storageText,
            ),
            selected = backends.dictation == DictationBackend.WAVEKEY,
            enabled = state !is PrivateModeState.Unavailable,
            onClick = ::goPrivate,
        )
        PreferenceGroupDivider()
        Text(
            disclosure(backends, recognizer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // §6.1: what is running right now, naming the real backend — including the automatic
        // fallback — rather than restating which preference is set.
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(
                    when (backends.dictation) {
                        DictationBackend.WAVEKEY -> R.string.wk_now_dictation_wavekey
                        DictationBackend.SYSTEM_ON_DEVICE -> R.string.wk_now_dictation_system_local
                        DictationBackend.SYSTEM_NETWORK -> R.string.wk_now_dictation_system_network
                        DictationBackend.NONE -> R.string.wk_now_dictation_none
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    when (backends.refinement) {
                        RefinementBackend.WAVEKEY -> R.string.wk_now_refine_wavekey
                        RefinementBackend.DEVICE_AI -> R.string.wk_now_refine_device_ai
                        RefinementBackend.DETERMINISTIC -> R.string.wk_now_refine_deterministic
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PrivateModeProgress(state)
        PrivateModeAction(
            state = state,
            missingText = missingText,
            required = recognizer == SystemRecognizer.NONE,
            onGoPrivate = ::goPrivate,
            onCancel = { confirmCancel = true },
            // §5.3.7 gives a failure two answers, and the second one is not a download: it
            // drops what failed and leaves the user where they already are.
            onDismissFailure = ::cancelDownload,
        )
    }

    // The mobile-data question is part of the same decision, so it is asked inside this one
    // dialog rather than as a second one that arrives after the user already said yes.
    confirmDownloadMetered?.let { metered ->
        ConfirmationDialog(
            onDismissRequest = { confirmDownloadMetered = null },
            onConfirmed = { confirmDownloadMetered = null; enqueue(meteredConsent = metered) },
            confirmButtonText = stringResource(
                if (metered) R.string.wk_private_confirm_metered_download
                else R.string.wk_private_confirm_download,
            ),
            title = { Text(stringResource(R.string.wk_private_confirm_title)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.wk_private_confirm_message, missingText, storageText,
                        ),
                    )
                    if (metered) Text(stringResource(R.string.wk_private_confirm_metered))
                }
            },
        )
    }
    if (confirmCancel) ConfirmationDialog(
        onDismissRequest = { confirmCancel = false },
        onConfirmed = {
            confirmCancel = false
            cancelDownload()
        },
        confirmButtonText = stringResource(R.string.wk_private_cancel_confirm),
        cancelButtonText = stringResource(R.string.wk_private_cancel_keep),
        title = { Text(stringResource(R.string.wk_private_cancel_title)) },
        content = {
            Text(
                if (installedIds.isEmpty()) stringResource(R.string.wk_private_cancel_message_none)
                else stringResource(R.string.wk_private_cancel_message, installedText),
            )
        },
    )
    // Reverting is free and deletes nothing by default: disk is recoverable, the download is
    // not. Deleting is offered here as its own answer, never as a consequence of turning off.
    if (confirmTurnOff) ConfirmationDialog(
        onDismissRequest = { confirmTurnOff = false },
        onConfirmed = {
            confirmTurnOff = false
            PrivateModePrefs.setGoogleVoice(prefs, google = true, manual = true)
        },
        confirmButtonText = stringResource(R.string.wk_private_turn_off_keep),
        neutralButtonText = stringResource(R.string.wk_private_turn_off_delete, installedText),
        onNeutral = {
            confirmTurnOff = false
            PrivateModePrefs.setGoogleVoice(prefs, google = true, manual = true)
            scope.launch {
                PrivateModeDownloads.delete(runtime.packInstaller, bundle)
                diskStates = withContext(Dispatchers.IO) {
                    PrivateModeDownloads.diskStates(runtime.packInstaller, bundle)
                }
            }
        },
        title = { Text(stringResource(R.string.wk_private_turn_off_title)) },
        content = {
            // On a phone that recognizes offline, going back to the platform recognizer sends
            // nothing anywhere, and a dialog that says otherwise is the disclosure lying.
            Text(
                stringResource(
                    if (onDeviceRecognizer) R.string.wk_private_turn_off_message_local
                    else R.string.wk_private_turn_off_message,
                ),
            )
        },
    )
    if (confirmRemove) ConfirmationDialog(
        onDismissRequest = { confirmRemove = false },
        onConfirmed = {
            confirmRemove = false
            PrivateModePrefs.setGoogleVoice(prefs, google = true, manual = true)
            scope.launch {
                PrivateModeDownloads.delete(runtime.packInstaller, bundle)
                diskStates = withContext(Dispatchers.IO) {
                    PrivateModeDownloads.diskStates(runtime.packInstaller, bundle)
                }
            }
        },
        confirmButtonText = stringResource(R.string.wk_private_remove),
        title = { Text(stringResource(R.string.wk_private_remove)) },
        content = {
            Text(
                stringResource(
                    if (onDeviceRecognizer) R.string.wk_private_remove_message_local
                    else R.string.wk_private_remove_message,
                    installedText,
                ),
            )
        },
    )
}

/** One bar for the whole bundle, or none. A stalled bar reads as broken. */
@Composable
private fun PrivateModeProgress(state: PrivateModeState) {
    when (state) {
        is PrivateModeState.Downloading -> {
            val fraction = state.fraction.toFloat()
            val label = stringResource(
                R.string.wk_private_downloading,
                (state.fraction * 100).toInt(),
                ByteSize.format(state.bytesTotal),
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        // One bar carries one number, said once. Per-pack bars would hand
                        // TalkBack two progress values for one download.
                        .semantics {
                            contentDescription = label
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        },
                )
                Text(
                    stringResource(R.string.wk_private_downloading_stage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is PrivateModeState.Installing -> PrivateModeNote(R.string.wk_private_installing)
        is PrivateModeState.Queued -> PrivateModeNote(
            if (state.waitingForNetwork) R.string.wk_private_queued
            else R.string.wk_private_queued_offline,
        )
        is PrivateModeState.Failed -> Text(
            failureText(state),
            style = MaterialTheme.typography.bodyMedium,
            // Colour is never the state: the reason line carries the error colour and the
            // container does not, because an error-tinted card reads as a crash.
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        else -> Unit
    }
}

@Composable
private fun PrivateModeNote(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun failureText(state: PrivateModeState.Failed): String {
    // Which half survived decides which sentence is true. Assuming it was always the speech
    // pack made the row claim the opposite of the status lines directly below it.
    val speechInstalled = state.installedPacks.any { it.kind == ModelKind.FINAL_ASR }
    val outcome = stringResource(
        when {
            state.installedPacks.isEmpty() -> R.string.wk_private_failed_none
            speechInstalled -> R.string.wk_private_partial_speech
            else -> R.string.wk_private_failed_speech
        },
    )
    val reason = when (state.error) {
        InstallError.NETWORK -> stringResource(R.string.wk_private_failed_reason_network)
        InstallError.CHECKSUM_MISMATCH ->
            stringResource(R.string.wk_private_failed_reason_checksum)
        InstallError.INSUFFICIENT_STORAGE -> stringResource(
            R.string.wk_private_failed_reason_storage,
            ByteSize.format(state.missingPacks.sumOf { it.installFootprintBytes }),
        )
        InstallError.IO -> stringResource(R.string.wk_private_failed_reason_io)
        InstallError.CANCELLED -> ""
    }
    return "$outcome $reason".trim()
}

/** One verb, on its own full-width line. A decision worth a gigabyte is not a chevron. */
@Composable
private fun PrivateModeAction(
    state: PrivateModeState,
    missingText: String,
    required: Boolean,
    onGoPrivate: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    when (state) {
        is PrivateModeState.Downloading, PrivateModeState.Installing, is PrivateModeState.Queued ->
            OutlinedButton(onCancel, modifier, shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.wk_private_cancel))
            }
        // On and Unavailable have nothing left to press: the state line says so, and what is
        // left to do with an installed bundle lives in the overflow.
        PrivateModeState.On, is PrivateModeState.Unavailable -> Unit
        // Retry and Leave it are both first-class answers: speech-only private mode is a
        // place the product is happy to leave someone, and a failure with one button is a
        // dead end for the user who does not want to try again.
        is PrivateModeState.Failed -> {
            FilledTonalButton(onGoPrivate, modifier, shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.wk_private_retry, missingText))
            }
            OutlinedButton(onDismissFailure, modifier, shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.wk_private_partial_dismiss))
            }
        }
        is PrivateModeState.PartlyOn -> FilledTonalButton(
            onGoPrivate, modifier, shape = MaterialTheme.shapes.large,
        ) { Text(stringResource(R.string.wk_private_partial_retry, missingText)) }
        PrivateModeState.Off -> FilledTonalButton(
            onGoPrivate, modifier, shape = MaterialTheme.shapes.large,
        ) {
            Text(
                if (required) stringResource(R.string.wk_private_required_go, missingText)
                else stringResource(R.string.wk_private_go, missingText),
            )
        }
        // Turned off with the models kept. §5.5.5: coming back is a preference write, so the
        // action quotes no size when there is nothing left to fetch.
        is PrivateModeState.OffWithModels -> FilledTonalButton(
            onGoPrivate, modifier, shape = MaterialTheme.shapes.large,
        ) {
            Text(
                if (state.missingPacks.isEmpty()) stringResource(R.string.wk_private_go_ready)
                else stringResource(R.string.wk_private_partial_retry, missingText),
            )
        }
    }
}

@Composable
private fun PrivateModeOverflow(
    canRemove: Boolean,
    canUseGoogle: Boolean,
    onRemove: () -> Unit,
    onUseGoogle: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                stringResource(R.string.wk_private_more),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wk_private_manage)) },
                onClick = {
                    expanded = false
                    SettingsDestination.navigateTo(SettingsDestination.VoiceModels)
                },
            )
            // The only place PREF_GOOGLE_VOICE is directly editable from this screen while
            // WaveKey's models stay installed.
            if (canUseGoogle) DropdownMenuItem(
                text = { Text(stringResource(R.string.wk_private_use_google)) },
                onClick = { expanded = false; onUseGoogle() },
            )
            if (canRemove) DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.wk_private_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { expanded = false; onRemove() },
            )
        }
    }
}

@Composable
private fun privateRowDescription(
    state: PrivateModeState,
    hasLocalSpeech: Boolean,
    missingText: String,
    installedText: String,
    storageText: String,
): String = when (state) {
    is PrivateModeState.Unavailable -> when (state.reason) {
        PrivateModeBlocker.LANGUAGE -> stringResource(R.string.wk_mode_private_unavailable_language)
        PrivateModeBlocker.ABI -> stringResource(R.string.wk_mode_private_unavailable_abi)
        PrivateModeBlocker.STORAGE ->
            stringResource(R.string.wk_mode_private_unavailable_storage, storageText)
    }
    PrivateModeState.On ->
        stringResource(R.string.wk_mode_private_on) + " " +
            stringResource(R.string.wk_private_installed, installedText)
    // Which half is installed decides the sentence. Speech-only and refiner-only are both
    // real end states and they say opposite things about where dictation runs.
    is PrivateModeState.PartlyOn ->
        if (hasLocalSpeech) stringResource(R.string.wk_mode_private_speech_only)
        else stringResource(R.string.wk_mode_private_refiner_only, missingText)
    is PrivateModeState.OffWithModels ->
        if (state.missingPacks.isEmpty()) stringResource(R.string.wk_mode_private_ready)
        else stringResource(R.string.wk_mode_private_ready_partial, missingText)
    else -> stringResource(R.string.wk_mode_private_missing, missingText)
}

/**
 * The disclosure, read off the backend that would actually run.
 *
 * It used to read the resolved state and the preference instead, which let it contradict the
 * status line immediately below it: on a phone with no recognizer of its own and the speech
 * pack installed, it announced that dictation still needs WaveKey's models directly above
 * "Dictation: WaveKey's model, on this phone." The rule is the same one the rest of this
 * feature follows — the copy follows the probe, and here the probe's answer is [backends].
 */
@Composable
private fun disclosure(
    backends: ActiveBackends,
    recognizer: SystemRecognizer,
): String = when (backends.dictation) {
    DictationBackend.WAVEKEY -> stringResource(R.string.wk_disclosure_private)
    DictationBackend.SYSTEM_ON_DEVICE -> stringResource(R.string.wk_disclosure_google_local)
    DictationBackend.SYSTEM_NETWORK -> stringResource(R.string.wk_disclosure_google)
    // Nothing can dictate: on a phone with no recognizer that is the sentence that explains
    // the whole screen, and nothing is being sent anywhere in the meantime.
    DictationBackend.NONE ->
        if (recognizer == SystemRecognizer.NONE) stringResource(R.string.wk_private_required)
        else stringResource(R.string.wk_disclosure_private)
}

/**
 * The header that keeps the Voice models screen and this control from disagreeing.
 *
 * It reads the resolved state rather than counting installed packs, because counting packs is
 * exactly how the two surfaces came to disagree: installed is not the same as selected, and
 * the control has known that since the engine preference became part of the state.
 */
fun privateModeHeader(state: PrivateModeState): Int = when (state) {
    PrivateModeState.On -> R.string.wk_private_header_on
    is PrivateModeState.PartlyOn -> R.string.wk_private_header_partly
    is PrivateModeState.OffWithModels ->
        if (state.missingPacks.isEmpty()) R.string.wk_private_header_ready
        else R.string.wk_private_header_off_models
    else -> R.string.wk_private_header_off
}
