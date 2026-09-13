// SPDX-License-Identifier: GPL-3.0-only
package com.supervoiceboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PLAN.md §3.3: the keyboard process must never be the one holding the network.
 *
 * Android grants permissions to an application, not to a process, so INTERNET is
 * app-wide and no test can assert otherwise. What *is* enforceable, and what the
 * rule actually means, is that every component that can reach the network runs
 * in `:ui` and the IME does not — so a compromised or leaking keyboard process
 * has nothing to send with.
 *
 * This reads the source manifest rather than the merged one: the merged manifest
 * only exists after a build, and the thing worth guarding is what a person wrote
 * here, in a fork whose base deliberately requested no INTERNET at all.
 */
class ManifestProcessSplitTest {

    private val manifest: String by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${file.absolutePath}", file.exists())
        file.readText()
    }

    /** The `<service>`/`<activity>`/`<provider>` element that declares [name]. */
    private fun component(name: String): String {
        val at = manifest.indexOf("android:name=\"$name\"")
        assertTrue("no component declares $name", at >= 0)
        val start = manifest.lastIndexOf('<', at)
        val end = manifest.indexOf('>', at)
        return manifest.substring(start, end + 1)
    }

    @Test
    fun `INTERNET is declared only with the reviewed scoping marker`() {
        if (!manifest.contains("android.permission.INTERNET")) return
        assertTrue(
            "INTERNET is declared without the wavekey:internet-scoped marker that " +
                "records why, and which process is allowed to use it",
            manifest.contains("wavekey:internet-scoped"),
        )
    }

    @Test
    fun `the IME runs in the main process, never in ui`() {
        val ime = component("LatinIME")
        assertFalse(
            "LatinIME must stay in the main process; it is the one that must not hold the network",
            ime.contains("android:process"),
        )
    }

    @Test
    fun `every network-touching component runs in the ui process`() {
        // ModelDownloadService is a scheduling object, not a Service: the download
        // itself runs in a WorkManager worker, and WorkManager's own foreground
        // service is the component that has to be pinned.
        val networked = listOf(
            "androidx.work.impl.foreground.SystemForegroundService",
        )
        for (name in networked) {
            assertTrue(
                "$name may download models and so must declare android:process=\":ui\"",
                component(name).contains("android:process=\":ui\""),
            )
        }
    }

    @Test
    fun `the refiner runs in its own process`() {
        assertTrue(
            "the refiner must not share the keyboard's process: a 0.5B model OOM would " +
                "take typing down with it",
            component("com.vboard.app.llm.LlmRefinerService").contains("android:process=\":llm\""),
        )
    }

    @Test
    fun `WorkManager's androidx-startup initializer is removed`() {
        // Without this the keyboard process hosts the downloader's machinery.
        assertTrue(
            "WorkManager's startup initializer must be removed so :ui owns downloads",
            manifest.contains("androidx.work.WorkManagerInitializer") &&
                manifest.contains("tools:node=\"remove\""),
        )
    }
}
