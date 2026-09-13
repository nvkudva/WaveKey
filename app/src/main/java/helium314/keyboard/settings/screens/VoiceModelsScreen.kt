// SPDX-License-Identifier: GPL-3.0-only
//
// WaveKey. New file: the voice models screen — download, cancel, remove,
// and import a file the user already has.
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.settings.SpectrumTile
import com.vboard.app.models.ModelDownloadService
import com.vboard.app.voice.VoiceRuntime
import com.vboard.app.voice.voiceRuntimeOrNull
import com.vboard.core.model.ByteSize
import com.vboard.core.model.DownloadDecision
import com.vboard.core.model.DownloadPolicy
import com.vboard.core.model.InstallError
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelPack
import com.vboard.core.model.PackInstaller
import com.vboard.core.model.PackState
import helium314.keyboard.latin.R
import com.vboard.app.settings.SettingsRepository.Defaults as VoiceDefaults
import com.vboard.app.settings.SettingsRepository.Keys as VoiceKeys
import com.vboard.core.model.ModelKind
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PreferenceGroup
import helium314.keyboard.settings.preferences.PreferenceGroupContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One row per model pack, with whatever action that pack's state allows.
 *
 * Dictation needs several hundred megabytes of model, which is why this is a
 * screen rather than a switch: the user decides what to spend, when, and can get
 * it back. Import exists because someone on a metered connection or an
 * air-gapped device should not have to pull the same archive twice.
 */
@Composable
fun VoiceModelsScreen(
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice_models),
        settings = emptyList(),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            VoiceModelsSection()
        }
    }
}

/**
 * The model list itself. Its own screen still exists for the setup wizard, but
 * the voice screen shows this inline: the models are what that screen is about,
 * and a switch list under a locked feature reads as settings for nothing.
 */
