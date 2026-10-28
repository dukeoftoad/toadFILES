package io.github.dukeoftoad.toadfiles

import io.github.dukeoftoad.toadfiles.domain.ZipSupport
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
}
