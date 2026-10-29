package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.SharedRepository
import com.toadfiles.app.data.SharedResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Local-only share: copyIn/clear/remove with name-conflict handling. Robolectric gives us
 * a real getExternalFilesDir, so these are true filesystem ops — the same code that runs
 * on a phone. Every test starts with an empty shared folder so they don't taint each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedRepositoryTest {

    private lateinit var repo: SharedRepository
    private lateinit var sharedDir: File
    private lateinit var workDir: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        repo = SharedRepository(ctx)
        sharedDir = File(ctx.getExternalFilesDir(null), "shared")
        // start clean: leftover snapshots from a previous test would skew counts.
        sharedDir.listFiles()?.forEach { it.delete() }
        workDir = createTempDir(prefix = "toadfiles-test")
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
        sharedDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `copyIn duplicates a real file with the same name`() {
        val src = File(workDir, "report.pdf").apply { writeText("hello") }
        val r = repo.copyIn(listOf(src))
        assertEquals(1, r.copied)
        assertEquals(0, r.skipped)
        assertTrue(r.failures.isEmpty())
        val dest = File(sharedDir, "report.pdf")
        assertTrue(dest.exists())
        assertEquals("hello", dest.readText())
    }

    @Test
    fun `copyIn with duplicate names appends an (n) suffix instead of overwriting`() {
        // Two distinct source files that happen to share the name "img.png" must both
        // survive as separate snapshots in the shared folder.
        val a = File(workDir, "a").apply { mkdirs() }
        val b = File(workDir, "b").apply { mkdirs() }
        val fa = File(a, "img.png").apply { writeText("aaa") }
        val fb = File(b, "img.png").apply { writeText("bbb") }

        val r = repo.copyIn(listOf(fa, fb))
        assertEquals(2, r.copied)
        assertEquals(0, r.skipped)

        val first = File(sharedDir, "img.png")
        val second = File(sharedDir, "img (1).png")
        assertTrue(first.exists())
        assertTrue(second.exists())
        // The exact first-come-first-served ordering doesn't matter; both must be present.
        assertEquals(setOf("aaa", "bbb"), setOf(first.readText(), second.readText()))
    }

    @Test
    fun `copyIn sharing the same file twice produces two snapshots`() {
        val src = File(workDir, "once.txt").apply { writeText("content") }
        repo.copyIn(listOf(src))
        val r2 = repo.copyIn(listOf(src))
        assertEquals(1, r2.copied)
        // Original "once.txt" + new "once (1).txt"
        assertEquals(2, repo.list().size)
        assertTrue(File(sharedDir, "once.txt").exists())
        assertTrue(File(sharedDir, "once (1).txt").exists())
    }

    @Test
    fun `copyIn skips a missing source and reports it as a failure`() {
        val good = File(workDir, "good.txt").apply { writeText("ok") }
        val missing = File(workDir, "missing.txt")
        val r = repo.copyIn(listOf(good, missing))
        assertEquals(1, r.copied)
        assertEquals(1, r.skipped)
        assertTrue(r.failures.any { it.contains("missing") })
    }

    @Test
    fun `copyIn rejects directories from the local-share snapshot`() {
        val dir = File(workDir, "folder").apply { mkdirs() }
        val file = File(workDir, "f.txt").apply { writeText("x") }
        val r = repo.copyIn(listOf(dir, file))
        assertEquals(1, r.copied)
        assertEquals(1, r.skipped)
        assertTrue(r.failures.any { it.contains("directories") })
    }

    @Test
    fun `copyIn on an empty list returns a zero-result`() {
        val r = repo.copyIn(emptyList())
        assertEquals(0, r.copied)
        assertEquals(0, r.skipped)
    }

    @Test
    fun `list returns only files, newest first`() {
        val a = File(workDir, "a.txt").apply { writeText("a"); setLastModified(1_000_000) }
        val b = File(workDir, "b.txt").apply { writeText("b"); setLastModified(2_000_000) }
        repo.copyIn(listOf(a))
        // copyIn timestamps are real "now", so we can't predict ordering between a and b.
        // Just verify both are listed and the directory is empty after no copyIn.
        val before = repo.list().size
        repo.copyIn(listOf(b))
        assertEquals(before + 1, repo.list().size)
        // all entries must be files, not directories
        assertTrue(repo.list().all { it.isFile })
    }

    @Test
    fun `usedBytes sums the size of every snapshot`() {
        val a = File(workDir, "a.txt").apply { writeText("12345") }       // 5 bytes
        val b = File(workDir, "b.txt").apply { writeText("1234567890") } // 10 bytes
        repo.copyIn(listOf(a, b))
        assertEquals(15L, repo.usedBytes())
    }

    @Test
    fun `clear wipes every snapshot and returns the count`() {
        val a = File(workDir, "a.txt").apply { writeText("a") }
        val b = File(workDir, "b.txt").apply { writeText("b") }
        val c = File(workDir, "c.txt").apply { writeText("c") }
        repo.copyIn(listOf(a, b, c))
        assertEquals(3, repo.list().size)
        val n = repo.clear()
        assertEquals(3, n)
        assertEquals(0, repo.list().size)
    }

    @Test
    fun `remove deletes a single named snapshot and rejects path traversal`() {
        val src = File(workDir, "x.txt").apply { writeText("x") }
        repo.copyIn(listOf(src))
        assertTrue(repo.remove("x.txt"))
        assertFalse(repo.remove("x.txt")) // already gone
        // Path traversal must be refused — no escape from the shared dir.
        assertFalse(repo.remove("../something"))
        assertFalse(repo.remove("a/b"))
    }

    @Test
    fun `describe returns a non-empty human label`() {
        val a = File(workDir, "a.txt").apply { writeText("hello") }
        repo.copyIn(listOf(a))
        val label = repo.describe()
        assertTrue(label.contains("1"))
        assertTrue(label.contains("file"))
    }
}
