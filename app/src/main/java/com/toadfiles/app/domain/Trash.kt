package com.toadfiles.app.domain

import java.io.File

/** One item sitting in the trash, together with where it came from. */
data class TrashEntry(
    val file: File,
    val originPath: String,
    val trashedAt: Long,
)

/**
 * Local trash. Deleted items are moved into a hidden ".toadfiles-trash" folder on the same
 * volume (an instant rename — never a copy) with a small sidecar ".tfmeta" file that
 * remembers the original path. Items older than [RETENTION_DAYS] days are purged
 * automatically. Plain file IO only — pure JVM, unit-testable.
 */
object Trash {

    const val DIR_NAME = ".toadfiles-trash"
    const val RETENTION_DAYS = 30
    private const val META_SUFFIX = ".tfmeta"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun dirFor(volumeRoot: File): File = File(volumeRoot, DIR_NAME)

    /** The volume root hosting [path]: the longest known root that contains it. */
    fun volumeRootOf(path: File, volumeRoots: List<File>): File {
        val p = normalize(path)
        return volumeRoots
            .filter { r -> val rp = normalize(r); p == rp || p.startsWith("$rp/") }
            .maxByOrNull { normalize(it).length }
            ?: volumeRoots.firstOrNull()
            ?: File("/")
    }

    /** Moves [files] into the trash. Returns the trashed copies; failures are omitted. */
    fun trash(files: List<File>, volumeRoots: List<File>): List<File> {
        val done = mutableListOf<File>()
        for (src in files) {
            try {
                if (!src.exists()) continue
                val dir = dirFor(volumeRootOf(src, volumeRoots))
                if (!dir.isDirectory && !dir.mkdirs()) continue
                val target = File(dir, uniqueTrashName(dir, src.name))
                if (!src.renameTo(target)) continue
                metaFor(target).writeText("origin=${src.absolutePath}\ntrashedAt=${System.currentTimeMillis()}")
                done += target
            } catch (_: Exception) {
                // Item stays where it is; the caller reports it as not deleted.
            }
        }
        return done
    }

    /** Restores a trashed item to its original location. Returns the restored file or null. */
    fun restore(trashed: File): File? {
        val meta = readMeta(trashed) ?: return null
        val parent = File(meta.second.substringBeforeLast('/', ""))
        val target = when {
            parent.isDirectory -> parent
            parent.mkdirs() -> parent
            else -> trashed.parentFile?.parentFile ?: return null // fall back to the volume root
        }
        val name = PathSafety.uniqueName(target, meta.second.substringAfterLast('/'))
        val dest = File(target, name)
        return if (trashed.renameTo(dest)) {
            metaFor(trashed).delete()
            dest
        } else {
            null
        }
    }

    fun deleteForever(trashed: File): Boolean {
        metaFor(trashed).delete()
        return FileOps.deleteRecursively(trashed)
    }

    /** All trashed items across [volumeRoots], newest first. */
    fun entries(volumeRoots: List<File>): List<TrashEntry> {
        val out = mutableListOf<TrashEntry>()
        for (root in volumeRoots) {
            val children = dirFor(root).listFiles() ?: continue
            for (f in children) {
                if (f.name.endsWith(META_SUFFIX)) continue
                val meta = readMeta(f)
                out += TrashEntry(
                    file = f,
                    originPath = meta?.second ?: "",
                    trashedAt = meta?.first ?: f.lastModified(),
                )
            }
        }
        return out.sortedByDescending { it.trashedAt }
    }

    /** Deletes items trashed more than [maxAgeDays] ago. Returns how many were purged. */
    fun purge(volumeRoots: List<File>, maxAgeDays: Int = RETENTION_DAYS): Int {
        val cutoff = System.currentTimeMillis() - maxAgeDays * DAY_MS
        return entries(volumeRoots).count { it.trashedAt < cutoff && deleteForever(it.file) }
    }

    /** Permanently removes every trashed item. Returns how many were removed. */
    fun empty(volumeRoots: List<File>): Int = entries(volumeRoots).count { deleteForever(it.file) }

    // ---------- internal ----------

    private fun normalize(f: File): String =
        f.absolutePath.let { if (it.length > 1) it.trimEnd('/') else it }

    private fun metaFor(trashed: File) = File(trashed.parentFile, trashed.name + META_SUFFIX)

    /** Timestamp-prefixed so several files with the same name can coexist in the trash. */
    private fun uniqueTrashName(dir: File, name: String): String {
        val stamp = System.currentTimeMillis()
        if (!File(dir, "${stamp}_$name").exists()) return "${stamp}_$name"
        var i = 1
        while (File(dir, "${stamp}_${i}_$name").exists()) i++
        return "${stamp}_${i}_$name"
    }

    /** Returns (trashedAt, originPath), or null when the sidecar is missing or corrupt. */
    private fun readMeta(trashed: File): Pair<Long, String>? = try {
        var at = -1L
        var origin = ""
        for (line in metaFor(trashed).readLines()) {
            if (line.startsWith("trashedAt=")) at = line.removePrefix("trashedAt=").toLongOrNull() ?: at
            else if (line.startsWith("origin=")) origin = line.removePrefix("origin=")
        }
        if (at > 0 && origin.isNotEmpty()) at to origin else null
    } catch (_: Exception) {
        null
    }
}
