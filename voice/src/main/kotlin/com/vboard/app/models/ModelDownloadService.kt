package com.vboard.app.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vboard.core.model.InstallError
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.NetworkState
import com.vboard.core.model.PackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Entry point for model downloads: decides whether a download may start, schedules it, and
 * publishes live state for the onboarding and settings screens.
 *
 * This used to be a `LifecycleService`. It kept the name (and the `start`/`cancel`/`states`
 * surface every caller already used) but the work itself now runs in [ModelDownloadWorker]:
 * the service returned `START_NOT_STICKY` with no reschedule, so a download the system killed
 * — the normal fate of a several-hundred-megabyte transfer — died permanently and silently
 * while the UI carried on animating a progress bar.
 */
object ModelDownloadService {

    private const val UNIQUE_PREFIX = "model-download:"
    internal const val TAG_ALL = "vboard-model-download"

    /**
     * Live install state per pack id, for the onboarding/settings UI.
     *
     * In-memory and therefore empty after process death; [observeScheduledWork] re-seeds it
     * from WorkManager, which is the durable record.
     */
    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
    val states: StateFlow<Map<String, PackState>> = _states

    internal fun publish(packId: String, state: PackState) {
        _states.value = _states.value + (packId to state)
    }

    /**
     * Records that a pack is gone, so every screen watching [states] finds out.
     *
     * A removal is a state change like any other, and it used to be the only one that never
     * reached this flow: the caller deleted the files and updated its own row, which left
     * every other observer — the private mode header above the same list, the control on the
     * Voice screen — showing the pack as installed until something else happened to re-read
     * the disk.
     */
    fun publishRemoved(packId: String) {
        publish(packId, PackState.NotInstalled)
    }

    // ------------------------------------------------------------ scheduling

    /**
     * Requests a download, defaulting to unmetered-only.
     *
     * Callers that cannot ask the user about data charges (the settings screen) get the safe
     * behaviour for free: on cellular the request is queued and the system starts it when
     * Wi-Fi appears, rather than spending the user's data allowance.
     */
    fun start(context: Context, packId: String, bundle: BundleProgress? = null) {
        enqueue(context, packId, allowMetered = false, bundle = bundle)
    }

    /**
     * Requests a download the user has explicitly agreed to pay mobile data for.
     *
     * Only ever called behind a confirmation that states the real size — see
     * [com.vboard.core.model.DownloadPolicy], which decides when that confirmation is owed.
     */
    fun startAllowingMetered(context: Context, packId: String, bundle: BundleProgress? = null) {
        enqueue(context, packId, allowMetered = true, bundle = bundle)
    }

    /**
     * What one pack's download is a part of, for a caller that offered the user one download.
     *
     * The worker knows only its own pack, and the notification it raises is the same download
     * the screen is showing — so the membership has to come from whoever defined the bundle.
     * Without it each of the two workers counted its own pack against the combined total and
     * both wrote the same notification, which then flipped between two unrelated percentages.
     *
     * The figures are deliberately not here: pack ids plus the catalog are enough to compute
     * them, and a second copy of the arithmetic is a second answer waiting to disagree.
     *
     * @property packIds every pack in the bundle, installed or not.
     * @property installedPackIds the ones already on disk when the download was requested.
     */
    data class BundleProgress(val packIds: List<String>, val installedPackIds: Set<String>)

    private fun enqueue(
        context: Context,
        packId: String,
        allowMetered: Boolean,
        bundle: BundleProgress?,
    ) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_PACK_ID to packId,
                    ModelDownloadWorker.KEY_BUNDLE_PACKS to
                        (bundle?.packIds?.toTypedArray() ?: emptyArray()),
                    ModelDownloadWorker.KEY_BUNDLE_INSTALLED to
                        (bundle?.installedPackIds?.toTypedArray() ?: emptyArray()),
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    // The scheduler, not our code, is what actually keeps a download off
                    // cellular: the constraint survives process death and reboots.
                    .setRequiredNetworkType(
                        if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                    )
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .addTag(TAG_ALL)
            .addTag(tagFor(packId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_PREFIX + packId,
            // REPLACE, not KEEP: a second tap usually means the user just granted metered
            // consent, and the queued unmetered request would otherwise win and sit there.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Cancels every in-flight or queued model download. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_ALL)
        clearInFlight(_states.value.keys)
    }

    fun cancel(context: Context, packId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + packId)
        clearInFlight(setOf(packId))
    }

