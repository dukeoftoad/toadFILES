package com.toadfiles.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Safe ZIP creation and extraction.
 *
 * Extraction protections (per the project security requirements and audit P1.2 / P2.11 / P3.1):
 *  - ZIP-slip: entry names are validated AND final paths are canonical-checked.
 *  - Zip bombs: entry count, total bytes actually emitted and the expansion ratio are
 *    enforced from the bytes really read/written, never from header fields, so
 *    data-descriptor entries with unknown sizes cannot bypass the caps. Arithmetic is
 *    overflow-safe Long math.
 *  - Staged extraction: entries land in a fresh hidden staging directory next to the
 *    destination (each entry first in its own temp file, promoted only after its stream
 *    closes cleanly) and are published into the destination only when the archive
 *    finished without a global abort. Limit overruns, bomb ratios, unreadable streams
 *    and cancellation delete the staging tree and leave the destination untouched.
 *  - No accidental overwrite: existing files are skipped unless [replace] is set.
 *  - All-or-nothing replace publication (audit P2.2): with [replace], every existing
 *    destination item is renamed to a hidden sibling backup before it is replaced; any
 *    later item failure, or cancellation, removes everything already published and
 *    restores the backups, so the destination is never left half-updated. The result
 *    reports the rollback precisely (a rolled-back run reports no successes).
 *
 * Creation protections: the archive is written to a temporary sibling and renamed into
 * place only on success; a failing source aborts and deletes the incomplete archive
 * instead of publishing it; cancellation checkpoints run per buffer, per directory and
 * per published item.
 * Symbolic links are never followed (audit P1.2): links to plain files are stored by
 * content, links to folders and dangling links are skipped with a recorded reason, and
 * a canonical visited-set makes link cycles impossible to recurse into.
 */
object ZipSupport {

    data class Limits(
        val maxEntries: Int = 50_000,
        val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
        val maxRatio: Long = 200,
    )

    /** Slack added to the compressed-byte count when judging the expansion ratio. */
    private const val RATIO_OVERHEAD = 64L

    /** Sentinel for global abort conditions; its message is reported verbatim. */
    private class ArchiveAbort(message: String) : IOException(message)

    /** Internal control-flow signal: a replace-mode publication item failed and the run
     *  must roll back. The specific failure is already recorded in the failures list. */
    private class PublicationAbort : IOException("publication aborted")

