package com.toadfiles.app

import com.toadfiles.app.domain.ZipSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Regression for the owner bug report "Extracting … 441 of 2 items": an outer zip
 * holding an APK + a .sha256 sidecar must extract EXACTLY its two top-level entries —
 * the APK is payload, never a nested archive to walk into.
 *
 * The old extractor drove the loop with ZipInputStream, which trusts LOCAL file
 * headers. An outer zip written by a streamed tool can carry an entry whose local
 * header lies about its sizes (never patched after streaming): the stream then read
 * zero bytes for the APK, closed the entry, and re-synced into the APK's OWN local
 * file headers — publishing res/…, META-INF/… on top of the two honest entries.
 * 2 declared entries ballooned to 441 walked ones, byte for byte the reported
 * symptom ("441 of 2 items — res/drawable-hdpi-v4/…"). The fix walks the central
 * directory ([ZipFile]) instead, so this test hand-builds that hostile archive
 * byte by byte — construction mirrors scripts/ZipResyncProof.java.
 */
class ZipSupportNestedResyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val apkName = "toadGALLERY-0.4.5-alpha-engine-debug.apk"
    private val shaName = "$apkName.sha256"

    /** 439 internal entries, written the streamed way (data descriptors) — the exact
     *  structures the old stream re-synced into. */
    private fun buildFakeApk(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(ByteArray(1_800) { (it % 7).toByte() })
            zos.closeEntry()
            for (i in 1..436) {
                zos.putNextEntry(ZipEntry("res/drawable-hdpi-v4/img_%04d.png".format(i)))
                zos.write(ByteArray(400 + (i % 37) * 13) { (it % 251).toByte() })
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(5_200) { (it % 11).toByte() })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\n".toByteArray())
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun locHeader(name: ByteArray, flags: Int, crc: Long, csize: Int, size: Int): ByteArray {
        val h = ByteArrayOutputStream()
        writeU32(h, 0x04034b50L); writeU16(h, 20); writeU16(h, flags); writeU16(h, 0 /*STORED*/)
        writeU16(h, 0x6000); writeU16(h, 0x5A11)
        writeU32(h, crc); writeU32(h, csize.toLong()); writeU32(h, size.toLong())
        writeU16(h, name.size); writeU16(h, 0)
        h.write(name)
        return h.toByteArray()
    }

    private fun cenHeader(name: ByteArray, crc: Long, csize: Int, size: Int, offset: Long): ByteArray {
        val h = ByteArrayOutputStream()
        writeU32(h, 0x02014b50L); writeU16(h, 20); writeU16(h, 20); writeU16(h, 0); writeU16(h, 0 /*STORED*/)
        writeU16(h, 0x6000); writeU16(h, 0x5A11)
        writeU32(h, crc); writeU32(h, csize.toLong()); writeU32(h, size.toLong())
        writeU16(h, name.size); writeU16(h, 0); writeU16(h, 0)
        writeU16(h, 0); writeU16(h, 0); writeU32(h, 0)
        writeU32(h, offset)
        h.write(name)
        return h.toByteArray()
    }

    /** [shaBytes] stored honestly; [apkBytes] stored under a local header that LIES
     *  (crc/csize/size left at 0 — the streamed-writer artifact). The central directory
     *  stays honest: exactly 2 entries with true sizes. */
    private fun buildHostileOuterZip(shaBytes: ByteArray, apkBytes: ByteArray): ByteArray {
        val z = ByteArrayOutputStream()
        val crcSha = CRC32().apply { update(shaBytes) }.value
        val crcApk = CRC32().apply { update(apkBytes) }.value
        val nameSha = shaName.toByteArray()
        val nameApk = apkName.toByteArray()

        val offSha = z.size().toLong()
        z.write(locHeader(nameSha, 0, crcSha, shaBytes.size, shaBytes.size))
        z.write(shaBytes)
        val offApk = z.size().toLong()
        z.write(locHeader(nameApk, 0, 0, 0, 0))
        z.write(apkBytes)

        val cd = ByteArrayOutputStream()
        cd.write(cenHeader(nameSha, crcSha, shaBytes.size, shaBytes.size, offSha))
        cd.write(cenHeader(nameApk, crcApk, apkBytes.size, apkBytes.size, offApk))
        val cdBytes = cd.toByteArray()

        val cdOff = z.size().toLong()
        z.write(cdBytes)
        writeU32(z, 0x06054b50L)
        writeU16(z, 0); writeU16(z, 0); writeU16(z, 2); writeU16(z, 2)
        writeU32(z, cdBytes.size.toLong()); writeU32(z, cdOff); writeU16(z, 0)
        return z.toByteArray()
    }

    @Test
    fun `nested archive entries never leak into the destination`() = runBlocking {
        val dir = tmp.newFolder("resync")
        val apkBytes = buildFakeApk()
        val shaBytes = ("d7f3a1c0e9b2  $shaName\n").toByteArray()
        val zip = File(dir, "toadGALLERY-0.4.5-alpha-engine-debug.zip")
        zip.writeBytes(buildHostileOuterZip(shaBytes, apkBytes))

        // Precondition: the fixture IS hostile — a local-header stream walks far more
        // than the 2 declared entries (the old engine's view of this archive).
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var walked = 0
            while (zis.nextEntry != null) {
                walked++
                val buf = ByteArray(8192)
                while (zis.read(buf) != -1) { /* drain, like the old extractor */ }
                runCatching { zis.closeEntry() }
            }
            assertTrue(
                "fixture must re-sync into the nested archive (walked $walked)",
                walked > 2,
            )
        }

        val target = File(dir, "out").apply { mkdirs() }
        val ticks = mutableListOf<com.toadfiles.app.domain.OpProgress>()
        val r = ZipSupport.extract(zip, target, replace = false) { p -> ticks += p }

        assertEquals("failed: ${r.failures}", 0, r.failed)
        assertEquals(0, r.skipped)
        assertEquals("exactly the declared top-level entries", 2, r.succeeded)

        // Destination holds exactly the APK + its sidecar — no res/…, no META-INF/….
        assertEquals(setOf(apkName, shaName), target.listFiles()!!.map { it.name }.toSet())
        assertFalse(File(target, "res").exists())
        assertArrayEquals("the APK must round-trip byte-identical", apkBytes, File(target, apkName).readBytes())
        assertArrayEquals(shaBytes, File(target, shaName).readBytes())

        // Progress honesty: total is the declared 2 — "441 of 2" can never return —
        // and the byte signal covers the whole archive.
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it.total == 2 && it.done <= 2 })
        assertEquals((apkBytes.size + shaBytes.size).toLong(), ticks.first().bytesTotal)
        assertEquals((apkBytes.size + shaBytes.size).toLong(), ticks.last().bytesDone)
        assertTrue(dir.listFiles()!!.none { it.name.contains(".staging-") })
    }
}
