// SPDX-License-Identifier: GPL-3.0-only
package com.vboard.app.models

import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.model.PackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a scheduled download looks like to a screen in another process.
 *
 * This is the whole of the second QA round's first blocker. The workers run in `:ui` and the
 * settings screen runs in the keyboard's process, so the in-memory state flow this module also
 * keeps is a different map on each side and the screen's copy never hears from the download.
 * Everything the screen can know about a running transfer arrives as a [WorkSnapshot] read back
 * out of WorkManager's shared database, and it is turned into a state here — so the reading
 * that left the control saying "Waiting for Wi-Fi." for two complete 980 MB downloads is a unit
 * test's business rather than an emulator's.
 */
class ModelDownloadProgressTest {

    private val speech = ModelCatalog.byKind(ModelKind.FINAL_ASR).single()

    private fun snapshot(
        enqueued: Boolean = false,
        running: Boolean = false,
        stage: String? = null,
        bytesDone: Long = -1L,
        bytesTotal: Long = -1L,
    ) = ModelDownloadService.WorkSnapshot(
        packId = speech.id,
        enqueued = enqueued,
        running = running,
        stage = stage,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
    )

    @Test
    fun `a running worker's bytes are a download in progress, not a queue`() {
        val scheduled = ModelDownloadService.scheduledOf(
            snapshot(
                running = true,
                stage = ModelDownloadService.STAGE_DOWNLOADING,
                bytesDone = 202_957_556L,
                bytesTotal = speech.totalBytes,
            ),
            connected = true,
        )
        assertEquals(
            PackState.Downloading(202_957_556L, speech.totalBytes),
            scheduled.progress,
        )
        assertFalse(
            scheduled.waitingForNetwork,
            "a worker that is transferring is not waiting for a link it already has",
        )
    }

    @Test
    fun `a worker that has not reported yet is still downloading, at zero`() {
        val scheduled = ModelDownloadService.scheduledOf(snapshot(running = true), connected = true)
        assertEquals(PackState.Downloading(0L, speech.totalBytes), scheduled.progress)
        assertFalse(scheduled.waitingForNetwork)
    }

    @Test
    fun `the install stage is not a percentage`() {
        val scheduled = ModelDownloadService.scheduledOf(
            snapshot(running = true, stage = ModelDownloadService.STAGE_INSTALLING),
            connected = true,
        )
        assertEquals(PackState.Verifying, scheduled.progress)
    }

    @Test
    fun `held with a connection is waiting for an unmetered link`() {
        val scheduled = ModelDownloadService.scheduledOf(snapshot(enqueued = true), connected = true)
        assertNull(scheduled.progress, "nothing has been transferred, so there is no bar")
        assertTrue(scheduled.waitingForNetwork)
    }

    @Test
    fun `held with no connection at all is a different sentence`() {
        val scheduled =
            ModelDownloadService.scheduledOf(snapshot(enqueued = true), connected = false)
        assertFalse(
            scheduled.waitingForNetwork,
            "saying \"waiting for Wi-Fi\" in airplane mode sends the user to look for a router",
        )
    }
}
