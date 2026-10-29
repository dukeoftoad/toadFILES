package com.toadfiles.app

import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileOps
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
        FileOps.copy(listOf(src), dst, ConflictStrategy.RENAME) { p -> lastTotal = p.total }
        assertEquals(1, lastTotal)
    }
}
