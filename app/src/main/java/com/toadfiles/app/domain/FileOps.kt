package com.toadfiles.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

data class OpProgress(val done: Int, val total: Int, val currentItem: String)

data class OpResult(
    val succeeded: Int,
    val skipped: Int,
    val failed: Int,
    val cancelled: Boolean = false,
    val failures: List<String> = emptyList(),
) {
    val summary: String
        get() = buildString {
            append(succeeded).append(" done")
            if (skipped > 0) append(", ").append(skipped).append(" skipped")
            if (failed > 0) append(", ").append(failed).append(" failed")
            if (cancelled) append(" (cancelled)")
        }

    /**
     * True only when the operation completed fully: nothing failed, nothing was
     * cancelled and at least one item succeeded. This is the gate the v1.7.3
     * delete-after-compress / delete-after-extract settings must honor — deleting
     * sources after a partial failure or an abort would throw away data the user
     * still needs (the archive/result is incomplete, so the sources are the only
     * complete copy).
     */
    val fullySucceeded: Boolean
        get() = !cancelled && failed == 0 && succeeded > 0
}

/**
 * Real file-system operations with honest reporting: every skipped or failed item is
 * counted and listed, never silently ignored. All work runs on Dispatchers.IO and is
 * cooperatively cancellable; partial targets are cleaned up on cancellation.
 *
 * Data-safety rules enforced here:
 * - Symbolic links are never followed during traversal: deleting a link unlinks the
 *   link itself, and copies/sizes never expand a linked folder (link cycles terminate).
 * - REPLACE never destroys the old destination until the replacement is complete
 *   (stage -> backup -> swap, with the backup restored on any failure).
 * - Cross-volume moves stage a full copy under a hidden name and only then publish it.
 */
object FileOps {

    private const val BUFFER = 64 * 1024

    /** Hidden staging/backup name prefixes; dot-prefixed so normal listings skip them. */
    private const val STAGE_PREFIX = ".tf-stage-"
    private const val BACKUP_PREFIX = ".tf-old-"

    fun listFilesSafe(dir: File): List<File>? =
        if (dir.isDirectory && dir.canRead()) dir.listFiles()?.toList() else null

