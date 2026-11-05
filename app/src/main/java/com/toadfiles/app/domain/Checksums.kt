package com.toadfiles.app.domain

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * File checksums for the Properties sheet (v2.1.0 "Add SHA").
 *
 * [sha256] streams the file in fixed chunks — memory stays flat whatever the file
 * size — and is cancellation-checked per chunk so dismissing the sheet stops the
 * read promptly (the caller runs it on IO inside a cancellable job, the same
 * discipline as the properties size/contents walk). The digest is lowercase hex,
 * ready to paste into a verifier without post-processing.
 */
object Checksums {

    /** 1 MiB chunks: few syscalls per GB, still responsive to cancellation. */
    private const val CHUNK = 1024 * 1024

    /**
     * SHA-256 of [file]'s contents as lowercase hex. Throws what the filesystem
     * throws (the properties sheet treats any failure as "—" via runCatching).
     * Unreadable/ vanished mid-read surfaces as an exception, never a wrong digest.
     */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return toHex(md.digest())
    }

    /** Lowercase hex, two chars per byte — the form every checksum verifier expects. */
    internal fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }
}
