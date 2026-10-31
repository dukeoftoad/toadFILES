package com.toadfiles.app

import com.toadfiles.app.domain.ArchiveFormat
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.CompressLevel
import com.toadfiles.app.domain.SevenZipSupport
import com.toadfiles.app.domain.VolumeSupport
import com.toadfiles.app.domain.ZipSupport
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.8.0 split-into-volumes ARCHIVE integration: volume starts on action sheets,
 * honest failure on gapped sets, full create→split→extract round-trips for both
 * pipelines, and compress-with-volumeBytes (including the split-failure contract).
 */
class ArchiveOpsVolumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun knownSources(dir: File): File {
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "a.txt").writeText("hello volumes")
        File(File(src, "nested").apply { mkdirs() }, "b.bin").writeBytes(ByteArray(512) { (it % 251).toByte() })
        return src
    }

    private fun assertRoundTripped(target: File, src: File) {
        assertEquals("hello volumes", File(target, "src/a.txt").readText())
        assertArrayEquals(
            ByteArray(512) { (it % 251).toByte() },
            File(target, "src/nested/b.bin").readBytes(),
        )
        assertTrue(File(src, "src").isDirectory) // source untouched
    }

    // ---------- detection ----------

    @Test
    fun `volume starts are extractable when the stripped type is`() {
        assertTrue(ArchiveOps.canExtract("dcim.7z.001"))
        assertTrue(ArchiveOps.canExtract("DCIM.ZIP.001"))
        assertTrue(ArchiveOps.isArchive("dcim.7z.001")) // long-press offers Extract
        assertFalse(ArchiveOps.canExtract("dcim.7z.002")) // only the start acts
        assertFalse(ArchiveOps.canExtract("movie.rar.001"))
        assertFalse(ArchiveOps.canExtract("note.001")) // stripped type has no extension
        assertFalse(ArchiveOps.canExtract("plain.zip.0010"))
        // Non-volume behaviour is unchanged.
        assertTrue(ArchiveOps.canExtract("a.zip"))
        assertTrue(ArchiveOps.canExtract("b.7z"))
        assertFalse(ArchiveOps.canExtract("c.rar"))
    }

    // ---------- honest gap failure ----------

    @Test
    fun `gap in the volume set fails before writing anything`() = runBlocking {
        val dir = tmp.newFolder("gap")
        val zip = File(dir, "pack.zip")
        ZipOutputStream(java.io.FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("one.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        // 8-byte volumes → several volumes; then punch a hole in the middle.
        val volumes = VolumeSupport.split(zip, 8)
        assertTrue("expected several volumes, got ${volumes.size}", volumes.size >= 3)
        File(dir, "pack.zip.002").delete()
        val target = File(dir, "out")
        val r = ArchiveOps.extract(File(dir, "pack.zip.001"), target, replace = true)
        assertEquals(1, r.failed)
        assertEquals(0, r.succeeded)
        assertTrue(r.failures.single().contains("Volume set incomplete: missing .002"))
        assertFalse("nothing may be written for a gapped set", target.exists())
    }

    @Test
    fun `missing 001 fails honestly`() = runBlocking {
        val dir = tmp.newFolder("no-start")
        // .002/.003 exist but the start is gone: the set is missing its first volume.
        File(dir, "pack.zip.002").writeBytes(ByteArray(16))
        File(dir, "pack.zip.003").writeBytes(ByteArray(16))
        val r = ArchiveOps.extract(File(dir, "pack.zip.001"), File(dir, "out"), replace = true)
        assertEquals(1, r.failed)
        assertTrue(r.failures.single().contains("Volume set incomplete: missing .001"))
    }

    // ---------- round-trips ----------

    @Test
    fun `zip volume set round-trips through ArchiveOps extract`() = runBlocking {
        val dir = tmp.newFolder("rt-zip")
        val src = knownSources(dir)
        val zip = File(dir, "pack.zip")
        assertEquals(1, ZipSupport.zip(listOf(src), zip).succeeded)
        val volumes = VolumeSupport.split(zip, 61)
        assertTrue("expected several volumes, got ${volumes.size}", volumes.size >= 3)
        val target = File(dir, "out")
        val r = ArchiveOps.extract(File(dir, "pack.zip.001"), target, replace = false)
        assertEquals("extraction failed: $r", 0, r.failed)
        assertEquals(2, r.succeeded) // a.txt + nested/b.bin
        assertRoundTripped(target, dir)
        assertTrue("merged temp must be cleaned up", dir.listFiles()!!.none { it.name.contains(".merge-") })
    }

    @Test
    fun `7z volume set round-trips through ArchiveOps extract`() = runBlocking {
        val dir = tmp.newFolder("rt-7z")
        val src = knownSources(dir)
        val seven = File(dir, "pack.7z")
        assertEquals(1, SevenZipSupport.create7z(listOf(src), seven, CompressLevel.FAST).succeeded)
        val volumes = VolumeSupport.split(seven, 16)
        assertTrue("expected several volumes, got ${volumes.size}", volumes.size >= 3)
        val target = File(dir, "out")
        val r = ArchiveOps.extract(File(dir, "pack.7z.001"), target, replace = false)
        assertEquals("extraction failed: $r", 0, r.failed)
        assertEquals(2, r.succeeded) // a.txt + nested/b.bin
        assertRoundTripped(target, dir)
    }

    @Test
    fun `7z volume entryCount is known upfront`() = runBlocking {
        val dir = tmp.newFolder("cnt")
        val seven = File(dir, "pack.7z")
        SevenZOutputFile(seven).use { sz ->
            for (name in listOf("one.txt", "two.txt", "d/three.txt")) {
                val e = SevenZArchiveEntry()
                e.name = name
                sz.putArchiveEntry(e)
                sz.write("x".toByteArray())
                sz.closeArchiveEntry()
            }
        }
        VolumeSupport.split(seven, 13)
        assertEquals(3, ArchiveOps.entryCount(File(dir, "pack.7z.001")))
        // zip volumes stream: indeterminate until extracted.
        val zip = File(dir, "p.zip")
        ZipOutputStream(java.io.FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("one.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        VolumeSupport.split(zip, 6)
        assertNull(ArchiveOps.entryCount(File(dir, "p.zip.001")))
    }

    // ---------- compress with volumeBytes ----------

    @Test
    fun `compress with volumeBytes splits the archive and it round-trips`() = runBlocking {
        val dir = tmp.newFolder("cv")
        val src = knownSources(dir)

        val zipOut = File(dir, "out.zip")
        val rz = ArchiveOps.compress(listOf(src), zipOut, ArchiveFormat.ZIP, volumeBytes = 37)
        assertEquals("compress failed: $rz", 0, rz.failed)
        assertFalse("the archive is replaced by its volumes", zipOut.exists())
        val zipVolumes = VolumeSupport.volumeSet(File(dir, "out.zip.001"))
        assertTrue("expected several volumes, got ${zipVolumes.size}", zipVolumes.size >= 3)
        val unzip = File(dir, "unzip")
        assertEquals(0, ArchiveOps.extract(File(dir, "out.zip.001"), unzip, replace = false).failed)
        assertRoundTripped(unzip, dir)

        val sevenOut = File(dir, "out.7z")
        val r7 = ArchiveOps.compress(listOf(src), sevenOut, ArchiveFormat.SEVEN_Z, level = CompressLevel.FAST, volumeBytes = 23)
        assertEquals("compress failed: $r7", 0, r7.failed)
        assertFalse(sevenOut.exists())
        assertTrue(VolumeSupport.volumeSet(File(dir, "out.7z.001")).size >= 3)
        val un7 = File(dir, "un7")
        assertEquals(0, ArchiveOps.extract(File(dir, "out.7z.001"), un7, replace = false).failed)
        assertRoundTripped(un7, dir)
    }

    @Test
    fun `failed split keeps the unsplit archive and reports the failure`() = runBlocking {
        val dir = tmp.newFolder("cv-fail")
        val src = File(dir, "f.txt").apply { writeText("split me") }
        val out = File(dir, "out.7z")
        // Block the SECOND volume's final name with a non-empty directory so the
        // split fails after the archive itself was created successfully.
        val blocker = File(dir, "out.7z.002").apply { mkdirs() }
        File(blocker, "precious.txt").writeText("user data")
        try {
            val r = ArchiveOps.compress(listOf(src), out, ArchiveFormat.SEVEN_Z, level = CompressLevel.FAST, volumeBytes = 3)
            assertTrue("split failure must be reported, got $r", r.failed >= 1)
            assertFalse("a failed split must not count as fully succeeded", r.fullySucceeded)
            assertTrue(r.failures.any { it.contains("original archive kept") })
            assertTrue("the unsplit archive must be kept", out.exists() && out.length() > 0)
            assertFalse("no partial volumes may remain", File(dir, "out.7z.001").exists())
            // … and the kept archive is still a perfectly good 7z.
            val target = File(dir, "out")
            assertEquals(0, ArchiveOps.extract(out, target, replace = false).failed)
            assertEquals("split me", File(target, "f.txt").readText())
        } finally {
            blocker.deleteRecursively()
        }
    }
}
