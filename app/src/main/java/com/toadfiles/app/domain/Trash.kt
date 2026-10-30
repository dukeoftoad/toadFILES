package com.toadfiles.app.domain

import java.io.File

/** One item sitting in the trash, together with where it came from. */
data class TrashEntry(
    val file: File,
    val originPath: String,
    val trashedAt: Long,
)

/**
 * Structured per-item outcome of moving items into the trash, so callers can report
 * honestly instead of inferring success from the absence of a file:
 * - [moved]: payload is in the trash and its metadata was committed (restorable);
 * - [rolledBack]: metadata could not be committed, the payload was moved back to its
 *   original path — the user's data is exactly where it was;
 * - [failed]: the item could not be moved into the trash at all (it also stays put).
 */
data class TrashResult(
    val moved: List<File> = emptyList(),
    val rolledBack: List<File> = emptyList(),
    val failed: List<File> = emptyList(),
) {
    val failureCount: Int get() = rolledBack.size + failed.size
}

/**
 * Local trash. Deleted items are moved into a hidden ".toadfiles-trash" folder on the same
 * volume (just a rename, never a copy) with a small ".tfmeta" sidecar in a dedicated
 * ".meta" subfolder that remembers the original path. Sidecars are committed
 * transactionally: the payload is only counted as trashed once its metadata is in place,
 * and a metadata failure rolls the payload back to where it came from. Permanent deletion
 * removes the payload first and the sidecar only after that succeeded, so an item is
 * never left in the trash without its restore metadata. Items older than
 * [RETENTION_DAYS] days are purged automatically. Plain file IO only, so the whole thing
 * is pure JVM and unit-testable.
 */
object Trash {

    const val DIR_NAME = ".toadfiles-trash"
    const val RETENTION_DAYS = 30

    /** Dedicated directory for ".tfmeta" sidecars, so a user file named "*.tfmeta" is never mistaken for metadata. */
    const val META_DIR_NAME = ".meta"
    private const val META_SUFFIX = ".tfmeta"
    private const val PENDING_SUFFIX = ".pending"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Clock seam for tests: trash names and timestamps derive from it. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    fun dirFor(volumeRoot: File): File = File(volumeRoot, DIR_NAME)

    private fun metaDirFor(trashDir: File): File = File(trashDir, META_DIR_NAME)

    /** The volume root hosting [path]: the longest known root that contains it. */
    fun volumeRootOf(path: File, volumeRoots: List<File>): File {
        val p = normalize(path)
        return volumeRoots
            .filter { r -> val rp = normalize(r); p == rp || p.startsWith("$rp/") }
            .maxByOrNull { normalize(it).length }
            ?: volumeRoots.firstOrNull()
            ?: File("/")
    }

