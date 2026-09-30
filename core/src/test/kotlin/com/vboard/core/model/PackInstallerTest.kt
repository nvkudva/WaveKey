package com.vboard.core.model

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackInstallerTest {

    @TempDir
    lateinit var root: Path

    private val fetcher = FakeFetcher()

    private fun installer(freeBytes: () -> Long = { Long.MAX_VALUE }) =
        PackInstaller(root, fetcher, freeBytes)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun body(size: Int, seed: Int = 1): ByteArray =
        ByteArray(size) { i -> ((i * 31 + seed * 17) and 0xff).toByte() }

    private fun url(rel: String) = "https://models.test/$rel"

    private fun spec(rel: String, content: ByteArray, sha: String = sha256Hex(content)) =
        ModelFileSpec(relativePath = rel, url = url(rel), sha256 = sha, sizeBytes = content.size.toLong())

    private fun pack(
        files: List<ModelFileSpec>,
        id: String = "test-pack",
        version: Int = 1,
    ) = ModelPack(
        id = id,
        displayName = "Test pack",
        kind = ModelKind.STREAMING_ASR,
        version = version,
        files = files,
        licenseNote = "test",
        required = true,
    )

    private fun serve(rel: String, content: ByteArray): ModelFileSpec {
        fetcher.bodies[url(rel)] = content
        return spec(rel, content)
    }

    private fun stagingDir(pack: ModelPack): Path = root.resolve(pack.id).resolve("staging-v${pack.version}")

    private fun finalDir(pack: ModelPack): Path = root.resolve(pack.id).resolve("v${pack.version}")

    // ---------------------------------------------------------------- happy path

    @Test
    fun `happy path installs single file pack with verified content`() = runTest {
        val content = body(100)
        val pack = pack(listOf(serve("model.onnx", content)))
        val installer = installer()

        assertEquals(PackState.NotInstalled, installer.stateOf(pack))
        assertNull(installer.installedDir(pack))

        val result = installer.install(pack)

        assertEquals(PackState.Installed, result)
        assertEquals(PackState.Installed, installer.stateOf(pack))
        val dir = assertNotNull(installer.installedDir(pack))
        assertEquals(finalDir(pack), dir)
        assertTrue(content.contentEquals(Files.readAllBytes(dir.resolve("model.onnx"))))
        assertEquals(pack.version.toString(), Files.readString(dir.resolve("installed.marker")).trim())
        assertFalse(Files.exists(stagingDir(pack)), "staging dir should be gone after activation")
        assertEquals(100L, installer.bytesOnDisk(pack))
    }

    @Test
    fun `install emits downloading then verifying then installed`() = runTest {
        val pack = pack(listOf(serve("model.onnx", body(50))))
        val states = mutableListOf<PackState>()

        installer().install(pack) { states += it }

        assertTrue(states.first() is PackState.Downloading, "first state was ${states.first()}")
        assertTrue(states.contains(PackState.Verifying))
        assertEquals(PackState.Installed, states.last())
        assertTrue(
            states.indexOf(PackState.Verifying) > states.indexOfFirst { it is PackState.Downloading },
            "Verifying must come after Downloading",
        )
    }

    @Test
    fun `progress is monotonic non-decreasing and reaches totalBytes`() = runTest {
        val pack = pack(
            listOf(
                serve("a.bin", body(95, seed = 2)),
                serve("b.bin", body(63, seed = 3)),
            ),
        )
        val progress = mutableListOf<PackState.Downloading>()

        installer().install(pack) { if (it is PackState.Downloading) progress += it }

        assertTrue(progress.isNotEmpty())
        progress.zipWithNext { a, b ->
            assertTrue(b.bytesDone >= a.bytesDone, "progress went backwards: ${a.bytesDone} -> ${b.bytesDone}")
        }
        assertTrue(progress.all { it.bytesTotal == pack.totalBytes })
        assertEquals(pack.totalBytes, progress.last().bytesDone)
        assertTrue(progress.all { it.fraction in 0.0..1.0 })
    }

    @Test
    fun `empty sha256 skips verification and still installs`() = runTest {
        val content = body(77)
        val pack = pack(listOf(serve("unpinned.bin", content).copy(sha256 = "")))
        val states = mutableListOf<PackState>()

        val result = installer().install(pack) { states += it }

        assertEquals(PackState.Installed, result)
        assertFalse(states.contains(PackState.Verifying), "no Verifying phase when sha256 is unpinned")
        assertTrue(content.contentEquals(Files.readAllBytes(finalDir(pack).resolve("unpinned.bin"))))
    }

    @Test
    fun `nested relative paths are created under the install dir`() = runTest {
        val content = body(40)
        val pack = pack(listOf(serve("sub/dir/tokens.txt", content)))

        val result = installer().install(pack)

        assertEquals(PackState.Installed, result)
        assertTrue(content.contentEquals(Files.readAllBytes(finalDir(pack).resolve("sub/dir/tokens.txt"))))
    }

    @Test
    fun `installing an already installed pack short-circuits without network calls`() = runTest {
        val pack = pack(listOf(serve("model.onnx", body(30))))
        val installer = installer()
        installer.install(pack)
        val callsAfterFirst = fetcher.calls.size

        val result = installer.install(pack)

        assertEquals(PackState.Installed, result)
        assertEquals(callsAfterFirst, fetcher.calls.size, "second install must not fetch")
    }

    // ---------------------------------------------------------------- resume

    @Test
    fun `network failure mid-file returns NETWORK and retains the part file`() = runTest {
        val content = body(100)
        val pack = pack(listOf(serve("model.onnx", content)))
        fetcher.failAfterBytes[url("model.onnx")] = 40
        val states = mutableListOf<PackState>()

        val result = installer().install(pack) { states += it }

        assertEquals(PackState.Failed(InstallError.NETWORK), result)
        assertEquals(PackState.Failed(InstallError.NETWORK), states.last())
        val part = stagingDir(pack).resolve("model.onnx.part")
        assertTrue(Files.exists(part), "partial download must be retained for resume")
        assertEquals(40L, Files.size(part))
        assertEquals(40L, installer().bytesOnDisk(pack))
        assertEquals(PackState.NotInstalled, installer().stateOf(pack))
    }

    @Test
    fun `second install resumes from part bytes with correct rangeStart and no redownload`() = runTest {
        val content = body(100)
        val pack = pack(listOf(serve("model.onnx", content)))
        fetcher.failAfterBytes[url("model.onnx")] = 40
        val installer = installer()

        assertEquals(PackState.Failed(InstallError.NETWORK), installer.install(pack))

        val result = installer.install(pack)

        assertEquals(PackState.Installed, result)
        assertEquals(
            listOf(FakeFetcher.Call(url("model.onnx"), 0L), FakeFetcher.Call(url("model.onnx"), 40L)),
            fetcher.calls,
        )
        assertEquals(100L, fetcher.servedBytes[url("model.onnx")], "bytes across calls must sum to file size, not 2x")
        // Resumed bytes were re-hashed, so the full-file checksum still verified.
        assertTrue(content.contentEquals(Files.readAllBytes(finalDir(pack).resolve("model.onnx"))))
    }

    @Test
    fun `resumed install reports progress starting at existing part bytes and stays monotonic`() = runTest {
        val pack = pack(listOf(serve("model.onnx", body(100))))
        fetcher.failAfterBytes[url("model.onnx")] = 40
        val installer = installer()
        installer.install(pack)

        val progress = mutableListOf<PackState.Downloading>()
        installer.install(pack) { if (it is PackState.Downloading) progress += it }

        assertEquals(40L, progress.first().bytesDone)
        progress.zipWithNext { a, b -> assertTrue(b.bytesDone >= a.bytesDone) }
        assertEquals(100L, progress.last().bytesDone)
    }

    @Test
    fun `multi-file resume skips already completed staging file`() = runTest {
        val aContent = body(50, seed = 4)
        val bContent = body(60, seed = 5)
        val a = serve("a.bin", aContent)
        val bSpec = spec("b.bin", bContent) // no body scripted yet -> first attempt fails on b
        val pack = pack(listOf(a, bSpec))
        val installer = installer()

        assertEquals(PackState.Failed(InstallError.NETWORK), installer.install(pack))
        assertTrue(Files.exists(stagingDir(pack).resolve("a.bin")), "a.bin should be completed in staging")

        fetcher.bodies[url("b.bin")] = bContent
        val result = installer.install(pack)

        assertEquals(PackState.Installed, result)
        assertEquals(1, fetcher.calls.count { it.url == url("a.bin") }, "completed file must not be fetched again")
        // b.bin was attempted (and failed before any bytes) once, then redownloaded from 0.
        assertEquals(listOf(0L, 0L), fetcher.calls.filter { it.url == url("b.bin") }.map { it.rangeStart })
        assertTrue(aContent.contentEquals(Files.readAllBytes(finalDir(pack).resolve("a.bin"))))
        assertTrue(bContent.contentEquals(Files.readAllBytes(finalDir(pack).resolve("b.bin"))))
    }

    // ---------------------------------------------------------------- checksum

    @Test
    fun `checksum mismatch fails and removes the corrupt partial`() = runTest {
        val goodContent = body(80, seed = 6)
        val corrupted = body(80, seed = 7)
        val pack = pack(listOf(spec("model.onnx", goodContent))) // sha of good content
        fetcher.bodies[url("model.onnx")] = corrupted

        val result = installer().install(pack)

        assertEquals(PackState.Failed(InstallError.CHECKSUM_MISMATCH), result)
        assertFalse(Files.exists(stagingDir(pack).resolve("model.onnx.part")), "corrupt .part must be deleted")
        assertFalse(Files.exists(stagingDir(pack).resolve("model.onnx")), "corrupt file must not be kept")
        assertEquals(0L, installer().bytesOnDisk(pack))
        assertEquals(PackState.NotInstalled, installer().stateOf(pack))
    }

    @Test
    fun `retry after checksum mismatch redownloads the file from scratch and succeeds`() = runTest {
        val goodContent = body(80, seed = 6)
        val pack = pack(listOf(spec("model.onnx", goodContent)))
        fetcher.bodies[url("model.onnx")] = body(80, seed = 7) // corrupt first
        val installer = installer()

        assertEquals(PackState.Failed(InstallError.CHECKSUM_MISMATCH), installer.install(pack))

        fetcher.bodies[url("model.onnx")] = goodContent
        val result = installer.install(pack)

        assertEquals(PackState.Installed, result)
        assertEquals(listOf(0L, 0L), fetcher.calls.map { it.rangeStart }, "retry must restart the file at byte 0")
        assertTrue(goodContent.contentEquals(Files.readAllBytes(finalDir(pack).resolve("model.onnx"))))
    }

    @Test
    fun `checksum mismatch on second file keeps the first verified file for retry`() = runTest {
        val aContent = body(50, seed = 8)
        val bGood = body(60, seed = 9)
        val a = serve("a.bin", aContent)
        val b = spec("b.bin", bGood)
        fetcher.bodies[url("b.bin")] = body(60, seed = 10) // corrupt
        val pack = pack(listOf(a, b))
        val installer = installer()

        assertEquals(PackState.Failed(InstallError.CHECKSUM_MISMATCH), installer.install(pack))
        assertTrue(Files.exists(stagingDir(pack).resolve("a.bin")))
        assertFalse(Files.exists(stagingDir(pack).resolve("b.bin.part")))

        fetcher.bodies[url("b.bin")] = bGood
        assertEquals(PackState.Installed, installer.install(pack))
        assertEquals(1, fetcher.calls.count { it.url == url("a.bin") }, "verified file redownloaded on retry")
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    fun `cancellation yields CANCELLED and retains the part file`() = runTest {
        val pack = pack(listOf(serve("model.onnx", body(100))))
        fetcher.hangAfterBytes[url("model.onnx")] = 30
        val installer = installer()
        val states = mutableListOf<PackState>()
        var result: PackState? = null

        val job: Job = launch { result = installer.install(pack) { states += it } }
        advanceUntilIdle() // run until the fetch hangs awaiting cancellation
        job.cancel()
        job.join()

        assertEquals(PackState.Failed(InstallError.CANCELLED), result)
        assertEquals(PackState.Failed(InstallError.CANCELLED), states.last())
        val part = stagingDir(pack).resolve("model.onnx.part")
        assertTrue(Files.exists(part), ".part must be retained after cancellation")
        assertEquals(30L, Files.size(part))
        assertEquals(30L, installer.bytesOnDisk(pack))
        assertEquals(PackState.NotInstalled, installer.stateOf(pack))
    }

    @Test
    fun `install after cancellation resumes from retained bytes`() = runTest {
        val content = body(100)
        val pack = pack(listOf(serve("model.onnx", content)))
        fetcher.hangAfterBytes[url("model.onnx")] = 30
        val installer = installer()

        val job = launch { installer.install(pack) }
        advanceUntilIdle()
        job.cancel()
        job.join()
        fetcher.hangAfterBytes.clear()

        val result = installer.install(pack)

        assertEquals(PackState.Installed, result)
        assertEquals(30L, fetcher.calls.last().rangeStart)
        assertEquals(100L, fetcher.servedBytes[url("model.onnx")])
        assertTrue(content.contentEquals(Files.readAllBytes(finalDir(pack).resolve("model.onnx"))))
    }

    // ---------------------------------------------------------------- storage

    @Test
    fun `insufficient storage fails without any fetcher calls`() = runTest {
        val pack = pack(listOf(serve("model.onnx", body(100))))
        val states = mutableListOf<PackState>()

        val result = installer(freeBytes = { 1_000L }).install(pack) { states += it }

        assertEquals(PackState.Failed(InstallError.INSUFFICIENT_STORAGE), result)
        assertEquals(listOf<PackState>(PackState.Failed(InstallError.INSUFFICIENT_STORAGE)), states)
        assertTrue(fetcher.calls.isEmpty(), "no network calls when storage is insufficient")
        assertEquals(0L, installer().bytesOnDisk(pack))
    }

    @Test
    fun `storage check counts only remaining bytes plus headroom`() = runTest {
        val headroom = 50_000_000L
        val pack = pack(listOf(serve("model.onnx", body(100))))
        fetcher.failAfterBytes[url("model.onnx")] = 40
        installer().install(pack) // leaves 40 bytes on disk, 60 remaining

        // Exactly remaining + headroom is enough...
        val ok = installer(freeBytes = { 60L + headroom }).install(pack)
        assertEquals(PackState.Installed, ok)

        // ...one byte less is not (fresh pack, nothing on disk).
        val pack2 = pack(listOf(serve("other.onnx", body(100, seed = 11))), id = "other-pack")
        val short = installer(freeBytes = { 100L + headroom - 1 }).install(pack2)
        assertEquals(PackState.Failed(InstallError.INSUFFICIENT_STORAGE), short)
    }

    // ---------------------------------------------------------------- atomic activation

    @Test
    fun `no installed marker exists until every file is verified`() = runTest {
        val a = serve("a.bin", body(50, seed = 12))
        val b = spec("b.bin", body(60, seed = 13)) // no body -> fails
        val pack = pack(listOf(a, b))

        val result = installer().install(pack)

        assertIs<PackState.Failed>(result)
        assertFalse(Files.exists(finalDir(pack).resolve("installed.marker")))
        assertFalse(Files.exists(finalDir(pack)))
        assertEquals(PackState.NotInstalled, installer().stateOf(pack))
        assertNull(installer().installedDir(pack))
    }

    @Test
    fun `installed state survives process restart via fresh installer instance`() = runTest {
        val pack = pack(listOf(serve("model.onnx", body(64))))
        installer().install(pack)

        // Fresh instance = simulated process death; state must come purely from disk.
        val fresh = PackInstaller(root, FakeFetcher())
        assertEquals(PackState.Installed, fresh.stateOf(pack))
        assertEquals(finalDir(pack), fresh.installedDir(pack))
        assertEquals(64L, fresh.bytesOnDisk(pack))
    }

    // ---------------------------------------------------------------- versions and delete

    @Test
    fun `pack with old version marker counts as NotInstalled for new version`() = runTest {
        val content = body(30)
        val v1 = pack(listOf(serve("model.onnx", content)), version = 1)
        installer().install(v1)
        assertEquals(PackState.Installed, installer().stateOf(v1))

        val v2 = pack(listOf(serve("model.onnx", content)), version = 2)
        assertEquals(PackState.NotInstalled, installer().stateOf(v2))
        assertNull(installer().installedDir(v2))
    }

    @Test
    fun `installing a version bump downloads into its own dirs and installs`() = runTest {
        val v1 = pack(listOf(serve("model.onnx", body(30))), version = 1)
        val installer = installer()
        installer.install(v1)

        val v2Content = body(45, seed = 14)
        val v2 = pack(listOf(serve("model.onnx", v2Content)), version = 2)
        val result = installer.install(v2)

        assertEquals(PackState.Installed, result)
        assertEquals(PackState.Installed, installer.stateOf(v2))
        assertTrue(v2Content.contentEquals(Files.readAllBytes(finalDir(v2).resolve("model.onnx"))))
        // v1 still on disk until delete() is called.
        assertTrue(Files.exists(finalDir(v1)))
    }

    @Test
    fun `delete removes installed files partial downloads and stale versions`() = runTest {
        val v1 = pack(listOf(serve("model.onnx", body(30))), version = 1)
        val installer = installer()
        installer.install(v1)

        // Leave a partial download for v2 as well.
        val v2 = pack(listOf(spec("model.onnx", body(60, seed = 15))), version = 2)
        fetcher.bodies[url("model.onnx")] = body(60, seed = 15)
        fetcher.failAfterBytes[url("model.onnx")] = 20
        installer.install(v2)
        assertTrue(installer.bytesOnDisk(v2) > 0)

        installer.delete(v2)

        assertFalse(Files.exists(root.resolve(v2.id)), "entire pack dir incl. old versions must be gone")
        assertEquals(PackState.NotInstalled, installer.stateOf(v1))
        assertEquals(PackState.NotInstalled, installer.stateOf(v2))
        assertEquals(0L, installer.bytesOnDisk(v1))
        assertEquals(0L, installer.bytesOnDisk(v2))
        assertNull(installer.installedDir(v1))
    }

    @Test
    fun `delete of a never-installed pack is a no-op`() = runTest {
        val pack = pack(listOf(spec("model.onnx", body(10))))
        installer().delete(pack) // must not throw
        assertEquals(PackState.NotInstalled, installer().stateOf(pack))
    }

    @Test
    fun `delete of an unreadable tree reports failure instead of throwing`() = runTest {
        val pack = pack(listOf(serve("model.onnx", body(40))))
        val installer = installer()
        installer.install(pack)

        // Files.walk and Files.deleteIfExists raise UncheckedIOException, which is
        // not an IOException: it escaped install()'s catch and killed the download
        // service's coroutine. Whatever the filesystem does here, delete() must
        // return normally.
        val dir = finalDir(pack)
        val readOnly = runCatching { dir.toFile().setWritable(false) }.getOrDefault(false)
        installer.delete(pack) // must not throw, writable or not
        if (readOnly) dir.toFile().setWritable(true)
        installer.delete(pack)
        assertEquals(PackState.NotInstalled, installer.stateOf(pack))
    }

    @Test
    fun `invalidate clears the marker so a corrupt install can be re-downloaded`() = runTest {
        val pack = pack(listOf(serve("model.onnx", body(64))))
        val installer = installer()
        installer.install(pack)
        assertEquals(PackState.Installed, installer.stateOf(pack))

        // The extractor found the payload unusable. Without this the pack reports
        // Installed forever and the only offered repair leads nowhere.
        installer.invalidate(pack)

        assertEquals(PackState.NotInstalled, installer.stateOf(pack))
        assertNull(installer.installedDir(pack))
        assertEquals(PackState.Installed, installer.install(pack))
    }

    @Test
    fun `invalidate on a pack that was never installed is harmless`() {
        val pack = pack(listOf(spec("model.onnx", body(10))))
        installer().invalidate(pack)
        assertEquals(PackState.NotInstalled, installer().stateOf(pack))
    }

    // ------------------------------------------------------------- discard notes

    /**
     * Cancelling a download and reclaiming its bytes happen in two different processes: the
     * screen cancels, the worker in `:ui` is still writing, and the per-pack lock above is a
     * mutex in one process only. So the screen deleted the staging directory while the transfer
     * refilled it, and a hundred megabytes of `.part` survived every cancel with no way to
     * reclaim it. The note is the coordination the two processes do share.
     */
    @Test
    fun `a discard note survives for the party that can act on it, and is read once`() {
        val pack = pack(listOf(spec("model.onnx", body(10))))
        val installer = installer()

        assertFalse(installer.discardRequested(pack), "nothing has asked for anything yet")

        installer.requestDiscard(pack)
        assertTrue(installer.discardRequested(pack), "the worker has to find the note")
        assertFalse(
            installer.discardRequested(pack),
            "and consume it, so the next cancel-by-replace keeps its resumable bytes",
        )
    }

    @Test
    fun `a fresh download supersedes a standing discard note`() {
        val pack = pack(listOf(spec("model.onnx", body(10))))
        val installer = installer()
        installer.requestDiscard(pack)

        installer.clearDiscardRequest(pack)

        assertFalse(installer.discardRequested(pack))
    }

    @Test
    fun `a discard note is not mistaken for an installed pack`() {
        val pack = pack(listOf(spec("model.onnx", body(10))))
        val installer = installer()
        installer.requestDiscard(pack)
        assertEquals(PackState.NotInstalled, installer.stateOf(pack))
        assertEquals(0L, installer.bytesOnDisk(pack))
    }

    // ---------------------------------------------------------------- bytesOnDisk

    @Test
    fun `bytesOnDisk sums partial and completed files across a multi-file pack`() = runTest {
        val a = serve("a.bin", body(50, seed = 16))
        val bContent = body(60, seed = 17)
        val b = spec("b.bin", bContent)
        fetcher.bodies[url("b.bin")] = bContent
        fetcher.failAfterBytes[url("b.bin")] = 20
        val pack = pack(listOf(a, b))
        val installer = installer()

        assertEquals(0L, installer.bytesOnDisk(pack))
        installer.install(pack) // a completes (50), b partial (20)

        assertEquals(70L, installer.bytesOnDisk(pack))

        assertEquals(PackState.Installed, installer.install(pack))
        assertEquals(110L, installer.bytesOnDisk(pack))
    }
}
