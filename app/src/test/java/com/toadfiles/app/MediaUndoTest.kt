package com.toadfiles.app

import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.data.MediaUndo
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Trash
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The shared media restore-undo engine ([MediaUndo]): honest per-payload accounting
 * (restored / renamed on collision / already gone / still in the trash), re-scan of
 * exactly the restored paths, and the honest delete summaries the media screens
 * report. Pure JVM — [Trash] is file-based and the media source is a fake, so no
 * Robolectric is needed (same shape as TrashTest).
 */
class MediaUndoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Records rescan calls; everything else answers empty, like the VM fakes do. */
    private class RecordingMedia : MediaQuerySource {
        val rescanned = mutableListOf<List<String>>()

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> = emptyList()

        override fun recent(limit: Int): List<MediaRepository.MediaEntry> = emptyList()

        override fun summaries(): List<CategorySummary> = emptyList()

        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0

        override fun rescan(paths: List<String>) {
            rescanned += paths
        }
    }

    @Test
    fun `undo reports a mixed batch with honest accounting and rescans only what came back`() = runTest {
        val vol = tmp.newFolder()
        val origin = File(vol, "docs").apply { mkdirs() }
        val keep = File(origin, "keep.txt").apply { writeText("k") }
        val vanished = File(origin, "vanished.txt").apply { writeText("v") }
        val moved = Trash.trash(listOf(keep, vanished), listOf(vol)).moved
        assertEquals(2, moved.size)
        moved[1].delete() // the second payload disappears from the trash before the undo

        val media = RecordingMedia()
        val outcome = MediaUndo.restore(moved, media)

        assertEquals(2, outcome.requested)
        assertEquals(1, outcome.restored)
        assertEquals(0, outcome.renamed)
        assertEquals(1, outcome.gone)
        assertEquals(0, outcome.stillInTrash)
        assertEquals(listOf(keep.absolutePath), outcome.restoredPaths)
        assertEquals(listOf(listOf(keep.absolutePath)), media.rescanned)
        assertTrue(keep.exists())
        assertFalse(moved[1].exists())
    }

    @Test
    fun `a collision at the origin is counted as a renamed restore`() = runTest {
        val vol = tmp.newFolder()
        val origin = File(vol, "docs").apply { mkdirs() }
        val src = File(origin, "a.txt").apply { writeText("old") }
        val payload = Trash.trash(listOf(src), listOf(vol)).moved.single()
        File(origin, "a.txt").writeText("new occupant") // occupies the original path now

        val outcome = MediaUndo.restore(listOf(payload), RecordingMedia())

        // Collision-safe restore (D4): the payload lands as "a (1).txt" and the count
        // says so — the VM turns it into the ", 1 renamed" note.
        assertEquals(1, outcome.restored)
        assertEquals(1, outcome.renamed)
        assertEquals(0, outcome.gone)
        val restored = File(outcome.restoredPaths.single())
        assertEquals("a (1).txt", restored.name)
        assertEquals("old", restored.readText())
        assertEquals("new occupant", File(origin, "a.txt").readText())
    }

    @Test
    fun `a payload whose restore is refused is accounted as still in the trash`() = runTest {
        val vol = tmp.newFolder()
        val orphan = File(Trash.dirFor(vol).apply { mkdirs() }, "123_orphan.txt").apply { writeText("x") }
        // No sidecar: Trash has no origin for this payload, so restore refuses and the
        // payload stays listed (and restorable) in the trash — not "gone".
        val media = RecordingMedia()

        val outcome = MediaUndo.restore(listOf(orphan), media)

        assertEquals(1, outcome.requested)
        assertEquals(0, outcome.restored)
        assertEquals(0, outcome.gone)
        assertEquals(1, outcome.stillInTrash)
        assertTrue(outcome.restoredPaths.isEmpty())
        assertTrue(orphan.exists())
        assertEquals(listOf(emptyList<String>()), media.rescanned)
    }

    @Test
    fun `delete summary is silent only on full success and names skips and failures`() {
        // Full success: the screen's Undo snackbar reports it — no toast.
        assertNull(MediaUndo.deleteSummary(moved = 2, total = 2, skipped = 0, failed = 0))
        // Pre-filtered skips are named, keeping the long-standing screen wording.
        assertEquals(
            "Moved 0 to trash · 1 had no on-disk path",
            MediaUndo.deleteSummary(moved = 0, total = 1, skipped = 1, failed = 0),
        )
        assertEquals(
            "Moved 1 to trash · 1 had no on-disk path",
            MediaUndo.deleteSummary(moved = 1, total = 2, skipped = 1, failed = 0),
        )
        // Trash failures are no longer swallowed by the skip branch (Browse's tone).
        assertEquals(
            "Moved 1 of 3 to trash (2 failed)",
            MediaUndo.deleteSummary(moved = 1, total = 3, skipped = 0, failed = 2),
        )
        assertEquals(
            "Moved 1 of 3 to trash (1 failed, 1 had no on-disk path)",
            MediaUndo.deleteSummary(moved = 1, total = 3, skipped = 1, failed = 1),
        )
    }
}