    /** Moves [files] into the trash, reporting every item's outcome. */
    fun trash(files: List<File>, volumeRoots: List<File>): TrashResult {
        val moved = mutableListOf<File>()
        val rolledBack = mutableListOf<File>()
        val failed = mutableListOf<File>()
        for (src in files) {
            var target: File? = null
            try {
                if (!src.exists()) {
                    failed += src
                    continue
                }
                val dir = dirFor(volumeRootOf(src, volumeRoots))
                val metaDir = metaDirFor(dir)
                if (!dir.isDirectory && !dir.mkdirs()) {
                    failed += src
                    continue
                }
                if (!metaDir.isDirectory && !metaDir.mkdirs()) {
                    failed += src
                    continue
                }
                target = File(dir, uniqueTrashName(dir, src.name))
                val sidecar = File(metaDir, target.name + META_SUFFIX)
                // 1. Prepare the metadata as a pending temp file, before anything moves.
                val pending = File(metaDir, target.name + META_SUFFIX + PENDING_SUFFIX)
                pending.writeText("origin=${src.absolutePath}\ntrashedAt=${clock()}")
                // 2. Move the payload into the trash (same volume, just a rename).
                if (!src.renameTo(target)) {
                    pending.delete()
                    failed += src
                    continue
                }
                // 3. Commit the metadata atomically; roll the payload back on failure.
                if (!pending.renameTo(sidecar)) {
                    pending.delete()
                    if (target.renameTo(src)) rolledBack += src else failed += src
                    continue
                }
                moved += target
            } catch (_: Exception) {
                // Anything thrown (e.g. the pending write) must leave the payload where
                // it was: move it back out of the trash if it already got there.
                val t = target
                if (t != null && t.exists() && !src.exists()) t.renameTo(src)
                if (src.exists()) rolledBack += src else failed += src
            }
        }
        return TrashResult(moved, rolledBack, failed)
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
            // The payload is safely back. If this cleanup fails the leftover sidecar is
            // pruned by the next entries() pass; a failure here never loses data.
            metaFor(trashed).delete()
            dest
        } else {
            null // payload still in the trash with its metadata intact — a retry is safe
        }
    }

    /**
     * Permanently removes a trashed item. The payload is deleted first and its sidecar
     * only after that succeeded, so a failed deletion leaves the item listed (and
     * restorable where possible) instead of stranded without metadata.
     */
    fun deleteForever(trashed: File): Boolean {
        val ok = FileOps.deleteRecursively(trashed)
        if (ok) metaFor(trashed).delete()
        return ok
    }

    /** All trashed items across [volumeRoots], newest first. */
    fun entries(volumeRoots: List<File>): List<TrashEntry> {
        val out = mutableListOf<TrashEntry>()
        for (root in volumeRoots) {
            val dir = dirFor(root)
            val children = dir.listFiles() ?: continue
            // Migrate pre-rename sidecars first so every payload below is read with its
            // real metadata, no matter in which order the children were listed.
            val migrated = mutableSetOf<File>()
            for (f in children) {
                if (f.name != META_DIR_NAME && f.name.endsWith(META_SUFFIX) && migrateLegacySidecar(dir, f)) {
                    migrated += f
                }
            }
            val payloads = mutableListOf<File>()
            for (f in children) {
                if (f.name == META_DIR_NAME || f in migrated) continue
                if (!f.exists()) continue // moved away between the two passes
                payloads += f
                val meta = readMeta(f)
                out += TrashEntry(
                    file = f,
                    originPath = meta?.second ?: "",
                    trashedAt = meta?.first ?: f.lastModified(),
                )
            }
            pruneOrphanMeta(dir, payloads)
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

    /** Sidecar location for a trashed payload: "<trash>/.meta/<payload>.tfmeta". */
    private fun metaFor(trashed: File): File {
        val metaDir = trashed.parentFile?.let { metaDirFor(it) }
        return if (metaDir != null) File(metaDir, trashed.name + META_SUFFIX)
        else File(trashed.parentFile, trashed.name + META_SUFFIX)
    }

    /** Timestamp-prefixed so several files with the same name can coexist in the trash. */
    private fun uniqueTrashName(dir: File, name: String): String {
        val stamp = clock()
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

    /**
     * Moves a pre-rename sidecar into the ".meta" directory. Returns true when [sidecar]
     * really was metadata for an existing payload and has been migrated; when the name
     * has no matching payload it may be a user's own "*.tfmeta" file, so it is kept and
     * listed as a payload — never deleted behind the user's back.
     */
    private fun migrateLegacySidecar(trashDir: File, sidecar: File): Boolean {
        val payload = File(trashDir, sidecar.name.removeSuffix(META_SUFFIX))
        if (!payload.exists()) return false
        val metaDir = metaDirFor(trashDir)
        if (!metaDir.isDirectory && !metaDir.mkdirs()) return false
        return try {
            val dest = File(metaDir, payload.name + META_SUFFIX)
            if (!sidecar.renameTo(dest)) {
                // The rename can refuse to replace an existing sidecar; copy the older
                // record over instead so the migration still lands.
                dest.writeText(sidecar.readText())
                sidecar.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Best-effort cleanup of sidecars whose payload is gone (crash leftovers, failed
     * restore cleanups, pending temp files). Metadata-only files never hold user data.
     */
    private fun pruneOrphanMeta(trashDir: File, payloads: List<File>) {
        val metaDir = metaDirFor(trashDir)
        val sidecars = metaDir.listFiles() ?: return
        val names = payloads.mapTo(mutableSetOf()) { it.name }
        for (m in sidecars) {
            val payloadName = m.name.removeSuffix(PENDING_SUFFIX).removeSuffix(META_SUFFIX)
            if (payloadName !in names) m.delete()
        }
    }
}
