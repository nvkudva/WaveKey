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
        installer: PackInstaller,
        bundle: PrivateModeBundle,
        installedPackIds: Set<String>,
        allowMetered: Boolean,
    ) {
        // Every pack in the bundle is told what the whole bundle is, so the notification one of
        // them raises counts the download the user actually asked for rather than its own share
        // of it.
        val progress = ModelDownloadService.BundleProgress(
            packIds = bundle.packs.map { it.id },
            installedPackIds = installedPackIds,
        )
        for (pack in bundle.missingPacks(installedPackIds)) {
            // A download supersedes any standing "throw these away" note left by a cancel.
            installer.clearDiscardRequest(pack)
            if (allowMetered) {
                ModelDownloadService.startAllowingMetered(context, pack.id, progress)
            } else {
                ModelDownloadService.start(context, pack.id, progress)
            }
        }
    }

    /**
     * One cancel stops the whole bundle. Packs that already finished stay finished — the
     * installer's state is on disk and nothing here touches it.
     *
     * It also throws away the partial bytes of the packs that did not finish. Nothing resumes
     * a cancelled download, so keeping them is not a saving: it is several hundred megabytes
     * the user cannot see in any list and cannot reclaim from any screen.
     */
    suspend fun cancel(
        context: Context,
        installer: PackInstaller,
        bundle: PrivateModeBundle,
        installedPackIds: Set<String>,
    ) {
        // The note goes down before the cancel, because the worker that is still writing is
        // in another process and reads it on its way out. Deleting the directory from here is
        // the other half: it is all that happens for a pack that was only ever queued, and a
        // running worker's own discard cleans up whatever it wrote after this.
        val missing = bundle.missingPacks(installedPackIds)
        missing.forEach { installer.requestDiscard(it) }
        ModelDownloadService.cancel(context, bundle.packs.map { it.id })
        missing.forEach { installer.discardPartial(it) }
    }

    /** Removes exactly the bundle's packs and nothing else. */
    suspend fun delete(installer: PackInstaller, bundle: PrivateModeBundle) {
        bundle.packs.forEach { delete(installer, it) }
    }

    /**
     * Removes one pack and tells everyone watching.
     *
     * The publish is the point: a removal is a state change, and the screens that show what
     * is installed learn about state changes from [ModelDownloadService.states].
     */
    suspend fun delete(installer: PackInstaller, pack: ModelPack) {
        installer.delete(pack)
        ModelDownloadService.publishRemoved(pack.id)
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
