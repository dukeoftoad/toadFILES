package com.toadfiles.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.tukaani.xz.LZMA2Options
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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
 * Creation (v1.7.3, [create7z]) reuses the zip pipeline's shape: staged temp file +
 * atomic rename, no-follow symlink walk with a visited set, per-file determinate progress
 * (pre-walked totals), cancellation checkpoints per file and per buffer, and the
 * [ZipSupport.Limits] entry cap so a runaway tree can't balloon the header.
 * commons-compress is pure Java and minSdk-26 safe (no native codecs are used — only the
 * LZMA/LZMA2/BCJ Java codecs from commons-compress + XZ for Java).
 *
 * v1.8.0 volumes: extraction is channel-based ([extract] over a SeekableByteChannel)
 * so the same hardened pipeline opens both a plain file and a multi-volume set via
 * [VolumeSupport.seekableChannel]. The old File-based entry point stays as a thin
 * wrapper that opens a read-only FileChannel and delegates — the pipeline body and
 * its protections are unchanged.
 */
object SevenZipSupport {

    /**
     * File-based entry point (v1.7.1). Opens a read-only [FileChannel] over the
     * archive and delegates to the channel-based [extract]; the channel is closed
     * by the wrapper whichever way the run ends. An unreadable file reports an
     * honest failed [OpResult] instead of throwing.
     *
     * v1.8.0: [password] is carried through to SevenZFile (null keeps the exact
     * v1.7.1 password-less behaviour).
     */
    suspend fun extract(
        sevenZFile: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits = ZipSupport.Limits(),
        password: CharArray? = null,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult {
        val channel = try {
            FileChannel.open(sevenZFile.toPath(), StandardOpenOption.READ)
        } catch (e: Exception) {
            return OpResult(0, 0, 1, false, listOf("${sevenZFile.name}: ${ZipSupport.friendlyArchiveError(e)}"))
        }
        return channel.use { ch ->
            extract(ch, sevenZFile.length(), targetDir, replace, limits, password, onProgress)
        }
    }

    /**
     * Channel-based pipeline (v1.8.0) — the v1.7.1 body verbatim, opening
     * [SevenZFile] over [channel] with [password] instead of over a File. The caller
     * owns [channel]; commons-compress's SevenZFile.close() also closes it, and a
     * FileChannel close is idempotent either way. [channelSizeHint] is informational
     * (the caller may only know the set's total size, e.g. a volume set) — SevenZFile
     * derives everything it needs from the channel itself.
     */
    suspend fun extract(
        channel: SeekableByteChannel,
        channelSizeHint: Long,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits = ZipSupport.Limits(),
        password: CharArray? = null,
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
                SevenZFile(channel, password)
            } catch (e: PasswordRequiredException) {
                abort = true
                fail++
                failures += ZipSupport.PASSWORD_UNSUPPORTED
                null
            }
            if (seven != null) {
                seven.use { sz ->
                    val allEntries = sz.entries.toList()
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
            failures += "7z archive: ${ZipSupport.friendlyArchiveError(e)}"
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
        SevenZFile(sevenZFile).use { it.entries.toList().size }
    } catch (_: Exception) {
        null
    }

    // ---------- creation (v1.7.3) ----------

    /**
     * [CompressLevel] → xz LZMA2 preset (dictionary + match-finder settings), mirroring
     * the xz CLI presets. STORE is handled separately via the COPY codec and never maps
     * to a preset.
     *
     * Encoder memory is roughly 10.5× the dictionary size (xz for Java's own formula):
     * FAST ≈ 11 MB, NORMAL ≈ 88 MB, MAXIMUM ≈ 700 MB. MAXIMUM is offered for desktop-grade
     * content; [create7z] converts an encoder OOM into an honest failure instead of a crash.
     */
    internal fun lzma2Preset(level: CompressLevel): Int = when (level) {
        CompressLevel.FAST -> 1      // 1 MiB dictionary
        CompressLevel.NORMAL -> 6    // 8 MiB dictionary (the xz default)
        CompressLevel.MAXIMUM -> 9   // 64 MiB dictionary
        CompressLevel.STORE -> throw IllegalArgumentException("STORE uses the COPY codec, not LZMA2")
    }

    /**
     * The commons-compress content-method configuration for [level]:
     *  - STORE → [SevenZMethod.COPY]: every byte lands in the archive verbatim;
     *  - FAST/NORMAL/MAXIMUM → LZMA2 at the xz preset from [lzma2Preset]
     *    (passed as a full [LZMA2Options], which commons-compress accepts natively).
     */
    internal fun contentMethodsFor(level: CompressLevel): List<SevenZMethodConfiguration> =
        if (level == CompressLevel.STORE) {
            listOf(SevenZMethodConfiguration(SevenZMethod.COPY))
        } else {
            val options = LZMA2Options()
            options.setPreset(lzma2Preset(level))
            listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, options))
        }

    /** Entry-count budget for creation: the 7z header must stay bounded like the zip one. */
    private class EntryBudget(private val max: Int) {
        var used = 0
            private set

        fun take() {
            if (++used > max) {
                throw ZipSupport.ArchiveAbort("Aborted: more than $max entries (possible bomb)")
            }
        }
    }

