// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.settings.screens.PrivacyBreakingSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The dictation engine preference, and the marker that says whose answer it is.
 *
 * The marker is what separates "the user picked the platform recognizer" from "nobody has
 * picked anything yet, so the shipped default applies". Without it the engine sync that runs
 * at every app start cannot tell the two apart, and an install whose owner explicitly chose
 * Google had that choice overwritten on the next launch — which is the one thing this whole
 * preference exists to prevent happening in either direction.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateModePrefsTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("private-mode-prefs-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test
    fun `choosing the platform recognizer records the choice as the user's own`() {
        PrivateModePrefs.setGoogleVoice(prefs, google = true, manual = true)

        assertTrue(
            "the engine preference must be set",
            prefs.getBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE, false),
        )
        assertTrue(
            "without the marker the next engine sync reads this as a default and overwrites it",
            prefs.getBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE_MANUAL, false),
        )
    }

    @Test
    fun `going private clears the marker and the network disclosure with it`() {
        PrivateModePrefs.setGoogleVoice(prefs, google = true, manual = true)
        PrivateModePrefs.recordNetworkConsent(prefs)

        PrivateModePrefs.setGoogleVoice(prefs, google = false, manual = false)

        assertFalse(
            "the local engine is not a manual choice of the platform recognizer",
            prefs.getBoolean(PrivacyBreakingSettings.PREF_GOOGLE_VOICE_MANUAL, false),
        )
        assertFalse(
            "turning private mode off later has to ask about the network again",
            PrivateModePrefs.networkConsentRecorded(prefs),
        )
    }

    /**
     * The switch on the privacy screen is a second way to choose an engine, and it used to
     * write the bare key through the generic preference row — no marker, so the engine sync
     * treated the choice as a default and reverted it at the next app start. This asserts the
     * switch still hands the write to the one place that records intent, because nothing about
     * a generic `SwitchPreference` makes that visible at the call site.
     */
    @Test
    fun `the privacy screen's engine switch writes through the choice recorder`() {
        val source = File(
            "src/main/java/helium314/keyboard/settings/screens/PrivacyBreakingScreen.kt",
        )
        assertTrue("source not found at ${source.absolutePath}", source.exists())
        val setting = source.readText()
            .substringAfter("PrivacyBreakingSettings.PREF_GOOGLE_VOICE,")
            .substringBefore("PrivacyBreakingSettings.PREF_GOOGLE_PASSWORD_MANAGER")
        assertTrue(
            "the Google voice switch must write through PrivateModePrefs.setGoogleVoice so " +
                "the choice is marked as the user's own",
            setting.contains("PrivateModePrefs.setGoogleVoice"),
        )
    }

    /**
     * Every write of the key goes through this one object. A second writer is how the marker
     * gets left behind, and the marker being left behind is invisible until an app restart.
     */
    @Test
    fun `nothing outside PrivateModePrefs writes the engine preference directly`() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "PrivateModePrefs.kt" }
            .filter { file ->
                file.readText().lineSequence().any {
                    it.contains("putBoolean") && it.contains("PREF_GOOGLE_VOICE") &&
                        !it.contains("PREF_GOOGLE_VOICE_MANUAL")
                }
            }
            .map { it.name }
            .toList()
        assertTrue(
            "these write the engine preference without recording whose choice it is: $offenders",
            offenders.isEmpty(),
        )
    }
}
