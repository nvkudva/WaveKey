// SPDX-License-Identifier: GPL-3.0-only
package com.vboard.app.llm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.vboard.core.model.DeviceAi

/**
 * Whether this device has a text AI of its own — Gemini Nano, through AICore — that WaveKey
 * could refine dictation with instead of downloading a refiner.
 *
 * ## What this actually answers, and what it does not
 *
 * The probe is real and it is deliberately pessimistic. It asks three things in order: is the
 * OS new enough for AICore at all, is the AICore package installed and enabled, and is there
 * a client library in this build that can ask AICore for a feature status. The third question
 * is the one that decides the answer today: WaveKey ships no ML Kit GenAI client, so
 * [probe] returns [DeviceAi.UNAVAILABLE] with [Detail.NO_CLIENT] on every device, including
 * ones that do have Nano.
 *
 * That is the honest answer rather than a placeholder. There is no Nano inference path in
 * this build: nothing here can produce refined text. Reporting [DeviceAi.AVAILABLE] would
 * make the settings screen promise a backend that does not exist, and the fallback — the
 * deterministic cleanup pipeline, which needs no model at all — is what would silently run
 * instead. `tools/nanocheck` is the probe that talks to AICore for real, and on the hardware
 * this was developed against it reports UNAVAILABLE.
 *
 * Adding the backend later means adding the client dependency and one `refine` implementation
 * behind [Detail.REPORTED]; every caller of this file already handles all four states, so
 * nothing above it changes.
 */
object DeviceAiProbe {

    /** Why the answer is what it is, so a settings line can say something true. */
    enum class Detail {
        /** Below the API level AICore exists on. */
        OS_TOO_OLD,

        /** AICore is not installed, or the user disabled it. */
        NO_AICORE,

        /** AICore is here, but this build has no client that can query or drive it. */
        NO_CLIENT,

        /** A client answered; the capability is whatever it said. */
        REPORTED,
    }

    data class Result(val capability: DeviceAi, val detail: Detail)

    /** AICore ships with Android 14 and is not backported. */
    private const val MIN_SDK_FOR_AICORE = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private const val AICORE_PACKAGE = "com.google.android.aicore"

    /**
     * The ML Kit GenAI entry point, if a build ever carries it. Looked up by name rather than
     * linked against: the probe has to run in a build that does not ship it, and a missing
     * class at that point is an answer, not a crash.
     */
    private const val GENAI_CLIENT = "com.google.mlkit.genai.prompt.Generation"

    fun probe(context: Context): Result {
        if (Build.VERSION.SDK_INT < MIN_SDK_FOR_AICORE) {
            return Result(DeviceAi.UNAVAILABLE, Detail.OS_TOO_OLD)
        }
        if (!aiCoreInstalled(context)) return Result(DeviceAi.UNAVAILABLE, Detail.NO_AICORE)
        if (!clientPresent()) return Result(DeviceAi.UNAVAILABLE, Detail.NO_CLIENT)
        // Unreachable in this build; kept so the shape of the answer is the one a real client
        // would fill in, rather than a branch someone has to invent later.
        return Result(DeviceAi.UNAVAILABLE, Detail.REPORTED)
    }

    /** The capability alone, for callers that only need to know whether it can run. */
    fun capability(context: Context): DeviceAi = probe(context).capability

    private fun aiCoreInstalled(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(AICORE_PACKAGE, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        // A package manager that refuses to answer is not evidence of a feature.
        Log.w(TAG, "could not ask about AICore", e)
        false
    }

    private fun clientPresent(): Boolean = try {
        Class.forName(GENAI_CLIENT, false, DeviceAiProbe::class.java.classLoader)
        true
    } catch (e: ClassNotFoundException) {
        false
    } catch (e: LinkageError) {
        false
    }

    private const val TAG = "WKDeviceAi"
}
