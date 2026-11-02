package com.toadfiles.app

import android.net.Uri
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.MediaTrashOps
import com.toadfiles.app.domain.MediaTrashResult
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.TrashResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The shared delete-to-trash pipeline for media-backed surfaces (Categories, Recent):
 * per-entry classification (noPath / stale / trashable), real moves into a volume trash
 * folder, per-file outcome mapping and the honest partial-failure toast. The trash
 * function is injectable, so classification and mapping are proven without touching the
 * disk, and the real [Trash.trash] is exercised end-to-end against a temp volume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaTrashOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Trash double: records what was offered and replays a canned outcome. */
    private class TrashDouble(
        val moved: List<File> = emptyList(),
        val rolledBack: List<File> = emptyList(),
        val failed: List<File> = emptyList(),
    ) : (List<File>, List<File>) -> TrashResult {
        val calls = mutableListOf<List<File>>()
        override fun invoke(files: List<File>, roots: List<File>): TrashResult {
            calls += files
            return TrashResult(moved = moved, rolledBack = rolledBack, failed = failed)
        }
    }

    private fun entry(id: Long, data: String?, name: String = "file$id.txt") =
        MediaRepository.MediaEntry(
            id = id,
            uri = Uri.parse("content://test/$id"),
            name = name,
            size = 1L,
            dateModified = 0L,
            mime = "text/plain",
            category = FileCategory.DOCS,
            data = data,
        )

    // ---------- classification ----------

    @Test
    fun `entries without a path are counted and never offered to the trash`() {
        val vol = tmp.newFolder()
        val live = File(vol, "live.txt").apply { writeText("x") }
        val double = TrashDouble()

        val result = MediaTrashOps.deleteToTrash(
            listOf(entry(1, null, "orphan.txt"), entry(2, live.absolutePath, "live.txt")),
            listOf(vol),
            double,
        )

        assertEquals(1, result.noPathCount)
        // Only the file with a real path reaches the trash pipeline.
        assertEquals(listOf(live.absolutePath), double.calls.single())
        assertTrue(result.staleEntries.isEmpty())
        assertTrue(result.failedEntries.isEmpty())
    }

    @Test
    fun `stale cached paths are skipped, reported separately and never offered to the trash`() {
        val vol = tmp.newFolder()
        val live = File(vol, "live.txt").apply { writeText("x") }
        val staleEntry = entry(1, File(vol, "renamed-away.txt").absolutePath, "renamed-away.txt")
        val liveEntry = entry(2, live.absolutePath, "live.txt")
        val double = TrashDouble()

        val result = MediaTrashOps.deleteToTrash(listOf(staleEntry, liveEntry), listOf(vol), double)

        // Only the real file was offered to the trash. The stale entry (moved or renamed
        // outside the app) landed in NO outcome bucket that would trash or row-drop it.
        assertEquals(listOf(live.absolutePath), double.calls.single())
        assertEquals(listOf(staleEntry), result.staleEntries)
        assertEquals(listOf(liveEntry), result.movedEntries)
        assertTrue(result.failedEntries.isEmpty())
        assertTrue(result.movedFiles.isEmpty())
    }

    @Test
    fun `a dangling symlink is trashable, not stale`() {
        val vol = tmp.newFolder()
        val target = File(vol, "target.txt").apply { writeText("x") }
        val link = File(vol, "link.txt")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        target.delete() // dangling: exists() is false, but the link itself still restores (D7)
        val double = TrashDouble()

        val result = MediaTrashOps.deleteToTrash(
            listOf(entry(1, link.absolutePath, "link.txt")),
            listOf(vol),
            double,
        )

        assertEquals(listOf(link.absolutePath), double.calls.single())
        assertTrue(result.staleEntries.isEmpty())
        assertEquals(listOf("file1.txt"), result.movedEntries.map { it.name })
    }

    // ---------- real trash round-trip ----------

    @Test
    fun `real files move into the volume trash folder and map back to their entries`() {
        val vol = tmp.newFolder()
        val a = File(vol, "a.txt").apply { writeText("a") }
        val b = File(vol, "b.txt").apply { writeText("b") }
        val entries = listOf(entry(1, a.absolutePath, "a.txt"), entry(2, b.absolutePath, "b.txt"))

        val result = MediaTrashOps.deleteToTrash(entries, listOf(vol))

        assertEquals(listOf("a.txt", "b.txt"), result.movedEntries.map { it.name })
        assertEquals(2, result.movedFiles.size)
        val trashDir = Trash.dirFor(vol)
        assertTrue(result.movedFiles.all { it.parentFile == trashDir })
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertTrue(result.staleEntries.isEmpty())
        assertTrue(result.failedEntries.isEmpty())
        assertEquals(0, result.noPathCount)
        // The payloads are real, committed trash entries with their origin metadata.
        assertEquals(2, Trash.entries(listOf(vol)).size)
    }

    // ---------- per-file outcome mapping ----------

    @Test
    fun `partial failures and rollbacks map back to their own entries`() {
        val vol = tmp.newFolder()
        val a = File(vol, "a.txt").apply { writeText("a") }
        val b = File(vol, "b.txt").apply { writeText("b") }
        val c = File(vol, "c.txt").apply { writeText("c") }
        val entries = listOf(
            entry(1, a.absolutePath, "a.txt"),
            entry(2, b.absolutePath, "b.txt"),
            entry(3, c.absolutePath, "c.txt"),
        )
        val target = File(Trash.dirFor(vol), "t1")
        val double = TrashDouble(moved = listOf(target), failed = listOf(b), rolledBack = listOf(c))

        val result = MediaTrashOps.deleteToTrash(entries, listOf(vol), double)

        assertEquals(listOf(target), result.movedFiles)
        assertEquals(listOf("a.txt"), result.movedEntries.map { it.name })
        // A rollback came back to its original path: for the user it "could not be
        // moved", and like a straight failure its row must never be dropped.
        assertEquals(listOf("b.txt", "c.txt"), result.failedEntries.map { it.name })
        assertTrue(result.staleEntries.isEmpty())
    }

    @Test
    fun `every file the trash refused is reported, none counted as moved`() {
        val vol = tmp.newFolder()
        val stuck = File(vol, "stuck.txt").apply { writeText("x") } // exists, but the trash refuses it
        val double = TrashDouble(failed = listOf(stuck))
        val e = entry(1, stuck.absolutePath, "stuck.txt")

        val result = MediaTrashOps.deleteToTrash(listOf(e), listOf(vol), double)

        assertEquals(listOf(e), result.failedEntries)
        assertTrue(result.movedEntries.isEmpty())
        assertTrue(result.movedFiles.isEmpty())
        // Not stale: the caller classified it as trashable, the trash itself refused.
        assertTrue(result.staleEntries.isEmpty())
    }

    // ---------- honest toast buckets ----------

    @Test
    fun `toastFor is null when nothing was skipped or failed`() {
        val result = MediaTrashResult(movedEntries = listOf(entry(1, "/x/a.txt")))
        assertNull(MediaTrashOps.toastFor(result))
    }

    @Test
    fun `toastFor reports the nothing-moved case honestly`() {
        assertEquals("Moved 0 to trash · 1 had no on-disk path", MediaTrashOps.toastFor(MediaTrashResult(noPathCount = 1)))
    }

    @Test
    fun `toastFor merges every skip reason into one honest line`() {
        val result = MediaTrashResult(
            movedEntries = listOf(entry(1, "/x/a.txt"), entry(2, "/x/b.txt")),
            staleEntries = listOf(entry(3, "/x/gone.txt")),
            failedEntries = listOf(entry(4, "/x/stuck.txt")),
            noPathCount = 1,
        )
        assertEquals(
            "Moved 2 to trash · 1 had no on-disk path · " +
                "1 were moved or renamed outside the app and were skipped · 1 could not be moved",
            MediaTrashOps.toastFor(result),
        )
    }
}
