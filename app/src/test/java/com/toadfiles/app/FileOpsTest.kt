package com.toadfiles.app

import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `copies files recursively`() = runBlocking {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        File(src, "a.txt").writeText("AAA")
        val sub = File(src, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeText("BBB")
        val r = FileOps.copy(listOf(src), dst, ConflictStrategy.RENAME)
        assertEquals(1, r.succeeded)
        assertEquals(0, r.failed)
        assertEquals("AAA", File(dst, "src/a.txt").readText())
        assertEquals("BBB", File(dst, "src/sub/b.txt").readText())
    }

    @Test
    fun `conflict rename keeps both files`() = runBlocking {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        File(src, "note.txt").writeText("incoming")
        File(dst, "note.txt").writeText("existing")
        val r = FileOps.copy(listOf(File(src, "note.txt")), dst, ConflictStrategy.RENAME)
        assertEquals(1, r.succeeded)
        assertEquals("existing", File(dst, "note.txt").readText())
        assertEquals("incoming", File(dst, "note (1).txt").readText())
    }

    @Test
    fun `conflict skip leaves target untouched`() = runBlocking {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        File(src, "note.txt").writeText("incoming")
        File(dst, "note.txt").writeText("existing")
        val r = FileOps.copy(listOf(File(src, "note.txt")), dst, ConflictStrategy.SKIP)
        assertEquals(1, r.skipped)
        assertEquals("existing", File(dst, "note.txt").readText())
    }

    @Test
    fun `conflict replace overwrites`() = runBlocking {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        File(src, "note.txt").writeText("incoming")
        File(dst, "note.txt").writeText("existing")
        val r = FileOps.copy(listOf(File(src, "note.txt")), dst, ConflictStrategy.REPLACE)
        assertEquals(1, r.succeeded)
        assertEquals("incoming", File(dst, "note.txt").readText())
    }

    @Test
    fun `copying folder into itself is rejected`() = runBlocking {
        val src = tmp.newFolder("src")
        val sub = File(src, "sub").apply { mkdirs() }
        var message: String? = null
        try {
            FileOps.copy(listOf(src), sub, ConflictStrategy.RENAME)
        } catch (e: Exception) {
            message = e.message
        }
        assertNotNull(message)
        assertTrue(message!!.contains("itself"))
    }

    @Test
    fun `move relocates and removes source`() = runBlocking {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        val f = File(src, "m.txt").apply { writeText("MOVE") }
        val r = FileOps.move(listOf(f), dst, ConflictStrategy.RENAME)
        assertEquals(1, r.succeeded)
        assertFalse(f.exists())
        assertEquals("MOVE", File(dst, "m.txt").readText())
    }

    @Test
    fun `missing source is skipped and reported`() = runBlocking {
        val dst = tmp.newFolder("dst")
        val ghost = File(tmp.root, "ghost.txt")
        val r = FileOps.copy(listOf(ghost), dst, ConflictStrategy.RENAME)
        assertEquals(1, r.skipped)
        assertEquals(0, r.failed)
    }

    @Test
    fun `delete removes nested trees`() = runBlocking {
        val dir = tmp.newFolder("deleteme")
        File(File(dir, "x").apply { mkdirs() }, "y.txt").writeText("1")
        val r = FileOps.delete(listOf(dir))
        assertEquals(1, r.succeeded)
        assertFalse(dir.exists())
    }

    @Test
    fun `rename rejects invalid names`() = runBlocking {
        val dir = tmp.newFolder("rn")
        val f = File(dir, "old.txt").apply { writeText("x") }
        assertNull(FileOps.rename(f, "sub/dir.txt"))
        assertNull(FileOps.rename(f, "  "))
        assertNull(FileOps.rename(f, "old.txt")) // collision with itself
        val renamed = FileOps.rename(f, "new.txt")
        assertNotNull(renamed)
        assertTrue(File(dir, "new.txt").exists())
    }

    @Test
    fun `progress callback reports totals`() = runBlocking {
        val src = tmp.newFolder("src2")
        val dst = tmp.newFolder("dst2")
        repeat(3) { File(src, "f$it.txt").writeText("x") }
        var lastTotal = 0
        FileOps.copy(listOf(src), dst, ConflictStrategy.RENAME, onProgress = { p -> lastTotal = p.total })
        assertEquals(1, lastTotal)
    }

    // ---------- data safety: symlinks ----------

    private fun createSymlink(link: File, target: File) {
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    @Test
    fun `deleting a folder symlink unlinks it and spares the linked folder`() = runBlocking {
        val target = tmp.newFolder("linked-target")
        File(target, "data.txt").writeText("keep me")
        val link = File(tmp.root, "link-to-folder")
        createSymlink(link, target)

        val r = FileOps.delete(listOf(link))

        assertEquals(1, r.succeeded)
        assertFalse(link.exists())
        assertTrue("the linked folder must survive", target.exists())
        assertEquals("keep me", File(target, "data.txt").readText())
    }

    @Test
    fun `deleting a dangling symlink removes the link`() = runBlocking {
        val link = File(tmp.root, "dangling")
        createSymlink(link, File(tmp.root, "never-existed"))

        val r = FileOps.delete(listOf(link))

        assertEquals(1, r.succeeded)
        assertFalse(link.exists())
    }

    @Test
    fun `a symlink cycle inside a deleted folder cannot hang or escape`() = runBlocking {
        val dir = tmp.newFolder("cyclic")
        val child = File(dir, "child").apply { mkdirs() }
        createSymlink(File(child, "loop"), dir)

        val r = FileOps.delete(listOf(dir))

        assertEquals(1, r.succeeded)
        assertFalse(dir.exists())
    }

    @Test
    fun `copying a tree never follows a folder symlink`() = runBlocking {
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("linked content")
        val tree = tmp.newFolder("tree")
        File(tree, "real.txt").writeText("real")
        createSymlink(File(tree, "shortcut"), outside)
        val dst = tmp.newFolder("dst")

        val r = FileOps.copy(listOf(tree), dst, ConflictStrategy.RENAME)

        assertEquals(1, r.failed)
        assertTrue(r.failures.any { it.contains("shortcut") })
        assertEquals("real", File(dst, "tree/real.txt").readText())
        assertFalse("the linked folder must not be expanded into the copy", File(dst, "tree/shortcut").exists())
        assertEquals("linked content", File(outside, "secret.txt").readText())
    }

    @Test
    fun `sizeOf does not descend into folder symlinks`() {
        val outside = tmp.newFolder("big-target")
        File(outside, "big.bin").writeText("x".repeat(10_000))
        val dir = tmp.newFolder("small")
        createSymlink(File(dir, "link"), outside)

        val size = FileOps.sizeOf(dir)

        assertTrue("expected only the link itself, got $size", size < 10_000)
    }

    // ---------- data safety: REPLACE keeps the old destination ----------

    @Test
    fun `REPLACE copy failure preserves the old destination and cleans the staging copy`() = runBlocking {
        val dst = tmp.newFolder("replace-dst")
        File(dst, "note.txt").writeText("existing")
        val src = tmp.newFolder("replace-src")
        // The incoming tree fails halfway: an unreadable subfolder cannot be listed.
        val incoming = File(src, "note.txt").apply { mkdirs() }
        File(incoming, "ok.txt").writeText("new")
        val locked = File(incoming, "locked").apply { mkdirs() }
        File(locked, "hidden.txt").writeText("x")
        assertTrue(locked.setReadable(false).also { locked.setExecutable(false) })

        try {
            val r = FileOps.copy(listOf(incoming), dst, ConflictStrategy.REPLACE)

            assertEquals(1, r.failed)
            assertEquals("existing", File(dst, "note.txt").readText())
            assertEquals(
                "no staging leftovers may remain",
                listOf("note.txt"),
                dst.listFiles()!!.map { it.name }.sorted(),
            )
        } finally {
            locked.setReadable(true)
            locked.setExecutable(true)
        }
    }

    @Test
    fun `REPLACE copy success leaves exactly one complete destination`() = runBlocking {
        val dst = tmp.newFolder("replace-ok")
        File(dst, "note.txt").writeText("old")
        val src = tmp.newFolder("replace-ok-src")
        val incoming = File(src, "note.txt").apply { mkdirs() }
        File(incoming, "a.txt").writeText("A")
        File(File(incoming, "sub").apply { mkdirs() }, "b.txt").writeText("B")

        val r = FileOps.copy(listOf(incoming), dst, ConflictStrategy.REPLACE)

        assertEquals(1, r.succeeded)
        assertEquals(0, r.failed)
        assertEquals("A", File(dst, "note.txt/a.txt").readText())
        assertEquals("B", File(dst, "note.txt/sub/b.txt").readText())
        assertEquals(
            "no staging or backup leftovers may remain",
            listOf("note.txt"),
            dst.listFiles()!!.map { it.name },
        )
    }

    @Test
    fun `REPLACE move failure preserves the old destination and the source`() = runBlocking {
        val dst = tmp.newFolder("move-dst")
        File(dst, "item.txt").writeText("existing")
        val src = tmp.newFolder("move-src")
        val incoming = File(src, "item.txt").apply { mkdirs() }
        File(incoming, "new.txt").writeText("new")
        // A read-only target folder cannot even have its destination renamed aside.
        assertTrue(dst.setWritable(false))

        try {
            val r = FileOps.move(listOf(incoming), dst, ConflictStrategy.REPLACE)

            assertEquals(1, r.failed)
            assertEquals("existing", File(dst, "item.txt").readText())
            assertEquals("new", File(incoming, "new.txt").readText())
        } finally {
            dst.setWritable(true)
        }
    }

    @Test
    fun `REPLACE move success swaps the content and leaves no backup behind`() = runBlocking {
        val dst = tmp.newFolder("move-ok")
        File(dst, "item.txt").writeText("old")
        val src = tmp.newFolder("move-ok-src")
        val incoming = File(src, "item.txt").apply { mkdirs() }
        File(incoming, "new.txt").writeText("new")

        val r = FileOps.move(listOf(incoming), dst, ConflictStrategy.REPLACE)

        assertEquals(1, r.succeeded)
        assertFalse(incoming.exists())
        assertEquals("new", File(dst, "item.txt/new.txt").readText())
        assertEquals(
            "no staging or backup leftovers may remain",
            listOf("item.txt"),
            dst.listFiles()!!.map { it.name },
        )
    }

    // ---------- cancellation inside the recursive / streaming steps (P2.4) ----------

    @Test
    fun `cancelling mid copy stops the recursion and removes the partial destination`() = runBlocking {
        val src = tmp.newFolder("cancel-copy-src")
        val dst = tmp.newFolder("cancel-copy-dst")
        File(src, "a.txt").writeText("A")
        File(src, "b.txt").writeText("B")
        File(src, "c.txt").writeText("C")
        var checks = 0
        // Checkpoint sequence: dir entry, then per 1-byte file: entry + 2 read-loop
        // iterations — so call 5 is the entry of the second file: the first file is
        // already on disk (a partial tree), the rest was never touched.
        val r = FileOps.copy(
            listOf(src), dst, ConflictStrategy.RENAME,
            checkCancelled = { if (++checks == 5) throw CancellationException("cancel after the first file") },
        )

        assertTrue(r.cancelled)
        assertEquals(0, r.succeeded)
        assertEquals(0, r.failed)
        assertTrue("cancellation is never reported as a per-item failure", r.failures.isEmpty())
        assertFalse("the half-written destination must be cleaned up", File(dst, "src").exists())
    }

    @Test
    fun `cancelling mid delete stops the walk with the rest of the tree intact`() = runBlocking {
        val dir = tmp.newFolder("cancel-delete")
        repeat(5) { File(dir, "f$it.txt").writeText("x") }
        var checks = 0
        // Walk checkpoints: pre-visit of the folder, then one before each file deletion —
        // call 4 cancels after exactly two files are gone.
        val r = FileOps.delete(
            listOf(dir),
            checkCancelled = { if (++checks == 4) throw CancellationException("cancel after two files") },
        )

        assertTrue(r.cancelled)
        assertEquals(0, r.succeeded)
        assertEquals(0, r.failed)
        assertEquals("exactly two files were deleted before the stop", 3, dir.listFiles()!!.size)
    }

    @Test
    fun `cancelling the running job stops a copy at the next checkpoint`() = runBlocking {
        val dst = tmp.newFolder("job-cancel-dst")
        // Folder names must match the paths asserted below (copy preserves source names).
        val d1 = tmp.newFolder("d1")
        val d2 = tmp.newFolder("d2")
        File(d1, "x.txt").writeText("x")
        File(d2, "y.txt").writeText("y")
        var job: Job? = null
        val job2 = launch {
            try {
                FileOps.copy(listOf(d1, d2), dst, ConflictStrategy.RENAME, onProgress = { p ->
                    if (p.done == 1) job?.cancel()
                })
            } catch (_: CancellationException) {
                // cooperative cancellation surfaces as cancellation of the caller job
            }
        }
        job = job2
        job2.join()

        assertEquals("the first source finished before the cancel", "x", File(dst, "d1/x.txt").readText())
        assertFalse("the second source must not have been copied", File(dst, "d2").exists())
    }

    // ---------- cross-volume moves keep the copy when source cleanup fails (P2.10) ----------

    @Test
    fun `move with failing source cleanup keeps the copy and reports failure`() = runBlocking {
        val srcParent = tmp.newFolder("mv-cleanup-src")
        val dst = tmp.newFolder("mv-cleanup-dst")
        val src = File(srcParent, "item").apply { mkdirs() }
        File(src, "data.txt").writeText("DATA")
        // The cross-volume (staging) route is forced by making the same-volume rename
        // fail (non-writable parent), and the post-publish source deletion fail too
        // (non-writable source). Requires a non-root test user, like the tests above.
        assertTrue(srcParent.setWritable(false))
        assertTrue(src.setWritable(false))

        try {
            val r = FileOps.move(listOf(src), dst, ConflictStrategy.RENAME)

            assertEquals(0, r.succeeded)
            assertEquals(1, r.failed)
            assertTrue(r.failures.any { it.contains("the original could not be deleted") })
            assertEquals("the copy must be intact at the destination", "DATA", File(dst, "item/data.txt").readText())
            assertEquals("no staging leftovers", listOf("item"), dst.listFiles()!!.map { it.name })
            assertTrue("the original must be kept when it cannot be deleted", File(src, "data.txt").exists())
        } finally {
            srcParent.setWritable(true)
            src.setWritable(true)
        }
    }

    @Test
    fun `REPLACE move with failing source cleanup keeps the new copy and reports failure`() = runBlocking {
        val srcParent = tmp.newFolder("mv-repl-src")
        val dst = tmp.newFolder("mv-repl-dst")
        File(dst, "item").apply { mkdirs() }
        File(dst, "item/old.txt").writeText("old")
        val src = File(srcParent, "item").apply { mkdirs() }
        File(src, "new.txt").writeText("new")
        assertTrue(srcParent.setWritable(false))
        assertTrue(src.setWritable(false))

        try {
            val r = FileOps.move(listOf(src), dst, ConflictStrategy.REPLACE)

            assertEquals(0, r.succeeded)
            assertEquals(1, r.failed)
            assertTrue(r.failures.any { it.contains("the original could not be deleted") })
            assertEquals("the new content must be published", "new", File(dst, "item/new.txt").readText())
            assertFalse("the old destination content was replaced", File(dst, "item/old.txt").exists())
            assertEquals("no staging or backup leftovers", listOf("item"), dst.listFiles()!!.map { it.name })
            assertTrue("the original must be kept when it cannot be deleted", File(src, "new.txt").exists())
        } finally {
            srcParent.setWritable(true)
            src.setWritable(true)
        }
    }
}
