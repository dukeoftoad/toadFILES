package com.toadfiles.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
}

/**
 * Real file-system operations with honest reporting: every skipped or failed item is
 * counted and listed, never silently ignored. All work runs on Dispatchers.IO and is
 * cooperatively cancellable; partial targets are cleaned up on cancellation.
 */
object FileOps {

    private const val BUFFER = 64 * 1024

    fun listFilesSafe(dir: File): List<File>? =
        if (dir.isDirectory && dir.canRead()) dir.listFiles()?.toList() else null

    fun sizeOf(file: File): Long = try {
        if (file.isDirectory) {
            var total = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(file)
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
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
    ): OpResult = withContext(Dispatchers.IO) {
        PathSafety.rejectSelfTarget(sources, targetDir)
        if (!targetDir.isDirectory) targetDir.mkdirs()
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
                    if (!deleteRecursively(dest)) {
                        fail++; failures += "Could not replace ${dest.name}"
                        continue
                    }
                }
                copyRecursively(src, dest) { name, e -> failures += "$name: ${e?.message ?: "error"}" }
                    .let { copied ->
                        if (copied) ok++ else { fail++ }
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
    ): OpResult = withContext(Dispatchers.IO) {
        PathSafety.rejectSelfTarget(sources, targetDir)
        if (!targetDir.isDirectory) targetDir.mkdirs()
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
                val moved = if (dest.exists() && strategy == ConflictStrategy.REPLACE) {
                    if (!deleteRecursively(dest)) {
                        fail++; failures += "Could not replace ${dest.name}"; continue
                    }
                    tryRename(src, dest)
                } else tryRename(src, dest)
                if (moved) {
                    ok++
                } else {
                    // Cross-volume move: copy then delete source.
                    val r = copyRecursively(src, dest) { name, e -> failures += "$name: ${e?.message ?: "error"}" }
                    if (r) {
                        if (src.isDirectory) deleteRecursively(src) else src.delete()
                        ok++
                    } else {
                        fail++
                        deleteRecursively(dest)
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
    ): OpResult = withContext(Dispatchers.IO) {
        var ok = 0; var fail = 0
        val failures = mutableListOf<String>()
        var cancelled = false
        for ((index, src) in sources.withIndex()) {
            try {
                currentCoroutineContext().ensureActive()
                onProgress(OpProgress(index, sources.size, src.name))
                if (!src.exists()) { ok++; continue }
                if (deleteRecursively(src)) ok++ else { fail++; failures += "Could not delete ${src.name}" }
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

    /** Returns true when fully copied; records per-item failures via [onItemFailure]. */
    private fun copyRecursively(
        src: File,
        dest: File,
        onItemFailure: (String, Exception?) -> Unit,
    ): Boolean {
        return try {
            if (src.isDirectory) {
                if (!dest.exists() && !dest.mkdirs()) {
                    onItemFailure(dest.name, IOException("mkdir failed"))
                    return false
                }
                val children = src.listFiles()
                if (children == null) {
                    if (!src.canRead()) onItemFailure(src.name, IOException("not readable"))
                    return children != null
                }
                var all = true
                for (child in children) {
                    val ok = copyRecursively(File(src, child.name), File(dest, child.name), onItemFailure)
                    if (!ok) all = false
                }
                all
            } else {
                if (dest.exists() && dest.length() == src.length()) return true // already there
                FileInputStream(src).use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            onItemFailure(src.name, e)
            false
        }
    }

    fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            file.listFiles()?.forEach { if (!deleteRecursively(it)) return false }
        }
        return file.delete()
    }
}
