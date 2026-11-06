package com.toadfiles.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream

/** Archive container formats toadFILES can CREATE (v1.7.3); extraction is wider (zip/7z). */
enum class ArchiveFormat(val extension: String, val label: String) {
    ZIP("zip", "ZIP"),
    SEVEN_Z("7z", "7z"),
}

/**
 * Archive detection + dispatch shared by Browse, Recent and Categories (v1.7.1).
 *
 * One source of truth for "is this an archive toadFILES can act on", so every surface
 * (row tap, action sheets, selection menus) offers the same Extract actions:
 *  - .zip: extract (hardened [ZipSupport.extract]) AND compress-to-zip.
 *  - .7z:  extract (hardened [SevenZipSupport.extract]) AND, since v1.7.3,
 *    compress-to-7z through the equally hardened [SevenZipSupport.create7z].
 * Other archive-ish extensions (rar, tar, gz, …) keep their old behaviour: generic file.
 *
 * v1.7.3: [compress] routes to the format's pipeline with the shared [CompressLevel]
 * chooser (Deflater levels for zip, LZMA2 presets / COPY for 7z).
 *
 * v1.8.0: [compress]/[extract] accept a password. Only the 7z pipeline uses it
 * (AES-256 data-only encryption, [SevenZipSupport.create7z]); the zip pipeline takes
 * the parameter for signature symmetry but keeps declining encrypted zips — and
 * [extract] reports [OpResult.passwordRequired] when an encrypted 7z is opened without
 * a usable password, so the UI can prompt and retry.
 *
 * v1.8.0 split-into-volumes (see [VolumeSupport]): a "DCIM.7z.001" is a volume START —
 * it is extractable when the stripped type is, [extract] enters the set through it, and
 * [compress] can split its own output via [volumeBytes]. A volume set with a gap fails
 * before anything is written.
 *
 * v2.1.0 (owner report): app bundles extract like the zip containers they are —
 * .xapk / .apkm are ZIP archives holding the real .apk files (base + splits), and
 * until now tapping one routed to a useless external viewer with no way to pull
 * the APKs out. They are read-only targets: [compress] still refuses them (a bundle
 * is never a compression SOURCE FORMAT), only [canExtract]/[isArchive]/[extract]
 * grew. A plain .apk deliberately stays OFF the list — its honest action is the
 * package installer, not unpacking dex/resources.
 */
object ArchiveOps {

    /** Extensions toadFILES can extract. */
    private val EXTRACTABLE = setOf("zip", "7z")

    /** App-bundle containers that are structurally zips — extraction dispatches to
     *  the zip pipeline; creation does not apply. */
    private val APP_BUNDLES = setOf("xapk", "apkm")

    private const val MERGE_BUFFER = 64 * 1024

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** The [name] with a volume suffix removed ("DCIM.7z.001" → "DCIM.7z"). */
    fun strippedVolumeName(name: String): String = VolumeSupport.baseName(name)

    /** True when tapping/long-pressing this name should offer archive (Extract) actions.
     *  v1.8.0: also true for a volume START whose stripped type is extractable
     *  ("dcim.7z.001" → true; "dcim.7z.002" → false — the set is entered via ".001"). */
    fun canExtract(name: String): Boolean =
        extension(name) in EXTRACTABLE || extension(name) in APP_BUNDLES ||
            (VolumeSupport.isVolumeStart(name) && extension(VolumeSupport.baseName(name)) in EXTRACTABLE)

    /** True when this name is itself an archive of a format we can create — v1.8.0:
     *  volume starts of such archives too, so long-press action sheets offer Extract. */
    fun isArchive(name: String): Boolean =
        extension(name) in EXTRACTABLE || extension(name) in APP_BUNDLES ||
            (VolumeSupport.isVolumeStart(name) && extension(VolumeSupport.baseName(name)) in EXTRACTABLE)

    /** Total entry count when it is known upfront (zip central directory, 7z header),
     *  for a determinate progress bar before the op starts; null when unknown.
     *  v1.8.0: 7z volume starts read their header through [VolumeSupport.seekableChannel];
     *  zip volume starts stream, so they stay indeterminate (null). */
    fun entryCount(archive: File): Int? {
        if (VolumeSupport.isVolumeStart(archive.name)) {
            return when (extension(VolumeSupport.baseName(archive.name))) {
                "7z" -> runCatching {
                    VolumeSupport.seekableChannel(archive).use { ch ->
                        SevenZFile(ch).use { it.entries.toList().size }
                    }
                }.getOrNull()
                else -> null
            }
        }
        return when (extension(archive.name)) {
            "zip", in APP_BUNDLES -> runCatching { java.util.zip.ZipFile(archive).use { it.size() } }.getOrNull()
            "7z" -> SevenZipSupport.entryCount(archive)
            else -> null
        }
    }