    /** Input stream that counts every byte actually consumed from the archive. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0
            private set

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) count += s
            return s
        }
    }

    private class Counts(var moved: Int = 0, var skipped: Int = 0, var failed: Int = 0)

    suspend fun extract(
        zipFile: File,
        targetDir: File,
        replace: Boolean,
        limits: Limits = Limits(),
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
        val counts = Counts()

        try {
            // The chain order matters (audit P2.3): the 64 KiB buffer prefetches from the
            // file BELOW the counter, so `raw.count` advances only for bytes the ZIP layer
            // actually consumes — the per-entry ratio delta stays real even when a whole
            // small archive sits inside a single buffer.
            val raw = CountingInputStream(BufferedInputStream(FileInputStream(zipFile), BUFFER_SIZE))
            ZipInputStream(raw).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name ?: ""
                    try {
                        currentCoroutineContext().ensureActive()
                        entries++
                        if (entries > limits.maxEntries) {
                            throw ArchiveAbort("Aborted: more than ${limits.maxEntries} entries (possible zip bomb)")
                        }
                        if (!PathSafety.isSafeEntryName(name)) {
                            skip++; continue
                        }
                        val stagedFile = File(staging, name)
                        PathSafety.ensureInside(staging, stagedFile)
                        val finalFile = File(targetDir, name)
                        PathSafety.ensureInside(targetDir, finalFile)
                        if (entry.isDirectory) {
                            if (!stagedFile.exists()) stagedFile.mkdirs()
                            continue
                        }
                        if (finalFile.exists() && !replace) { skip++; continue }
                        stagedFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }

                        // Fail fast when the header declares more than the remaining budget;
                        // the actual-byte accounting below is the authority either way.
                        if (entry.size > 0 && entry.size > limits.maxTotalBytes - totalBytes) {
                            throw ArchiveAbort(
                                "Aborted: exceeds ${Format.size(limits.maxTotalBytes)} uncompressed (possible zip bomb)"
                            )
                        }

                        // Stream into a temp file; promote it inside staging only after the
                        // entry closed cleanly (incl. CRC verification), so no partial or
                        // corrupt entry file survives an error.
                        val partFile = File(stagedFile.parentFile, "${stagedFile.name}.part-${System.nanoTime()}")
                        val compressedAtStart = raw.count
                        try {
                            FileOutputStream(partFile).use { fos ->
                                val buf = ByteArray(BUFFER_SIZE)
                                var written = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = zis.read(buf)
                                    if (n == -1) break
                                    written += n
                                    totalBytes += n
                                    if (totalBytes > limits.maxTotalBytes) {
                                        throw ArchiveAbort(
                                            "Aborted: exceeds ${Format.size(limits.maxTotalBytes)} uncompressed (possible zip bomb)"
                                        )
                                    }
                                    // Ratio guard from bytes actually consumed: header sizes are
                                    // -1 for data-descriptor entries, so never trust them.
                                    if (limits.maxRatio > 0) {
                                        val compressed = raw.count - compressedAtStart + RATIO_OVERHEAD
                                        if (compressed in 1..(Long.MAX_VALUE / limits.maxRatio) &&
                                            written > compressed * limits.maxRatio
                                        ) {
                                            throw ArchiveAbort(
                                                "Aborted: suspicious expansion ratio in $name (possible zip bomb)"
                                            )
                                        }
                                    }
                                    fos.write(buf, 0, n)
                                }
                            }
                            zis.closeEntry() // verifies CRC + sizes; throws on corrupt entries
                            if (stagedFile.exists()) stagedFile.delete()
                            if (!partFile.renameTo(stagedFile)) {
                                throw IOException("could not stage $name")
                            }
                        } finally {
                            if (partFile.exists()) partFile.delete()
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        cancelled = true
                        break
                    } catch (ab: ArchiveAbort) {
                        abort = true
                        fail++
                        failures += ab.message ?: "aborted"
                        break
                    } catch (e: Exception) {
                        fail++
                        failures += "$name: ${e.message ?: "error"}"
                    } finally {
                        onProgress(OpProgress(entries, -1, name))
                    }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            cancelled = true
        } catch (ab: ArchiveAbort) {
            abort = true
            fail++
            failures += ab.message ?: "aborted"
        } catch (e: Exception) {
            // Stream-level death (truncated/corrupt archive): nothing after this point
            // can be trusted, so nothing gets published.
            abort = true
            fail++
            failures += "${zipFile.name}: ${e.message ?: "unreadable archive"}"
        }

        if (!cancelled && !abort) {
            if (!targetDir.isDirectory && !targetDir.mkdirs()) {
                fail++
                failures += "Cannot create ${targetDir.name}"
            } else {
                try {
                    publishStaging(staging, targetDir, replace, counts, failures)
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

    suspend fun zip(
        sources: List<File>,
        outFile: File,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = withContext(Dispatchers.IO) {
        PathSafety.rejectSelfTarget(sources, outFile.parentFile ?: File("."))
        val parent = outFile.parentFile ?: File(".")
        if (!parent.isDirectory && !parent.mkdirs()) {
            return@withContext OpResult(0, 0, 1, false, listOf("Cannot create ${parent.name}"))
        }
        val tmp = File(parent, ".${outFile.name}.part-${System.nanoTime()}")
        var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        var done = 0
        val total = sources.size
        val usedEntryNames = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val skipped = mutableListOf<String>()
        var topLevelSkipped = 0
        var completed = false
        try {
            ZipOutputStream(FileOutputStream(tmp).buffered(BUFFER_SIZE)).use { zos ->
                for (src in sources) {
                    try {
                        currentCoroutineContext().ensureActive()
                        onProgress(OpProgress(done, total, src.name))
                        val reason = unfollowableLinkReason(src)
                        if (reason != null) {
                            // The SOURCE ITSELF is a link we never follow: nothing of it
                            // enters the archive. Nested skips inside a real folder are
                            // recorded in `skipped` without failing the source.
                            skipped += "${src.name}: not added: $reason (links are not followed)"
                            topLevelSkipped++
                        } else {
                            addToZip(src, uniqueEntryName(usedEntryNames, src.name), zos, visited, skipped)
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        cancelled = true
                        break
                    } catch (e: Exception) {
                        // The stream may already hold a half-written entry: publishing it
                        // would hand out a broken ZIP, so abort and delete (audit P2.11).
                        fail++
                        failures += "${src.name}: ${e.message ?: "error"}"
                        break
                    }
                    done++
                }
            }
            completed = !cancelled && fail == 0
        } catch (e: Exception) {
            if (!cancelled) {
                fail++
                failures += "${outFile.name}: ${e.message ?: "write failed"}"
            }
        }
        if (completed) {
            try {
                Files.move(tmp.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                fail++
                failures += "${outFile.name}: ${e.message ?: "publish failed"}"
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

    // ---------- internal helpers ----------

    private const val BUFFER_SIZE = 64 * 1024

    /** Hidden backup name prefix for replace-mode publication; mirrors FileOps.
     *  Dot-prefixed so normal listings skip it. */
    private const val BACKUP_PREFIX = ".tf-old-"

