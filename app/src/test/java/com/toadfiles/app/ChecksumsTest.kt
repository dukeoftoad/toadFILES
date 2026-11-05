package com.toadfiles.app

import com.toadfiles.app.domain.Checksums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * v2.1.0 "Add SHA": the streaming SHA-256 behind the Properties sheet's checksum row.
 * Known vectors pin the digest itself; a multi-chunk file pins the streaming loop
 * (1 MiB chunks) against a direct one-shot digest of the same bytes.
 */
class ChecksumsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `empty file matches the well-known sha-256 vector`() {
        val f = tmp.newFile("empty.bin")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksums.sha256(f),
        )
    }

    @Test
    fun `abc matches the well-known sha-256 vector`() {
        val f = tmp.newFile("abc.bin")
        f.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksums.sha256(f),
        )
    }

    @Test
    fun `streaming a multi-chunk file equals a one-shot digest of the same bytes`() {
        // ~2.5 MB: crosses the 1 MiB chunk boundary several times, with a final
        // partial chunk — exactly the loop shape a big download exercises.
        val data = ByteArray(2_500_000) { (it * 31 and 0xFF).toByte() }
        val f = tmp.newFile("big.bin")
        FileOutputStream(f).use { it.write(data) }

        val direct = MessageDigest.getInstance("SHA-256").digest(data)
        assertEquals(Checksums.toHex(direct), Checksums.sha256(f))
    }

    @Test
    fun `digests are lowercase hex, two chars per byte`() {
        val hex = Checksums.toHex(byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xff.toByte()))
        assertEquals("000f7f80ff", hex)
        assertTrue(Checksums.sha256(tmp.newFile("x.bin").apply { writeBytes(byteArrayOf(1)) }).all { it in "0123456789abcdef" })
    }

    @Test
    fun `a changed byte changes the digest`() {
        val a = tmp.newFile("a.bin").apply { writeBytes(ByteArray(4096) { 7 }) }
        val b = tmp.newFile("b.bin").apply { writeBytes(ByteArray(4096) { 7 }.also { it[4095] = 8 }) }
        assertTrue(Checksums.sha256(a) != Checksums.sha256(b))
    }
}
