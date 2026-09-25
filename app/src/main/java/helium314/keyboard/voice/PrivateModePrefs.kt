// SPDX-License-Identifier: GPL-3.0-only
//
// WaveKey. New file: the preference side of private mode — the one-time
// migration that decides whether this install may adopt the shipped default,
// the automatic engine switch that follows an install, and the record of the
// network-recognizer disclosure.
package helium314.keyboard.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.vboard.app.models.PrivateModeDownloads
import com.vboard.app.voice.voiceRuntimeOrNull
import com.vboard.core.model.GoogleVoiceMigration
import com.vboard.core.model.InstallHistory
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.PackState
import com.vboard.core.model.PrivateModeBundle
import com.vboard.core.model.migrateGoogleVoiceDefault
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.screens.PrivacyBreakingSettings
import java.util.Locale

/**
 * Everything private mode has to write to preferences, in one place.
 *
 * The decisions themselves are `:core`'s ([migrateGoogleVoiceDefault],
 * `PrivateMode.backends`); this file reports the facts those need and applies the answers.
 */
object PrivateModePrefs {

    /**
     * Decides once, per install, whether the shipped default may apply here.
     *
     * Reads the model root, so call it off the main thread. It deliberately does nothing when
     * the voice runtime is missing: without it there is no way to know whether this install
     * owns WaveKey's models, and marking the decision made on no evidence is the one outcome
     * that could move an existing user onto a network recognizer silently.
     */
    fun migrateDefaultOnce(context: Context) {
        val prefs = context.prefs()
        if (PrivacyBreakingSettings.defaultDecided(prefs)) return
        val runtime = voiceRuntimeOrNull(context) ?: run {
            Log.w(TAG, "no voice runtime; leaving the dictation default undecided")
            return
        }
        val installed = ModelCatalog.packs.any {
            runCatching { runtime.packInstaller.stateOf(it) }.getOrNull() == PackState.Installed
        }
        val outcome = migrateGoogleVoiceDefault(
            InstallHistory(
                choiceRecorded = prefs.contains(PrivacyBreakingSettings.PREF_GOOGLE_VOICE),
                anyWaveKeyPackInstalled = installed,
                voiceSessionRecorded = prefs.getBoolean(
                    PrivacyBreakingSettings.PREF_VOICE_SESSION_SEEN, false,
                ),
            ),
        )
        prefs.edit {
            if (outcome == GoogleVoiceMigration.PIN_ON_DEVICE) {
                // Explicit, not implicit: the value is now this install's own answer, so no
                // later default change can reach it either.
                putBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE, false)
                putBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE_MANUAL, true)
            }
            putBoolean(PrivacyBreakingSettings.PREF_DEFAULT_MIGRATED, true)
        }
        Log.i(TAG, "dictation default decided: $outcome")
    }

    /**
     * Switches dictation onto WaveKey's recognizer the moment its pack is installed.
     *
     * The switch is not gradual and it is not a second decision: the user asked for private
     * mode, and the engine is a consequence of what is installed. It stops at
     * [PrivacyBreakingSettings.PREF_GOOGLE_VOICE_MANUAL], which is the one row where the user
     * says they want the platform recognizer while keeping the models.
     *
     * Reads the model root; call it off the main thread.
     */
    fun syncEngineToInstalledPacks(context: Context, language: String) {
        val prefs = context.prefs()
        if (prefs.getBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE_MANUAL, false)) return
        val runtime = voiceRuntimeOrNull(context) ?: return
        val bundle = PrivateModeDownloads.bundleFor(language)
        val installedIds = bundle.packs
            .filter {
                runCatching { runtime.packInstaller.stateOf(it) }.getOrNull() == PackState.Installed
            }
            .mapTo(mutableSetOf()) { it.id }
        if (!bundle.hasLocalSpeech(installedIds)) return
        if (!PrivacyBreakingSettings.googleVoiceEnabled(prefs)) return
        prefs.edit { putBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE, false) }
        Log.i(TAG, "recognizer pack installed; dictation switched to WaveKey's own")
    }

    /**
     * Applies the user's own choice of dictation engine, from the private mode control.
     *
     * [manual] marks the platform recognizer as chosen rather than defaulted, so an installed
     * pack does not switch away from it again. Going private clears that mark, and drops the
     * network-recognizer disclosure with it: turning private mode off later has to ask again
     * rather than trade on an answer given to a different question.
     */
    fun setGoogleVoice(prefs: SharedPreferences, google: Boolean, manual: Boolean) {
        prefs.edit {
            putBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE, google)
            putBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE_MANUAL, manual)
            if (!google) remove(PrivacyBreakingSettings.PREF_NETWORK_VOICE_CONSENT)
        }
    }

    fun networkConsentRecorded(prefs: SharedPreferences) =
        prefs.getBoolean(PrivacyBreakingSettings.PREF_NETWORK_VOICE_CONSENT, false)

    fun recordNetworkConsent(prefs: SharedPreferences) {
        prefs.edit { putBoolean(PrivacyBreakingSettings.PREF_NETWORK_VOICE_CONSENT, true) }
    }

    /**
     * Notes that this install has dictated, which is what the migration reads on the next
     * upgrade. A boolean, not a count: how often is not the question, and a keyboard does not
     * need to keep a tally of how much its user talks.
     */
    fun recordVoiceSession(prefs: SharedPreferences) {
        if (prefs.getBoolean(PrivacyBreakingSettings.PREF_VOICE_SESSION_SEEN, false)) return
        prefs.edit { putBoolean(PrivacyBreakingSettings.PREF_VOICE_SESSION_SEEN, true) }
    }

    /** The bundle for the language the keyboard is currently typing in. */
    fun bundleFor(language: String): PrivateModeBundle = PrivateModeDownloads.bundleFor(language)

    /**
     * The language private mode is about right now: the active subtype's.
     *
     * Falls back to the system locale where the subtype manager is not up yet — at app start,
     * before the first keyboard is shown. Getting it wrong there costs an engine sync that
     * runs again the next time the Voice screen is opened, not a wrong download.
     */
    fun dictationLanguage(): String =
        runCatching { RichInputMethodManager.getInstance().currentSubtypeLocale.language }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().language

    private const val TAG = "WKPrivateMode"
}
