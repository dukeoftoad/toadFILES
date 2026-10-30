package com.toadfiles.app

import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.TrashEntry
import com.toadfiles.app.domain.TrashResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun resetTrashSeams() {
        Trash.clock = { System.currentTimeMillis() }
        Trash.onAfterPendingWrite = null
    }

    private fun volume(): File = tmp.newFolder()

    private fun trashDir(vol: File) = Trash.dirFor(vol)

    private fun metaDir(vol: File) = File(trashDir(vol), Trash.META_DIR_NAME)

    private fun sidecarOf(payload: File): File =
        File(File(payload.parentFile!!, Trash.META_DIR_NAME), payload.name + ".tfmeta")

    @Test
    fun `trash moves a file and restore puts it back`() {
        val vol = volume()
        val src = File(vol, "a.txt").apply { writeText("hello") }

        val trashed = Trash.trash(listOf(src), listOf(vol))
        assertEquals(1, trashed.moved.size)
        assertFalse(src.exists())

        val entries = Trash.entries(listOf(vol))
        assertEquals(1, entries.size)
        assertEquals(src.absolutePath, entries[0].originPath)

        val restored = Trash.restore(trashed.moved[0])
        assertNotNull(restored)
        assertEquals(src.absolutePath, restored!!.absolutePath)
        assertEquals("hello", src.readText())
        assertTrue(Trash.entries(listOf(vol)).isEmpty())
    }

    @Test
    fun `folders go to trash and come back whole`() {
        val vol = volume()
        val dir = File(vol, "pics").apply { mkdirs() }
        File(dir, "1.txt").writeText("x")

        val trashed = Trash.trash(listOf(dir), listOf(vol))
        assertEquals(1, trashed.moved.size)
        assertFalse(dir.exists())

        val restored = Trash.restore(trashed.moved[0])
        assertNotNull(restored)
        assertTrue(File(restored!!, "1.txt").exists())
    }

    @Test
    fun `trashing keeps same-named files apart`() {
        val vol = volume()
        val a = File(vol, "a.txt").apply { writeText("one") }
        val first = Trash.trash(listOf(a), listOf(vol))
        val b = File(vol, "a.txt").apply { writeText("two") }
        val second = Trash.trash(listOf(b), listOf(vol))

        assertEquals(2, Trash.entries(listOf(vol)).size)
        assertEquals("one", first.moved[0].readText())
        assertEquals("two", second.moved[0].readText())
    }

    @Test
    fun `restore never overwrites an existing file`() {
        val vol = volume()
        val src = File(vol, "a.txt").apply { writeText("old") }
        val trashed = Trash.trash(listOf(src), listOf(vol))
        File(vol, "a.txt").writeText("new")

        val restored = Trash.restore(trashed.moved[0])
        assertNotNull(restored)
        assertFalse(restored!!.absolutePath == src.absolutePath)
        assertEquals("old", restored.readText())
        assertEquals("new", File(vol, "a.txt").readText())
    }

    @Test
    fun `metadata write failure keeps the file at its origin`() {
        val vol = volume()
        val src = File(vol, "keep.txt").apply { writeText("precious") }
        val dir = trashDir(vol).apply { mkdirs() }
        val meta = metaDir(vol).apply { mkdirs() }
        assertTrue(meta.setWritable(false))

        try {
            val result = Trash.trash(listOf(src), listOf(vol))

            assertTrue(result.moved.isEmpty())
            assertEquals(1, result.failureCount)
            assertTrue("payload must stay at its origin", src.exists())
            assertEquals("precious", src.readText())
            // Nothing was left half-moved inside the trash folder.
            assertTrue(trashDir(vol).listFiles()!!.filter { it.name != Trash.META_DIR_NAME }.isEmpty())
            assertTrue(Trash.entries(listOf(vol)).isEmpty())
        } finally {
            meta.setWritable(true)
        }
    }

    @Test
    fun `metadata commit failure rolls the payload back to its origin`() {
        val vol = volume()
        Trash.clock = { 1000L }
        val first = File(vol, "a.txt").apply { writeText("one") }
        assertEquals(1, Trash.trash(listOf(first), listOf(vol)).moved.size)

        // Block the next metadata commit: the sidecar slot exists as a directory,
        // so the pending -> sidecar rename must fail.
        File(metaDir(vol), "1000_1_a.txt.tfmeta").mkdirs()

        val second = File(vol, "a.txt").apply { writeText("two") }
        val result = Trash.trash(listOf(second), listOf(vol))

        assertTrue(result.moved.isEmpty())
        assertEquals(listOf(second), result.rolledBack)
        assertTrue("payload must be back at its origin", second.exists())
        assertEquals("two", second.readText())
        assertFalse(File(trashDir(vol), "1000_1_a.txt").exists())
        // The rolled-back item is not listed and no pending sidecar is left behind.
        assertTrue(Trash.entries(listOf(vol)).none { it.file.name == "1000_1_a.txt" })
        assertTrue(metaDir(vol).listFiles()!!.none { it.name.endsWith(".pending") })
    }

    @Test
    fun `purge removes only items past retention`() {
        val vol = volume()
        val old = File(vol, "old.txt").apply { writeText("x") }
        val trashedOld = Trash.trash(listOf(old), listOf(vol))
        // Pretend this item was trashed 40 days ago.
        sidecarOf(trashedOld.moved[0])
            .writeText("origin=${old.absolutePath}\ntrashedAt=${System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000}")
        val fresh = File(vol, "new.txt").apply { writeText("y") }
        Trash.trash(listOf(fresh), listOf(vol))

        assertEquals(1, Trash.purge(listOf(vol)))
        val left = Trash.entries(listOf(vol))
        assertEquals(1, left.size)
        assertEquals("new.txt", left[0].originPath.substringAfterLast('/'))
    }

    @Test
    fun `empty wipes everything`() {
        val vol = volume()
        val files = (1..3).map { i -> File(vol, "f$i.txt").apply { writeText("x$i") } }
        Trash.trash(files, listOf(vol))

        assertEquals(3, Trash.empty(listOf(vol)))
        assertTrue(Trash.entries(listOf(vol)).isEmpty())
    }

    @Test
    fun `volumeRootOf picks the longest matching root`() {
        val vol = volume()
        val sub = File(vol, "sub").apply { mkdirs() }
        assertEquals(sub, Trash.volumeRootOf(File(sub, "a.txt"), listOf(vol, sub)))
        assertEquals(vol, Trash.volumeRootOf(File(vol, "other/b.txt"), listOf(vol, sub)))
    }

    @Test
    fun `a user file named like metadata stays visible in the trash`() {
        val vol = volume()
        val src = File(vol, "notes.tfmeta").apply { writeText("payload") }

        val result = Trash.trash(listOf(src), listOf(vol))
        assertEquals(1, result.moved.size)

        val entries = Trash.entries(listOf(vol))
        assertEquals(1, entries.size)
        assertEquals(src.absolutePath, entries[0].originPath)
        assertEquals("payload", entries[0].file.readText())

        val restored = Trash.restore(result.moved[0])
        assertNotNull(restored)
        assertEquals("payload", src.readText())
    }

    @Test
    fun `legacy sidecars next to the payload are migrated into the meta folder`() {
        val vol = volume()
        val dir = trashDir(vol).apply { mkdirs() }
        val payload = File(dir, "42_old.txt").apply { writeText("x") }
        File(dir, "42_old.txt.tfmeta")
            .writeText("origin=/somewhere/old.txt\ntrashedAt=12345")

        val entries = Trash.entries(listOf(vol))

        assertEquals(1, entries.size)
        assertEquals("/somewhere/old.txt", entries[0].originPath)
        assertEquals(12345L, entries[0].trashedAt)
        assertFalse("the sidecar must move out of the payload folder", File(dir, "42_old.txt.tfmeta").exists())
        assertTrue(sidecarOf(payload).exists())
    }

    @Test
    fun `orphan sidecars are pruned and never listed`() {
        val vol = volume()
        metaDir(vol).apply { mkdirs() }
        File(metaDir(vol), "999_gone.txt.tfmeta").writeText("origin=/x\ntrashedAt=1")
        File(metaDir(vol), "888_never-happened.txt.tfmeta.pending").writeText("origin=/y\ntrashedAt=1")

        assertTrue(Trash.entries(listOf(vol)).isEmpty())
        assertFalse(File(metaDir(vol), "999_gone.txt.tfmeta").exists())
        assertFalse(File(metaDir(vol), "888_never-happened.txt.tfmeta.pending").exists())
    }

    @Test
    fun `deleteForever keeps the sidecar when the payload cannot be deleted`() {
        val vol = volume()
        val src = File(vol, "stuck.txt").apply { writeText("x") }
        val trashed = Trash.trash(listOf(src), listOf(vol)).moved[0]
        val dir = trashDir(vol)
        assertTrue(dir.setWritable(false))

        try {
            assertFalse(Trash.deleteForever(trashed))
            assertTrue("payload survives", trashed.exists())
            assertTrue("metadata survives for retry and restore", sidecarOf(trashed).exists())
            assertEquals(1, Trash.entries(listOf(vol)).size)
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun `deleteForever removes the payload first and the sidecar after`() {
        val vol = volume()
        val src = File(vol, "gone.txt").apply { writeText("x") }
        val trashed = Trash.trash(listOf(src), listOf(vol)).moved[0]

        assertTrue(Trash.deleteForever(trashed))

        assertFalse(trashed.exists())
        assertFalse(sidecarOf(trashed).exists())
        assertTrue(Trash.entries(listOf(vol)).isEmpty())
    }

    @Test
    fun `a failed restore keeps the payload and its metadata`() {
        val vol = volume()
        val src = File(vol, "comeback.txt").apply { writeText("x") }
        val trashed = Trash.trash(listOf(src), listOf(vol)).moved[0]
        val dir = trashDir(vol)
        assertTrue(dir.setWritable(false))

        try {
            assertNull(Trash.restore(trashed))
            assertTrue("payload still in the trash", trashed.exists())
            assertTrue("metadata still committed", sidecarOf(trashed).exists())
            assertEquals(1, Trash.entries(listOf(vol)).size)
        } finally {
            dir.setWritable(true)
        }
    }

    // ---------- audit P1.3: crash between payload move and metadata commit ----------

    /** Simulates process death exactly between step 2 (payload rename) and step 3
     *  (sidecar commit): payload in the trash, valid ".pending" record, no committed
     *  sidecar. The next entries() must recover it: listed with its real origin and
     *  restorable (audit P1.3). */
    @Test
    fun `pending record after crash is recovered and restorable`() {
        val vol = volume()
        // The crash already renamed the origin away: it must NOT exist on disk anymore.
        val originPath = File(vol, "crashed.txt").absolutePath
        val dir = trashDir(vol).apply { mkdirs() }
        metaDir(vol).mkdirs()
        val payload = File(dir, "123_crashed.txt").apply { writeText("survivor") }
        val pending = File(metaDir(vol), payload.name + ".tfmeta.pending")
            .apply { writeText("origin=$originPath\ntrashedAt=123") }

        val entries = Trash.entries(listOf(vol))
        assertEquals(1, entries.size)
        assertEquals(originPath, entries[0].originPath)
        // The pending record was promoted to a committed sidecar…
        val committed = File(metaDir(vol), payload.name + ".tfmeta")
        assertTrue(committed.exists())
        assertFalse(pending.exists())

        // …and restore works without any further recovery pass.
        val restored = Trash.restore(entries[0].file)
        assertNotNull(restored)
        assertEquals(originPath, restored!!.absolutePath)
        assertEquals("survivor", File(originPath).readText())
        assertTrue(Trash.entries(listOf(vol)).isEmpty())
    }

    /** Same crash window, but the item is restored BEFORE any listing ran: the pending
     *  fallback in readMeta must keep restore working even without promotion. */
    @Test
    fun `restore reads the pending record directly after a crash`() {
        val vol = volume()
        val originPath = File(vol, "direct.txt").absolutePath
        val dir = trashDir(vol).apply { mkdirs() }
        metaDir(vol).mkdirs()
        val payload = File(dir, "777_direct.txt").apply { writeText("direct") }
        File(metaDir(vol), payload.name + ".tfmeta.pending")
            .writeText("origin=$originPath\ntrashedAt=777")

        val restored = Trash.restore(payload)
        assertNotNull(restored)
        assertEquals(originPath, restored!!.absolutePath)
        assertEquals("direct", File(originPath).readText())
    }

    /** A pending record whose payload never made it (process died BEFORE the payload
     *  rename) is an orphan: no phantom entry, and the pending file is cleaned up. */
    @Test
    fun `orphan pending record without payload is pruned and not listed`() {
        val vol = volume()
        trashDir(vol).mkdirs()
        metaDir(vol).mkdirs()
        val pending = File(metaDir(vol), "999_ghost.txt.tfmeta.pending")
            .apply { writeText("origin=/gone/g.txt\ntrashedAt=999") }

        val entries = Trash.entries(listOf(vol))
        assertTrue(entries.isEmpty())
        assertFalse(pending.exists())
    }

    /** Corrupt pending records (invalid schema) are never promoted — but the payload
     *  is still listed (with unknown origin) instead of being hidden from the user. */
    @Test
    fun `corrupt pending record is not promoted but payload stays listed`() {
        val vol = volume()
        val dir = trashDir(vol).apply { mkdirs() }
        metaDir(vol).mkdirs()
        val payload = File(dir, "555_corrupt.txt").apply { writeText("data") }
        val pending = File(metaDir(vol), payload.name + ".tfmeta.pending")
            .apply { writeText("this is not metadata") }

        val entries = Trash.entries(listOf(vol))
        assertEquals(1, entries.size)
        // No committed sidecar appeared…
        assertFalse(File(metaDir(vol), payload.name + ".tfmeta").exists())
        // …and pruning removed the useless pending record only after the listing kept
        // the payload visible.
        assertFalse(pending.exists())
        assertTrue(payload.exists())
    }

    /** Audit P2.7 collision test: a user's real "*.tfmeta" payload with a same-basename
     *  sibling is NOT migrated into .meta — only records with the valid metadata schema
     *  are. Both files stay listed as payloads. */
    @Test
    fun `user tfmeta file with sibling is not mistaken for legacy metadata`() {
        val vol = volume()
        val dir = trashDir(vol).apply { mkdirs() }
        // A user file (already trashed as a payload) that happens to end in .tfmeta…
        val userPayload = File(dir, "notes.tfmeta").apply { writeText("my own notes, not metadata") }
        // …and a sibling payload with the basename "notes".
        File(dir, "notes").apply { writeText("the sibling") }

        val entries = Trash.entries(listOf(vol))
        val names = entries.map { it.file.name }.toSet()
        assertTrue("notes.tfmeta" in names)
        assertTrue("notes" in names)
        // Nothing was migrated into .meta — the user file is still a payload.
        assertFalse(File(metaDir(vol), "notes.tfmeta").exists())
    }

    /** Audit P2.7, stricter: even a "*.tfmeta" file whose content parses as valid
     *  metadata is not migrated unless its origin actually names the sibling payload. */
    @Test
    fun `valid-looking metadata that does not reference the sibling stays a payload`() {
        val vol = volume()
        val dir = trashDir(vol).apply { mkdirs() }
        // Parses as perfectly valid metadata — but for "other.txt", not for "notes".
        val userPayload = File(dir, "notes.tfmeta")
            .apply { writeText("origin=/elsewhere/other.txt\ntrashedAt=99") }
        File(dir, "notes").apply { writeText("the sibling") }

        val entries = Trash.entries(listOf(vol))
        val names = entries.map { it.file.name }.toSet()
        assertTrue("notes.tfmeta" in names)
        assertTrue("notes" in names)
        assertFalse("nothing was migrated into .meta", File(metaDir(vol), "notes.tfmeta").exists())
        assertEquals(
            "the user file was not touched",
            "origin=/elsewhere/other.txt\ntrashedAt=99",
            userPayload.readText(),
        )
    }

    /** Positive control for the strict validation: a genuine legacy sidecar for a
     *  clash-renamed payload ("<stamp>_<n>_<name>") still migrates — the origin matches
     *  once the stamp groups are stripped. */
    @Test
    fun `legacy sidecar for a clash-renamed payload still migrates`() {
        val vol = volume()
        val dir = trashDir(vol).apply { mkdirs() }
        val payload = File(dir, "123_2_dup.txt").apply { writeText("x") }
        File(dir, "123_2_dup.txt.tfmeta")
            .writeText("origin=/somewhere/dup.txt\ntrashedAt=7")

        val entries = Trash.entries(listOf(vol))

        assertEquals(1, entries.size)
        assertEquals("/somewhere/dup.txt", entries[0].originPath)
        assertFalse(File(dir, "123_2_dup.txt.tfmeta").exists())
        assertTrue(sidecarOf(payload).exists())
    }

    /** Positive control: an original name that itself starts with "<digits>_" must not
     *  be over-stripped — the sidecar still migrates with its real origin. */
    @Test
    fun `legacy sidecar for an original name starting with digits still migrates`() {
        val vol = volume()
        val dir = trashDir(vol).apply { mkdirs() }
        val payload = File(dir, "1000_2024_report.txt").apply { writeText("x") }
        File(dir, "1000_2024_report.txt.tfmeta")
            .writeText("origin=/somewhere/2024_report.txt\ntrashedAt=8")

        val entries = Trash.entries(listOf(vol))

        assertEquals(1, entries.size)
        assertEquals("/somewhere/2024_report.txt", entries[0].originPath)
        assertTrue(sidecarOf(payload).exists())
    }

    // ---------- audit P2.6: a listing must never prune an in-flight transaction ----------

    /** Deterministic barrier test (audit P2.6): trash() parks in the window between the
     *  pending-sidecar write and the payload rename, and only then is a listing started.
     *  The one repository lock must hold the listing back until the transaction commits:
     *  the pending record is promoted (not pruned), trash() completes without a
     *  rollback, and the item ends up listed and restorable. Latches only — no sleeps. */
    @Test
    fun `listing started mid-transaction never prunes the in-flight pending record`() {
        val vol = volume()
        val src = File(vol, "inflight.txt").apply { writeText("data") }
        Trash.clock = { 4242L }

        val parked = CountDownLatch(1)
        val listingStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        Trash.onAfterPendingWrite = {
            parked.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        /** Daemon threads: a parked latch must never keep the test JVM alive after a failure. */
        fun thread(name: String, block: () -> Unit): Thread =
            Thread({ block() }, name).apply { isDaemon = true }

        var trashResult: TrashResult? = null
        val trashing = thread("trash-in-flight") {
            trashResult = Trash.trash(listOf(src), listOf(vol))
        }.apply { start() }
        try {
            assertTrue("trash() never reached the pending window", parked.await(10, TimeUnit.SECONDS))

            // Parked exactly between the pending write and the payload rename.
            val pending = File(metaDir(vol), "4242_inflight.txt.tfmeta.pending")
            assertTrue("pending record is on disk", pending.exists())
            assertFalse("payload not moved yet", File(trashDir(vol), "4242_inflight.txt").exists())
            assertTrue("origin untouched while parked", src.exists())

            var listed: List<TrashEntry> = emptyList()
            val listing = thread("trash-listing") {
                listingStarted.countDown()
                listed = Trash.entries(listOf(vol))
            }.apply { start() }
            assertTrue("listing never started", listingStarted.await(10, TimeUnit.SECONDS))

            // The listing may only proceed once the transaction released the lock.
            release.countDown()
            trashing.join(TimeUnit.SECONDS.toMillis(10))
            listing.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse("trash() did not finish", trashing.isAlive)
            assertFalse("listing did not finish", listing.isAlive)

            // The transaction completed: no rollback, the pending record was promoted.
            val result = trashResult!!
            assertEquals(listOf(File(trashDir(vol), "4242_inflight.txt")), result.moved)
            assertTrue(result.rolledBack.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertFalse("pending record was pruned mid-transaction", pending.exists())
            assertTrue(File(metaDir(vol), "4242_inflight.txt.tfmeta").exists())

            // The listing saw the committed item…
            assertEquals(1, listed.size)
            assertEquals(src.absolutePath, listed[0].originPath)

            // …and it is restorable.
            val restored = Trash.restore(listed[0].file)
            assertNotNull(restored)
            assertEquals(src.absolutePath, restored!!.absolutePath)
            assertEquals("data", src.readText())
        } finally {
            release.countDown() // never leave the parked transaction hanging
            if (trashing.isAlive) trashing.join(TimeUnit.SECONDS.toMillis(10))
            Trash.onAfterPendingWrite = null
        }
    }
}
