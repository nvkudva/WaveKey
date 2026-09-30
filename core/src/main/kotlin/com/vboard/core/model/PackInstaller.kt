package com.vboard.core.model

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resumable, checksum-verified downloader/installer for [ModelPack]s.
 *
 * On-disk layout under [rootDir]:
 * ```
 * <packId>/v<version>/                    final (activated) dir, contains "installed.marker"
 * <packId>/staging-v<version>/            staging dir during download
 * <packId>/staging-v<version>/<file>.part in-flight download of one file
 * <packId>/staging-v<version>/<file>      file fully downloaded and verified, awaiting activation
 * ```
 *
 * All state is derived from the filesystem (no in-memory cache) so it survives process death.
 */
class PackInstaller(
    private val rootDir: Path,
    private val fetcher: Fetcher,
    private val freeBytes: () -> Long = { Long.MAX_VALUE },
) {

    /**
     * One mutex per pack id: concurrent install() calls for the same pack would
     * otherwise interleave writes into the same .part file and can activate a
     * corrupt archive that passes its own (self-consistent) running digest
     * (QA finding VB-QA-07). The second caller simply waits, then short-circuits
     * on the Installed marker.
     */
    private val packLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(pack: ModelPack): Mutex =
        packLocks.computeIfAbsent(pack.id) { Mutex() }

    /** Current state derived from disk (marker files), safe to call anytime. */
    fun stateOf(pack: ModelPack): PackState {
        val marker = markerFile(pack)
        if (Files.isRegularFile(marker)) {
            // readAllBytes, not readString: this module is a plain JVM library, so
            // it compiles against Java 11 happily and then meets an Android
            // runtime that has no Files.readString. The NoSuchMethodError landed
            // inside this runCatching, so every installed pack read as "not
            // installed" and the keyboard reported models it had just downloaded
            // as missing (PLAN.md R28).
            val markedVersion = runCatching {
                Files.readAllBytes(marker).decodeToString().trim().toInt()
            }.getOrNull()
            // The marker says an install finished; the files say it survived.
            // Without this second half a pack whose payload was deleted or
            // truncated underneath us still reads Installed, the UI offers no
            // repair, and the failure only surfaces when the engine tries to
            // load it - by which time the user is holding an open mic.
            if (markedVersion == pack.version && filesPresent(pack)) return PackState.Installed
        }
        return PackState.NotInstalled
    }

    /**
     * Every declared non-archive file of [pack] is present.
     *
     * Presence, not size: a pack may legitimately declare a zero-byte file,
     * and calling that corrupt would refuse to install it.
     *
     * Archives are skipped deliberately: ModelStore unpacks one and then
     * reclaims the space, so a correctly installed speech pack has no archive
     * on disk and checking for it would report every one of them missing.
     * Their extracted state is ModelStore's to track.
     */
    private fun filesPresent(pack: ModelPack): Boolean {
        val dir = finalDir(pack)
        return pack.files.none { !it.archive && !Files.isRegularFile(dir.resolve(it.relativePath)) }
    }

    /** Directory containing the activated pack files, or null unless [PackState.Installed]. */
    fun installedDir(pack: ModelPack): Path? =
        finalDir(pack).takeIf { stateOf(pack) == PackState.Installed }

    /**
     * Downloads all files (resuming any `.part` files), verifies sha256 (when non-empty), then
     * atomically activates the pack. Emits progress via [onState] and returns the final state.
     * Coroutine cancellation yields [PackState.Failed] with [InstallError.CANCELLED]; partial
     * files are retained for resume.
     */
    suspend fun install(pack: ModelPack, onState: (PackState) -> Unit = {}): PackState =
        lockFor(pack).withLock { installLocked(pack, onState) }

    private suspend fun installLocked(pack: ModelPack, onState: (PackState) -> Unit): PackState {
        if (stateOf(pack) == PackState.Installed) {
            onState(PackState.Installed)
            return PackState.Installed
        }

        // Starts from catalog estimates and is replaced by the server's real sizes below.
        var totalBytes = pack.totalBytes
        var lastEmittedBytes = -1L

        fun emitProgress(bytesDone: Long) {
            lastEmittedBytes = maxOf(bytesDone, lastEmittedBytes)
            onState(PackState.Downloading(lastEmittedBytes, totalBytes))
        }

        fun fail(error: InstallError): PackState {
            val state = PackState.Failed(error)
            onState(state)
            return state
        }

        // Storage check: no network calls if the remaining bytes plus headroom won't fit.
        // Archives briefly coexist with their extracted contents, so the footprint is
        // larger than the download itself.
        val remainingBytes = (pack.installFootprintBytes - bytesOnDisk(pack)).coerceAtLeast(0L)
        if (freeBytes() < remainingBytes + STORAGE_HEADROOM_BYTES) {
            return fail(InstallError.INSUFFICIENT_STORAGE)
        }

        val staging = stagingDir(pack)
        try {
            Files.createDirectories(staging)
        } catch (e: IOException) {
            return fail(InstallError.IO)
        }

        // Pre-flight: ask the server for each file's authoritative size. The catalog's
        // sizeBytes are only estimates (upstream rebuilds its artifacts), so using them
        // as a completion gate rejected perfectly good downloads and made every retry
        // fail identically after instantly "re-completing" the progress bar. A size of
        // -1 means the server wouldn't say; that file is then accepted on a clean stream.
        val expected = LongArray(pack.files.size) { -1L }
        for ((i, spec) in pack.files.withIndex()) {
            val target = staging.resolve(spec.relativePath)
            if (Files.isRegularFile(target)) {
                expected[i] = sizeOrZero(target) // already downloaded and verified
                continue
            }
            val remote = try {
                fetcher.contentLength(spec.url)
            } catch (e: CancellationException) {
                return fail(InstallError.CANCELLED)
            } catch (e: IOException) {
                -1L
            }
            // Some servers answer HEAD with Content-Length: 0 for a real file. Only believe
            // a zero when the catalog agrees the file is empty; otherwise treat it as unknown.
            expected[i] = if (remote == 0L && spec.sizeBytes > 0L) -1L else remote
        }
        totalBytes = pack.files.indices.sumOf { i ->
            if (expected[i] >= 0) expected[i] else pack.files[i].sizeBytes
        }

        var completedBytes = 0L
        for ((index, spec) in pack.files.withIndex()) {
            val target = staging.resolve(spec.relativePath)
            val part = staging.resolve(spec.relativePath + PART_SUFFIX)
            val expectedBytes = expected[index]

            try {
                target.parent?.let(Files::createDirectories)
                // Completed earlier (already verified before its rename): skip.
                if (Files.isRegularFile(target)) {
                    completedBytes += Files.size(target)
                    emitProgress(completedBytes)
                    continue
                }
                // A remnant longer than the artifact (interrupted write, or upstream
                // changed the file) can never be resumed into a match: start it over.
                if (expectedBytes >= 0 && sizeOrZero(part) > expectedBytes) {
                    Files.deleteIfExists(part)
                }
            } catch (e: IOException) {
                return fail(InstallError.IO)
            }

            val digest = MessageDigest.getInstance("SHA-256")

            // Re-hash any existing partial bytes so verification stays a single streamed pass.
            var existingBytes = 0L
            try {
                if (Files.isRegularFile(part)) {
                    Files.newInputStream(part).use { input ->
                        val buffer = ByteArray(HASH_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            existingBytes += read
                        }
                    }
                }
            } catch (e: IOException) {
                return fail(InstallError.IO)
            }

            emitProgress(completedBytes + existingBytes)
            val progressBase = completedBytes + existingBytes

            // A .part that already holds the whole file (the previous attempt died between
            // the last byte and activation) needs no request: asking for the range past the
            // end earns an HTTP 416, which used to look like a network failure forever.
            val alreadyFetched = existingBytes > 0 && expectedBytes >= 0 && existingBytes == expectedBytes
            if (!alreadyFetched) {
                try {
                    // Written through a FileOutputStream (rather than
                    // Files.newOutputStream) purely so the descriptor is reachable:
                    // flush() only empties userspace buffers, and a power loss
                    // between the last write and the completion marker would leave
                    // a pack marked installed whose bytes never reached the disk.
                    val out = FileOutputStream(part.toFile(), /* append = */ true)
                    try {
                        val sink = DigestOutputStream(BufferedOutputStream(out), digest)
                        fetcher.fetch(spec.url, existingBytes, sink) { newBytes ->
                            emitProgress(progressBase + newBytes)
                        }
                        sink.flush()
                        out.fd.sync()
                    } finally {
                        out.close()
                    }
                } catch (e: CancellationException) {
                    // Partial files retained for resume.
                    return fail(InstallError.CANCELLED)
                } catch (e: IOException) {
                    // Network failure; the .part stays on disk for resume.
                    return fail(InstallError.NETWORK)
                }
            }

            val actualBytes: Long
            try {
                actualBytes = sizeOrZero(part)
                if (expectedBytes >= 0) {
                    if (actualBytes != expectedBytes) {
                        // Truncated (or over-long) body without an error; keep the .part for resume.
                        return fail(InstallError.NETWORK)
                    }
                } else if (actualBytes == 0L && spec.sizeBytes > 0L) {
                    // Server size unknown AND nothing arrived for a file we expect content from.
                    return fail(InstallError.NETWORK)
                }
                if (spec.sha256.isNotEmpty()) {
                    onState(PackState.Verifying)
                    val actual = toHex(digest.digest())
                    if (!actual.equals(spec.sha256, ignoreCase = true)) {
                        // Drop the corrupt payload so a retry redownloads this file only.
                        Files.deleteIfExists(part)
                        Files.deleteIfExists(target)
                        return fail(InstallError.CHECKSUM_MISMATCH)
                    }
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                return fail(InstallError.IO)
            }

            completedBytes += actualBytes
            emitProgress(completedBytes)
        }

        // Activation: atomic move of the staging dir into place, then write the marker.
        try {
            val final = finalDir(pack)
            deleteRecursively(final) // stale, never-activated remnants
            try {
                Files.move(staging, final, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(staging, final)
            }
            // The marker is the only thing that makes a pack "installed", so it must
            // become durable strictly after the payload it vouches for. Sync the
            // renamed directory entries first, then the marker, then its directory.
            syncDir(final)
            syncDir(final.parent)
            writeFileDurably(final.resolve(MARKER_NAME), pack.version.toString().toByteArray())
            syncDir(final)
        } catch (e: IOException) {
            return fail(InstallError.IO)
        }

        onState(PackState.Installed)
        return PackState.Installed
    }

    /**
     * Result of handing the installer a file the user supplied themselves.
     *
     * Deliberately not an exception: an import failing is an ordinary answer to
     * "is this the right file?", and the screen has something to say for each.
     */
    enum class ImportResult {
        /** Staged and verified; [install] will now finish without a network. */
        STAGED,

        /** The bytes are not this file — a different model, or a truncated copy. */
        DIGEST_MISMATCH,

        /** The pack does not expect a file with that name. */
        UNKNOWN_FILE,

        /** Already installed; nothing to import. */
        ALREADY_INSTALLED,

        /** Could not be written — no space, or the volume went away. */
        IO_ERROR,
    }

    /**
     * Accepts a model file the user already has, in place of downloading it.
     *
     * Someone on a metered connection, an air-gapped device, or simply with the
     * archive already on disk should not have to pull several hundred megabytes
     * again. The bytes land in the same staging directory a download would use,
     * so [install] then verifies, extracts and finalizes them through exactly the
     * same path — this adds a source, not a second install pipeline.
     *
     * The digest is checked here rather than at install time because that is
     * where the user can still act on it: after staging, an unverified file is
     * indistinguishable from a resumed download.
     *
     * [fileName] is matched against the pack's expected relative paths;
     * [openSource] is called at most once.
     */
    suspend fun importFile(
        pack: ModelPack,
        fileName: String,
        openSource: () -> InputStream,
    ): ImportResult = lockFor(pack).withLock {
        if (stateOf(pack) == PackState.Installed) return@withLock ImportResult.ALREADY_INSTALLED
        val spec = pack.files.firstOrNull { it.relativePath.substringAfterLast('/') == fileName }
            ?: return@withLock ImportResult.UNKNOWN_FILE

        val staging = stagingDir(pack)
        val target = staging.resolve(spec.relativePath)
        val temp = staging.resolve(spec.relativePath + IMPORT_SUFFIX)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            target.parent?.let(Files::createDirectories)
            Files.deleteIfExists(temp)
            openSource().use { input ->
                Files.newOutputStream(temp).use { output ->
                    val buffer = ByteArray(HASH_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temp) }
            return@withLock ImportResult.IO_ERROR
        }

        // An empty sha256 in the catalog means "not pinned yet" and is the same
        // policy the downloader follows; anything else must match exactly.
        if (spec.sha256.isNotEmpty() && toHex(digest.digest()) != spec.sha256.lowercase()) {
            runCatching { Files.deleteIfExists(temp) }
            return@withLock ImportResult.DIGEST_MISMATCH
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temp) }
            return@withLock ImportResult.IO_ERROR
        }
        ImportResult.STAGED
    }

    /**
     * Deletes installed files AND partial downloads for the pack, including old
     * versions. Takes the same per-pack lock [install] holds so a delete cannot
     * run against a directory a download is writing into.
     */
    suspend fun delete(pack: ModelPack) {
        lockFor(pack).withLock { deleteRecursively(packDir(pack)) }
    }

    /**
     * Discards staged and partial bytes, leaving an installed payload untouched.
     *
     * This is what cancelling a download has to do with the half-fetched archive. Keeping it
     * would be defensible if anything ever offered to resume it, but nothing does once the
     * work is cancelled, so the bytes are simply unreachable storage the user cannot see and
     * cannot reclaim — several hundred megabytes of it for one pack.
     */
    suspend fun discardPartial(pack: ModelPack) {
        lockFor(pack).withLock { deleteRecursively(stagingDir(pack)) }
    }

    /**
     * Leaves a note asking whoever is downloading [pack] to throw its partial bytes away.
     *
     * The per-pack lock above is a mutex in one process, and the download runs in another one:
     * the screen that cancels cannot wait for the worker to stop writing, so it deleted the
     * staging directory while the transfer was still filling it and the worker simply created
     * it again. A hundred megabytes of `.part` survived every cancel that way, invisible in
     * every list and reclaimable from nowhere.
     *
     * A file is the coordination both processes do share. The canceller leaves it, the worker
     * consumes it once its own writing has stopped, and a fresh download clears it — so the
     * note is only ever about the download that was cancelled.
     */
    fun requestDiscard(pack: ModelPack) {
        runCatching {
            Files.createDirectories(packDir(pack))
            Files.write(discardNote(pack), byteArrayOf())
        }
    }

    /** True when a cancel asked for [pack]'s partial bytes; clears the note. */
    fun discardRequested(pack: ModelPack): Boolean =
        runCatching { Files.deleteIfExists(discardNote(pack)) }.getOrDefault(false)

    /** Drops any standing discard note, because a fresh download supersedes it. */
    fun clearDiscardRequest(pack: ModelPack) {
        runCatching { Files.deleteIfExists(discardNote(pack)) }
    }

    private fun discardNote(pack: ModelPack): Path =
        packDir(pack).resolve("discard-v${pack.version}")

    /**
     * Drops the pack's installed marker so [stateOf] reports [PackState.NotInstalled]
     * and the UI offers a re-download.
     *
     * Consumers call this when an installed payload turns out to be unusable (an
     * archive that will not extract, a model the native loader rejects). Without
     * it the pack reports Installed forever with no repair path, which is a dead
     * end for the user: the only affordance the error offers leads to a screen
     * that already says the pack is there.
     */
    fun invalidate(pack: ModelPack) {
        try {
            Files.deleteIfExists(markerFile(pack))
        } catch (e: IOException) {
            // Best effort: a marker we cannot delete is no worse than before.
        }
    }

    /** Bytes already downloaded (partial + complete) for resume-aware UI. */
    fun bytesOnDisk(pack: ModelPack): Long {
        val final = finalDir(pack)
        val staging = stagingDir(pack)
        return pack.files.sumOf { spec ->
            maxOf(
                sizeOrZero(final.resolve(spec.relativePath)),
                sizeOrZero(staging.resolve(spec.relativePath)),
                sizeOrZero(staging.resolve(spec.relativePath + PART_SUFFIX)),
            )
        }
    }

    private fun packDir(pack: ModelPack): Path = rootDir.resolve(pack.id)

    private fun finalDir(pack: ModelPack): Path = packDir(pack).resolve("v${pack.version}")

    private fun stagingDir(pack: ModelPack): Path = packDir(pack).resolve("staging-v${pack.version}")

    private fun markerFile(pack: ModelPack): Path = finalDir(pack).resolve(MARKER_NAME)

    private fun sizeOrZero(path: Path): Long =
        try {
            if (Files.isRegularFile(path)) Files.size(path) else 0L
        } catch (e: IOException) {
            0L
        }

    /**
     * Best-effort recursive delete; returns false if anything survived.
     *
     * Never throws. Files.walk and Files.deleteIfExists raise [UncheckedIOException],
     * which is NOT an [IOException]: it slipped straight through the installer's
     * catch, out of install(), and into the download service's bare coroutine
     * launch, taking the process with it.
     */
    private fun deleteRecursively(path: Path): Boolean {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        var ok = true
        try {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { entry ->
                    try {
                        Files.deleteIfExists(entry)
                    } catch (e: IOException) {
                        ok = false
                    } catch (e: UncheckedIOException) {
                        ok = false
                    }
                }
            }
        } catch (e: IOException) {
            ok = false
        } catch (e: UncheckedIOException) {
            ok = false
        }
        return ok
    }

    /** Writes [bytes] to [path] and forces them to the platter before returning. */
    @Throws(IOException::class)
    private fun writeFileDurably(path: Path, bytes: ByteArray) {
        FileOutputStream(path.toFile()).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    /**
     * Forces a directory's entries to disk, so a rename that precedes a completion
     * marker survives a power loss. Best effort: opening a directory as a channel
     * is not portable, and a filesystem that refuses simply leaves us where the
     * code was before.
     */
    private fun syncDir(dir: Path?) {
        if (dir == null) return
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // Not supported here; nothing to do.
        } catch (e: UnsupportedOperationException) {
            // Ditto.
        }
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX_DIGITS[v ushr 4])
            append(HEX_DIGITS[v and 0x0f])
        }
    }

    private companion object {
        const val MARKER_NAME = "installed.marker"
        const val PART_SUFFIX = ".part"
        const val IMPORT_SUFFIX = ".import"
        const val STORAGE_HEADROOM_BYTES = 50_000_000L // 50 MB
        const val HASH_BUFFER_SIZE = 64 * 1024
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