@Composable
fun VoiceModelsSection(only: ModelKind? = null, inOwnGroup: Boolean = true) {
    val context = LocalContext.current
    val runtime = remember { voiceRuntimeOrNull(context) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (runtime == null) {
            Text(
                text = stringResource(R.string.voice_models_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        val scheduled by ModelDownloadService.observeScheduledWork(context)
            .collectAsState(initial = emptyList())
        val liveStates by ModelDownloadService.states.collectAsState()
        for (pack in ModelCatalog.packs.filter { only == null || it.kind == only }) {
            PackRow(
                inOwnGroup = inOwnGroup,
                pack = pack,
                runtime = runtime,
                liveState = liveStates[pack.id],
                queued = scheduled.any { it.packId == pack.id && it.waitingForNetwork },
                running = scheduled.any { it.packId == pack.id },
            )
        }
        if (only == null) Text(
            text = stringResource(R.string.voice_models_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun PackRow(
    inOwnGroup: Boolean = true,
    pack: ModelPack,
    runtime: VoiceRuntime,
    liveState: PackState?,
    queued: Boolean,
    running: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Disk is the durable answer; the live flow is empty after process death.
    var diskState by remember(pack.id) { mutableStateOf<PackState>(PackState.NotInstalled) }
    var message by remember(pack.id) { mutableStateOf<String?>(null) }
    // Set when the link is metered and the user has not agreed to spend data on
    // this download; the dialog names the real size before anything is enqueued.
    var confirmMeteredBytes by remember(pack.id) { mutableStateOf<Long?>(null) }
    var confirmRemove by remember(pack.id) { mutableStateOf(false) }

    /**
     * Never starts a download on cellular without asking. DownloadPolicy owns
     * that rule so it cannot be lost in a Compose callback.
     */
    fun requestDownload(meteredConsent: Boolean) {
        when (val decision = DownloadPolicy.decide(
            network = ModelDownloadService.networkState(context),
            meteredConsent = meteredConsent,
            bytes = pack.totalBytes,
        )) {
            is DownloadDecision.ConfirmMetered -> confirmMeteredBytes = decision.bytes
            is DownloadDecision.Enqueue -> {
                if (decision.allowMetered) ModelDownloadService.startAllowingMetered(context, pack.id)
                else ModelDownloadService.start(context, pack.id)
            }
        }
    }
    LaunchedEffect(pack.id, liveState) {
        diskState = withContext(Dispatchers.IO) { runtime.packInstaller.stateOf(pack) }
    }
    // Disk wins once nothing is scheduled: the worker's last published state is
    // what it *did*, and a pack it installed into a directory this process cannot
    // read must not keep reading "Installed" here while the mic says otherwise.
    val state = if (running || queued) liveState ?: diskState else diskState

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = context.getString(R.string.voice_models_importing)
            val result = importInto(context, runtime, pack, uri)
            message = context.getString(
                when (result) {
                    PackInstaller.ImportResult.STAGED -> R.string.voice_models_import_ok
                    PackInstaller.ImportResult.DIGEST_MISMATCH -> R.string.voice_models_import_wrong_file
                    PackInstaller.ImportResult.UNKNOWN_FILE -> R.string.voice_models_import_unknown_file
                    PackInstaller.ImportResult.ALREADY_INSTALLED -> R.string.voice_models_import_installed
                    PackInstaller.ImportResult.IO_ERROR -> R.string.voice_models_import_io
                },
            )
            if (result == PackInstaller.ImportResult.STAGED) {
                // Staged bytes still have to be extracted and finalized, which is
                // exactly what a download's last stage does — so run that. It is
                // enqueued without a network constraint on purpose: the files are
                // already here, so asking about mobile data would be asking about
                // bytes nobody is going to fetch.
                ModelDownloadService.startAllowingMetered(context, pack.id)
            }
            diskState = withContext(Dispatchers.IO) { runtime.packInstaller.stateOf(pack) }
        }
    }

    // Removing a pack throws away several hundred megabytes and, for a required
    // one, stops dictation working. The metered *download* already confirms; the
    // destructive half cannot ask for less.
    if (confirmRemove) {
        val size = ByteSize.format(pack.totalBytes)
        ConfirmationDialog(
            onDismissRequest = { confirmRemove = false },
            onConfirmed = {
                confirmRemove = false
                scope.launch {
                    withContext(Dispatchers.IO) { runtime.packInstaller.delete(pack) }
                    diskState = PackState.NotInstalled
                    message = context.getString(R.string.wk_models_removed, size)
                }
            },
            confirmButtonText = stringResource(R.string.voice_models_remove),
            title = { Text(pack.displayName) },
            content = {
                Text(
                    stringResource(
                        if (pack.required) R.string.wk_models_remove_required
                        else R.string.wk_models_remove_message,
                        size,
                    )
                )
            },
        )
    }

    confirmMeteredBytes?.let { bytes ->
        ConfirmationDialog(
            onDismissRequest = { confirmMeteredBytes = null },
            onConfirmed = {
                confirmMeteredBytes = null
                requestDownload(meteredConsent = true)
            },
            confirmButtonText = stringResource(R.string.voice_models_use_mobile_data),
            title = { Text(stringResource(R.string.voice_models_metered_title)) },
            content = {
                Text(stringResource(R.string.voice_models_metered_message, ByteSize.format(bytes)))
            },
        )
    }
    // One verb per card, on its own full-width line: a 482 MB download is a
    // decision, and a decision does not belong in the trailing slot of a list
    // row where a chevron would go. Everything else — the escape hatch, the
    // destructive one — lives in the overflow, so the primary slot never turns
    // into "remove" under a thumb that learned it meant "get".
    val overflow: @Composable () -> Unit = {
        var expanded by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painterResource(R.drawable.ic_more_vert),
                    stringResource(R.string.wk_models_more),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.voice_models_import)) },
                    onClick = { expanded = false; importer.launch(arrayOf("*/*")) },
                )
                if (state is PackState.Installed) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.voice_models_remove),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { expanded = false; confirmRemove = true },
                    )
                }
            }
        }
    }
    val primaryAction: @Composable () -> Unit = {
        when {
            running || state is PackState.Downloading || state is PackState.Verifying ->
                OutlinedButton(
                    onClick = { ModelDownloadService.cancel(context, pack.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) { Text(stringResource(R.string.voice_models_cancel)) }
            // Installed needs no button at all: the state line says so, and the
            // only thing left to do with it is in the overflow.
            state is PackState.Installed -> Unit
            else -> FilledTonalButton(
                onClick = { requestDownload(meteredConsent = false) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    if (state is PackState.Failed) stringResource(R.string.wk_models_retry)
                    else stringResource(R.string.wk_models_download_size, ByteSize.format(pack.totalBytes))
                )
            }
        }
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The key this pack powers, in the colours it wears on the
                // keyboard: the mic for speech, the AI fix glyph for the refiner.
                // It is described rather than decorative — the picture is what
                // ties a 482 MB download to the button the user presses, and a
                // screen reader gets none of that from the name.
                SpectrumTile(
                    icon = if (pack.kind == ModelKind.REFINER_LLM) R.drawable.ic_ai_fix
                        else R.drawable.sym_keyboard_voice_rounded,
                    contentDescription = stringResource(
                        if (pack.kind == ModelKind.REFINER_LLM) R.string.wk_models_tile_fix
                        else R.string.wk_models_tile_voice
                    ),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        pack.displayName,
                        // The pack is the most important object in its card, so it is
                        // not typographically smaller than the switches it governs.
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // Whether a pack gates the feature decides what the user has to
                    // do next, and a word inside a dim sentence is not where that
                    // belongs. A badge is read before the sentence is.
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (pack.required) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            stringResource(
                                if (pack.required) R.string.wk_models_required
                                else R.string.wk_models_optional
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pack.required) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                overflow()
            }
            Text(
                text = describe(context, pack, state, queued),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is PackState.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // WaveKey: an installed pack still has to be the one that runs.
            // Which engine and whether refinement is on both live elsewhere on
            // this screen, so say here whether this pack is actually in play.
            if (state is PackState.Installed) inUseLine(context, pack)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state is PackState.Downloading) {
                val progressText = describe(context, pack, state, queued)
                LinearProgressIndicator(
                    progress = { state.fraction.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        // A bar is a picture of a number. TalkBack is told the
                        // number, and told it again as it moves.
                        .semantics {
                            contentDescription = progressText
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(state.fraction.toFloat(), 0f..1f)
                        },
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            primaryAction()
        }
    }
    if (inOwnGroup) PreferenceGroup(content = body) else PreferenceGroupContent(content = body)
}

/** Streams the picked document into the installer's staging area. */
private suspend fun importInto(
    context: Context,
    runtime: VoiceRuntime,
    pack: ModelPack,
    uri: Uri,
): PackInstaller.ImportResult = withContext(Dispatchers.IO) {
    val name = displayName(context, uri) ?: return@withContext PackInstaller.ImportResult.UNKNOWN_FILE
    runCatching {
        runtime.packInstaller.importFile(pack, name) {
            context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("could not open $name")
        }
    }.getOrElse { PackInstaller.ImportResult.IO_ERROR }
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

private fun describe(context: Context, pack: ModelPack, state: PackState, queued: Boolean): String {
    val size = ByteSize.format(pack.totalBytes)
    return when {
        queued && state !is PackState.Downloading -> context.getString(R.string.voice_models_queued)
        state is PackState.Downloading -> context.getString(
            R.string.voice_models_downloading,
            (state.fraction * 100).toInt(),
            size,
        )
        state is PackState.Verifying -> context.getString(R.string.voice_models_verifying)
        state is PackState.Installed -> context.getString(R.string.voice_models_installed, size)
        state is PackState.Failed -> context.getString(
            when (state.error) {
                InstallError.NETWORK -> R.string.voice_models_failed_network
                InstallError.CHECKSUM_MISMATCH -> R.string.voice_models_failed_checksum
                InstallError.INSUFFICIENT_STORAGE -> R.string.voice_models_failed_storage
                InstallError.CANCELLED -> R.string.voice_models_failed_cancelled
                InstallError.IO -> R.string.voice_models_failed_io
            },
        )
        // Required and optional are badges above this line now, so all it has
        // left to say is what the download costs.
        else -> context.getString(R.string.wk_models_size, size)
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VoiceModelsScreen { }
        }
    }
}

/**
 * Whether an installed pack is the one currently doing the work.
 *
 * Composable, and it reads `prefChanged` on purpose. Switching engine or
 * turning refinement off writes a preference, which is not Compose state:
 * without something observable in this scope the line kept its old text until
 * the screen was left and entered again, which is what made a live setting
 * look broken.
 */
@Composable
private fun inUseLine(context: android.content.Context, pack: ModelPack): String? {
    val changed = (context.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((changed?.value ?: 0) < 0) return null
    val prefs = context.prefs()
    return when (pack.kind) {
        ModelKind.FINAL_ASR, ModelKind.STREAMING_ASR ->
            if (PrivacyBreakingSettings.googleVoiceEnabled(prefs)) context.getString(R.string.wk_engine_not_in_use)
            else context.getString(R.string.wk_engine_in_use)
        ModelKind.REFINER_LLM ->
            if (prefs.getBoolean(VoiceKeys.LLM_REFINE, VoiceDefaults.LLM_REFINE)) context.getString(R.string.wk_refiner_in_use)
            else context.getString(R.string.wk_refiner_not_in_use)
    }
}
