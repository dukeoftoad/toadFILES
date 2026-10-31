package com.toadfiles.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Safe 7z extraction (v1.7.1) — the 7z twin of [ZipSupport.extract], reusing the exact
 * same hardened staging/publication machinery:
 *
 *  - ZIP-slip: entry names validated by [PathSafety.isSafeEntryName] AND canonical-checked
 *    via [PathSafety.ensureInside] for both the staged and the final path.
 *  - Bomb caps: [ZipSupport.Limits] entry count and total emitted bytes are enforced from
 *    the bytes actually written. (7z declares per-entry sizes in the header, so the total
 *    byte budget is also fail-fast checked per entry; 7z's solid compression makes a
 *    per-entry compressed/uncompressed ratio unreliable, so the ratio guard stays a
 *    zip-only protection — the byte cap still bounds the damage.)
 *  - Staged extraction + atomic publish/rollback: shared verbatim with zip via
 *    [ZipSupport.publishStaging] and [ZipSupport.Counts].
 *  - Encrypted archives: 7z with a password (header- or content-encrypted) fails with the
 *    app-wide friendly copy [ZipSupport.PASSWORD_UNSUPPORTED] instead of a codec crash.
 *  - Cancellation checkpoints per entry and per buffer; everything runs on Dispatchers.IO.
 *
 * 7z is extract-only by design (v1.7.1): creation stays on the zip pipeline, so there is
 * deliberately no "compress to 7z" anywhere in the UI. commons-compress is pure Java and
 * minSdk-26 safe (no native codecs are used — only the LZMA/LZMA2/BCJ Java decoders).
 */
object SevenZipSupport {

    suspend fun extract(
        sevenZFile: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits = ZipSupport.Limits(),
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = withContext(Dispatchers.IO) {
        val parent = targetDir.parentFile ?: File(".")
        if (!parent.isDirectory && !parent.mkdirs()) {
            return@withContext OpResult(0, 0, 1, false, listOf("Cannot create ${parent.name}"))
        }
        val staging = File(parent, ".${targetDir.name}.staging-${System.nanoTime()}")
        if (!staging.isDirectory && !staging.mkdirs()) {
            return@withContext OpResult(0, 0, 1, false, listOf("Cannot create ${staging.name}"))
        }

        var skip = 0
        var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        var abort = false
        var entries = 0
        var totalBytes = 0L
        val counts = ZipSupport.Counts()
        // The 7z header carries the full entry list, so progress is always determinate.
        var total = -1

        try {
            val seven = try {
                SevenZFile(sevenZFile)
            } catch (e: PasswordRequiredException) {
                abort = true
                fail++
                failures += ZipSupport.PASSWORD_UNSUPPORTED
                null
            }
            if (seven != null) {
                seven.use { sz ->
                    val allEntries = sz.entries
                    total = allEntries.size
                    for (entry in allEntries) {
                        val name = entry.name
                        try {
                            currentCoroutineContext().ensureActive()
                            entries++
                            if (entries > limits.maxEntries) {
                                throw ZipSupport.ArchiveAbort(
                                    "Aborted: more than ${limits.maxEntries} entries (possible zip bomb)"
                                )
                            }
                            if (!PathSafety.isSafeEntryName(name)) {
                                skip++
                            } else {
                                val stagedFile = File(staging, name)
                                PathSafety.ensureInside(staging, stagedFile)
                                val finalFile = File(targetDir, name)
                                PathSafety.ensureInside(targetDir, finalFile)
                                if (entry.isDirectory) {
                                    if (!stagedFile.exists()) stagedFile.mkdirs()
                                } else if (!entry.hasStream()) {
                                    // 7z stores empty files as stream-less entries: stage
                                    // them as empty files so they don't silently vanish.
                                    stagedFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                                    if (!stagedFile.exists()) stagedFile.createNewFile()
                                } else if (finalFile.exists() && !replace) {
                                    skip++
                                } else {
                                    // Fail fast on the declared size; the actual-byte
                                    // accounting below is the authority either way.
                                    if (entry.size > 0 && entry.size > limits.maxTotalBytes - totalBytes) {
                                        throw ZipSupport.ArchiveAbort(
                                            "Aborted: exceeds ${Format.size(limits.maxTotalBytes)} uncompressed (possible zip bomb)"
                                        )
                                    }
                                    stagedFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                                    val partFile = File(
                                        stagedFile.parentFile,
                                        "${stagedFile.name}.part-${System.nanoTime()}",
                                    )
                                    try {
                                        sz.getInputStream(entry).use { ins ->
                                            FileOutputStream(partFile).use { fos ->
                                                val buf = ByteArray(BUFFER_SIZE)
                                                while (true) {
                                                    currentCoroutineContext().ensureActive()
                                                    val n = ins.read(buf)
                                                    if (n == -1) break
                                                    totalBytes += n
                                                    if (totalBytes > limits.maxTotalBytes) {
                                                        throw ZipSupport.ArchiveAbort(
                                                            "Aborted: exceeds ${Format.size(limits.maxTotalBytes)} uncompressed (possible zip bomb)"
                                                        )
                                                    }
                                                    fos.write(buf, 0, n)
                                                }
                                            }
                                        }
                                        if (stagedFile.exists()) stagedFile.delete()
                                        if (!partFile.renameTo(stagedFile)) {
                                            throw IOException("could not stage $name")
                                        }
                                    } finally {
                                        if (partFile.exists()) partFile.delete()
                                    }
                                }
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            cancelled = true
                            break
                        } catch (ab: ZipSupport.ArchiveAbort) {
                            abort = true
                            fail++
                            failures += ab.message ?: "aborted"
                            break
                        } catch (e: PasswordRequiredException) {
                            fail++
                            failures += ZipSupport.PASSWORD_UNSUPPORTED
                        } catch (e: Exception) {
                            fail++
                            failures += "$name: ${ZipSupport.friendlyArchiveError(e)}"
                        } finally {
                            if (total >= 0) onProgress(OpProgress(entries, total, name))
                        }
                    }
                }
            }
        } catch (e: PasswordRequiredException) {
            abort = true
            fail++
            failures += ZipSupport.PASSWORD_UNSUPPORTED
        } catch (e: Exception) {
            // Stream-level death (truncated/corrupt archive): nothing after this point
            // can be trusted, so nothing gets published.
            abort = true
            fail++
            failures += "${sevenZFile.name}: ${ZipSupport.friendlyArchiveError(e)}"
        }

        if (!cancelled && !abort) {
            if (!targetDir.isDirectory && !targetDir.mkdirs()) {
                fail++
                failures += "Cannot create ${targetDir.name}"
            } else {
                try {
                    ZipSupport.publishStaging(staging, targetDir, replace, counts, failures)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    cancelled = true
                } catch (e: Exception) {
                    fail++
                    failures += "${targetDir.name}: ${e.message ?: "publish failed"}"
                }
            }
        }
        // Published files were moved out; anything left (aborts, cancels, conflicts) is junk.
        FileOps.deleteRecursively(staging)

        OpResult(counts.moved, skip + counts.skipped, fail + counts.failed, cancelled, failures)
    }

    /** Entry count from the 7z header, for determinate progress before the op starts;
     *  null when the archive can't be opened (the op itself will report the error). */
    fun entryCount(sevenZFile: File): Int? = try {
        SevenZFile(sevenZFile).use { it.entries.size }
    } catch (_: Exception) {
        null
    }

    private const val BUFFER_SIZE = 64 * 1024
}
