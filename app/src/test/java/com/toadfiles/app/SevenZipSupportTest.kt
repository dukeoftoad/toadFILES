package com.toadfiles.app

import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.CompressLevel
import com.toadfiles.app.domain.SevenZipSupport
import com.toadfiles.app.domain.ZipSupport
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import java.io.File

/**
 * v1.7.1 7z support: entry iteration, extraction through the shared hardened staging
 * pipeline, ZIP-slip rejection, determinate progress and dispatch via ArchiveOps.
 * v1.7.3 adds the creation pipeline: round-trips, level mapping (COPY for STORE, LZMA2
 * presets otherwise), staging atomicity, entry caps and the no-follow link policy.
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

    // ---------- creation (v1.7.3) ----------

    @Test
    fun `compress levels map to 7z methods and LZMA2 presets`() {
        // STORE stores verbatim via the COPY codec — no LZMA2 preset at all.
        val store = SevenZipSupport.contentMethodsFor(CompressLevel.STORE)
        assertEquals(SevenZMethod.COPY, store.single().method)
        assertNull(store.single().options)
        // The compressing levels map to the xz presets 1 / 6 / 9 (dict 1 / 8 / 64 MiB).
        assertEquals(1, SevenZipSupport.lzma2Preset(CompressLevel.FAST))
        assertEquals(6, SevenZipSupport.lzma2Preset(CompressLevel.NORMAL))
        assertEquals(9, SevenZipSupport.lzma2Preset(CompressLevel.MAXIMUM))
        val dictByLevel = mapOf(
            CompressLevel.FAST to 1 shl 20,
            CompressLevel.NORMAL to 1 shl 23,
            CompressLevel.MAXIMUM to 1 shl 26,
        )
        for ((level, expectedDict) in dictByLevel) {
            val m = SevenZipSupport.contentMethodsFor(level).single()
            assertEquals(SevenZMethod.LZMA2, m.method)
            val options = m.options as LZMA2Options
            assertEquals("dict size for $level", expectedDict, options.dictSize)
        }
    }

    @Test
    fun `create7z round-trips files, folder trees and empty folders`() = runBlocking {
        val dir = tmp.newFolder("c1")
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "root.txt").writeText("R")
        File(File(src, "nested").apply { mkdirs() }, "deep.txt").writeText("D")
        File(src, "empty").mkdirs()
        val out = File(dir, "packed.7z")

        val r = SevenZipSupport.create7z(listOf(src), out, CompressLevel.FAST)

        assertEquals(0, r.failed)
        assertEquals(1, r.succeeded)
        assertTrue(out.exists() && out.length() > 0)
        // Round-trip through the hardened extractor, not a second writer.
        val target = File(dir, "unpacked")
        val x = SevenZipSupport.extract(out, target, replace = false)
        assertEquals(0, x.failed)
        assertEquals("R", File(target, "src/root.txt").readText())
        assertEquals("D", File(target, "src/nested/deep.txt").readText())
        assertTrue("empty folders must survive the round-trip", File(target, "src/empty").isDirectory)
    }

    @Test
    fun `every compress level creates a readable archive`() = runBlocking {
        for (level in CompressLevel.entries) {
            if (level == CompressLevel.MAXIMUM) {
                // Preset 9 uses a 64 MiB dictionary (~700 MB encoder memory, xz's own
                // 10.5×dict formula). Only exercise it where the heap allows it.
                assumeTrue(
                    "MAXIMUM needs a ~1 GiB heap for its 64 MiB LZMA2 dictionary",
                    Runtime.getRuntime().maxMemory() > 1_200_000_000L,
                )
            }
            val dir = tmp.newFolder("lv-${level.name}")
            val src = File(dir, "data.txt").apply { writeText("toad ".repeat(5000)) }
            val out = File(dir, "a.7z")

            val r = SevenZipSupport.create7z(listOf(src), out, level)

            assertEquals("creation failed for $level: $r", 0, r.failed)
            assertEquals(1, r.succeeded)
            val target = File(dir, "out")
            val x = SevenZipSupport.extract(out, target, replace = false)
            assertEquals("extraction failed for $level: $x", 0, x.failed)
            assertEquals("toad ".repeat(5000), File(target, "data.txt").readText())
        }
    }

    @Test
    fun `STORE creates an uncompressed archive and LZMA2 compresses`() = runBlocking {
        val dir = tmp.newFolder("c2")
        // Incompressible payload: COPY can't shrink it, so the STORE archive must be at
        // least as large as the payload itself.
        val blob = File(dir, "blob.bin").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
        val stored = File(dir, "stored.7z")
        assertEquals(1, SevenZipSupport.create7z(listOf(blob), stored, CompressLevel.STORE).succeeded)
        assertTrue(
            "COPY must not shrink anything (archive ${stored.length()} vs payload ${blob.length()})",
            stored.length() >= blob.length(),
        )
        // Compressible payload: LZMA2 at the fastest preset must shrink it substantially.
        val text = File(dir, "text.txt").apply { writeText("toad ".repeat(20_000)) }
        val packed = File(dir, "packed.7z")
        assertEquals(1, SevenZipSupport.create7z(listOf(text), packed, CompressLevel.FAST).succeeded)
        assertTrue(
            "LZMA2 must compress repetitive text (archive ${packed.length()} vs payload ${text.length()})",
            packed.length() < text.length() / 2,
        )
    }

    @Test
    fun `cancelled creation never creates the target`() = runBlocking {
        val dir = tmp.newFolder("c3")
        val src = File(dir, "src").apply { mkdirs() }
        for (i in 0 until 60) File(src, "f$i.txt").writeText("content-$i".repeat(200))
        val out = File(dir, "x.7z")
        var job: kotlinx.coroutines.Job? = null
        val job2 = launch {
            try {
                SevenZipSupport.create7z(listOf(src), out, CompressLevel.FAST) { p ->
                    if (p.done >= 3) job?.cancel()
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // cooperative cancellation surfaces as cancellation of the caller job
            }
        }
        job = job2
        job2.join()
        assertFalse("cancelled creation must not publish", out.exists())
        assertTrue("staging temp must be cleaned up", dir.listFiles()!!.none { it.name.contains(".part-") })
    }

    @Test
    fun `failed source aborts and deletes the 7z`() = runBlocking {
        val dir = tmp.newFolder("c4")
        File(dir, "good.txt").writeText("G")
        val ghost = File(dir, "ghost.txt") // never created
        val out = File(dir, "out.7z")
        val r = SevenZipSupport.create7z(listOf(File(dir, "good.txt"), ghost), out, CompressLevel.FAST)
        assertTrue("failure must be reported, got $r", r.failed >= 1)
        assertEquals(0, r.succeeded)
        assertFalse("broken archive must not be published", out.exists())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".part-") })
    }

    @Test
    fun `entry cap aborts 7z creation`() = runBlocking {
        val dir = tmp.newFolder("c5")
        val src = File(dir, "many").apply { mkdirs() }
        for (i in 0 until 20) File(src, "f$i.txt").writeText("x")
        val out = File(dir, "many.7z")
        val r = SevenZipSupport.create7z(
            listOf(src), out, CompressLevel.FAST,
            limits = ZipSupport.Limits(maxEntries = 10),
        )
        assertTrue(r.failed >= 1)
        assertTrue(r.failures.any { it.contains("more than 10 entries") })
        assertFalse(out.exists())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".part-") })
    }

    @Test
    fun `duplicate source names get distinct entry names in 7z`() = runBlocking {
        val dir = tmp.newFolder("c6")
        val a = File(dir, "a").apply { mkdirs() }
        val b = File(dir, "b").apply { mkdirs() }
        File(a, "same.txt").writeText("A")
        File(b, "same.txt").writeText("B")
        val out = File(dir, "dup.7z")
        val r = SevenZipSupport.create7z(listOf(File(a, "same.txt"), File(b, "same.txt")), out, CompressLevel.FAST)
        assertEquals(2, r.succeeded)
        val names = SevenZFile(out).use { sz -> sz.entries.map { it.name } }
        assertEquals(setOf("same.txt", "same (1).txt"), names.toSet())
    }

    @Test
    fun `create7z skips a symlink to an outside folder`() = runBlocking {
        val outside = tmp.newFolder("outside").apply {
            File(this, "secret.txt").writeText("must not leak")
        }
        val selection = tmp.newFolder("selection").apply {
            File(this, "readme.txt").writeText("fine")
            java.nio.file.Files.createSymbolicLink(
                File(this, "link-to-outside").toPath(), outside.toPath(),
            )
        }
        val out = File(tmp.newFolder("c7out"), "a.7z")

        val r = SevenZipSupport.create7z(listOf(selection), out, CompressLevel.FAST)

        assertEquals(1, r.succeeded)
        assertEquals(1, r.skipped)
        assertEquals(0, r.failed)
        val names = SevenZFile(out).use { sz -> sz.entries.map { it.name } }
        assertTrue(names.any { it.endsWith("readme.txt") })
        // Nothing from the link target may appear in the archive.
        assertFalse(names.any { it.contains("secret.txt") })
    }

    @Test
    fun `create7z reports determinate file-entry progress`() = runBlocking {
        val dir = tmp.newFolder("c8")
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "one.txt").writeText("1")
        File(src, "two.txt").writeText("2")
        File(File(src, "sub").apply { mkdirs() }, "three.txt").writeText("3")
        val ticks = mutableListOf<com.toadfiles.app.domain.OpProgress>()
        val r = SevenZipSupport.create7z(listOf(src), File(dir, "out.7z"), CompressLevel.FAST) { ticks += it }
        assertEquals(0, r.failed)
        assertTrue(ticks.isNotEmpty())
        // 3 file entries total (folders don't count), and the run reaches 3/3.
        assertEquals(3, ticks.maxOf { it.total })
        assertEquals(3, ticks.last().done)
        assertEquals(3, ticks.last().total)
    }

    @Test
    fun `archive ops compress dispatches 7z creation`() = runBlocking {
        val dir = tmp.newFolder("c9")
        val src = File(dir, "f.txt").apply { writeText("via dispatch") }
        val out = File(dir, "o.7z")
        val r = com.toadfiles.app.domain.ArchiveOps.compress(
            listOf(src), out, com.toadfiles.app.domain.ArchiveFormat.SEVEN_Z, CompressLevel.NORMAL,
        )
        assertEquals(0, r.failed)
        assertEquals(1, r.succeeded)
        val target = File(dir, "out")
        assertEquals(1, ArchiveOps.extract(out, target, replace = false).succeeded)
        assertEquals("via dispatch", File(target, "f.txt").readText())
    }
}