    /**
     * Creates a solid 7z archive from [sources] at [target] (v1.7.3).
     *
     * Mirrors [ZipSupport.zip] protection-for-protection: the archive is assembled in a
     * hidden temp sibling and atomically renamed onto [target] only on success (an
     * existing target is replaced); a failing source aborts the whole run so no broken
     * archive is ever published; cancellation checkpoints run per source, per file and
     * per buffer; symbolic links are never followed (links to files stored by content,
     * folder/dangling links skipped with reasons, visited-set breaks cycles); duplicate
     * top-level names get "(1)"-style entry names; and [limits.maxEntries] bounds the
     * header size. Progress is determinate: the file count comes from the same pre-walk
     * that produces the entries.
     */
    suspend fun create7z(
        sources: List<File>,
        target: File,
        level: CompressLevel = CompressLevel.NORMAL,
        limits: ZipSupport.Limits = ZipSupport.Limits(),
        password: CharArray? = null,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = withContext(Dispatchers.IO) {
        PathSafety.rejectSelfTarget(sources, target.parentFile ?: File("."))
        val parent = target.parentFile ?: File(".")
        if (!parent.isDirectory && !parent.mkdirs()) {
            return@withContext OpResult(0, 0, 1, false, listOf("Cannot create ${parent.name}"))
        }
        val tmp = File(parent, ".${target.name}.part-${System.nanoTime()}")
        var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        var filesDone = 0
        val totalFiles = sources.sumOf { ZipSupport.countFileEntries(it) }
        onProgress(OpProgress(0, totalFiles, ""))
        val usedEntryNames = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val skipped = mutableListOf<String>()
        var topLevelSkipped = 0
        val budget = EntryBudget(limits.maxEntries)
        var completed = false
        try {
            val out = if (password == null) SevenZOutputFile(tmp) else SevenZOutputFile(tmp, password)
            out.use { sz ->
                sz.setContentMethods(contentMethodsFor(level))
                for (src in sources) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val reason = ZipSupport.unfollowableLinkReason(src)
                        if (reason != null) {
                            // The SOURCE ITSELF is a link we never follow: nothing of it
                            // enters the archive. Nested skips inside a real folder are
                            // recorded in `skipped` without failing the source.
                            skipped += "${src.name}: not added: $reason (links are not followed)"
                            topLevelSkipped++
                        } else {
                            addTo7z(
                                src, ZipSupport.uniqueEntryName(usedEntryNames, src.name),
                                sz, visited, skipped, budget,
                            ) {
                                filesDone++
                                onProgress(OpProgress(filesDone, totalFiles, src.name))
                            }
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        cancelled = true
                        break
                    } catch (ab: ZipSupport.ArchiveAbort) {
                        // Entry-budget overrun: nothing after this can be trusted.
                        fail++
                        failures += ab.message ?: "aborted"
                        break
                    } catch (e: Exception) {
                        // The stream may already hold a half-written entry: publishing it
                        // would hand out a broken archive, so abort and delete.
                        fail++
                        failures += "${src.name}: ${e.message ?: "error"}"
                        break
                    }
                }
            }
            completed = !cancelled && fail == 0
        } catch (ce: kotlinx.coroutines.CancellationException) {
            cancelled = true
        } catch (oom: OutOfMemoryError) {
            // The LZMA2 encoder needs ~10.5× the dictionary; a too-big dictionary on a
            // heap-limited device must degrade to an honest failure, not a crash.
            fail++
            failures += "${target.name}: not enough memory for the ${level.name.lowercase()} " +
                "compression level — try a lower one"
        } catch (e: Exception) {
            if (!cancelled) {
                fail++
                failures += "${target.name}: ${e.message ?: "write failed"}"
            }
        }
        if (completed) {
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                fail++
                failures += "${target.name}: ${e.message ?: "publish failed"}"
                completed = false
            }
        }
        if (tmp.exists()) tmp.delete()
        // `completed` implies every followable source was added; an archive that was
        // never published means nothing the user asked for succeeded. Unfollowed links
        // are reported as skips (with reasons), not failures — the archive is still valid.
        OpResult(
            if (completed) sources.size - topLevelSkipped else 0,
            skipped.size,
            fail,
            cancelled,
            failures + skipped,
        )
    }

    /**
     * 7z twin of ZipSupport's addToZip: same no-follow link policy, same visited-set
     * cycle guard, same relative entry names — writing [SevenZArchiveEntry]s instead.
     * Cancellation is checked per file AND per directory.
     */
    private suspend fun addTo7z(
        file: File,
        entryName: String,
        sz: SevenZOutputFile,
        visited: MutableSet<String>,
        skipped: MutableList<String>,
        budget: EntryBudget,
        onFileDone: () -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val canonical = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
        if (!visited.add(canonical)) return // link cycle / already added: never recurse twice
        val reason = ZipSupport.unfollowableLinkReason(file)
        if (reason != null) {
            skipped += "$entryName: not added: $reason (links are not followed)"
            return
        }
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                // Empty folders must survive the round-trip: a stream-less directory
                // entry, exactly like the zip pipeline's "name/" entries.
                budget.take()
                val e = SevenZArchiveEntry()
                e.name = "$entryName/"
                e.isDirectory = true
                sz.putArchiveEntry(e)
                sz.closeArchiveEntry()
            } else {
                for (c in children) addTo7z(c, "$entryName/${c.name}", sz, visited, skipped, budget, onFileDone)
            }
            return
        }
        budget.take()
        writeFileEntry(file, entryName, sz)
        onFileDone()
    }

    /** Opens the source before the entry: an unreadable source then never leaves a
     *  half-open entry behind in the archive. Sizes/stream flags are derived by
     *  closeArchiveEntry from the bytes actually written (commons-compress), so a file
     *  that changed length mid-read aborts honestly instead of corrupting the header. */
    private suspend fun writeFileEntry(file: File, entryName: String, sz: SevenZOutputFile) {
        FileInputStream(file).use { fis ->
            val e = SevenZArchiveEntry()
            e.name = entryName
            sz.putArchiveEntry(e)
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = fis.read(buf)
                if (n == -1) break
                sz.write(buf, 0, n)
            }
            sz.closeArchiveEntry()
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}