    fun sizeOf(file: File): Long = try {
        if (isSymlink(file)) {
            noFollowSize(file)
        } else if (file.isDirectory) {
            var total = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(file)
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
                if (isSymlink(f)) {
                    total += noFollowSize(f)
                    continue
                }
                val children = if (f.isDirectory) f.listFiles() else null
                if (children != null) children.forEach { stack.addLast(it) }
                else if (f.isFile) total += f.length()
            }
            total
        } else file.length()
    } catch (_: Exception) {
        0L
    }

    suspend fun copy(
        sources: List<File>,
        targetDir: File,
        strategy: ConflictStrategy,
        onProgress: (OpProgress) -> Unit = {},
        checkCancelled: (() -> Unit)? = null,
    ): OpResult = withContext(Dispatchers.IO) {
        PathSafety.rejectSelfTarget(sources, targetDir)
        if (!targetDir.isDirectory) targetDir.mkdirs()
        val ct = currentCoroutineContext()
        val cancelCheck: () -> Unit = checkCancelled ?: { ct.ensureActive() }
        var ok = 0; var skip = 0; var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        for ((index, src) in sources.withIndex()) {
            try {
                currentCoroutineContext().ensureActive()
                onProgress(OpProgress(index, sources.size, src.name))
                if (!src.exists()) {
                    skip++; continue
                }
                val destName = if (File(targetDir, src.name).exists() && strategy == ConflictStrategy.RENAME) {
                    PathSafety.uniqueName(targetDir, src.name)
                } else src.name
                val dest = File(targetDir, destName)
                if (dest.exists() && strategy == ConflictStrategy.SKIP) {
                    skip++; continue
                }
                if (dest.exists() && strategy == ConflictStrategy.REPLACE) {
                    val error = swapReplace(src, dest) { staging ->
                        copyRecursively(src, staging, cancelCheck) { name, e -> failures += "$name: ${e?.message ?: "error"}" }
                    }
                    if (error == null) ok++ else { fail++; failures += error }
                    continue
                }
                try {
                    copyRecursively(src, dest, cancelCheck) { name, e -> failures += "$name: ${e?.message ?: "error"}" }
                        .let { copied ->
                            if (copied) ok++ else { fail++ }
                        }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // The destination path is always freshly created on this route (REPLACE
                    // goes through swapReplace), so the half-written tree is ours to remove.
                    deleteRecursively(dest)
                    throw ce
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                cancelled = true
                break
            } catch (e: Exception) {
                fail++
                failures += "${src.name}: ${e.message ?: "error"}"
            }
        }
        onProgress(OpProgress(sources.size, sources.size, ""))
        OpResult(ok, skip, fail, cancelled, failures)
    }

    suspend fun move(
        sources: List<File>,
        targetDir: File,
        strategy: ConflictStrategy,
        onProgress: (OpProgress) -> Unit = {},
        checkCancelled: (() -> Unit)? = null,
    ): OpResult = withContext(Dispatchers.IO) {
        PathSafety.rejectSelfTarget(sources, targetDir)
        if (!targetDir.isDirectory) targetDir.mkdirs()
        val ct = currentCoroutineContext()
        val cancelCheck: () -> Unit = checkCancelled ?: { ct.ensureActive() }
        var ok = 0; var skip = 0; var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        for ((index, src) in sources.withIndex()) {
            try {
                currentCoroutineContext().ensureActive()
                onProgress(OpProgress(index, sources.size, src.name))
                if (!src.exists()) { skip++; continue }
                val destName = if (File(targetDir, src.name).exists() && strategy == ConflictStrategy.RENAME) {
                    PathSafety.uniqueName(targetDir, src.name)
                } else src.name
                val dest = File(targetDir, destName)
                if (dest.exists() && strategy == ConflictStrategy.SKIP) { skip++; continue }
                if (dest.exists() && strategy == ConflictStrategy.REPLACE) {
                    // Move the old destination aside first; it stays recoverable until
                    // the replacement is fully in place. (The source is never renamed
                    // into a staging slot here: a failed swap would have to un-do it.)
                    val backup = File(targetDir, PathSafety.uniqueName(targetDir, BACKUP_PREFIX + dest.name))
                    if (!dest.renameTo(backup)) {
                        fail++; failures += "Could not replace ${dest.name}"; continue
                    }
                    var replaced = false
                    try {
                        // The destination path is free now, so a same-volume rename
                        // usually finishes the job in one atomic step.
                        if (tryRename(src, dest)) {
                            replaced = true
                        } else {
                            // Cross-volume: stage a full copy under a hidden name and
                            // only then publish it under the final name.
                            val staging = File(targetDir, PathSafety.uniqueName(targetDir, STAGE_PREFIX + src.name))
                            try {
                                val copied = copyRecursively(src, staging, cancelCheck) { name, e -> failures += "$name: ${e?.message ?: "error"}" }
                                if (copied && staging.renameTo(dest)) replaced = true else fail++
                            } finally {
                                if (staging.exists() && !replaced) deleteRecursively(staging)
                            }
                        }
                        if (replaced) {
                            // Source is already gone when the rename moved it; a failed
                            // cleanup still counts as a failure — the copy stays either way.
                            if (deleteRecursively(src, cancelCheck)) ok++ else {
                                fail++
                                failures += "Copied ${src.name} to the destination, but the original could not be deleted"
                            }
                        }
                    } finally {
                        if (replaced) {
                            if (backup.exists()) deleteRecursively(backup)
                        } else if (!backup.renameTo(dest)) {
                            failures += "Could not restore the previous ${dest.name}; old copy kept as ${backup.name}"
                        }
                    }
                    continue
                }
                val moved = tryRename(src, dest)
                if (moved) {
                    ok++
                } else {
                    // Cross-volume move: stage a full copy under a hidden name and only
                    // then publish it, so the destination path never holds a partial tree.
                    val staging = File(targetDir, PathSafety.uniqueName(targetDir, STAGE_PREFIX + src.name))
                    var published = false
                    try {
                        val copied = copyRecursively(src, staging, cancelCheck) { name, e -> failures += "$name: ${e?.message ?: "error"}" }
                        if (copied && staging.renameTo(dest)) {
                            published = true
                            // The destination is a complete copy now; if the original cannot
                            // be removed the move is only partial and must not count as done.
                            if (deleteRecursively(src, cancelCheck)) ok++ else {
                                fail++
                                failures += "Copied ${src.name} to the destination, but the original could not be deleted"
                            }
                        } else {
                            fail++
                        }
                    } finally {
                        if (!published && staging.exists()) deleteRecursively(staging)
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                cancelled = true
                break
            } catch (e: Exception) {
                fail++
                failures += "${src.name}: ${e.message ?: "error"}"
            }
        }
        onProgress(OpProgress(sources.size, sources.size, ""))
        OpResult(ok, skip, fail, cancelled, failures)
    }

    suspend fun delete(
        sources: List<File>,
        onProgress: (OpProgress) -> Unit = {},
        checkCancelled: (() -> Unit)? = null,
    ): OpResult = withContext(Dispatchers.IO) {
        val ct = currentCoroutineContext()
        val cancelCheck: () -> Unit = checkCancelled ?: { ct.ensureActive() }
        var ok = 0; var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        for ((index, src) in sources.withIndex()) {
            try {
                currentCoroutineContext().ensureActive()
                onProgress(OpProgress(index, sources.size, src.name))
                // A dangling symlink has exists() == false but still needs unlinking.
                if (!src.exists() && !isSymlink(src)) { ok++; continue }
                if (deleteRecursively(src, cancelCheck)) ok++ else { fail++; failures += "Could not delete ${src.name}" }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                cancelled = true
                break
            } catch (e: Exception) {
                fail++
                failures += "${src.name}: ${e.message ?: "error"}"
            }
        }
        onProgress(OpProgress(sources.size, sources.size, ""))
        OpResult(ok, 0, fail, cancelled, failures)
    }

    suspend fun rename(source: File, newName: String): File? = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) return@withContext null
        val dest = File(source.parentFile, clean)
        if (dest.exists() || !source.renameTo(dest)) null else dest
    }

    // ---------- internal helpers ----------

    /** True when [file] is a symbolic link, checked without following it. */
    private fun isSymlink(file: File): Boolean =
        try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Exception) {
            false
        }

    /** The size of the link itself, never of the thing it points at. */
    private fun noFollowSize(file: File): Long = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
    } catch (_: Exception) {
        0L
    }

    /**
     * Replaces an existing [dest] with a fresh copy of [src] while keeping the old
     * destination recoverable until the replacement is fully in place:
     * 1. [stage] materialises the new content under a hidden staging name (the old
     *    destination stays untouched while this runs);
     * 2. the old destination is renamed to a hidden backup (same directory, cheap);
     * 3. the staged item is promoted onto [dest] with a rename in the same directory.
     * On any failure the backup is renamed back, so [dest] is never left missing or
     * half-written. Returns null on success or a failure message for the report.
     */
    private suspend fun swapReplace(
        src: File,
        dest: File,
        stage: (File) -> Boolean,
    ): String? {
        val dir = dest.parentFile ?: return "Could not replace ${dest.name}"
        val staging = File(dir, PathSafety.uniqueName(dir, STAGE_PREFIX + dest.name))
        var backup: File? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!stage(staging)) return "Could not replace ${dest.name}"
            currentCoroutineContext().ensureActive()
            backup = File(dir, PathSafety.uniqueName(dir, BACKUP_PREFIX + dest.name))
            if (!dest.renameTo(backup)) return "Could not replace ${dest.name}"
            if (!staging.renameTo(dest)) {
                if (!backup.renameTo(dest)) {
                    // Could not restore either: keep the old content under its hidden name.
                    val kept = backup.name
                    backup = null
                    return "Could not replace ${dest.name}; old copy kept as $kept"
                }
                return "Could not replace ${dest.name}"
            }
            return null
        } finally {
            if (staging.exists()) deleteRecursively(staging)
            backup?.let { if (it.exists()) deleteRecursively(it) }
        }
    }

    private fun tryRename(src: File, dest: File): Boolean =
        try {
            if (src.isDirectory) {
                // renameTo moves the whole tree when target is on the same volume
                src.renameTo(dest)
            } else {
                Files.move(src.toPath(), dest.toPath()).let { true }
            }
        } catch (_: Exception) {
            false
        }

    /**
     * Returns true when fully copied; records per-item failures via [onItemFailure].
     * [checkCancelled] is polled before every directory and file visit and inside the
     * stream loop, so a cancelled operation stops promptly. It throws
     * [kotlinx.coroutines.CancellationException], which is rethrown — never reported as
     * a copy failure — so callers can clean partial outputs and flag the cancellation.
     */
    private fun copyRecursively(
        src: File,
        dest: File,
        checkCancelled: () -> Unit,
        onItemFailure: (String, Exception?) -> Unit,
    ): Boolean {
        return try {
            checkCancelled()
            if (isSymlink(src)) {
                // Policy: links to plain files are copied by content; links to folders
                // (and broken links) are never followed — they are recorded instead of
                // silently expanding the linked tree or looping on a link cycle.
                val target = try {
                    Files.readAttributes(src.toPath(), BasicFileAttributes::class.java)
                } catch (_: Exception) {
                    null
                }
                if (target != null && target.isRegularFile) {
                    copyFileContents(src, dest, checkCancelled)
                } else {
                    val why = if (target == null) "broken symbolic link" else "symbolic link to a folder"
                    onItemFailure(src.name, IOException("not copied: $why (links are not followed)"))
                    false
                }
            } else if (src.isDirectory) {
                if (!dest.exists() && !dest.mkdirs()) {
                    onItemFailure(dest.name, IOException("mkdir failed"))
                    return false
                }
                val children = src.listFiles()
                if (children == null) {
                    // listFiles() returns null for unreadable dirs (or IO races) — record it,
                    // otherwise the caller counts a failure with no cause attached.
                    onItemFailure(src.name, IOException("not readable"))
                    return false
                }
                var all = true
                for (child in children) {
                    val ok = copyRecursively(File(src, child.name), File(dest, child.name), checkCancelled, onItemFailure)
                    if (!ok) all = false
                }
                all
            } else {
                copyFileContents(src, dest, checkCancelled)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            onItemFailure(src.name, e)
            false
        }
    }

    private fun copyFileContents(src: File, dest: File, checkCancelled: () -> Unit): Boolean {
        if (dest.exists() && dest.length() == src.length()) return true // already there
        FileInputStream(src).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    checkCancelled() // job-state poll only: cheap, no syscalls per iteration
                    val n = input.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                }
            }
        }
        return true
    }

    /**
     * Deletes [file] and, for a real directory, everything inside it. Symbolic links —
     * including links to directories and dangling links — are unlinked themselves;
     * their targets are never entered, so deleting a link can never touch the linked
     * folder's contents and link cycles cannot occur.
     *
     * Never cancellable: cleanup paths (staging/backup removal) must always run to the
     * end. [delete] and [move] use the checkpoint-aware variant below instead.
     */
    fun deleteRecursively(file: File): Boolean = deleteRecursively(file, checkCancelled = null)

    /** As [deleteRecursively], but [checkCancelled] is polled before each visit; a
     *  cancelled walk stops promptly and rethrows
     *  [kotlinx.coroutines.CancellationException] instead of reporting `false`. */
    private fun deleteRecursively(file: File, checkCancelled: (() -> Unit)?): Boolean {
        return try {
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) {
                Files.delete(path)
                return true
            }
            if (!file.exists()) return true
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancelled?.invoke()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancelled?.invoke()
                    Files.delete(f) // also unlinks file symlinks without touching their targets
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    exc?.let { throw it }
                    checkCancelled?.invoke()
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }
}
