package com.toadfiles.app

import com.toadfiles.app.domain.VolumeSupport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException

/**
 * v1.8.0 split-into-volumes domain: naming/detection, gap discovery, byte-exact
 * splitting (with the never-lose-data cleanup contract), and reading a whole set
 * as one stream / one seekable channel.
 */
class VolumeSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun drained(buf: ByteBuffer): String {
        buf.flip()
        return String(buf.array(), 0, buf.limit())
    }

    // ---------- naming / detection ----------

    @Test
    fun `isVolumeStart accepts 001 names only`() {
        assertTrue(VolumeSupport.isVolumeStart("DCIM.7z.001"))
        assertTrue(VolumeSupport.isVolumeStart("backup.zip.001"))
        assertTrue(VolumeSupport.isVolumeStart("x.001"))
        assertFalse(VolumeSupport.isVolumeStart("DCIM.7z.002"))
        assertFalse(VolumeSupport.isVolumeStart("DCIM.7z.010"))
        assertFalse(VolumeSupport.isVolumeStart("DCIM.7z"))
        assertFalse(VolumeSupport.isVolumeStart("001"))
        assertFalse(VolumeSupport.isVolumeStart(".001")) // bare dot-file, not a set
        assertFalse(VolumeSupport.isVolumeStart("a.0010"))
        assertFalse(VolumeSupport.isVolumeStart(""))
    }

    @Test
    fun `archiveName strips the trailing 001`() {
        assertEquals("DCIM.7z", VolumeSupport.archiveName(File("/any/DCIM.7z.001")))
        assertEquals("backup.zip", VolumeSupport.archiveName(File("backup.zip.001")))
        assertEquals("x", VolumeSupport.archiveName(File("x.001")))
    }

    @Test
    fun `volumeSet returns the contiguous run and stops at gaps`() {
        val dir = tmp.newFolder("set1")
        val first = File(dir, "b.zip.001").apply { writeText("A") }
        // Missing .002 while .003 exists: the run stops at the first missing number.
        assertEquals(listOf(first), VolumeSupport.volumeSet(first))

        File(dir, "b.zip.002").writeText("B")
        File(dir, "b.zip.003").writeText("C")
        File(dir, "b.zip.004").writeText("D")
        assertEquals(
            listOf("b.zip.001", "b.zip.002", "b.zip.003", "b.zip.004"),
            VolumeSupport.volumeSet(first).map { it.name },
        )

        File(dir, "b.zip.003").delete() // gap in the middle now
        assertEquals(
            listOf("b.zip.001", "b.zip.002"),
            VolumeSupport.volumeSet(first).map { it.name },
        )
    }

    @Test
    fun `volumeSet never throws for missing files`() {
        val dir = tmp.newFolder("set2")
        // A missing .001 yields an empty run; a lone .001 is a valid single-volume set.
        assertEquals(emptyList<File>(), VolumeSupport.volumeSet(File(dir, "gone.zip.001")))
        val lone = File(dir, "lone.zip.001").apply { writeText("only") }
        assertEquals(listOf(lone), VolumeSupport.volumeSet(lone))
        // A non-start (e.g. .002) is never a set entry point.
        assertEquals(emptyList<File>(), VolumeSupport.volumeSet(File(dir, "lone.zip.002")))
        // A .001 that exists but is a directory is not a usable start.
        File(dir, "dir.zip.001").mkdirs()
        assertEquals(emptyList<File>(), VolumeSupport.volumeSet(File(dir, "dir.zip.001")))
    }

    @Test
    fun `firstMissingVolume detects gaps and completeness`() {
        val dir = tmp.newFolder("gaps")
        fun freshSet(name: String, numbers: List<Int>) {
            val d = File(dir, name).apply { mkdirs() }
            numbers.forEach { File(d, "$name.bin.${"%03d".format(it)}").writeText("v$it") }
        }
        fun startOf(name: String): File = File(File(dir, name), "$name.bin.001")

        freshSet("complete", listOf(1, 2, 3, 4))
        assertNull(VolumeSupport.firstMissingVolume(startOf("complete")))

        freshSet("gap-mid", listOf(1, 2, 4))
        assertEquals(3, VolumeSupport.firstMissingVolume(startOf("gap-mid")))

        freshSet("gap-early", listOf(1, 3, 4))
        assertEquals(2, VolumeSupport.firstMissingVolume(startOf("gap-early")))

        freshSet("single", listOf(1))
        assertNull(VolumeSupport.firstMissingVolume(startOf("single")))

        // The .001 itself missing: the set is missing its first volume.
        assertEquals(1, VolumeSupport.firstMissingVolume(File(File(dir, "gap-mid"), "absent.bin.001")))
    }

    // ---------- splitting ----------

    @Test
    fun `split produces exact-size volumes and removes the original`() = runBlocking {
        val dir = tmp.newFolder("sp1")
        val archive = File(dir, "data.bin")
        archive.writeBytes(ByteArray(10) { 'a'.code.toByte() }) // 10 bytes
        val volumes = VolumeSupport.split(archive, 4)
        assertEquals(listOf("data.bin.001", "data.bin.002", "data.bin.003"), volumes.map { it.name })
        assertEquals(listOf(4L, 4L, 2L), volumes.map { it.length() })
        // The volumes, concatenated, are the original bytes.
        val joined = volumes.flatMap { it.readBytes().toList() }
        assertArrayEquals(ByteArray(10) { 'a'.code.toByte() }, joined.toByteArray())
        assertFalse("original must be removed once every volume is live", archive.exists())
    }

    @Test
    fun `split of an empty file makes a single empty volume`() = runBlocking {
        val dir = tmp.newFolder("sp2")
        val archive = File(dir, "empty.bin").apply { writeText("") }
        val volumes = VolumeSupport.split(archive, 4)
        assertEquals(listOf("empty.bin.001"), volumes.map { it.name })
        assertEquals(0L, volumes.single().length())
        assertFalse(archive.exists())
    }

    @Test
    fun `split failure keeps the original and leaves no partial volumes`() = runBlocking {
        val dir = tmp.newFolder("sp3")
        val payload = ByteArray(10) { 'k'.code.toByte() }
        val archive = File(dir, "keep.bin").apply { writeBytes(payload) }
        assertTrue(dir.setWritable(false))
        try {
            try {
                VolumeSupport.split(archive, 4)
                fail("split must fail when no volume can be created")
            } catch (_: IOException) {
                // expected: the hidden temp could not even be created
            }
            assertTrue("original must survive a failed split", archive.exists())
            assertArrayEquals(payload, archive.readBytes())
            for (n in 1..3) assertFalse("no .00$n volume may remain", File(dir, "keep.bin.00$n").exists())
            assertTrue("no temps may remain", dir.listFiles()!!.none { it.name.contains(".part-") })
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun `split failure mid-run cleans up already-published volumes`() = runBlocking {
        val dir = tmp.newFolder("sp4")
        val payload = ByteArray(10) { 'm'.code.toByte() }
        val archive = File(dir, "mid.bin").apply { writeBytes(payload) }
        // Block the SECOND volume's final name with a non-empty directory: volume 1
        // publishes fine, volume 2 must fail at the rename (the dir can't be deleted).
        val blocker = File(dir, "mid.bin.002").apply { mkdirs() }
        File(blocker, "precious.txt").writeText("user data")
        try {
            try {
                VolumeSupport.split(archive, 4)
                fail("split must fail when a volume cannot be published")
            } catch (_: IOException) {
                // expected: could not replace mid.bin.002
            }
            assertTrue("original must survive a failed split", archive.exists())
            assertArrayEquals(payload, archive.readBytes())
            assertFalse("published volume 1 must be rolled back", File(dir, "mid.bin.001").exists())
            assertFalse("volume 3 was never reached", File(dir, "mid.bin.003").exists())
            assertTrue("no temps may remain", dir.listFiles()!!.none { it.name.contains(".part-") })
            assertTrue("the blocker's contents are untouched", File(blocker, "precious.txt").exists())
        } finally {
            blocker.deleteRecursively()
        }
    }

    @Test
    fun `split cancelled before it runs keeps the original`() = runBlocking {
        val dir = tmp.newFolder("sp5")
        val archive = File(dir, "cancel.bin").apply { writeText("payload") }
        val job = launch(start = CoroutineStart.LAZY) { VolumeSupport.split(archive, 2) }
        job.cancel() // cancelled before the lazy job ever starts
        job.join()
        assertTrue(archive.exists())
        assertFalse(File(dir, "cancel.bin.001").exists())
    }

    // ---------- reading a set ----------

    @Test
    fun `totalSize sums the live volumes`() {
        val dir = tmp.newFolder("read1")
        val first = File(dir, "s.zip.001").apply { writeText("01234") }
        assertEquals(5L, VolumeSupport.totalSize(first))
        File(dir, "s.zip.002").writeText("567")
        File(dir, "s.zip.003").writeText("89")
        assertEquals(10L, VolumeSupport.totalSize(first))
        assertEquals(0L, VolumeSupport.totalSize(File(dir, "missing.zip.001")))
    }

    @Test
    fun `inputStream concatenates the set in order`() {
        val dir = tmp.newFolder("read2")
        val first = File(dir, "s.zip.001").apply { writeText("AAA-") }
        File(dir, "s.zip.002").writeText("BBB-")
        File(dir, "s.zip.003").writeText("CCC")
        VolumeSupport.inputStream(first).use { ins ->
            assertEquals("AAA-BBB-CCC", ins.readBytes().decodeToString())
        }
    }

    @Test
    fun `seekableChannel spans the set and crosses volume boundaries`() {
        val dir = tmp.newFolder("read3")
        val first = File(dir, "s.7z.001").apply { writeText("01234") }
        File(dir, "s.7z.002").writeText("567")
        File(dir, "s.7z.003").writeText("89")
        VolumeSupport.seekableChannel(first).use { ch ->
            assertEquals(10L, ch.size())
            assertEquals(0L, ch.position())

            // A read straddling the .001/.002 boundary: bytes 4..6 ("4" from volume
            // 1, "5" and "6" from volume 2) arrive through a single read() call.
            ch.position(4)
            val straddle = ByteBuffer.allocate(3)
            while (straddle.hasRemaining()) assertTrue(ch.read(straddle) > 0)
            assertEquals("456", drained(straddle))
            assertEquals(7L, ch.position())

            // A long read spanning all three volumes: bytes 2..9.
            ch.position(2)
            val across = ByteBuffer.allocate(8)
            while (across.hasRemaining()) assertTrue(ch.read(across) > 0)
            assertEquals("23456789", drained(across))
            assertEquals(10L, ch.position())

            // At EOF reads return -1; a zero-remaining buffer reads 0.
            assertEquals(-1, ch.read(ByteBuffer.allocate(1)))
            assertEquals(0, ch.read(ByteBuffer.allocate(0)))
        }
    }

    @Test
    fun `seekableChannel reads EOF and refuses writes`() {
        val dir = tmp.newFolder("read4")
        val first = File(dir, "s.7z.001").apply { writeText("01234") }
        File(dir, "s.7z.002").writeText("567")
        VolumeSupport.seekableChannel(first).use { ch ->
            assertEquals(8L, ch.size())
            // Sequential read of everything, then EOF.
            val all = ByteBuffer.allocate(8)
            while (all.hasRemaining()) assertTrue(ch.read(all) > 0)
            assertEquals("01234567", drained(all))
            assertEquals(-1, ch.read(ByteBuffer.allocate(1)))
            // Reads from beyond size are legal and just hit EOF.
            ch.position(8L)
            assertEquals(-1, ch.read(ByteBuffer.allocate(1)))
            // Seeking back re-reads the same bytes.
            ch.position(2L)
            assertEquals(2L, ch.position())
            try {
                ch.write(ByteBuffer.allocate(1))
                fail("volume channels are read-only")
            } catch (_: NonWritableChannelException) {
            }
            try {
                ch.truncate(1L)
                fail("volume channels cannot be truncated")
            } catch (_: NonWritableChannelException) {
            }
        }
    }
}
