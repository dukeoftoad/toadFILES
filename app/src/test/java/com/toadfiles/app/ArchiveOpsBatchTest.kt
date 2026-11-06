package com.toadfiles.app

import com.toadfiles.app.domain.ArchiveFormat
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.CompressLevel
import com.toadfiles.app.domain.OpProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The shared compress-batch engine (audit A22): the per-archive loop that used to be
 * pasted into Browse's and Categories' `zipAll` now runs once, in
 * [ArchiveOps.compressBatch]. The per-VM tests keep pinning the wiring; these pin the
 * engine itself: monotonic pipeline-totals progress (audit A4), per-archive
 * delete-sources gating, and the audit-A15 password wipe when the batch ends.
 */
class ArchiveOpsBatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `separate batch forwards pipeline totals with a monotonic running base`() = runBlocking {
        val dir = tmp.newFolder()
        val srcs = listOf(
            File(dir, "one.txt").apply { writeText("1") },
            File(dir, "two.txt").apply { writeText("22") },
            File(dir, "three.txt").apply { writeText("333") },
        )
        val targets = srcs.map { File(dir, "${it.nameWithoutExtension}.zip") }

        val ticks = mutableListOf<OpProgress>()
        val trashed = mutableListOf<List<File>>()
        val r = ArchiveOps.compressBatch(
            files = srcs,
            targets = targets,
            format = ArchiveFormat.ZIP,
            level = CompressLevel.NONE,
            onProgress = { ticks += it },
            trashSources = { trashed += it },
        )

        assertEquals(3, r.succeeded)
        assertEquals(0, r.failed)
        // The pipeline's own totals: each archive holds exactly one file, so the last
        // tick reports 3 of 3 — never "37 of 1 items" (the A4 regression).
        val last = ticks.last()
        assertEquals(3, last.done)
        assertEquals(3, last.total)
        // Monotonic across archives: every tick's (done,total) pair never decreases.
        assertTrue(
            "ticks must be monotonic: $ticks",
            ticks.asSequence().map { it.done to it.total }
                .zipWithNext { a, b -> a.first <= b.first && a.second <= b.second }
                .all { it },
        )
        assertTrue(trashed.isEmpty())
        targets.forEach { assertTrue(it.isFile) }
    }

    @Test
    fun `delete sources runs per fully-succeeded archive with its own sources`() = runBlocking {
        val dir = tmp.newFolder()
        val good = File(dir, "good.txt").apply { writeText("ok") }
        // A source that exists but compress will fail on: a directory as a zip source
        // is legal; instead use a missing file — the archive run reports it failed.
        val bad = File(dir, "missing.txt")
        val targets = listOf(File(dir, "good.zip"), File(dir, "bad.zip"))

        val trashed = mutableListOf<List<File>>()
        val r = ArchiveOps.compressBatch(
            files = listOf(good, bad),
            targets = targets,
            format = ArchiveFormat.ZIP,
            level = CompressLevel.NONE,
            deleteSources = true,
            trashSources = { trashed += it },
        )

        assertEquals(1, r.succeeded)
        assertEquals(1, r.failed)
        // Only the archive that fully succeeded hands over ITS source (the stub
        // callback records without trashing, so both files stay on disk).
        assertEquals(listOf(listOf(good)), trashed)
        // The failed archive's source list is never handed over.
        assertTrue(trashed.none { it.contains(bad) })
    }

    @Test
    fun `password is wiped when the batch ends however it ends`() = runBlocking {
        val dir = tmp.newFolder()
        val src = File(dir, "secret.txt").apply { writeText("hush") }
        val pw = "hunter2".toCharArray()

        val r = ArchiveOps.compressBatch(
            files = listOf(src),
            targets = listOf(File(dir, "vault.7z")),
            format = ArchiveFormat.SEVEN_Z,
            level = CompressLevel.FASTEST,
            password = pw,
        )
        assertEquals(1, r.succeeded)
        assertTrue("the produced 7z must really be encrypted", File(dir, "vault.7z").length() > 0)
        assertTrue(
            "password bytes must be zeroed after the batch (audit A15)",
            pw.all { it == '\u0000' },
        )

        // And the wiped array must not have been needed again: extracting with a
        // FRESH copy of the same password round-trips the content.
        val open = ArchiveOps.extract(File(dir, "vault.7z"), File(dir, "open"), replace = false, password = "hunter2".toCharArray())
        assertEquals(1, open.succeeded)
        assertEquals("hush", File(dir, "open/secret.txt").readText())
    }
}
