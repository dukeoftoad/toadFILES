package com.toadfiles.app

import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.undoDeleteBatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The shared undo engine (audit A22): the byte-identical restore loop that used to
 * live in Browse/Categories/Recent's `undoDelete` now runs once, here. The per-VM
 * tests keep pinning the wiring; these pin the accounting itself (N5 counts, D4
 * collision renames, D1 rescan hand-off) directly against a real trash volume.
 */
class UndoOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rescanCalls = mutableListOf<List<String>>()
    private val rescan: suspend (List<String>) -> Unit = { rescanCalls += it }

    @Test
    fun `restores a batch with honest counts and rescans the restored paths`() = runBlocking {
        val vol = tmp.newFolder()
        val originDir = File(vol, "docs").apply { mkdirs() }
        val a = File(originDir, "a.txt").apply { writeText("alpha") }
        val b = File(originDir, "b.txt").apply { writeText("beta") }
        val trashed = Trash.trash(listOf(a, b), listOf(vol))
        assertEquals(2, trashed.moved.size)
        assertFalse(a.exists())

        val outcome = undoDeleteBatch(trashed.moved, rescan)

        assertEquals(2, outcome.requested)
        assertEquals(2, outcome.restored)
        assertEquals(0, outcome.renamed)
        assertEquals(0, outcome.gone)
        assertEquals(listOf(a.absolutePath, b.absolutePath), outcome.restoredPaths)
        assertEquals("Restored 2 items", outcome.toastMessage())
        assertTrue(a.isFile && a.readText() == "alpha")
        assertTrue(b.isFile && b.readText() == "beta")
        assertEquals(listOf(outcome.restoredPaths), rescanCalls)
        assertTrue(Trash.entries(listOf(vol)).isEmpty())
    }

    @Test
    fun `payloads that vanished from the trash are reported gone not restored`() = runBlocking {
        val vol = tmp.newFolder()
        val f = File(vol, "vanishing.txt").apply { writeText("poof") }
        val trashed = Trash.trash(listOf(f), listOf(vol))
        assertEquals(1, trashed.moved.size)
        trashed.moved[0].delete() // gone before the undo runs

        val outcome = undoDeleteBatch(trashed.moved, rescan)

        assertEquals(1, outcome.requested)
        assertEquals(0, outcome.restored)
        assertEquals(1, outcome.gone)
        assertEquals("Restored 0 of 1 — 1 already gone", outcome.toastMessage())
        assertEquals(emptyList<String>(), outcome.restoredPaths)
    }

    @Test
    fun `collision renames are counted and reflected in the toast`() = runBlocking {
        val vol = tmp.newFolder()
        val originDir = File(vol, "docs").apply { mkdirs() }
        val f = File(originDir, "note.txt").apply { writeText("first") }
        val trashed = Trash.trash(listOf(f), listOf(vol))
        assertEquals(1, trashed.moved.size)
        // The user created a new note.txt while the original sat in the trash: the
        // restore must not overwrite it — it lands as a renamed sibling (D4).
        File(originDir, "note.txt").writeText("newer")

        val outcome = undoDeleteBatch(trashed.moved, rescan)

        assertEquals(1, outcome.restored)
        assertEquals(1, outcome.renamed)
        assertEquals("Restored 1 item, 1 renamed", outcome.toastMessage())
        assertTrue(File(originDir, "note.txt").readText() == "newer")
        assertTrue(outcome.restoredPaths[0] != f.absolutePath)
    }

    @Test
    fun `empty batch is a zero outcome and touches nothing`() = runBlocking {
        val outcome = undoDeleteBatch(emptyList(), rescan)
        assertEquals(0, outcome.requested)
        assertEquals(0, outcome.restored)
        assertEquals(emptyList<String>(), outcome.restoredPaths)
        assertTrue(rescanCalls.isEmpty())
    }
}