    /**
     * Moves the validated staging tree into [targetDir], file by file, honouring [replace].
     * Directories are merged; files are renamed onto their final paths (same volume, so
     * each move is a fast atomic rename).
     *
     * Publication never leaves the destination half-updated (audit P2.2): with [replace]
     * set, an existing destination item is first renamed to a hidden sibling backup, and
     * any later item failure — or cancellation — aborts the run and rolls everything
     * back: published items are removed, folders this run created are deleted and every
     * backup is renamed back, so the destination ends up exactly as it was. The outcome
     * is reported precisely through [counts] and [failures] (a rolled-back run reports
     * no successes). On success the backups are discarded. Cancellation checkpoints run
     * per staged folder and per published item (audit P2.4).
     */
    private suspend fun publishStaging(
        staging: File,
        targetDir: File,
        replace: Boolean,
        counts: Counts,
        failures: MutableList<String>,
    ) {
        val backups = mutableListOf<Pair<File, File>>() // backup -> original it stands in for
        val published = mutableListOf<File>()           // items moved in without a backup
        val createdDirs = mutableListOf<File>()         // folders this run brought into existence
        var completed = false
        try {
            val stagingPath = staging.canonicalPath
            val stack = ArrayDeque<File>()
            stack.addLast(staging)
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive() // audit P2.4: checkpoint per staged folder
                val dir = stack.removeLast()
                val children = dir.listFiles()
                if (children == null) {
                    counts.failed++
                    failures += "Cannot read staged folder ${dir.path.removePrefix(stagingPath)}"
                    continue
                }
                for (child in children) {
                    // audit P2.2 / P2.4: a checkpoint per published item, so cancellation
                    // is honoured between moves and is rolled back like any other failure.
                    currentCoroutineContext().ensureActive()
                    val rel = child.canonicalPath.removePrefix(stagingPath + File.separator)
                    if (rel.isEmpty()) continue // defensive: never map staging itself onto the target
                    val dest = File(targetDir, rel)
                    PathSafety.ensureInside(targetDir, dest)
                    if (child.isDirectory) {
                        if (dest.exists() && !dest.isDirectory) {
                            if (!replace) {
                                counts.skipped++
                                failures += "Kept file ${dest.name}; archive has a folder with that name"
                                continue
                            }
                            val backup = backupItem(dest)
                            if (backup == null) {
                                counts.failed++
                                failures += "Cannot replace ${dest.name} with a folder"
                                throw PublicationAbort()
                            }
                            backups += backup to dest
                            if (!mkdirsTracked(dest, targetDir, createdDirs)) {
                                counts.failed++
                                failures += "Cannot create ${dest.name}"
                                throw PublicationAbort()
                            }
                        } else if (!dest.isDirectory && !mkdirsTracked(dest, targetDir, createdDirs)) {
                            counts.failed++
                            failures += "Cannot create ${dest.name}"
                            if (replace) throw PublicationAbort()
                            continue
                        }
                        stack.addLast(child)
                    } else {
                        var backedUp = false
                        if (dest.exists()) {
                            if (!replace) {
                                counts.skipped++
                                continue
                            }
                            val backup = backupItem(dest)
                            if (backup == null) {
                                counts.failed++
                                failures += "Could not back up $rel for replacement"
                                throw PublicationAbort()
                            }
                            backups += backup to dest
                            backedUp = true
                        }
                        val parent = dest.parentFile
                        if (parent != null && !parent.isDirectory &&
                            !mkdirsTracked(parent, targetDir, createdDirs)
                        ) {
                            counts.failed++
                            failures += "$rel: cannot create its folder"
                            if (replace) throw PublicationAbort()
                            continue
                        }
                        try {
                            Files.move(child.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            counts.moved++
                            if (!backedUp) published += dest
                        } catch (e: Exception) {
                            counts.failed++
                            failures += "$rel: ${e.message ?: "publish failed"}"
                            if (replace) throw PublicationAbort()
                        }
                    }
                }
            }
            completed = true
        } catch (ce: kotlinx.coroutines.CancellationException) {
            rollbackPublication(targetDir, "cancelled", backups, published, createdDirs, counts, failures)
            throw ce
        } catch (_: PublicationAbort) {
            rollbackPublication(targetDir, "failed", backups, published, createdDirs, counts, failures)
        } catch (e: Exception) {
            counts.failed++
            failures += "${targetDir.name}: ${e.message ?: "publish failed"}"
            rollbackPublication(targetDir, "failed", backups, published, createdDirs, counts, failures)
        } finally {
            if (completed) {
                for ((backup, _) in backups) {
                    if (backup.exists() && !FileOps.deleteRecursively(backup)) {
                        failures += "Could not discard the backup ${backup.name}"
                    }
                }
            }
        }
    }

