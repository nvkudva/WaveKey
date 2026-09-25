// SPDX-License-Identifier: GPL-3.0-only
package com.vboard.app.models

import android.content.Context
import com.vboard.app.llm.DeviceAiProbe
import com.vboard.app.llm.refinerAbiSupported
import com.vboard.core.model.DeviceCapabilities
import com.vboard.core.model.ModelPack
import com.vboard.core.model.PackInstaller
import com.vboard.core.model.PackState
import com.vboard.core.model.PrivateModeBundle
import com.vboard.core.model.SystemRecognizer
import java.io.File

/**
 * The Android half of private mode: it turns a bundle into download requests, cancellations
 * and deletions, and it reports the device facts the pure resolver needs.
 *
 * It owns no policy. Which packs are in the bundle, which state the control shows and which
 * backend runs are all decided in `:core`; this file exists so the UI does not have to know
 * that a bundle is several WorkManager requests, and so "cancel" means all of them.
 */
object PrivateModeDownloads {

    /**
     * The bundle for [language] on this device. The refiner is dropped where its runtime has
     * no build, which is what makes the figures on a 32-bit phone correct for free.
     */
    fun bundleFor(language: String): PrivateModeBundle =
        PrivateModeBundle.of(language, refinerAbiSupported)

    /**
     * What the device can do, probed rather than assumed.
     *
     * The recognizer half is the caller's to report: the platform `SpeechRecognizer` lives in
     * the keyboard module, which is where the session that uses it lives too.
     */
    fun capabilities(context: Context, recognizer: SystemRecognizer): DeviceCapabilities =
        DeviceCapabilities(
            recognizer = recognizer,
            deviceAi = DeviceAiProbe.capability(context),
            refinerAbiSupported = refinerAbiSupported,
        )

    /** Disk state per bundle pack. Call off the main thread: it stats the model root. */
    fun diskStates(installer: PackInstaller, bundle: PrivateModeBundle): Map<String, PackState> =
        bundle.packs.associate { it.id to installer.stateOf(it) }

    /**
     * Enqueues every pack that is not installed yet.
     *
     * Only the missing ones: a retry after a partial failure must not re-download the half
     * the user already paid for, and re-enqueuing an installed pack would put its row back
     * through verification for nothing.
     */
    fun start(
        context: Context,
        bundle: PrivateModeBundle,
        installedPackIds: Set<String>,
        allowMetered: Boolean,
    ) {
        for (pack in bundle.missingPacks(installedPackIds)) {
            if (allowMetered) ModelDownloadService.startAllowingMetered(context, pack.id)
            else ModelDownloadService.start(context, pack.id)
        }
    }

    /**
     * One cancel stops the whole bundle. Packs that already finished stay finished — the
     * installer's state is on disk and nothing here touches it.
     */
    fun cancel(context: Context, bundle: PrivateModeBundle) {
        ModelDownloadService.cancel(context, bundle.packs.map { it.id })
    }

    /** Removes exactly the bundle's packs and nothing else. */
    suspend fun delete(installer: PackInstaller, bundle: PrivateModeBundle) {
        bundle.packs.forEach { installer.delete(it) }
    }

    /** Free space on the volume the packs install onto, for the storage pre-check. */
    fun usableSpaceBytes(store: ModelStore): Long {
        var candidate: File? = store.rootDir
        while (candidate != null && !candidate.isDirectory) candidate = candidate.parentFile
        return candidate?.usableSpace ?: 0L
    }

    /** Bytes the bundle's installed packs occupy, for the copy that offers to free them. */
    fun installedBytes(bundle: PrivateModeBundle, installedPackIds: Set<String>): Long =
        bundle.installedPacks(installedPackIds).sumOf(ModelPack::totalBytes)
}