    /**
     * Cancels a set of packs as one act, for a caller that offered the user a single
     * download. Cancelling them one at a time is the same work, but it leaves the live state
     * of the packs the user did not think about still reading Downloading until the next disk
     * read, and the combined progress bar is built from exactly that state.
     */
    fun cancel(context: Context, packIds: Collection<String>) {
        val manager = WorkManager.getInstance(context)
        packIds.forEach { manager.cancelUniqueWork(UNIQUE_PREFIX + it) }
        // Finished rows go too. A recorded failure is what keeps the control in its Failed
        // state, and "leave it" is the user answering that failure: if the row survived, the
        // error would come back the next time the screen was opened, with no way to dismiss it.
        runCatching { manager.pruneWork() }
        clearInFlight(packIds.toSet())
    }

    /**
     * Drops in-flight state for [packIds]; an installed pack keeps its state.
     *
     * A recorded failure counts as in-flight: it is an outcome still waiting for an answer,
     * and cancelling is that answer. Leaving it behind would keep the control in its Failed
     * state with a Retry button after the user has said they are done.
     */
    private fun clearInFlight(packIds: Set<String>) {
        _states.value = _states.value.mapValues { (id, state) ->
            if (id in packIds && state != PackState.Installed) PackState.NotInstalled else state
        }
    }

    private fun tagFor(packId: String) = "$TAG_ALL:$packId"

    // ----------------------------------------------------------- observation

    /**
     * A pack the scheduler is holding, and how far it has got.
     *
     * [waitingForNetwork] means it is held for a link it is allowed to use, which is a
     * different thing from being held because there is no link at all.
     *
     * [progress] is the worker's own last report, read back out of WorkManager rather than
     * out of [states]. That is the whole point of it: the workers run in the `:ui` process and
     * the settings screen runs in the keyboard's, so [states] — an in-memory flow in an
     * `object` — is a different map on each side and the screen's copy never hears a word from
     * the download. WorkManager's progress is written to a database both processes share, so
     * it is the only report of a running download the screen can actually see. Null until the
     * worker has reported anything, which is the honest reading of a pack that is queued.
     */
    data class Scheduled(
        val packId: String,
        val waitingForNetwork: Boolean,
        val progress: PackState? = null,
    )

    /**
     * One pack's row as WorkManager holds it, reduced to the facts this module reads.
     *
     * Separated from the query so the mapping below is a pure function that a unit test can
     * reach: the state the screen renders is decided here, and it was decided wrongly for the
     * whole of a download.
     */
    internal data class WorkSnapshot(
        val packId: String,
        val enqueued: Boolean,
        val running: Boolean,
        val stage: String?,
        val bytesDone: Long,
        val bytesTotal: Long,
    )

    /**
     * What one snapshot means for the UI.
     *
     * A pack that has reported bytes is downloading even while WorkManager still calls the
     * job ENQUEUED for a moment, and a pack that has reported the install stage has stopped
     * downloading — so neither is ever "waiting for Wi-Fi". Saying so while the bytes climb
     * is how the control sat in Queued for two complete downloads, and how it offered to wait
     * for Wi-Fi on a connection the user had explicitly paid for.
     */
    internal fun scheduledOf(snapshot: WorkSnapshot, connected: Boolean): Scheduled {
        // A worker that has not published a byte count yet is still downloading, and the size
        // it is downloading is a catalog fact — so the first second of a transfer shows a bar
        // at zero rather than a sentence about waiting for a network it already has.
        val total = snapshot.bytesTotal.takeIf { it > 0L }
            ?: ModelCatalog.byId(snapshot.packId)?.totalBytes
            ?: 0L
        val progress = when {
            snapshot.stage == STAGE_INSTALLING -> PackState.Verifying
            snapshot.running && total > 0L ->
                PackState.Downloading(snapshot.bytesDone.coerceAtLeast(0L), total)
            else -> null
        }
        return Scheduled(
            packId = snapshot.packId,
            // Held and connected means held for an unmetered link; held with no connection at
            // all is a different sentence, and saying "waiting for Wi-Fi" in airplane mode
            // sends the user to look for a router. A running worker is held for nothing.
            waitingForNetwork = snapshot.enqueued && connected,
            progress = progress,
        )
    }

