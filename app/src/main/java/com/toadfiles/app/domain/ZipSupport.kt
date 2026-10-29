package com.toadfiles.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Safe ZIP creation and extraction.
 *
 * Extraction protections (per the project security requirements):
 *  - ZIP-slip: entry names are validated AND final paths are canonical-checked.
 *  - Zip bombs: entry count, total uncompressed bytes and compression-ratio caps.
 *  - No accidental overwrite: existing files are skipped unless [replace] is set.
 */
object ZipSupport {

    data class Limits(
        val maxEntries: Int = 50_000,
        val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
        val maxRatio: Long = 200,
    )

    suspend fun extract(
        zipFile: File,
        targetDir: File,
        replace: Boolean,
        limits: Limits = Limits(),
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = withContext(Dispatchers.IO) {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return@withContext OpResult(0, 0, 1, false, listOf("Cannot create ${targetDir.name}"))
        }
        var ok = 0; var skip = 0; var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        var entries = 0
        var totalBytes = 0L
        try {
            ZipInputStream(FileInputStream(zipFile).buffered(BUFFER_SIZE)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    try {
                        currentCoroutineContext().ensureActive()
                        entries++
                        if (entries > limits.maxEntries) {
                            failures += "Aborted: more than ${limits.maxEntries} entries (possible zip bomb)"
                            fail++
                            break
                        }
                        val name = entry.name
                        if (!PathSafety.isSafeEntryName(name)) {
                            skip++; continue
                        }
                        val outFile = File(targetDir, name)
                        PathSafety.ensureInside(targetDir, outFile)
                        if (entry.isDirectory) {
                            if (!outFile.exists()) outFile.mkdirs()
                            continue
                        }
                        outFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                        if (outFile.exists()) {
                            if (!replace) { skip++; continue }
                            if (!outFile.delete()) { fail++; failures += "Cannot replace ${entry.name}"; continue }
                        }
                        if (entry.size > 0) {
                            totalBytes += entry.size
                            if (totalBytes > limits.maxTotalBytes) {
                                failures += "Aborted: exceeds ${Format.size(limits.maxTotalBytes)} uncompressed (possible zip bomb)"
                                fail++
                                break
                            }
                        }
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(BUFFER_SIZE)
                            var written = 0L
                            val ratioLimit = (entry.compressedSize.coerceAtLeast(0) + 64) * limits.maxRatio
                            while (true) {
                                val n = zis.read(buf)
                                if (n == -1) break
                                written += n
                                if (entry.compressedSize > 0 && written > ratioLimit) {
                                    throw IOException("Suspicious expansion ratio in ${entry.name}")
                                }
                                fos.write(buf, 0, n)
                            }
                        }
                        ok++
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        cancelled = true
                        break
                    } catch (e: Exception) {
                        fail++
                        failures += "${entry.name}: ${e.message ?: "error"}"
                    } finally {
                        zis.closeEntry()
                        onProgress(OpProgress(entries, -1, entry.name))
                    }
                }
            }
        } catch (e: Exception) {
            if (!cancelled) {
                fail++
                failures += "${zipFile.name}: ${e.message ?: "unreadable archive"}"
            }
        }
        OpResult(ok, skip, fail, cancelled, failures)
    }

    suspend fun zip(
        sources: List<File>,
        outFile: File,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = withContext(Dispatchers.IO) {
        PathSafety.rejectSelfTarget(sources, outFile.parentFile ?: File("."))
        var ok = 0; var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        var done = 0
        val total = sources.size
        try {
            ZipOutputStream(FileOutputStream(outFile).buffered(BUFFER_SIZE)).use { zos ->
                for (src in sources) {
                    try {
                        currentCoroutineContext().ensureActive()
                        onProgress(OpProgress(done, total, src.name))
                        addToZip(src, src.name, zos)
                        ok++
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        cancelled = true
                        break
                    } catch (e: Exception) {
                        fail++
                        failures += "${src.name}: ${e.message ?: "error"}"
                    }
                    done++
                }
            }
        } catch (e: Exception) {
            if (!cancelled) {
                fail++
                failures += "${outFile.name}: ${e.message ?: "write failed"}"
                outFile.delete()
            }
        }
        if (cancelled) outFile.delete()
        OpResult(ok, 0, fail, cancelled, failures)
    }

    private const val BUFFER_SIZE = 64 * 1024

    private fun addToZip(file: File, entryName: String, zos: ZipOutputStream) {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                zos.putNextEntry(ZipEntry("$entryName/"))
                zos.closeEntry()
            } else {
                for (c in children) addToZip(c, "$entryName/${c.name}", zos)
            }
            return
        }
        val entry = ZipEntry(entryName)
        entry.time = file.lastModified()
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = fis.read(buf)
                if (n == -1) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }
}
