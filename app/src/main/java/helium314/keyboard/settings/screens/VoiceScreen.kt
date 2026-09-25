// SPDX-License-Identifier: GPL-3.0-only
//
// WaveKey: voice settings, inside HeliBoard's settings rather than
// carried over from VBoard's own settings app (W2.5). The keys and defaults are
// :voice's — see com.vboard.app.settings.SettingsRepository — so the screen and
// the dictation path cannot disagree about what a switch means.
package helium314.keyboard.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.core.content.edit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.vboard.app.settings.SettingsRepository.Defaults as VoiceDefaults
import com.vboard.app.settings.SettingsRepository.Keys as VoiceKeys
import com.vboard.app.llm.DeviceAiProbe
import com.vboard.app.llm.refinerAbiSupported
import com.vboard.app.voice.voiceRuntimeOrNull
import com.vboard.core.model.ByteSize
import com.vboard.core.model.DeviceAi
import com.vboard.core.model.SystemRecognizer
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.session.SilenceTimeout
import helium314.keyboard.latin.R
import helium314.keyboard.voice.GoogleVoiceSession
import helium314.keyboard.voice.PrivateModePrefs
import helium314.keyboard.voice.VoiceStripView
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsSections
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.PreferenceGroup
import helium314.keyboard.settings.preferences.PreferenceGroupDivider
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun VoiceScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    // Raw mode is the verbatim escape hatch: with it on, none of the cleanup
    // switches below do anything, so they are hidden rather than left lying.
    val raw = prefs.getBoolean(VoiceKeys.RAW_TRANSCRIPT, VoiceDefaults.RAW_TRANSCRIPT)
    // Gemini Nano's absence affects text cleanup only, so it is said here rather
    // than as an asterisk on the mode rows: the deterministic pass still runs,
    // and WaveKey's own model is what adds the rest.
    val nanoAbsent = DeviceAiProbe.capability(LocalContext.current) != DeviceAi.AVAILABLE
    // Grouped by what each setting acts on: the speech-to-text half, then the
    // clean-up half, then the two rows that belong to neither.
    val speech = listOfNotNull(
        VoiceKeys.SILENCE_TIMEOUT,
        VoiceKeys.PROVISIONAL_COMMIT,
        VoiceStripView.SHOW_MINIMIZE_KEY,
    )
    // LiteRT-LM has no 32-bit ARM build, so on those installs the refiner can
    // never run. Offering a switch that silently does nothing is worse than
    // offering none.
    val correction = listOfNotNull(
        VoiceKeys.LLM_REFINE.takeIf { refinerAbiSupported },
        VoiceKeys.REFINEMENT_JOURNAL.takeIf { refinerAbiSupported },
        VoiceKeys.REFINEMENT_JOURNAL_COPY.takeIf { refinerAbiSupported },
    )
    // Raw transcript overrides every switch below it. It used to sit at the
    // bottom of the screen and delete them, so rows vanished with no visible
    // cause; it leads them now, and they read as unavailable instead.
    val cleanup = listOf(
        VoiceKeys.RAW_TRANSCRIPT,
        VoiceKeys.AUTO_CAP,
        VoiceKeys.SELF_CORRECTIONS,
        VoiceKeys.REMOVE_FILLERS,
        VoiceKeys.AGGRESSIVE_FILLERS,
        VoiceKeys.AUTO_PUNCTUATE,
        VoiceKeys.SPOKEN_COMMANDS,
    )
    val other = listOf(VoiceKeys.TELEMETRY)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice),
        // Registered so the settings search resolves them, even though the
        // screen draws its own layout rather than a plain list.
        settings = speech + correction + cleanup + other,
        content = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
            ) {
                // Where dictation and text cleanup run, as one decision, above
                // everything it governs. The per-pack rows are not here: they are
                // maintenance and they live on the Voice models screen, which this
                // block's overflow reaches. The screen holds the decision; that
                // screen holds the parts.
                PreferenceCategory(stringResource(R.string.wk_cat_processing))
                PreferenceGroup {
                    PrivateModeSection()
                    PreferenceGroupDivider()
                    SettingsSections(speech, inOwnGroup = false)
                }

                // What happens to the text afterwards. The cleanup below is
                // deterministic and runs with no model at all, so it does not
                // belong under a heading that implies a download gates it.
                PreferenceCategory(stringResource(R.string.wk_engine_title))
                PreferenceGroup {
                    if (nanoAbsent) Text(
                        stringResource(R.string.wk_nano_absent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SettingsSections(correction, inOwnGroup = false)
                }

                PreferenceCategory(stringResource(R.string.wk_cat_cleanup))
                PreferenceGroup {
                    SettingsSections(listOf(VoiceKeys.RAW_TRANSCRIPT), inOwnGroup = false)
                    PreferenceGroupDivider()
                    Dimmed(!raw) {
                        SettingsSections(cleanup.drop(1), inOwnGroup = false)
                    }
                }

                PreferenceCategory(stringResource(R.string.wk_cat_other))
                SettingsSections(other)
            }
        },
    )
}

