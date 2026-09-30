package com.vboard.app.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vboard.app.R
import com.vboard.app.voice.VoiceRuntimeHost
import com.vboard.core.model.InstallError
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelPack
import com.vboard.core.model.PackState
import com.vboard.core.model.PrivateMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads and installs one model pack.
 *
 * Why a worker and not the foreground service it replaces: the service returned
 * `START_NOT_STICKY` and never rescheduled, so a download the system killed — routine for a
 * multi-hundred-megabyte transfer — stopped permanently and silently while the UI kept
 * showing a progress bar. WorkManager persists the request across process death and reboots,
 * enforces the network constraint itself (so cellular data cannot be spent by accident even
 * if a caller forgets), and retries with backoff.
 *
 * Retrying is cheap because [com.vboard.core.model.PackInstaller] resumes from the `.part`
 * files already on disk; none of its staging, per-pack mutex or atomic activation changes
 * here — this class only decides *when* install() runs and reports what it says.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val app get() = (applicationContext as VoiceRuntimeHost).voiceRuntime

    /**
     * Latest state the installer reported, read by the notification/progress pump.
     *
     * [com.vboard.core.model.PackInstaller.install] takes a plain (non-suspending) callback
     * and fires it on every 64 KB chunk, so nothing suspending — `setProgress`, `setForeground`
     * — can be called from inside it, and forwarding all of it unthrottled is thousands of
     * recompositions and notification rebuilds per pack. The callback therefore only stores
     * here, and a pump coroutine publishes at a human rate.
     */
    @Volatile
    private var latest: PackState? = null

    private var lastUiPublishAt = 0L

    /**
     * The download this pack is one part of, as the caller defined it.
     *
     * The notification and the screen have to be the same story: AC 10 gives them one
     * percentage point of slack, and a per-pack notification next to a per-bundle screen is
     * out by half for the whole of the first pack. Worse, both of a bundle's workers write the
     * same notification, so two workers each reporting their own share of a combined total made
     * one notification flip between two unrelated percentages.
     */
    private val bundlePacks: List<ModelPack> get() =
        inputData.getStringArray(KEY_BUNDLE_PACKS)?.mapNotNull(ModelCatalog::byId).orEmpty()

    /** Packs already on disk when the user asked for the download. */
    private val preInstalledIds: Set<String> get() =
        inputData.getStringArray(KEY_BUNDLE_INSTALLED)?.toSet().orEmpty()

    /**
     * Where the whole download the user asked for has got to.
     *
     * Both of a bundle's workers run in the same process, so the live state map is the one
     * place that knows about all of it; this worker's own progress has not reached it yet at
     * the point the notification is built, which is why it is passed in. The arithmetic itself
     * is `:core`'s, so the notification and the screen cannot drift apart.
     */
    private fun bundleFraction(pack: ModelPack, state: PackState.Downloading): Float {
        val packs = bundlePacks
        val total = packs.sumOf { it.totalBytes }
        if (total <= 0L) return state.fraction.toFloat()
        val states = ModelDownloadService.states.value +
            preInstalledIds.associateWith { PackState.Installed } +
            (pack.id to state)
        return (PrivateMode.downloadedBytes(packs, states).toFloat() / total).coerceIn(0f, 1f)
    }

    /** What the notification is about: the bundle if there is one, otherwise this pack. */
    private fun notificationText(pack: ModelPack): String =
        if (bundlePacks.isNotEmpty()) {
            applicationContext.getString(R.string.download_notification_bundle)
        } else {
            pack.displayName
        }

    override suspend fun doWork(): Result = coroutineScope {
        val packId = inputData.getString(KEY_PACK_ID) ?: return@coroutineScope Result.failure()
        val pack = ModelCatalog.byId(packId) ?: return@coroutineScope Result.failure()

        runCatching { setForeground(foregroundInfo(notificationText(pack), 0f)) }
            .onFailure {
                // Android 12+ refuses a foreground start from the background. The download
                // still runs; it just does so without a progress notification rather than
                // crashing the worker.
                Log.w(TAG, "could not show the download notification", it)
            }

        ModelDownloadService.publish(packId, PackState.Downloading(0L, pack.totalBytes))

        // WorkManager's progress is not a nicety here: it is the only report of this download
        // that the settings screen can read. The screen runs in the keyboard's process and this
        // worker runs in `:ui`, so the in-memory state flow it also watches is a different map
        // that never hears from us.
        val pump = launch {
            while (isActive) {
                delay(NOTIFICATION_INTERVAL_MS)
                when (val state = latest) {
                    is PackState.Downloading -> runCatching {
                        setProgress(
                            workDataOf(
                                KEY_PACK_ID to pack.id,
                                KEY_STAGE to ModelDownloadService.STAGE_DOWNLOADING,
                                KEY_BYTES_DONE to state.bytesDone,
                                KEY_BYTES_TOTAL to state.bytesTotal,
                            ),
                        )
                        setForeground(
                            foregroundInfo(notificationText(pack), bundleFraction(pack, state)),
                        )
                    }
                    PackState.Verifying -> runCatching { publishStage(pack, ModelDownloadService.STAGE_INSTALLING) }
                    else -> Unit
                }
            }
        }

        val result = try {
            app.packInstaller.install(pack) { state -> onInstallState(pack, state) }
        } catch (e: CancellationException) {
            discardIfAsked(pack)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "install threw for $packId", e)
            PackState.Failed(InstallError.IO)
        } finally {
            pump.cancel()
        }

        if (result != PackState.Installed) {
            val error = (result as? PackState.Failed)?.error
            // A cancel is either the user's (ModelDownloadService.cancel already reset the
            // row) or WorkManager replacing this run with a fresh one for the same pack.
            // Publishing "cancelled" in the second case would overwrite the new worker's
            // progress with an error the user never caused.
            if (error == InstallError.CANCELLED) discardIfAsked(pack)
            else ModelDownloadService.publish(packId, result)
            // A network blip is WorkManager's problem, not the user's: reschedule with
            // backoff and keep the .part files. Everything else needs a human (no storage,
            // a corrupt payload, an explicit cancel), so surface it and stop.
            return@coroutineScope if (error == InstallError.NETWORK && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                if (error != InstallError.CANCELLED) notifyOutcome(pack, installed = false)
                // The cause travels in the output data, because that is the only record of it
                // the settings screen — in another process — can read. Published in memory it
                // reached nobody, which is why a download that ran out of attempts left the
                // control showing no failure at all.
                failureResult(pack, error)
            }
        }

        // Extraction is part of "installed" from the user's point of view: a pack whose
        // archive has not been unpacked cannot be loaded by the recognizer. It is also the
        // longest step with no percentage to report, so the stage is recorded before it starts
        // rather than left reading 99% until it ends.
        publishStage(pack, ModelDownloadService.STAGE_INSTALLING)
        try {
            app.modelStore.ensureExtracted(app.packInstaller, pack)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ensureExtracted has already cleared the installed marker, so the UI offers a
            // re-download rather than claiming the pack is ready.
            Log.e(TAG, "extraction failed for $packId", e)
            ModelDownloadService.publish(packId, PackState.Failed(InstallError.IO))
            notifyOutcome(pack, installed = false)
            return@coroutineScope failureResult(pack, InstallError.IO)
        }

        ModelDownloadService.publish(packId, PackState.Installed)
        notifyOutcome(pack, installed = true)
        Result.success()
    }

    /** A failure the screen in the other process can name. */
    private fun failureResult(pack: ModelPack, error: InstallError?): Result =
        if (error == null || error == InstallError.CANCELLED) {
            Result.failure()
        } else {
            Result.failure(workDataOf(KEY_PACK_ID to pack.id, KEY_ERROR to error.name))
        }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val name = inputData.getString(KEY_PACK_ID)
            ?.let { ModelCatalog.byId(it) }
            ?.let { notificationText(it) }
            ?: applicationContext.getString(R.string.download_notification_title)
        return foregroundInfo(name, 0f)
    }

    // ------------------------------------------------------------- progress

    /**
     * Records the stage in WorkManager, for the screen in the other process.
     *
     * Verifying and extracting have no meaningful percentage, and the last thing the bytes
     * said was "almost done" — so without this the control would sit at 99% for the length of
     * an extraction instead of saying what is happening.
     */
    private suspend fun publishStage(pack: ModelPack, stage: String) {
        runCatching {
            setProgress(workDataOf(KEY_PACK_ID to pack.id, KEY_STAGE to stage))
        }
        ModelDownloadService.publish(pack.id, PackState.Verifying)
    }

    /**
     * Throws away this pack's partial bytes when a cancel asked for them.
     *
     * It happens here because this is the only place that knows the writing has stopped. The
     * screen that cancelled is in another process and cannot wait for that, so its own delete
     * raced the transfer and a hundred megabytes of `.part` survived every cancel.
     * [NonCancellable] because by this point the coroutine is already cancelled.
     */
    private suspend fun discardIfAsked(pack: ModelPack) {
        withContext(NonCancellable) {
            if (app.packInstaller.discardRequested(pack)) {
                app.packInstaller.discardPartial(pack)
            }
        }
    }

    private fun onInstallState(pack: ModelPack, state: PackState) {
        latest = state
        // Terminal states (Verifying, Installed, Failed) always go through; progress ticks
        // are rate-limited so the UI is not asked to recompose thousands of times per pack.
        val now = SystemClock.elapsedRealtime()
        if (state is PackState.Downloading && now - lastUiPublishAt < PUBLISH_INTERVAL_MS) return
        lastUiPublishAt = now
        ModelDownloadService.publish(pack.id, state)
    }

    // --------------------------------------------------------- notifications

    private fun foregroundInfo(text: String, fraction: Float): ForegroundInfo {
        ensureChannel()
        val notification = buildProgressNotification(text, fraction)
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildProgressNotification(text: String, fraction: Float): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(text)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setContentIntent(setupPendingIntent())
            .build()

    /**
     * Where tapping the download notification goes.
     *
     * VBoard sent the user to its own onboarding activity. This fork has none —
     * the voice and model screens live inside HeliBoard's settings (W2.5) — so
     * the target is whatever the launcher launches, resolved at runtime rather
     * than named here, which keeps :voice free of any dependency on :app.
     */
    private fun setupPendingIntent(): PendingIntent? {
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Final notification for one pack.
     *
     * The old one said "Voice models ready" only when *every* required pack was present,
     * which after the accuracy pack became optional would have called a perfectly working
     * install a failure. It now reports what the user can do, not how many files exist.
     */
    private fun notifyOutcome(pack: ModelPack, installed: Boolean) {
        ensureChannel()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
            ?: return
        val canDictate = app.modelStore.dictationReady(app.packInstaller)
        val title = when {
            installed && canDictate -> applicationContext.getString(R.string.setup_notify_voice_ready)
            installed -> applicationContext.getString(R.string.setup_notify_pack_ready, pack.displayName)
            else -> applicationContext.getString(R.string.setup_notify_pack_failed, pack.displayName)
        }
        manager.notify(
            DONE_NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentIntent(setupPendingIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val TAG = "VBoardDownload"
        internal const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 41
        private const val DONE_NOTIFICATION_ID = 42

        /** WorkManager's own backoff already spaces these out; four is plenty. */
        private const val MAX_ATTEMPTS = 4
        /** How often the in-app progress flow is refreshed. */
        private const val PUBLISH_INTERVAL_MS = 400L

        /** How often the notification and the persisted WorkInfo progress are rewritten. */
        private const val NOTIFICATION_INTERVAL_MS = 1_000L

        internal const val KEY_PACK_ID = "pack_id"
        internal const val KEY_STAGE = "stage"
        internal const val KEY_ERROR = "error"
        internal const val KEY_BYTES_DONE = "bytes_done"
        internal const val KEY_BYTES_TOTAL = "bytes_total"
        internal const val KEY_BUNDLE_PACKS = "bundle_packs"
        internal const val KEY_BUNDLE_INSTALLED = "bundle_installed"
    }
}
