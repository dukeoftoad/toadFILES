package com.toadfiles.app.domain

import androidx.annotation.VisibleForTesting
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files

/** One item sitting in the trash, together with where it came from. */
data class TrashEntry(
    val file: File,
    val originPath: String,
    val trashedAt: Long,
    /** Payload size in bytes recorded at trash time (audit A20) — a single stat taken
     *  while the pending sidecar was written, so Storage's trash-usage card never has
     *  to walk the payload tree on every refresh. Files only: a directory payload
     *  keeps the walk-at-read fallback (-1), because walking inside the trash
     *  transaction would hold the trash lock for the whole traversal. Legacy sidecars
     *  predating the field also report -1 and fall back. */
    val sizeBytes: Long = -1,
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
 * and a metadata failure rolls the payload back to where it came from. A process death
 * between the payload move and the metadata commit leaves a valid ".pending" record —
 * the next listing promotes it to a committed sidecar, so the item stays restorable
 * (audit P1.3). All mutations and listings run under one lock, so orphan cleanup can
 * never race an in-flight transaction (audit P2.6). Permanent deletion removes the
 * payload first and the sidecar only after that succeeded. Items older than
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

    /** Byte budget for a payload name inside the trash: the sidecar adds ".tfmeta"
     *  (".pending" during the transaction) and the filesystem caps a name at 255
     *  bytes, so long original names are truncated to fit (D9). */
    private const val MAX_PAYLOAD_NAME_BYTES = 240

    /** Extra bytes reserved inside [MAX_PAYLOAD_NAME_BYTES] for the clash variant's "_<n>". */
    private const val CLASH_SUFFIX_RESERVE_BYTES = 8

    /** A "<stamp>_" group the trash prefixes to payload names (also "<n>_" on a clash). */
    private val STAMP_GROUP = Regex("^\\d+_")

    /** Serializes every mutation/listing: orphan cleanup must never race an in-flight
     *  transaction (audit P2.6), and crash recovery must never race a concurrent trash(). */
    private val lock = Any()

    /** Clock seam for tests: trash names and timestamps derive from it. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Test-only gate (audit P2.6 barrier test): invoked inside [trash] right after the
     * pending sidecar is written and before the payload is renamed — the exact window
     * in which a concurrent listing used to be able to prune the active transaction's
     * pending file. Always null in production, so this is a no-op there.
     */
    @VisibleForTesting
    internal var onAfterPendingWrite: (() -> Unit)? = null

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
    fun trash(files: List<File>, volumeRoots: List<File>): TrashResult = synchronized(lock) {
        val moved = mutableListOf<File>()
        val rolledBack = mutableListOf<File>()
        val failed = mutableListOf<File>()
        for (src in files) {
            var target: File? = null
            try {
                // exists() follows symlinks, so a dangling link looks "gone" — but its
                // path still names a restorable link (renameTo moves the link itself),
                // so only skip it when it neither exists nor is a symbolic link (D7).
                if (!src.exists() && !Files.isSymbolicLink(src.toPath())) {
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
                //    The origin is URL-encoded: a '\n' in a filename must not corrupt
                //    the line-based schema (readMeta decodes, with a raw-path fallback
                //    for legacy sidecars — see [decodeOrigin]).
                val pending = File(metaDir, target.name + META_SUFFIX + PENDING_SUFFIX)
                pending.writeText(
                    "origin=${URLEncoder.encode(src.absolutePath, "UTF-8")}\ntrashedAt=${clock()}\n" +
                        // Audit A20: record the payload size while the cheap stat is
                        // available, so refresh-time usage never walks the tree.
                        "size=${if (src.isFile) src.length() else -1L}",
                )
                // Test-only barrier (audit P2.6): lets a test park the transaction in
                // this window to prove a concurrent listing waits instead of pruning.
                onAfterPendingWrite?.invoke()
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
    fun restore(trashed: File): File? = synchronized(lock) {
        val meta = readMeta(trashed) ?: return null
        val parent = File(meta.originPath.substringBeforeLast('/', ""))
        val target = when {
            parent.isDirectory -> parent
            parent.mkdirs() -> parent
            else -> trashed.parentFile?.parentFile ?: return null // fall back to the volume root
        }
        val name = PathSafety.uniqueName(target, meta.originPath.substringAfterLast('/'))
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
    fun deleteForever(trashed: File): Boolean = synchronized(lock) {
        val ok = FileOps.deleteRecursively(trashed)
        if (ok) metaFor(trashed).delete()
        ok
    }

    /** All trashed items across [volumeRoots], newest first. */
    fun entries(volumeRoots: List<File>): List<TrashEntry> = synchronized(lock) {
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
            // Crash recovery (audit P1.3): a process death between the payload rename and
            // the sidecar commit leaves a valid ".pending" record next to an existing
            // payload. Promote it to a committed sidecar BEFORE reading metadata, so the
            // item is listed with its real origin and stays restorable. Pending records
            // without a payload are orphans and fall through to pruneOrphanMeta.
            recoverPendingMeta(dir)
            for (f in children) {
                if (f.name == META_DIR_NAME || f in migrated) continue
                // Same symlink rule as trash(): a dangling-link payload is still a
                // real payload and must stay listed and restorable (D7).
                if (!f.exists() && !Files.isSymbolicLink(f.toPath())) continue // moved away between the two passes
                payloads += f
                val meta = readMeta(f)
                out += TrashEntry(
                    file = f,
                    originPath = meta?.originPath ?: "",
                    // Without metadata the trashed-at time is UNKNOWN, not old: rename
                    // preserves mtime, so falling back to lastModified() would let the
                    // next purge destroy a sidecar-less payload despite the 30-day
                    // retention promise. 0 keeps it listed (it sorts last — the list
                    // is newest-first) and manually deletable, but never auto-purged (D10).
                    trashedAt = meta?.trashedAt ?: 0L,
                    sizeBytes = meta?.sizeBytes ?: -1L,
                )
            }
            pruneOrphanMeta(dir, payloads)
        }
        return out.sortedByDescending { it.trashedAt }
    }

    /** Deletes items trashed more than [maxAgeDays] ago. Returns how many were purged.
     *  Items with an unknown trashed-at time (0 — no or corrupt metadata) are never
     *  auto-purged; they stay listed and manually deletable (D10). */
    fun purge(volumeRoots: List<File>, maxAgeDays: Int = RETENTION_DAYS): Int {
        val cutoff = System.currentTimeMillis() - maxAgeDays * DAY_MS
        return entries(volumeRoots).count { it.trashedAt > 0 && it.trashedAt < cutoff && deleteForever(it.file) }
    }

    /** Permanently removes every trashed item. Returns how many were removed. */
    fun empty(volumeRoots: List<File>): Int = entries(volumeRoots).count { deleteForever(it.file) }

    /** The recorded origin path for [trashed], or null when its metadata is missing or
     *  corrupt. Lets callers report collision renames honestly (a restore that landed
     *  as "name (1).ext" — D4) without re-reading the sidecar format themselves. */
    fun originOf(trashed: File): String? = synchronized(lock) { readMeta(trashed)?.originPath }

    // ---------- internal ----------

    private fun normalize(f: File): String =
        f.absolutePath.let { if (it.length > 1) it.trimEnd('/') else it }

    /** Sidecar location for a trashed payload: "<trash>/.meta/<payload>.tfmeta". */
    private fun metaFor(trashed: File): File {
        val metaDir = trashed.parentFile?.let { metaDirFor(it) }
        return if (metaDir != null) File(metaDir, trashed.name + META_SUFFIX)
        else File(trashed.parentFile, trashed.name + META_SUFFIX)
    }

    /** Timestamp-prefixed so several files with the same name can coexist in the trash.
     *  Near the filesystem's 255-byte filename cap the name portion is truncated on a
     *  UTF-8 character boundary (D9): the display name comes from the sidecar origin,
     *  so the truncation is invisible to users — payload + ".tfmeta" (+ ".pending" and
     *  the clash variant's "_<n>") must all fit under the cap. */
    private fun uniqueTrashName(dir: File, name: String): String {
        val stamp = clock()
        val prefix = "$stamp" // plain ASCII digits, always survives truncation
        val nameBudget =
            MAX_PAYLOAD_NAME_BYTES - CLASH_SUFFIX_RESERVE_BYTES - prefix.toByteArray(Charsets.UTF_8).size - 1
        val trimmed = truncateUtf8Bytes(name, nameBudget)
        if (!File(dir, "${prefix}_$trimmed").exists()) return "${prefix}_$trimmed"
        var i = 1
        while (File(dir, "${prefix}_${i}_$trimmed").exists()) i++
        return "${prefix}_${i}_$trimmed"
    }

    /** Longest prefix of [s] that is at most [maxBytes] UTF-8 bytes, cut on a character
     *  boundary so a multi-byte sequence is never split. */
    private fun truncateUtf8Bytes(s: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end-- // don't split a sequence
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /** Sidecar contents: trashed-at time, origin path and the recorded payload size
     *  (-1 when unknown — directory payloads and legacy sidecars; audit A20). */
    private data class Meta(val trashedAt: Long, val originPath: String, val sizeBytes: Long)

    /** Returns the sidecar contents, or null when the sidecar is missing or corrupt. */
    private fun readMeta(trashed: File): Meta? {
        // Crash window (audit P1.3): the payload moved but the sidecar is still
        // ".pending". Fall back to the pending record so restore keeps working;
        // entries() promotes it on the next listing anyway.
        return parseMetaFile(metaFor(trashed)) ?: parseMetaFile(pendingMetaFor(trashed))
    }

    /** Parses the metadata file at [f] with [parseMeta]; null when missing, unreadable
     *  or not valid metadata. Shared by every reader so the schema lives in one place. */
    private fun parseMetaFile(f: File?): Meta? = try {
        f?.let { parseMeta(it.readText()) }
    } catch (_: Exception) {
        null
    }

    /** Validates the sidecar schema: both "trashedAt=<positive long>" and a non-empty
     *  absolute "origin=<path>" must be present, else it is not trusted metadata.
     *  "size=<long>" is optional (legacy sidecars and directory payloads: -1). */
    private fun parseMeta(text: String): Meta? {
        var at = -1L
        var origin = ""
        var size = -1L
        for (line in text.lineSequence()) {
            if (line.startsWith("trashedAt=")) at = line.removePrefix("trashedAt=").toLongOrNull() ?: at
            else if (line.startsWith("origin=")) origin = decodeOrigin(line.removePrefix("origin="))
            else if (line.startsWith("size=")) size = line.removePrefix("size=").toLongOrNull() ?: -1L
        }
        return if (at > 0 && origin.startsWith("/")) Meta(at, origin, size) else null
    }

    /** Sidecar origins are URL-encoded on write so a '\n' in a filename cannot corrupt
     *  the line-based schema (D8). Every encoded origin starts with "%2F" (every
     *  absolute path starts with "/"), which is also the discriminator against legacy
     *  sidecars that store the raw path: a literal '+' or '%' in an old path must not
     *  be decoded into something else. Decoding failures and non-absolute results fall
     *  back to the raw value, which [parseMeta] then validates as before. */
    private fun decodeOrigin(raw: String): String {
        if (!raw.startsWith("%2F", ignoreCase = true)) return raw // legacy plain path
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return if (decoded.startsWith("/")) decoded else raw
    }

    private fun pendingMetaFor(trashed: File): File? = trashed.parentFile
        ?.let { metaDirFor(it) }
        ?.let { File(it, trashed.name + META_SUFFIX + PENDING_SUFFIX) }

    /** Promotes valid ".pending" records whose payload made it into the trash (crash
     *  recovery, audit P1.3). Invalid or orphan pending files are left for pruning. */
    private fun recoverPendingMeta(trashDir: File) {
        val metaDir = metaDirFor(trashDir)
        val pendings = metaDir.listFiles { f -> f.name.endsWith(PENDING_SUFFIX) } ?: return
        for (pending in pendings) {
            val committedName = pending.name.removeSuffix(PENDING_SUFFIX)
            if (!committedName.endsWith(META_SUFFIX)) continue
            val payload = File(trashDir, committedName.removeSuffix(META_SUFFIX))
            if (!payload.exists()) continue // transaction never reached the payload move
            if (parseMetaFile(pending) == null) continue
            val committed = File(metaDir, committedName)
            // Rename first (atomic); a refused replace falls back to copy + delete.
            if (!pending.renameTo(committed)) {
                try {
                    committed.writeText(pending.readText())
                    pending.delete()
                } catch (_: Exception) {
                    // Committed write failed: keep the pending record for the next pass.
                }
            }
        }
    }

    /**
     * Moves a pre-rename sidecar into the ".meta" directory. Returns true when [sidecar]
     * really was metadata for an existing payload and has been migrated; anything else
     * — no matching payload, or a file that does not parse as valid metadata naming the
     * payload (audit P2.7) — is kept and listed as a payload, never deleted or hidden
     * behind the user's back.
     */
    private fun migrateLegacySidecar(trashDir: File, sidecar: File): Boolean {
        val payload = File(trashDir, sidecar.name.removeSuffix(META_SUFFIX))
        if (!payload.exists()) return false
        // Schema validation (audit P2.7): a user file named "*.tfmeta" with a sibling of
        // the same basename must NOT be mistaken for metadata — only a record that
        // parses as valid trash metadata AND whose origin actually names this payload
        // is migrated. Anything else is preserved as an ordinary payload.
        val meta = parseMetaFile(sidecar)
        if (meta == null || !referencesPayload(meta.originPath, payload.name)) return false
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
            if (m.name.endsWith(PENDING_SUFFIX)) {
                // A pending record is dead when the transaction never reached the
                // payload move, when its committed sidecar already exists, or when it
                // is not valid metadata. Valid pendings with a live payload are left
                // for recoverPendingMeta on the next listing.
                val base = m.name.removeSuffix(PENDING_SUFFIX)
                val payloadName = base.removeSuffix(META_SUFFIX)
                val committedExists = File(metaDir, base).exists()
                val valid = parseMetaFile(m) != null
                if (payloadName !in names || committedExists || !valid) m.delete()
                continue
            }
            val payloadName = m.name.removeSuffix(META_SUFFIX)
            if (payloadName !in names) m.delete()
        }
    }

    /** True when a legacy sidecar's [originPath] names the payload [payloadName]: the
     *  trash stores a payload under "<stamp>_" (plus a "<n>_" group when the name
     *  clashed), so the origin's last segment must equal the payload name with zero,
     *  one or two of those groups stripped. Zero covers unstamped legacy layouts;
     *  stopping at two keeps genuine records whose original name itself starts with
     *  "<digits>_" (audit P2.7: metadata that names no sibling payload is not metadata). */
    private fun referencesPayload(originPath: String, payloadName: String): Boolean {
        val base = originPath.substringAfterLast('/')
        val strippedOnce = payloadName.replaceFirst(STAMP_GROUP, "")
        val strippedTwice = strippedOnce.replaceFirst(STAMP_GROUP, "")
        return base == payloadName || base == strippedOnce || base == strippedTwice
    }
}
