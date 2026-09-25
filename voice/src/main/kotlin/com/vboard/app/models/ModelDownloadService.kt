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
import com.vboard.core.model.NetworkState
import com.vboard.core.model.PackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

    // ------------------------------------------------------------ scheduling

    /**
     * Requests a download, defaulting to unmetered-only.
     *
     * Callers that cannot ask the user about data charges (the settings screen) get the safe
     * behaviour for free: on cellular the request is queued and the system starts it when
     * Wi-Fi appears, rather than spending the user's data allowance.
     */
    fun start(context: Context, packId: String) {
        enqueue(context, packId, allowMetered = false)
    }

    /**
     * Requests a download the user has explicitly agreed to pay mobile data for.
     *
     * Only ever called behind a confirmation that states the real size — see
     * [com.vboard.core.model.DownloadPolicy], which decides when that confirmation is owed.
     */
    fun startAllowingMetered(context: Context, packId: String) {
        enqueue(context, packId, allowMetered = true)
    }

    private fun enqueue(context: Context, packId: String, allowMetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_PACK_ID to packId))
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
        _states.value = _states.value.mapValues { (_, state) ->
            if (state is PackState.Downloading || state == PackState.Verifying) {
                PackState.NotInstalled
            } else {
                state
            }
        }
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
        clearInFlight(packIds.toSet())
    }

    /** Drops in-flight state for [packIds]; an installed pack keeps its state. */
    private fun clearInFlight(packIds: Set<String>) {
        _states.value = _states.value.mapValues { (id, state) ->
            if (id in packIds && (state is PackState.Downloading || state == PackState.Verifying)) {
                PackState.NotInstalled
            } else {
                state
            }
        }
    }

    private fun tagFor(packId: String) = "$TAG_ALL:$packId"

    // ----------------------------------------------------------- observation

    /** A pack the scheduler is holding until its network constraint is met. */
    data class Scheduled(val packId: String, val waitingForNetwork: Boolean)

    /**
     * Packs WorkManager currently has enqueued or running.
     *
     * The UI needs this because [states] is in-memory: after the process is killed mid-
     * download the flow is empty and the pack reads as "not installed" from disk, so without
     * this the screen would offer a Download button for a download that is already running.
     * It also surfaces the genuinely new state "queued, waiting for Wi-Fi", which the old
     * service could not express at all.
     */
    fun observeScheduledWork(context: Context): Flow<List<Scheduled>> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(TAG_ALL)
            .map { infos ->
                infos.mapNotNull { info ->
                    if (info.state.isFinished) return@mapNotNull null
                    val packId = info.tags
                        .firstOrNull { it.startsWith("$TAG_ALL:") }
                        ?.removePrefix("$TAG_ALL:")
                        ?: return@mapNotNull null
                    // Mirror any progress the worker recorded, so a fresh process shows a
                    // real percentage instead of restarting the bar at zero.
                    val done = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_DONE, -1L)
                    val total = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_TOTAL, -1L)
                    if (done >= 0 && total > 0 && packId !in _states.value) {
                        publish(packId, PackState.Downloading(done, total))
                    }
                    Scheduled(
                        packId = packId,
                        waitingForNetwork = info.state == WorkInfo.State.ENQUEUED,
                    )
                }
            }

    /**
     * Current network state, for copy that has to name the trade-off ("Wi-Fi recommended"
     * vs. "this will use mobile data").
     */
    fun networkState(context: Context): NetworkState = Connectivity.current(context)

    private const val BACKOFF_SECONDS = 30L
}