/**
 * One row of the mode chooser.
 *
 * A disabled row keeps its text. `alpha(0.5f)` alone drops it below contrast minimums, so the
 * reason it is unavailable lives in the description, which TalkBack reads regardless of alpha.
 */
@Composable
internal fun EngineOption(
    name: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 0.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

fun createVoiceSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.VOICE_MODELS, R.string.settings_screen_voice_models, R.string.voice_models_summary) {
        // WaveKey: the models are the gate on the whole feature, so this row
        // answers "can I dictate right now?" before it offers to take you
        // somewhere (docs/settings-ia.md).
        val ctx = LocalContext.current
        val runtime = voiceRuntimeOrNull(ctx)
        val ready = runtime?.modelStore?.dictationReady(runtime.packInstaller) == true
        // The size is the private mode bundle's, so this row and the control above
        // it can never quote different numbers. It is no longer a precondition:
        // nothing here presents a download as the price of using the keyboard.
        val needed = ByteSize.format(
            PrivateModePrefs.bundleFor(PrivateModePrefs.dictationLanguage()).downloadBytes(emptySet())
        )
        Card(
            onClick = { SettingsDestination.navigateTo(SettingsDestination.VoiceModels) },
            colors = CardDefaults.cardColors(
                // The urgent tint is only for a phone where dictation genuinely
                // cannot run. With the platform recognizer as the default, a fresh
                // install is not broken, and the card must not perform urgency for
                // a download the user does not need.
                containerColor =
                    if (ready || GoogleVoiceSession.systemRecognizer(ctx) != SystemRecognizer.NONE)
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    else MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_settings_voice), null, Modifier.size(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (ready) R.string.settings_door_voice_ready
                            else R.string.settings_door_voice_no_models
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (ready) stringResource(R.string.wk_models_manage)
                        else stringResource(R.string.wk_models_download, needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NextScreenIcon()
            }
        }
    },
    Setting(
        context, VoiceStripView.SHOW_MINIMIZE_KEY,
        R.string.voice_minimize_key, R.string.voice_minimize_key_summary,
    ) {
        SwitchPreference(it, VoiceStripView.DEFAULT_SHOW_MINIMIZE_KEY)
    },
    Setting(context, VoiceKeys.SILENCE_TIMEOUT, R.string.voice_silence_timeout, R.string.voice_silence_timeout_summary) { setting ->
        val ctx = LocalContext.current
        ListPreference(
            setting,
            items = SilenceTimeout.entries.map { timeout ->
                val label = when (timeout) {
                    SilenceTimeout.OFF -> ctx.getString(R.string.voice_silence_timeout_off)
                    else -> ctx.getString(
                        R.string.voice_silence_timeout_seconds,
                        (timeout.millis ?: 0L) / 1000L,
                    )
                }
                label to timeout.name
            },
            default = VoiceDefaults.SILENCE_TIMEOUT.name,
        )
    },
    Setting(context, VoiceKeys.RAW_TRANSCRIPT, R.string.voice_raw_transcript, R.string.voice_raw_transcript_summary) {
        SwitchPreference(it, VoiceDefaults.RAW_TRANSCRIPT)
    },
    Setting(context, VoiceKeys.REMOVE_FILLERS, R.string.voice_remove_fillers, R.string.voice_remove_fillers_summary) {
        SwitchPreference(it, VoiceDefaults.REMOVE_FILLERS)
    },
    Setting(context, VoiceKeys.AGGRESSIVE_FILLERS, R.string.voice_aggressive_fillers, R.string.voice_aggressive_fillers_summary) {
        SwitchPreference(it, VoiceDefaults.AGGRESSIVE_FILLERS)
    },
    Setting(context, VoiceKeys.SELF_CORRECTIONS, R.string.voice_self_corrections, R.string.voice_self_corrections_summary) {
        SwitchPreference(it, VoiceDefaults.SELF_CORRECTIONS)
    },
    Setting(context, VoiceKeys.AUTO_PUNCTUATE, R.string.voice_auto_punctuate, R.string.voice_auto_punctuate_summary) {
        SwitchPreference(it, VoiceDefaults.AUTO_PUNCTUATE)
    },
    Setting(context, VoiceKeys.AUTO_CAP, R.string.voice_auto_capitalize, R.string.voice_auto_capitalize_summary) {
        SwitchPreference(it, VoiceDefaults.AUTO_CAP)
    },
    Setting(context, VoiceKeys.SPOKEN_COMMANDS, R.string.voice_spoken_commands, R.string.voice_spoken_commands_summary) {
        SwitchPreference(it, VoiceDefaults.SPOKEN_COMMANDS)
    },
    Setting(context, VoiceKeys.PROVISIONAL_COMMIT, R.string.voice_provisional_commit, R.string.voice_provisional_commit_summary) {
        SwitchPreference(it, VoiceDefaults.PROVISIONAL_COMMIT)
    },
    Setting(context, VoiceKeys.LLM_REFINE, R.string.voice_llm_refine, R.string.voice_llm_refine_summary) {
        SwitchPreference(it, VoiceDefaults.LLM_REFINE)
    },
    Setting(
        context,
        VoiceKeys.REFINEMENT_JOURNAL,
        R.string.voice_refinement_journal,
        R.string.voice_refinement_journal_summary,
    ) { setting ->
        val ctx = LocalContext.current
        val journal = voiceRuntimeOrNull(ctx)?.refinementJournal
        val kept = journal?.size ?: 0
        SwitchPreference(
            name = setting.title,
            key = setting.key,
            default = VoiceDefaults.REFINEMENT_JOURNAL,
            description = if (kept == 0) setting.description else ctx.getString(
                R.string.voice_refinement_journal_kept,
                kept,
            ),
            // Off means gone. A record of what you said is not something to
            // leave sitting in memory after you have said to stop keeping it.
            onCheckedChange = { enabled -> if (!enabled) journal?.clear() },
        )
    },
    Setting(
        context,
        VoiceKeys.REFINEMENT_JOURNAL_COPY,
        R.string.voice_refinement_journal_copy,
        R.string.voice_refinement_journal_copy_summary,
    ) { setting ->
        val ctx = LocalContext.current
        val journal = voiceRuntimeOrNull(ctx)?.refinementJournal
        val kept = journal?.size ?: 0
        Preference(
            name = setting.title,
            description = if (kept == 0) setting.description else ctx.getString(
                R.string.voice_refinement_journal_kept,
                kept,
            ),
            onClick = {
                if (journal != null && kept > 0) {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("WaveKey refinements", journal.export()),
                    )
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.voice_refinement_journal_copied, kept),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    },
    Setting(context, VoiceKeys.TELEMETRY, R.string.voice_telemetry, R.string.voice_telemetry_summary) { setting ->
        val ctx = LocalContext.current
        // W7.3: whoever is asked to turn measurement on gets to see what it
        // measured. The numbers live in the running IME and vanish with it.
        val snapshot = voiceRuntimeOrNull(ctx)?.metrics?.snapshot()
        val rate = snapshot?.sendReadyRate
        SwitchPreference(
            name = setting.title,
            key = setting.key,
            default = VoiceDefaults.TELEMETRY,
            description = if (rate == null) setting.description else ctx.getString(
                R.string.voice_telemetry_measured,
                (rate * 100).toInt(),
                snapshot.utterances,
                snapshot.meanTimeToSendReadyMs / 1000.0,
            ),
        )
    },
)

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VoiceScreen(onClickBack = { })
        }
    }
}
