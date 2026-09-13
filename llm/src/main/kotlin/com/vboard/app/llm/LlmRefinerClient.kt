package com.vboard.app.llm

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.vboard.core.correct.SmartFailure
import com.vboard.core.correct.SmartOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The refiner as the keyboard sees it: a connection to [LlmRefinerService] in
 * the `:llm` process.
 *
 * Every method answers the same way the in-process refiner did when it could not
 * do its job — null, or a typed [SmartFailure] — so the callers' existing "keep
 * the rules-only text" paths cover the new failure modes for free. What is new
 * is that there are more of them: the process can be killed for memory at any
 * moment, and a call in flight then comes back as a [RemoteException] rather
 * than a result.
 *
 * Honest limitation, unchanged by the move: generation is one blocking call, so
 * a timeout stops the *caller* waiting, not the model working. What the split
 * buys is that a model which wedges or dies takes its own process with it and
 * leaves the keyboard typing.
 */
class LlmRefinerClient(context: Context) : RemoteRefiner {

    private val appContext = context.applicationContext

    /** Serializes binding; the service serializes the calls themselves. */
    private val connectLock = Mutex()

    /**
     * One transaction at a time, on this side of the boundary.
     *
     * The service already serializes on its engine lock, but callers that queue
     * there do so holding a binder thread each, out of a pool of sixteen shared
     * with everything else — and a blocking transaction cannot be abandoned, so
     * every waiting caller also parks an [Dispatchers.IO] thread for the whole
     * of the generate ahead of it. Waiting for this mutex is cancellable;
     * waiting in the binder pool is not.
     */
    private val callLock = Mutex()

    @Volatile
    private var binder: ILlmRefiner? = null

    /**
     * Whether [connection] is still registered. Separate from [binder] because
     * the two come apart: after `onServiceDisconnected` the binder is gone while
     * the connection is not, and a `disconnect()` that keyed off the binder left
     * it registered — so the framework kept restarting the model process.
     */
    @Volatile
    private var bound = false

    /**
     * The bind in flight, written by [connect] and read by the framework's
     * callbacks on another thread — so every access goes through [pendingLock].
     *
     * The lock buys two things a volatile would not. The callback is guaranteed
     * to see the deferred that the connect which caused it installed; and a
     * disconnect cannot clear a bind that belongs to a later connect, which
     * would leave that one waiting out its whole timeout for a callback whose
     * deferred had already been dropped.
     */
    private val pendingLock = Any()
    private var pending: CompletableDeferred<ILlmRefiner?>? = null

    /** Takes the bind in flight, so exactly one caller completes it. */
    private fun takePending(): CompletableDeferred<ILlmRefiner?>? =
        synchronized(pendingLock) { pending.also { pending = null } }

    private fun setPending(deferred: CompletableDeferred<ILlmRefiner?>?) =
        synchronized(pendingLock) { pending = deferred }

    /** Clears [deferred] only if it is still the one in flight. */
    private fun clearPending(deferred: CompletableDeferred<ILlmRefiner?>) =
        synchronized(pendingLock) { if (pending === deferred) pending = null }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stub = ILlmRefiner.Stub.asInterface(service)
            binder = stub
            takePending()?.complete(stub)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The process died — with the model in it, which is the outcome this
            // whole arrangement exists to make survivable.
            Log.w(TAG, "refiner process went away")
            binder = null
            takePending()?.complete(null)
        }
    }

    override suspend fun preload(): Boolean = call { it.preload() } == true

    override suspend fun refine(text: String, timeoutMs: Long): String? =
        call { it.refine(text, timeoutMs) }

    override suspend fun correct(text: String, timeoutMs: Long): SmartOutput {
        val bundle = call { it.correct(text, timeoutMs) }
            ?: return SmartOutput.failed(SmartFailure.LOAD_FAILED)
        val corrected = bundle.getString(LlmRefinerService.KEY_TEXT)
        if (!corrected.isNullOrEmpty()) return SmartOutput.of(corrected)
        val failure = bundle.getString(LlmRefinerService.KEY_FAILURE)
            ?.let { name -> SmartFailure.entries.firstOrNull { it.name == name } }
        return SmartOutput.failed(failure ?: SmartFailure.ERROR)
    }

    /**
     * Drops the connection so the `:llm` process can be reclaimed with the model
     * in it. Called from the same idle path that releases the recognizers.
     */
    fun disconnect() {
        if (!bound) return
        bound = false
        binder = null
        runCatching { appContext.unbindService(connection) }
    }

    private suspend fun <T> call(block: (ILlmRefiner) -> T): T? {
        val service = connect() ?: return null
        return callLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block(service)
                } catch (e: RemoteException) {
                    // Includes DeadObjectException: the model process was killed
                    // between binding and answering.
                    Log.w(TAG, "refiner call failed", e)
                    binder = null
                    null
                }
            }
        }
    }

    private suspend fun connect(): ILlmRefiner? {
        binder?.let { if (it.asBinder().isBinderAlive) return it else binder = null }
        return connectLock.withLock {
            binder?.let { return@withLock it }
            val deferred = CompletableDeferred<ILlmRefiner?>()
            setPending(deferred)
            val intent = Intent(appContext, LlmRefinerService::class.java)
            val didBind = runCatching {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!didBind) {
                Log.w(TAG, "could not bind the refiner process")
                clearPending(deferred)
                runCatching { appContext.unbindService(connection) }
                return@withLock null
            }
            bound = true
            val result = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
            clearPending(deferred)
            if (result == null) Log.w(TAG, "refiner process did not start in time")
            result
        }
    }

    private companion object {
        const val TAG = "VBoardLlmClient"

        /**
         * Starting a process and handing back a binder is fast; loading the model
         * is not, and does not happen here. A wait longer than this means the
         * device is in trouble, and the caller is better served by its
         * deterministic fallback than by more waiting.
         */
        const val BIND_TIMEOUT_MS = 2_000L
    }
}

/** What the keyboard calls; implemented over binder, faked in tests. */
interface RemoteRefiner {
    /** True when the model is loaded and ready; false when there is nothing to load. */
    suspend fun preload(): Boolean
    suspend fun refine(text: String, timeoutMs: Long = 3_000L): String?
    suspend fun correct(text: String, timeoutMs: Long = CORRECT_TIMEOUT_MS): SmartOutput

    companion object {
        /** Mirrors LlmRefiner.CORRECT_TIMEOUT_MS, which now lives in another process. */
        const val CORRECT_TIMEOUT_MS = 6_000L
    }
}

/**
 * LiteRT-LM ships `arm64-v8a` and `x86_64` only. On a 32-bit install the
 * native library is simply absent, so loading the engine would throw
 * `UnsatisfiedLinkError` deep inside the refiner process rather than failing
 * a check here. The keyboard still works; it just never offers refinement.
 */
val refinerAbiSupported: Boolean
    get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

/** Null when no refiner pack is installed; the model path is readable from any process. */
fun refinerClientOrNull(context: Context, host: RefinerModelHost): LlmRefinerClient? {
    if (!refinerAbiSupported) return null
    host.refinerModelPath() ?: return null
    return LlmRefinerClient(context)
}
