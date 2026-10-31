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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 *  - Encrypted archives (v1.8.0): 7z created with a password uses commons-compress
 *    AES-256 data-only encryption (headers stay readable, like ZArchiver's "Data
 *    (AES-256)" option). Extraction accepts a password; a missing one is reported as
 *    [OpResult.passwordRequired] (the UI prompts and retries), a wrong one as the
 *    friendly failure [WRONG_PASSWORD]. Encrypted headers (7z "header encryption",
 *    which commons-compress cannot create) also surface as passwordRequired when
 *    opened without a password. Encrypted ZIP stays declined with
 *    [ZipSupport.PASSWORD_UNSUPPORTED] — java.util.zip cannot decrypt.
 *  - Cancellation checkpoints per entry and per buffer; everything runs on Dispatchers.IO.
 *
 * Creation (v1.7.3, [create7z]) reuses the zip pipeline's shape: staged temp file +
 * atomic rename, no-follow symlink walk with a visited set, per-file determinate progress
 * (pre-walked totals), cancellation checkpoints per file and per buffer, and the
 * [ZipSupport.Limits] entry cap so a runaway tree can't balloon the header.
 * commons-compress is pure Java and minSdk-26 safe (no native codecs are used — only the
 * LZMA/LZMA2/BCJ Java codecs from commons-compress + XZ for Java).
 */
object SevenZipSupport {

    /** v1.8.0 friendly copy when the supplied password doesn't decrypt the archive. */
    internal const val WRONG_PASSWORD = "Wrong password"

    /** v1.8.0 friendly copy accompanying [OpResult.passwordRequired] results. */
    internal const val PASSWORD_REQUIRED = "Password required"

    suspend fun extract(
        sevenZFile: File,
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
        var passwordRequired = false
        val counts = ZipSupport.Counts()
        // The 7z header carries the full entry list, so progress is always determinate.
        var total = -1
        val hasPassword = password != null && password.isNotEmpty()

        try {
            val seven = try {
                // The null/isEmpty check is spelled out so `password` smart-casts to
                // CharArray for the commons-compress constructor.
                if (password != null && password.isNotEmpty()) {
                    SevenZFile(sevenZFile, password)
                } else {
                    SevenZFile(sevenZFile)
                }
            } catch (e: PasswordRequiredException) {
                // The archive needs a password even to be opened (e.g. encrypted headers)
                // and none was supplied: ask for one instead of failing blindly.
                passwordRequired = true
                abort = true
                fail++
                failures += PASSWORD_REQUIRED
                null
            } catch (e: Exception) {
                // With a password supplied, an open failure is a wrong password in the
                // overwhelming majority of cases (AES auth at open time); without one it
                // is an honest open error. Either way nothing may be published.
                abort = true
                fail++
                failures += if (hasPassword) {
                    WRONG_PASSWORD
                } else {
                    "${sevenZFile.name}: ${ZipSupport.friendlyArchiveError(e)}"
                }
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
                            // This entry's data needs a password and none was supplied.
                            // One prompt covers the whole archive: stop, publish nothing.
                            passwordRequired = true
                            abort = true
                            fail++
                            failures += PASSWORD_REQUIRED
                            break
                        } catch (e: Exception) {
                            if (hasPassword) {
                                // A password was supplied and a read failed: commons-compress
                                // surfaces an AES authentication failure as a general
                                // IOException/CRC error (no dedicated type), so any read
                                // failure here is treated as a wrong password. A genuinely
                                // corrupt archive would report the same friendly message —
                                // acceptable, and retrying with the right password is the
                                // only useful next step either way.
                                abort = true
                                fail++
                                failures += WRONG_PASSWORD
                                break
                            }
                            fail++
                            failures += "$name: ${ZipSupport.friendlyArchiveError(e)}"
                        } finally {
                            if (total >= 0) onProgress(OpProgress(entries, total, name))
                        }
                    }
                }
            }
        } catch (e: PasswordRequiredException) {
            passwordRequired = true
            abort = true
            fail++
            failures += PASSWORD_REQUIRED
        } catch (e: Exception) {
            // Stream-level death (truncated/corrupt archive): nothing after this point
            // can be trusted, so nothing gets published.
            abort = true
            fail++
            failures += if (hasPassword) {
                WRONG_PASSWORD
            } else {
                "${sevenZFile.name}: ${ZipSupport.friendlyArchiveError(e)}"
            }
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

        OpResult(
            counts.moved, skip + counts.skipped, fail + counts.failed, cancelled, failures,
            passwordRequired,
        )
    }

    /** Entry count from the 7z header, for determinate progress before the op starts;
     *  null when the archive can't be opened (the op itself will report the error). */
    fun entryCount(sevenZFile: File): Int? = try {
        SevenZFile(sevenZFile).use { it.entries.toList().size }
    } catch (_: Exception) {
        null
    }

    // ---------- creation (v1.7.3) ----------

    /** ULTRA dictionary size for 7z: 128 MiB ≈ ZArchiver's ultra — preset 9 with the
     *  dictionary pushed past xz's default 64 MiB. */
    internal const val ULTRA_DICT_BYTES = 128 * 1024 * 1024

    /**
     * [CompressLevel] → xz LZMA2 preset (dictionary + match-finder settings), mirroring
     * the xz CLI presets. NONE is handled separately via the COPY codec and never maps
     * to a preset. ULTRA is preset 9 like MAXIMUM — the difference is made in
     * [contentMethodsFor], which swaps in the 128 MiB ULTRA dictionary.
     *
     * Encoder memory is roughly 10.5× the dictionary size (xz for Java's own formula):
     * FASTEST ≈ 11 MB, FAST ≈ 44 MB, NORMAL ≈ 88 MB, MAXIMUM ≈ 700 MB, ULTRA ≈ 1.3 GB.
     * MAXIMUM/ULTRA are offered for desktop-grade content; [create7z] converts an
     * encoder OOM into an honest failure instead of a crash.
     */
    internal fun lzma2Preset(level: CompressLevel): Int = when (level) {
        CompressLevel.FASTEST -> 1  // 1 MiB dictionary
        CompressLevel.FAST -> 3    // 4 MiB dictionary
        CompressLevel.NORMAL -> 6  // 8 MiB dictionary (the xz default)
        CompressLevel.MAXIMUM -> 9 // 64 MiB dictionary
        CompressLevel.ULTRA -> 9   // + the 128 MiB dictionary from [contentMethodsFor]
        CompressLevel.NONE -> throw IllegalArgumentException("NONE uses the COPY codec, not LZMA2")
    }

    /**
     * The commons-compress content-method configuration for [level]:
     *  - NONE → [SevenZMethod.COPY]: every byte lands in the archive verbatim;
     *  - the compressing levels → LZMA2 at the xz preset from [lzma2Preset]
     *    (passed as a full [LZMA2Options], which commons-compress accepts natively);
     *  - ULTRA → LZMA2 at preset 9 but with the 128 MiB dictionary [ULTRA_DICT_BYTES]
     *    (≈ ZArchiver ultra), NOT the plain preset-9 64 MiB dictionary.
     */
    internal fun contentMethodsFor(level: CompressLevel): List<SevenZMethodConfiguration> =
        if (level == CompressLevel.NONE) {
            listOf(SevenZMethodConfiguration(SevenZMethod.COPY))
        } else {
            val options = LZMA2Options()
            options.setPreset(lzma2Preset(level))
            if (level == CompressLevel.ULTRA) options.setDictSize(ULTRA_DICT_BYTES)
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
     * v1.8.0: a non-null, non-empty [password] turns on commons-compress AES-256
     * data-only encryption — the entry list and sizes stay readable (ZArchiver's
     * "Data (AES-256)" equivalent); only the entry streams are encrypted. A null or
     * empty password creates a plain archive.
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
            // Spelled out so `password` smart-casts to CharArray for the encrypted
            // constructor (a null or empty password must NOT encrypt anything).
            val out = if (password != null && password.isNotEmpty()) SevenZOutputFile(tmp, password)
            else SevenZOutputFile(tmp)
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
            // The LZMA2 encoder needs ~10.5× the dictionary (up to ≈1.3 GB at ULTRA's
            // 128 MiB dictionary); a too-big dictionary on a heap-limited device must
            // degrade to an honest failure, not a crash.
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
