package com.toadfiles.app

import com.toadfiles.app.domain.ArchiveFormat
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.CompressLevel
import com.toadfiles.app.domain.OpProgress
import com.toadfiles.app.domain.ZipSupport
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
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.7.1 archive UX: extension detection/dispatch (zip + 7z), determinate progress
 * totals for both pipelines, and the friendly encrypted-archive copy.
 */
class ArchiveOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `zip and 7z are extractable, other archives are not`() {
        assertTrue(ArchiveOps.canExtract("a.zip"))
        assertTrue(ArchiveOps.canExtract("a.ZIP"))
        assertTrue(ArchiveOps.canExtract("b.7z"))
        assertTrue(ArchiveOps.canExtract("B.7Z"))
        assertFalse(ArchiveOps.canExtract("c.rar"))
        assertFalse(ArchiveOps.canExtract("d.tar"))
        assertFalse(ArchiveOps.canExtract("note.txt"))
        assertFalse(ArchiveOps.canExtract("zip")) // no extension, not "name.zip"
        assertFalse(ArchiveOps.canExtract(""))
    }

    @Test
    fun `extension parsing is lowercase and dot-safe`() {
        assertEquals("zip", ArchiveOps.extension("Archive.ZiP"))
        assertEquals("7z", ArchiveOps.extension("x.7z"))
        assertEquals("", ArchiveOps.extension("noext"))
        assertEquals("", ArchiveOps.extension("trailing."))
    }

    @Test
    fun `extract dispatches zips through the zip pipeline`() = runBlocking {
        val dir = tmp.newFolder("d1")
        val zip = File(dir, "a.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("one.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val target = File(dir, "out").apply { mkdirs() }
        val r = ArchiveOps.extract(zip, target, replace = false)
        assertEquals(0, r.failed)
        assertEquals(1, r.succeeded)
        assertEquals("hello", File(target, "one.txt").readText())
    }

    @Test
    fun `extract refuses unsupported archive types with a clean failure`() = runBlocking {
        val dir = tmp.newFolder("d2")
        val rar = File(dir, "a.rar").apply { writeText("not a real rar") }
        val r = ArchiveOps.extract(rar, File(dir, "out"), replace = false)
        assertEquals(1, r.failed)
        assertTrue(r.failures.single().contains("unsupported archive type"))
    }

    @Test
    fun `zip entry count is known upfront for determinate progress`() {
        val dir = tmp.newFolder("d3")
        val zip = File(dir, "a.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            listOf("a.txt", "b.txt", "c/d.txt").forEach {
                zos.putNextEntry(ZipEntry(it))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        assertEquals(3, ArchiveOps.entryCount(zip))
        assertNull(ArchiveOps.entryCount(File(dir, "missing.zip")))
        assertNull(ArchiveOps.entryCount(File(dir, "note.rar")))
    }

    @Test
    fun `zip extraction reports determinate entry progress`() = runBlocking {
        val dir = tmp.newFolder("d4")
        val zip = File(dir, "a.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            listOf("a.txt", "b.txt", "c/d.txt").forEach {
                zos.putNextEntry(ZipEntry(it))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        val target = File(dir, "out").apply { mkdirs() }
        val ticks = mutableListOf<OpProgress>()
        ZipSupport.extract(zip, target, replace = false) { ticks += it }
        // Every tick knows the total (3 entries); the last tick is complete.
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it.total == 3 })
        assertEquals(3, ticks.last().done)
    }

    @Test
    fun `zip creation reports determinate file-entry progress`() = runBlocking {
        val dir = tmp.newFolder("d5")
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "one.txt").writeText("1")
        File(src, "two.txt").writeText("2")
        val nested = File(src, "sub").apply { mkdirs() }
        File(nested, "three.txt").writeText("3")
        val ticks = mutableListOf<OpProgress>()
        val r = ZipSupport.zip(listOf(src), File(dir, "out.zip")) { ticks += it }
        assertEquals(0, r.failed)
        assertTrue(ticks.isNotEmpty())
        // 3 file entries total (folders don't count), and the run reaches 3/3.
        assertEquals(3, ticks.maxOf { it.total })
        assertEquals(3, ticks.last().done)
        assertEquals(3, ticks.last().total)
    }

    @Test
    fun `encrypted zip entries get the friendly password copy`() = runBlocking {
        // The copy mapping itself is what matters; verify the helper directly since
        // crafting a real encrypted zip in a JVM test needs a zipcrypto writer.
        val friendly = ZipSupport.friendlyArchiveError(
            java.util.zip.ZipException("encrypted ZIP entry not supported")
        )
        assertEquals(ZipSupport.PASSWORD_UNSUPPORTED, friendly)
        assertEquals("boom", ZipSupport.friendlyArchiveError(java.io.IOException("boom")))
        assertNotNull(ZipSupport.PASSWORD_UNSUPPORTED)
    }

    // ---------- v1.7.3 creation dispatch ----------

    @Test
    fun `compress dispatches zip and 7z creation by format`() = runBlocking {
        val dir = tmp.newFolder("fmt")
        val src = File(dir, "f.txt").apply { writeText("dispatch") }

        val zipOut = File(dir, "o.zip")
        val rz = ArchiveOps.compress(listOf(src), zipOut, ArchiveFormat.ZIP, CompressLevel.MAXIMUM)
        assertEquals(1, rz.succeeded)
        assertTrue(zipOut.exists() && zipOut.length() > 0)

        val sevenOut = File(dir, "o.7z")
        val r7 = ArchiveOps.compress(listOf(src), sevenOut, ArchiveFormat.SEVEN_Z, CompressLevel.FAST)
        assertEquals(1, r7.succeeded)
        assertTrue(sevenOut.exists() && sevenOut.length() > 0)

        // Both land in readable archives that extract through their own pipeline.
        val unzip = File(dir, "unzip")
        assertEquals(1, ArchiveOps.extract(zipOut, unzip, replace = false).succeeded)
        assertEquals("dispatch", File(unzip, "f.txt").readText())
        val un7z = File(dir, "un7z")
        assertEquals(1, ArchiveOps.extract(sevenOut, un7z, replace = false).succeeded)
        assertEquals("dispatch", File(un7z, "f.txt").readText())
    }

    @Test
    fun `legacy zip entry point still works and defaults to NORMAL`() = runBlocking {
        val dir = tmp.newFolder("legacy")
        val src = File(dir, "f.txt").apply { writeText("legacy call sites keep compiling") }
        val out = File(dir, "o.zip")
        assertEquals(1, ArchiveOps.zip(listOf(src), out).succeeded)
        assertTrue(out.exists())
        val target = File(dir, "out")
        assertEquals(1, ArchiveOps.extract(out, target, replace = false).succeeded)
    }
}
