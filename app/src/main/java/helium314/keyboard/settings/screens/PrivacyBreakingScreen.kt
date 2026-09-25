// SPDX-License-Identifier: GPL-3.0-only
//
// WaveKey. New file: the two features that break the fork's
// on-device-only promise live here, behind their own switches and nowhere else.
// Both default to off; nothing leaves the device until one is turned on.
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.Surface
import com.vboard.core.model.googleVoiceDefault
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.voice.PrivateModePrefs

object PrivacyBreakingSettings {
    /** Dictation goes to the platform recognizer instead of WaveKey's own models. */
    const val PREF_GOOGLE_VOICE = "pref_google_voice_typing"

    /**
     * The shipped default: the platform recognizer, so a fresh install dictates on the first
     * mic press with nothing downloaded.
     *
     * It only applies once [PREF_DEFAULT_MIGRATED] says the one-time migration has looked at
     * this install — see [googleVoiceEnabled]. An existing install that already owns WaveKey's
     * models must not start sending audio to a network recognizer because a default changed,
     * and a read that happens before the migration has run would do exactly that.
     */
    const val DEFAULT_GOOGLE_VOICE = true

    /** Written once the one-time default migration has decided about this install. */
    const val PREF_DEFAULT_MIGRATED = "pref_google_voice_default_migrated"

    /**
     * Set when the user picked the recognizer themselves, in the private mode detail. It stops
     * the automatic switch that follows an install from overruling them.
     */
    const val PREF_GOOGLE_VOICE_MANUAL = "pref_google_voice_manual"

    /** The user has been told that audio goes to the device's speech service, and continued. */
    const val PREF_NETWORK_VOICE_CONSENT = "pref_network_voice_consent"

    /** Set the first time a dictation session starts, as evidence the install is in use. */
    const val PREF_VOICE_SESSION_SEEN = "pref_voice_session_seen"

    /** Offer Google Password Manager (inline autofill) fills on login fields. */
    const val PREF_GOOGLE_PASSWORD_MANAGER = "pref_google_password_manager"
    const val DEFAULT_GOOGLE_PASSWORD_MANAGER = false

    /**
     * Whether the platform recognizer is the one that runs.
     *
     * The fallback is not the constant: before the migration has run, an install with no
     * stored answer is read as the old behaviour. That closes the window between process
     * start and the migration finishing, in which a mic press would otherwise adopt the new
     * default on an install that was never allowed to.
     */
    fun googleVoiceEnabled(prefs: SharedPreferences) =
        prefs.getBoolean(
            PREF_GOOGLE_VOICE,
            googleVoiceDefault(DEFAULT_GOOGLE_VOICE, defaultDecided(prefs)),
        )

    /** True once the one-time migration has looked at this install. */
    fun defaultDecided(prefs: SharedPreferences) = prefs.getBoolean(PREF_DEFAULT_MIGRATED, false)

    fun passwordManagerEnabled(prefs: SharedPreferences) =
        prefs.getBoolean(PREF_GOOGLE_PASSWORD_MANAGER, DEFAULT_GOOGLE_PASSWORD_MANAGER)
}

fun createPrivacyBreakingSettings(context: Context) = listOf(
    Setting(
        context, PrivacyBreakingSettings.PREF_GOOGLE_VOICE,
        R.string.privacy_breaking_google_voice, R.string.privacy_breaking_google_voice_summary,
    ) { setting ->
        // Flipping this switch is the user choosing an engine, so it records the choice the
        // same way every other engine control does. Writing the bare key left the choice
        // indistinguishable from a default, and the engine sync at the next app start read it
        // as one and overwrote it.
        SwitchPreference(setting, PrivacyBreakingSettings.DEFAULT_GOOGLE_VOICE) { google ->
            PrivateModePrefs.setGoogleVoice(context.prefs(), google = google, manual = true)
        }
    },
    Setting(
        context, PrivacyBreakingSettings.PREF_GOOGLE_PASSWORD_MANAGER,
        R.string.privacy_breaking_password_manager, R.string.privacy_breaking_password_manager_summary,
    ) {
        SwitchPreference(it, PrivacyBreakingSettings.DEFAULT_GOOGLE_PASSWORD_MANAGER)
    },
)