    /**
     * Extracts [archive] into [targetDir] with the format's hardened pipeline.
     *
     * v1.8.0: [archive] may be the ".001" volume of a split set. The set is validated
     * for gaps BEFORE anything is written (a gap is an honest failure), then dispatched
     * on the stripped extension: 7z volumes extract straight through
     * [VolumeSupport.seekableChannel] in the channel-based [SevenZipSupport.extract];
     * zip volumes are merged into a hidden temp sibling and run through the stock
     * [ZipSupport.extract] (see [extractZipVolumes] for why). [password] is passed
     * through to the 7z pipeline only — zip doesn't support passwords yet.
     */
    suspend fun extract(
        archive: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits = ZipSupport.Limits(),
        password: CharArray? = null,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult {
        try {
            if (VolumeSupport.isVolumeStart(archive.name)) {
                return extractVolumes(archive, targetDir, replace, limits, password, onProgress)
            }
            return when (extension(archive.name)) {
                "zip", in APP_BUNDLES ->
                    ZipSupport.extract(archive, targetDir, replace, limits, password, onProgress)
                "7z" -> SevenZipSupport.extract(archive, targetDir, replace, limits, password, onProgress)
                else -> OpResult(0, 0, 1, false, listOf("${archive.name}: unsupported archive type"))
            }
        } finally {
            // Audit A15: the caller's password bytes never outlive the operation. The
            // array is created fresh per attempt by every caller (retry flow included),
            // so blanking it here leaks nothing and can never race a second user.
            password?.fill('\u0000')
        }
    }

    /** Volume-set dispatch; a gap fails before anything is written. */
    private suspend fun extractVolumes(
        archive: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits,
        password: CharArray?,
        onProgress: (OpProgress) -> Unit,
    ): OpResult {
        VolumeSupport.firstMissingVolume(archive)?.let { missing ->
            return OpResult(
                0, 0, 1, false,
                listOf("Volume set incomplete: missing .${"%03d".format(missing)}"),
            )
        }
        return when (extension(VolumeSupport.baseName(archive.name))) {
            "7z" -> VolumeSupport.seekableChannel(archive).use { ch ->
                SevenZipSupport.extract(
                    ch, VolumeSupport.totalSize(archive), targetDir, replace, limits, password, onProgress,
                )
            }
            "zip" -> extractZipVolumes(archive, targetDir, replace, limits, onProgress)
            else -> OpResult(0, 0, 1, false, listOf("${archive.name}: unsupported archive type"))
        }
    }

    /**
     * zip-volume route (v1.8.0). ZipSupport.extract is a File-based pipeline; rather
     * than refactor it into an extractStream variant mid-release (and collide with the
     * parallel password plumbing), the volume set is stream-concatenated into a hidden
     * temp sibling ".<name>.merge-<nano>" and run through the stock hardened extractor
     * — every zip protection applies verbatim. The temp is deleted in finally whatever
     * happens; the target dir is untouched unless the zip pipeline itself publishes.
     * Cost: one extra disk copy of the archive while extracting.
     */
    private suspend fun extractZipVolumes(
        archive: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits,
        onProgress: (OpProgress) -> Unit,
    ): OpResult = withContext(Dispatchers.IO) {
        val parent = archive.parentFile ?: File(".")
        val merged = File(parent, ".${archive.name}.merge-${System.nanoTime()}")
        try {
            VolumeSupport.inputStream(archive).use { input ->
                FileOutputStream(merged).use { out ->
                    val buf = ByteArray(MERGE_BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                }
            }
            // zip volumes can't be encrypted (zip has no password support) — pass null.
            ZipSupport.extract(merged, targetDir, replace, limits, null, onProgress)
        } catch (ce: CancellationException) {
            OpResult(0, 0, 1, true, listOf("${archive.name}: merge cancelled"))
        } finally {
            merged.delete()
        }
    }

    /**
     * Compresses [sources] into [outFile] with the chosen [format] and [level] (v1.7.3).
     *
     * v1.8.0: after a fully successful create, [volumeBytes] > 0 splits the archive into
     * "name.001, name.002, …" volumes via [VolumeSupport.split] (the original archive
     * file is replaced by the volumes). A failed or cancelled split reports the failure
     * and keeps the unsplit archive — data is never lost. [password] goes to the 7z
     * pipeline only (AES-256 data-only encryption); the zip pipeline ignores it —
     * encrypted zip creation isn't supported.
     */
    suspend fun compress(
        sources: List<File>,
        outFile: File,
        format: ArchiveFormat,
        level: CompressLevel = CompressLevel.NORMAL,
        password: CharArray? = null,
        volumeBytes: Long? = null,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult {
        val created = when (format) {
            ArchiveFormat.ZIP -> ZipSupport.zip(sources, outFile, level, onProgress)
            ArchiveFormat.SEVEN_Z -> SevenZipSupport.create7z(
                sources, outFile, level, password = password, onProgress = onProgress,
            )
        }
        if (volumeBytes == null || volumeBytes <= 0 || !created.fullySucceeded) return created
        return try {
            VolumeSupport.split(outFile, volumeBytes)
            created
        } catch (ce: CancellationException) {
            OpResult(
                0, created.skipped, 1, true,
                created.failures + "${outFile.name}: split cancelled — original archive kept",
            )
        } catch (e: Exception) {
            OpResult(
                created.succeeded, created.skipped, created.failed + 1, false,
                created.failures + "${outFile.name}: ${e.message ?: "split failed"} — original archive kept",
            )
        }
    }

    /** Zip creation kept for the v1.7.1–1.7.2 call sites; new code uses [compress]. */
    suspend fun zip(
        sources: List<File>,
        outFile: File,
        level: CompressLevel = CompressLevel.NORMAL,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = ZipSupport.zip(sources, outFile, level, onProgress)

    /**
     * The one compress-batch engine (v1.8.0 loop, audit A22): runs [targets]
     * sequentially — a single archive, or one per source for "separate archive for
     * each item" — with the same format, level, password and volume size for each,
     * and aggregates the per-archive results into one combined [OpResult] (summed
     * counts; cancelled if any run was cancelled; failures concatenated).
     *
     * Progress ticks across archives stay monotonic: the running base of finished
     * archives is added to each tick, and the pipeline's OWN totals are forwarded
     * (audit A4 — substituting the selection size showed "37 of 1 items" and clamped
     * the bar at the first tick).
     *
     * Delete-after-compress follows the app's delete convention: per archive, only a
     * fully successful run hands that archive's own [sources] to [trashSources] — a
     * failed sibling never deletes another's sources.
     *
     * Audit A15: [password] is blanked in `finally` when the batch ends, however it
     * ends. It is deliberately NOT blanked inside [compress] — a multi-archive batch
     * hands the same array to every archive — so this outermost seam is the one
     * honest point of consumption. Every production caller creates the array fresh
     * per attempt (dialog confirm, password retry), so nothing reuses it afterwards.
     */
    suspend fun compressBatch(
        files: List<File>,
        targets: List<File>,
        format: ArchiveFormat,
        level: CompressLevel = CompressLevel.NORMAL,
        password: CharArray? = null,
        volumeBytes: Long? = null,
        deleteSources: Boolean = false,
        onProgress: (OpProgress) -> Unit = {},
        trashSources: suspend (List<File>) -> Unit = {},
    ): OpResult {
        val separate = targets.size > 1
        try {
            var succeeded = 0
            var skipped = 0
            var failed = 0
            var cancelled = false
            val failures = mutableListOf<String>()
            var base = 0
            for (i in targets.indices) {
                val srcs = if (separate) listOf(files[i]) else files
                var archiveTotal = 0
                val r = compress(srcs, targets[i], format, level, password = password, volumeBytes = volumeBytes) { p ->
                    archiveTotal = p.total
                    onProgress(OpProgress(base + p.done, base + p.total, p.currentItem))
                }
                succeeded += r.succeeded
                skipped += r.skipped
                failed += r.failed
                if (r.cancelled) cancelled = true
                failures += r.failures
                base += archiveTotal
                if (deleteSources && r.fullySucceeded) trashSources(srcs)
            }
            return OpResult(
                succeeded = succeeded,
                skipped = skipped,
                failed = failed,
                cancelled = cancelled,
                failures = failures.distinct(),
            )
        } finally {
            password?.fill('\u0000')
        }
    }
}