    /** Renames [item] aside to a hidden sibling backup in its own parent (an atomic
     *  same-directory rename), or returns null when the rename failed. */
    private fun backupItem(item: File): File? {
        val parent = item.parentFile ?: return null
        val backup = File(parent, PathSafety.uniqueName(parent, BACKUP_PREFIX + item.name))
        return if (item.renameTo(backup)) backup else null
    }

    /** [File.mkdirs] that remembers every level it had to create in [createdDirs] (top
     *  down), so a rollback can remove them again deepest first. The walk stops at
     *  [stopAt], which is known to exist. Returns false when [dir] could not be made. */
    private fun mkdirsTracked(dir: File, stopAt: File, createdDirs: MutableList<File>): Boolean {
        val missing = ArrayDeque<File>()
        var d: File? = dir
        while (d != null && d != stopAt && !d.isDirectory) {
            missing.addFirst(d)
            d = d.parentFile
        }
        if (missing.isEmpty()) return dir.isDirectory
        if (!missing.first().mkdirs()) return false
        createdDirs += missing
        return true
    }

    /**
     * Undoes a partially applied publication: removes everything this run moved in,
     * deletes the folders it created (deepest first), then renames every backup back to
     * its original path. Best-effort and honest — anything that cannot be undone is
     * reported in [failures]. [counts.moved] is zeroed: nothing published survived.
     */
    private fun rollbackPublication(
        targetDir: File,
        reason: String,
        backups: List<Pair<File, File>>,
        published: List<File>,
        createdDirs: List<File>,
        counts: Counts,
        failures: MutableList<String>,
    ) {
        counts.moved = 0
        var removed = 0
        for (item in published.asReversed()) {
            if (FileOps.deleteRecursively(item)) removed++
            else failures += "Could not roll back ${item.name}"
        }
        for (dir in createdDirs.asReversed()) {
            if (dir.exists() && !FileOps.deleteRecursively(dir)) {
                failures += "Could not roll back folder ${dir.name}"
            }
        }
        var restored = 0
        for ((backup, original) in backups.asReversed()) {
            if (original.exists() && !FileOps.deleteRecursively(original)) {
                failures += "Could not roll back ${original.name}; previous version kept as ${backup.name}"
                continue
            }
            if (backup.renameTo(original)) restored++
            else failures += "Could not restore the previous ${original.name}; old copy kept as ${backup.name}"
        }
        failures += "Publication into ${targetDir.name} $reason and was rolled back: " +
            "$removed published item(s) removed, $restored replaced item(s) restored"
    }

    /** Same "(1)"-style suffixing as [PathSafety.uniqueName], but purely name-based for entry names. */
    private fun uniqueEntryName(used: MutableSet<String>, name: String): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val next = "$base ($i)$ext"
            if (used.add(next)) return next
            i++
        }
    }

    /**
     * Adds [file] to the archive. Symbolic links are never followed (audit P1.2):
     * links to plain files are stored by content; links to folders and dangling links
     * are skipped with a recorded reason instead of expanding a tree outside the
     * selection. [visited] holds canonical paths already added, so a link cycle cannot
     * recurse forever. Cancellation is checked per file AND per directory.
     */
    private suspend fun addToZip(
        file: File,
        entryName: String,
        zos: ZipOutputStream,
        visited: MutableSet<String>,
        skipped: MutableList<String>,
    ) {
        currentCoroutineContext().ensureActive()
        val canonical = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
        if (!visited.add(canonical)) return // link cycle / already added: never recurse twice
        val reason = unfollowableLinkReason(file)
        if (reason != null) {
            skipped += "$entryName: not added: $reason (links are not followed)"
            return
        }
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                zos.putNextEntry(ZipEntry("$entryName/"))
                zos.closeEntry()
            } else {
                for (c in children) addToZip(c, "$entryName/${c.name}", zos, visited, skipped)
            }
            return
        }
        writeFileEntry(file, entryName, zos)
    }

    /** Non-null when [file] is a symbolic link this policy refuses to follow: a link to
     *  a folder, or a dangling link. Links to plain files return null (stored by content). */
    private fun unfollowableLinkReason(file: File): String? {
        if (!java.nio.file.Files.isSymbolicLink(file.toPath())) return null
        val target = try {
            java.nio.file.Files.readAttributes(
                file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            )
        } catch (_: Exception) {
            null
        }
        return when {
            target != null && target.isRegularFile -> null
            target == null -> "broken symbolic link"
            else -> "symbolic link to a folder"
        }
    }

    /** Opens the source before the archive entry: an unreadable source then never
     *  leaves a half-open entry behind in the stream. */
    private suspend fun writeFileEntry(file: File, entryName: String, zos: ZipOutputStream) {
        FileInputStream(file).use { fis ->
            val entry = ZipEntry(entryName)
            entry.time = file.lastModified()
            zos.putNextEntry(entry)
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = fis.read(buf)
                if (n == -1) break
                zos.write(buf, 0, n)
            }
            zos.closeEntry()
        }
    }
}