    /**
     * Packs WorkManager currently has enqueued or running.
     *
     * The UI needs this because [states] is in-memory: after the process is killed mid-
     * download the flow is empty and the pack reads as "not installed" from disk, so without
     * this the screen would offer a Download button for a download that is already running.
     * It also surfaces the genuinely new state "queued, waiting for Wi-Fi", which the old
     * service could not express at all.
     */
    /**
     * Everything WorkManager can tell the screens about the downloads in one snapshot.
     *
     * @property scheduled packs enqueued or running, with the progress they have reported.
     * @property failures the last run of a pack that ended in a cause the user is owed, by pack
     *   id. Durable for the same reason the progress is: the worker's own report of a failure
     *   goes into an in-memory flow in the `:ui` process, which the settings screen cannot read,
     *   so without this the Failed state was unreachable and a download that ran out of
     *   attempts simply went quiet.
     */
    data class Downloads(
        val scheduled: List<Scheduled> = emptyList(),
        val failures: Map<String, InstallError> = emptyMap(),
    )

    fun observeDownloads(context: Context): Flow<Downloads> = flow {
        val manager = WorkManager.getInstance(context)
        while (true) {
            val infos = withContext(Dispatchers.IO) {
                runCatching { manager.getWorkInfosByTag(TAG_ALL).get() }.getOrNull().orEmpty()
            }
            val connected = Connectivity.current(context) != NetworkState.OFFLINE
            val scheduled = infos.mapNotNull { info -> snapshotOf(info)?.let { scheduledOf(it, connected) } }
            // At most one row per pack: every enqueue REPLACEs the same unique name, which
            // deletes the row the previous run left behind. So a recorded failure is always the
            // last thing that happened to that pack, and it stands until the user retries or
            // says to leave it.
            val failures = infos.mapNotNull { info ->
                if (info.state != WorkInfo.State.FAILED) return@mapNotNull null
                val packId = info.outputData.getString(ModelDownloadWorker.KEY_PACK_ID)
                    ?: return@mapNotNull null
                val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                    ?.let { name -> InstallError.entries.firstOrNull { it.name == name } }
                    ?: return@mapNotNull null
                packId to error
            }.toMap()
            emit(Downloads(scheduled, failures))
            // Polled, not observed. `getWorkInfosByTagFlow` is backed by a Room query whose
            // invalidation does not cross process boundaries: the workers write their progress
            // in `:ui` and this collector lives in the keyboard's process, so the flow emitted
            // the row as it stood when the download was enqueued and then went silent for the
            // entire transfer. The screen sat in "Waiting for Wi-Fi." through two complete
            // 980 MB downloads and kept saying it after both workers had succeeded. Re-reading
            // the shared database is the only way this side hears anything, and once a second
            // is the rate the worker writes at.
            delay(if (scheduled.isEmpty()) IDLE_POLL_MS else ACTIVE_POLL_MS)
        }
    }.distinctUntilChanged()

    /** Just the scheduled half, for callers with nothing to say about a failure. */
    fun observeScheduledWork(context: Context): Flow<List<Scheduled>> =
        observeDownloads(context).map { it.scheduled }

    /** One unfinished WorkInfo, as [scheduledOf] needs it. Finished work is not scheduled. */
    private fun snapshotOf(info: WorkInfo): WorkSnapshot? {
        if (info.state.isFinished) return null
        val packId = info.tags
            .firstOrNull { it.startsWith("$TAG_ALL:") }
            ?.removePrefix("$TAG_ALL:")
            ?: return null
        return WorkSnapshot(
            packId = packId,
            enqueued = info.state == WorkInfo.State.ENQUEUED,
            running = info.state == WorkInfo.State.RUNNING,
            stage = info.progress.getString(ModelDownloadWorker.KEY_STAGE),
            bytesDone = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_DONE, -1L),
            bytesTotal = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_TOTAL, -1L),
        )
    }

    /**
     * Current network state, for copy that has to name the trade-off ("Wi-Fi recommended"
     * vs. "this will use mobile data").
     */
    fun networkState(context: Context): NetworkState = Connectivity.current(context)

    private const val BACKOFF_SECONDS = 30L

    /** The stage a worker records while bytes are arriving. */
    internal const val STAGE_DOWNLOADING = "downloading"

    /** The stage a worker records while it is verifying and extracting rather than fetching. */
    internal const val STAGE_INSTALLING = "installing"

    /** The worker rewrites its progress once a second; re-reading it faster buys nothing. */
    private const val ACTIVE_POLL_MS = 1_000L

    /** With nothing scheduled there is only the user's next tap to notice, which is instant. */
    private const val IDLE_POLL_MS = 3_000L
}
