package com.toadfiles.app

import com.toadfiles.app.domain.ZipSupport
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeZip(out: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `extracts normal archive`() = runBlocking {
        val dir = tmp.newFolder("z1")
        val zip = File(dir, "a.zip")
        makeZip(zip, mapOf("one.txt" to "hello".toByteArray(), "sub/two.txt" to "world".toByteArray()))
        val target = File(dir, "out").apply { mkdirs() }
        val r = ZipSupport.extract(zip, target, replace = false)
        assertEquals(0, r.failed)
        assertEquals(2, r.succeeded)
        assertEquals("hello", File(target, "one.txt").readText())
        assertEquals("world", File(target, "sub/two.txt").readText())
    }

    @Test
    fun `blocks zip slip entries`() = runBlocking {
        val dir = tmp.newFolder("z2")
        val zip = File(dir, "evil.zip")
        // Craft an archive with a traversal entry name.
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("../slipped.txt"))
            zos.write("bad".toByteArray())
            zos.closeEntry()
        }
        val target = File(dir, "out").apply { mkdirs() }
        val r = ZipSupport.extract(zip, target, replace = false)
        assertEquals(0, r.succeeded)
        assertTrue(r.skipped >= 1)
        assertFalse(File(dir, "slipped.txt").exists())
        assertFalse(File(target.parentFile, "slipped.txt").exists())
    }

    @Test
    fun `respects replace flag on existing files`() = runBlocking {
        val dir = tmp.newFolder("z3")
        val zip = File(dir, "a.zip")
        makeZip(zip, mapOf("same.txt" to "NEW".toByteArray()))
        val target = File(dir, "out").apply { mkdirs() }
        File(target, "same.txt").writeText("OLD")
        val skipped = ZipSupport.extract(zip, target, replace = false)
        assertEquals(1, skipped.skipped)
        assertEquals("OLD", File(target, "same.txt").readText())
        val replaced = ZipSupport.extract(zip, target, replace = true)
        assertEquals(1, replaced.succeeded)
        assertEquals("NEW", File(target, "same.txt").readText())
    }

    @Test
    fun `entry limit stops zip bombs`() = runBlocking {
        val dir = tmp.newFolder("z4")
        val zip = File(dir, "many.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            for (i in 0 until 50) {
                zos.putNextEntry(ZipEntry("f$i.txt"))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        val target = File(dir, "out").apply { mkdirs() }
        val r = ZipSupport.extract(zip, target, replace = false, limits = ZipSupport.Limits(maxEntries = 10))
        assertTrue(r.failures.first().contains("zip bomb"))
        assertTrue(r.succeeded <= 10)
    }

    @Test
    fun `zips files and folder trees`() = runBlocking {
        val dir = tmp.newFolder("z5")
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "root.txt").writeText("R")
        File(File(src, "nested").apply { mkdirs() }, "deep.txt").writeText("D")
        val out = File(dir, "packed.zip")
        val r = ZipSupport.zip(listOf(src), out)
        assertEquals(1, r.succeeded)
        assertTrue(out.exists() && out.length() > 0)
        // Verify contents round-trip.
        ZipInputStream(FileInputStream(out)).use { zis ->
            val names = generateSequence { zis.nextEntry }.map { it.name }.toList()
            assertTrue(names.any { it.endsWith("root.txt") })
            assertTrue(names.any { it.endsWith("nested/deep.txt") })
        }
    }

    // ---------- hardening (audit P1.2 / P2.11 / P2.10 / P3.1) ----------

    /** Builds a data-descriptor archive: entry sizes are unknown until the stream is read. */
    private fun makeDescriptorZip(out: File, content: ByteArray) {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            val e = ZipEntry("blob.bin") // DEFLATED + unset size -> data descriptor
            zos.putNextEntry(e)
            zos.write(content)
            zos.closeEntry()
        }
    }

    @Test
    fun `unknown-size entries hit the total byte cap`() = runBlocking {
        val dir = tmp.newFolder("h1")
        val zip = File(dir, "blob.zip")
        val payload = ByteArray(1_500_000).also { java.util.Random(42).nextBytes(it) } // incompressible
        makeDescriptorZip(zip, payload)
        // Precondition: the archive really reports unknown sizes (data descriptor).
        ZipInputStream(FileInputStream(zip)).use { zis ->
            val e = zis.nextEntry
            assertEquals("blob.bin", e.name)
            assertEquals(-1L, e.size)
            assertEquals(-1L, e.compressedSize)
        }
        val target = File(dir, "out")
        val r = ZipSupport.extract(zip, target, replace = false, limits = ZipSupport.Limits(maxTotalBytes = 1_000_000))
        assertTrue(r.failures.first().contains("uncompressed"))
        assertFalse("no partial tree may be published", target.exists())
        assertTrue("staging must be cleaned up", dir.listFiles()!!.none { it.name.contains(".staging-") })
    }

    @Test
    fun `unknown-size entries hit the ratio cap`() = runBlocking {
        val dir = tmp.newFolder("h2")
        val zip = File(dir, "bomb.zip")
        makeDescriptorZip(zip, ByteArray(2_000_000)) // zeros: ~2000:1 expansion
        val target = File(dir, "out")
        val r = ZipSupport.extract(zip, target, replace = false, limits = ZipSupport.Limits(maxRatio = 50))
        assertTrue(r.failures.first().contains("zip bomb"))
        assertEquals(0, r.succeeded)
        assertFalse(target.exists())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".staging-") })
    }

    @Test
    fun `truncated archive aborts without publishing`() = runBlocking {
        val dir = tmp.newFolder("h3")
        val zip = File(dir, "cut.zip")
        val blob = "ABCD".repeat(512) // 2 KB per entry so the cut lands inside entry data
        makeZip(zip, mapOf("a.txt" to blob.toByteArray(), "b.txt" to blob.toByteArray(), "c.txt" to blob.toByteArray()))
        val full = zip.readBytes()
        FileOutputStream(zip).use { it.write(full, 0, full.size / 2) } // cut into an entry's data
        val target = File(dir, "out")
        val r = ZipSupport.extract(zip, target, replace = false)
        assertTrue("stream death must be reported, got $r", r.failed >= 1)
        assertFalse(target.exists())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".staging-") })
    }

    @Test
    fun `cancellation cleans staging and never publishes`() = runBlocking {
        val dir = tmp.newFolder("h4")
        val zip = File(dir, "many.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            for (i in 0 until 60) {
                zos.putNextEntry(ZipEntry("f$i.txt"))
                zos.write(("content-$i").repeat(100).toByteArray())
                zos.closeEntry()
            }
        }
        val target = File(dir, "out")
        var job: kotlinx.coroutines.Job? = null
        val job2 = launch {
            try {
                ZipSupport.extract(zip, target, replace = false) { p ->
                    if (p.done >= 3) job?.cancel()
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // cooperative cancellation surfaces as cancellation of the caller job
            }
        }
        job = job2
        job2.join()
        assertFalse("cancelled extraction must not publish", target.exists())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".staging-") })
    }

    @Test
    fun `failed source aborts and deletes the archive`() = runBlocking {
        val dir = tmp.newFolder("h5")
        File(dir, "good.txt").writeText("G")
        val ghost = File(dir, "ghost.txt") // never created
        val out = File(dir, "out.zip")
        val r = ZipSupport.zip(listOf(File(dir, "good.txt"), ghost), out)
        assertTrue("failure must be reported, got $r", r.failed >= 1)
        assertEquals(0, r.succeeded)
        assertFalse("broken archive must not be published", out.exists())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".part-") })
    }

    @Test
    fun `zip creation leaves no temp archive behind`() = runBlocking {
        val dir = tmp.newFolder("h6")
        File(dir, "one.txt").writeText("1")
        val out = File(dir, "ok.zip")
        val r = ZipSupport.zip(listOf(File(dir, "one.txt")), out)
        assertEquals(1, r.succeeded)
        assertTrue(out.exists())
        assertEquals("1", ZipInputStream(FileInputStream(out)).use { z ->
            z.nextEntry; z.readBytes().decodeToString()
        })
        assertTrue(dir.listFiles()!!.none { it.name.contains(".part-") })
    }

    @Test
    fun `duplicate source names get distinct entry names`() = runBlocking {
        val dir = tmp.newFolder("h7")
        val a = File(dir, "a").apply { mkdirs() }
        val b = File(dir, "b").apply { mkdirs() }
        File(a, "same.txt").writeText("A")
        File(b, "same.txt").writeText("B")
        val out = File(dir, "dup.zip")
        val r = ZipSupport.zip(listOf(File(a, "same.txt"), File(b, "same.txt")), out)
        assertEquals(2, r.succeeded)
        val entries = ZipInputStream(FileInputStream(out)).use { zis ->
            generateSequence { zis.nextEntry }.map { it.name to String(zis.readBytes()) }.toList()
        }
        assertEquals(setOf("same.txt", "same (1).txt"), entries.map { it.first }.toSet())
        assertEquals("A", entries.first { it.first == "same.txt" }.second)
        assertEquals("B", entries.first { it.first == "same (1).txt" }.second)
    }

    @Test
    fun `slip entry plus bomb aborts without publishing or escaping`() = runBlocking {
        val dir = tmp.newFolder("h8")
        val zip = File(dir, "mix.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("../escape.txt")) // slip attempt: skipped, extraction continues
            zos.write("bad".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("blob.bin"))
            zos.write(ByteArray(5_000_000)) // ratio bomb with a 100x cap
            zos.closeEntry()
        }
        val target = File(dir, "out")
        val r = ZipSupport.extract(zip, target, replace = false, limits = ZipSupport.Limits(maxRatio = 100))
        assertTrue(r.skipped >= 1) // the slip attempt
        assertTrue(r.failures.any { it.contains("zip bomb") })
        assertFalse(target.exists())
        assertFalse(File(dir, "escape.txt").exists())
    }

    @Test
    fun `extraction creates the destination folder when missing`() = runBlocking {
        val dir = tmp.newFolder("h9")
        val zip = File(dir, "a.zip")
        makeZip(zip, mapOf("one.txt" to "1".toByteArray(), "sub/two.txt" to "2".toByteArray()))
        val target = File(dir, "fresh") // does not exist yet
        val r = ZipSupport.extract(zip, target, replace = false)
        assertEquals(0, r.failed)
        assertEquals(2, r.succeeded)
        assertEquals("1", File(target, "one.txt").readText())
        assertEquals("2", File(target, "sub/two.txt").readText())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".staging-") })
    }

    @Test
    fun `empty directory entries are published`() = runBlocking {
        val dir = tmp.newFolder("h10")
        val zip = File(dir, "dirs.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("empty/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("full/keep.txt"))
            zos.write("k".toByteArray())
            zos.closeEntry()
        }
        val target = File(dir, "out").apply { mkdirs() }
        val r = ZipSupport.extract(zip, target, replace = false)
        assertEquals(1, r.succeeded)
        assertTrue(File(target, "empty").isDirectory)
        assertEquals("k", File(target, "full/keep.txt").readText())
    }

    // ---------- audit P1.2: ZIP creation never follows directory symlinks ----------

    private fun entryNames(zip: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(FileInputStream(zip)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                names += e.name
                zis.closeEntry()
            }
        }
        return names
    }

    @Test
    fun `zip skips a symlink to an outside folder`() = runBlocking {
        val outside = tmp.newFolder("outside").apply {
            File(this, "secret.txt").writeText("must not leak")
        }
        val selection = tmp.newFolder("selection").apply {
            File(this, "readme.txt").writeText("fine")
            java.nio.file.Files.createSymbolicLink(
                File(this, "link-to-outside").toPath(), outside.toPath(),
            )
        }
        val out = File(tmp.newFolder("zips"), "a.zip")

        val result = ZipSupport.zip(listOf(selection), out)

        assertEquals(1, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        val names = entryNames(out)
        assertTrue(names.any { it.endsWith("readme.txt") })
        // Nothing from the link target may appear in the archive.
        assertFalse(names.any { it.contains("secret.txt") })
    }

    @Test
    fun `zip stores file symlinks by content`() = runBlocking {
        val target = tmp.newFile("target.txt").apply { writeText("via link") }
        val dir = tmp.newFolder("filesel").apply {
            File(this, "real.txt").writeText("real")
            java.nio.file.Files.createSymbolicLink(File(this, "link.txt").toPath(), target.toPath())
        }
        val out = File(tmp.newFolder("zips"), "b.zip")

        val result = ZipSupport.zip(listOf(dir), out)

        assertEquals(1, result.succeeded)
        assertEquals(0, result.skipped)
        // The link's CONTENT made it into the archive.
        val names = entryNames(out)
        assertTrue(names.any { it.endsWith("link.txt") })
    }

    @Test
    fun `zip survives a symlink cycle without recursing forever`() = runBlocking {
        val dir = tmp.newFolder("cyclic").apply {
            File(this, "keep.txt").writeText("x")
        }
        // a/link -> a  and  a/b/link -> a: classic cycle bait.
        java.nio.file.Files.createSymbolicLink(File(dir, "self").toPath(), dir.toPath())
        val sub = File(dir, "sub").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(sub, "up").toPath(), dir.toPath())
        val out = File(tmp.newFolder("zips"), "c.zip")

        val result = ZipSupport.zip(listOf(dir), out)

        // Terminates (the visited set breaks the cycle), nothing duplicated.
        val names = entryNames(out)
        assertTrue(names.any { it.endsWith("keep.txt") })
        assertTrue(names.count { it.endsWith("keep.txt") } == 1)
    }

    @Test
    fun `zip reports a dangling symlink as skipped`() = runBlocking {
        val dir = tmp.newFolder("dangling").apply {
            File(this, "ok.txt").writeText("fine")
            java.nio.file.Files.createSymbolicLink(
                File(this, "broken.txt").toPath(),
                File(this, "nowhere.txt").toPath(),
            )
        }
        val out = File(tmp.newFolder("zips"), "d.zip")

        val result = ZipSupport.zip(listOf(dir), out)

        assertEquals(1, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        assertTrue(entryNames(out).none { it.endsWith("broken.txt") })
    }
}
