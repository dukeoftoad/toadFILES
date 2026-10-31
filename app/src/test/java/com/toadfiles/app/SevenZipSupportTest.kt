package com.toadfiles.app

import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.SevenZipSupport
import com.toadfiles.app.domain.ZipSupport
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.7.1 7z support: entry iteration, extraction through the shared hardened staging
 * pipeline, ZIP-slip rejection, determinate progress and dispatch via ArchiveOps.
 * (Password handling can't be tested without a real encrypted fixture — the
 * PasswordRequiredException mapping is plain try/catch around the commons-compress API.)
 */
class SevenZipSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeSevenZ(out: File, entries: Map<String, ByteArray>) {
        SevenZOutputFile(out).use { sz ->
            entries.forEach { (name, content) ->
                val e = SevenZArchiveEntry()
                e.name = name
                sz.putArchiveEntry(e)
                sz.write(content)
                sz.closeArchiveEntry()
            }
        }
    }

    @Test
    fun `entryCount reads the 7z header`() {
        val dir = tmp.newFolder("s0")
        val f = File(dir, "a.7z")
        makeSevenZ(f, mapOf("one.txt" to "hello".toByteArray(), "sub/two.txt" to "world".toByteArray()))
        assertEquals(2, SevenZipSupport.entryCount(f))
        assertNull(SevenZipSupport.entryCount(File(dir, "missing.7z")))
    }

    @Test
    fun `extracts normal 7z archive`() = runBlocking {
        val dir = tmp.newFolder("s1")
        val f = File(dir, "a.7z")
        makeSevenZ(f, mapOf("one.txt" to "hello".toByteArray(), "sub/two.txt" to "world".toByteArray()))
        val target = File(dir, "out").apply { mkdirs() }
        val r = SevenZipSupport.extract(f, target, replace = false)
        assertEquals(0, r.failed)
        assertEquals(2, r.succeeded)
        assertEquals("hello", File(target, "one.txt").readText())
        assertEquals("world", File(target, "sub/two.txt").readText())
    }

    @Test
    fun `archive ops dispatch 7z through the 7z pipeline`() = runBlocking {
        val dir = tmp.newFolder("s2")
        val f = File(dir, "a.7z")
        makeSevenZ(f, mapOf("one.txt" to "via dispatch".toByteArray()))
        val target = File(dir, "out").apply { mkdirs() }
        val r = ArchiveOps.extract(f, target, replace = false)
        assertEquals(0, r.failed)
        assertEquals(1, r.succeeded)
        assertEquals("via dispatch", File(target, "one.txt").readText())
    }

    @Test
    fun `blocks 7z slip entries`() = runBlocking {
        val dir = tmp.newFolder("s3")
        val f = File(dir, "evil.7z")
        makeSevenZ(f, mapOf("../slipped.txt" to "bad".toByteArray()))
        val target = File(dir, "out").apply { mkdirs() }
        val r = SevenZipSupport.extract(f, target, replace = false)
        assertEquals(0, r.succeeded)
        assertTrue(r.skipped >= 1)
        assertFalse(File(dir, "slipped.txt").exists())
        assertFalse(File(target, "slipped.txt").exists())
    }

    @Test
    fun `respects replace flag on existing files`() = runBlocking {
        val dir = tmp.newFolder("s4")
        val f = File(dir, "a.7z")
        makeSevenZ(f, mapOf("same.txt" to "NEW".toByteArray()))
        val target = File(dir, "out").apply { mkdirs() }
        File(target, "same.txt").writeText("OLD")
        val skipped = SevenZipSupport.extract(f, target, replace = false)
        assertEquals(1, skipped.skipped)
        assertEquals("OLD", File(target, "same.txt").readText())
        val replaced = SevenZipSupport.extract(f, target, replace = true)
        assertEquals(1, replaced.succeeded)
        assertEquals("NEW", File(target, "same.txt").readText())
    }

    @Test
    fun `extraction reports determinate entry progress`() = runBlocking {
        val dir = tmp.newFolder("s5")
        val f = File(dir, "a.7z")
        makeSevenZ(f, mapOf("a.txt" to "1".toByteArray(), "b.txt" to "2".toByteArray(), "c/d.txt" to "3".toByteArray()))
        val target = File(dir, "out").apply { mkdirs() }
        val ticks = mutableListOf<com.toadfiles.app.domain.OpProgress>()
        SevenZipSupport.extract(f, target, replace = false) { ticks += it }
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it.total == 3 }) // header knows the count upfront
        assertEquals(3, ticks.last().done)
    }

    @Test
    fun `total byte cap aborts a bomb instead of filling the disk`() = runBlocking {
        val dir = tmp.newFolder("s6")
        val f = File(dir, "a.7z")
        makeSevenZ(f, mapOf("big.txt" to ByteArray(2048) { 'x'.code.toByte() }))
        val target = File(dir, "out").apply { mkdirs() }
        val r = SevenZipSupport.extract(
            f, target, replace = false,
            limits = ZipSupport.Limits(maxTotalBytes = 1024),
        )
        assertEquals(1, r.failed)
        assertTrue(r.failures.any { it.contains("possible zip bomb") })
        assertFalse(File(target, "big.txt").exists())
    }
}
