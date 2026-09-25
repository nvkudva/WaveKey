// SPDX-License-Identifier: GPL-3.0-only
//
// WaveKey. New file: the one-time disclosure shown before the first mic
// press that would send audio to the device's speech service.
package helium314.keyboard.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsDestination

/**
 * WaveKey: states that dictation audio goes to the device's speech service, before any audio
 * is captured.
 *
 * It exists because setup can be skipped, and on a device whose only recognizer is the
 * network one the first mic press would otherwise be the first time the user found out. An
 * input method cannot show a dialog of its own with two real answers — the voice strip has
 * room for one action — so this is an activity, the same shape as
 * [MicPermissionActivity]: it opens, asks once, and closes.
 *
 * Nothing records a decline. Declining leaves the install exactly as it was, so the next mic
 * press asks again rather than silently starting to send audio.
 */
class NetworkVoiceConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.wk_consent_network_title)
            .setMessage(R.string.wk_disclosure_google)
            .setPositiveButton(R.string.wk_consent_continue) { _, _ ->
                PrivateModePrefs.recordNetworkConsent(prefs())
                finish()
            }
            .setNegativeButton(R.string.wk_consent_go_private) { _, _ ->
                openVoiceSettings()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openVoiceSettings() {
        SettingsDestination.navigateTo(SettingsDestination.Voice)
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "could not open voice settings", it) }
    }

    companion object {
        private const val TAG = "WKVoiceConsent"

        /** Shows the disclosure from a context that is not an activity — the IME. */
        fun launch(context: Context) {
            val intent = Intent(context, NetworkVoiceConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "could not show the dictation disclosure", it) }
        }
    }
}
