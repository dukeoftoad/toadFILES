package com.toadfiles.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.Trash
import java.io.File

/**
 * Read-side contract over the OS media library used by the screen ViewModels. Exists so
 * tests can substitute a delayed/controllable fake and prove that out-of-order query
 * results never overwrite newer screen state.
 */
interface MediaQuerySource {
    fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry>
    fun recent(limit: Int): List<MediaRepository.MediaEntry>
    fun summaries(): List<CategorySummary>
    fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int

    /** Re-indexes [paths] with the platform media scanner so files the OS index no
     *  longer knows about (e.g. restored from the trash) reappear in Recent,
     *  Categories and system galleries. Paths may be files or directories (a
     *  directory's contained files are walked, bounded). Default no-op so test
     *  fakes and other implementations stay untouched; the real repository scans. */
    fun rescan(paths: List<String>) {}
}

/**
 * MediaStore-backed category and recent-file queries. Real and on-demand, the OS
 * already keeps the index so there is no manual filesystem scanning here.
 */
class MediaRepository(private val context: Context) : MediaQuerySource {

    private val resolver: ContentResolver = context.contentResolver

    data class MediaEntry(
        val id: Long,
        val uri: Uri,
        val name: String,
        val size: Long,
        val dateModified: Long,
        val mime: String,
        val category: FileCategory,
        /** Real path from the DATA column; null when unknown. Used for "Show in folder". */
        val data: String? = null,
    )

    /**
     * Category listing, newest first. Rows whose backing file no longer exists are
     * hidden — and so the index heals instead of showing ghosts forever, those rows
     * are also removed from MediaStore after the cursor is closed (best-effort,
     * capped per query, see [reconcileStaleRows]). Trashed files are hidden too but
     * never deleted here: their files still exist inside the trash folder.
     */
    @Suppress("DEPRECATION") // DATA is deprecated but is the only way back to a real path
    override fun list(category: FileCategory, limit: Int): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        val stale = mutableListOf<MediaEntry>()
        val (selection, args) = selectionFor(category)
        try {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DATA,
                ),
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val iData = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    val data = if (iData >= 0) c.getString(iData) else null
                    fun entry(): MediaEntry = MediaEntry(
                        id = id,
                        uri = ContentUrisHelper.uriFor(category, id),
                        name = c.getString(iName) ?: "?",
                        size = c.getLong(iSize),
                        dateModified = c.getLong(iDate) * 1000L,
                        mime = c.getString(iMime) ?: "application/octet-stream",
                        category = category,
                        data = data,
                    )
                    // Same visibility rule as recent() and totalsFor(): renamed-away files
                    // can linger in the media index and would otherwise appear inside
                    // categories.
                    if (data != null && isHiddenPath(data)) {
                        // Trash-path rows still exist on disk — hide, never delete. But a
                        // row whose file vanished outright is a ghost: remember it so the
                        // index can be reconciled once the cursor is closed.
                        if (!isTrashPath(data)) stale += entry()
                        continue
                    }
                    out += entry()
                }
            }
            reconcileStaleRows(stale)
        } catch (_: Exception) {
            // MediaStore unavailable/permission gap: honest empty result, UI shows empty state.
        }
        return out
    }

    /**
     * True per-category totals for the grid cards, counted in VISIBLE files — exactly
     * the rows [list] and [recent] would show (audit P2.5). The old implementation
     * listed up to 2000 entries and reported the capped size as the total; COUNT/SUM
     * aggregates keep the numbers real and uncapped, and [totalsFor] excludes the
     * hidden rows without introducing any cap.
     */
    override fun summaries(): List<CategorySummary> =
        FileCategory.entries.map { c ->
            val (count, bytes) = totalsFor(c)
            CategorySummary(c, count, bytes)
        }

    override fun recent(limit: Int): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        val stale = mutableListOf<MediaEntry>()
        try {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.DATA,
                ),
                null,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val iData = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    val type = c.getInt(iType)
                    val category = when (type) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> FileCategory.IMAGES
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> FileCategory.VIDEOS
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> FileCategory.AUDIO
                        else -> FileCategory.DOCS
                    }
                    val data = if (iData >= 0) c.getString(iData) else null
                    fun entry(): MediaEntry = MediaEntry(
                        id = id,
                        uri = ContentUrisHelper.uriFor(category, id),
                        name = c.getString(iName) ?: "?",
                        size = c.getLong(iSize),
                        dateModified = c.getLong(iDate) * 1000L,
                        mime = c.getString(iMime) ?: "application/octet-stream",
                        category = category,
                        data = data,
                    )
                    // Renaming a file into the trash does not always update the media index
                    // immediately, so trashed files can still surface as "recent". Hide them.
                    if (data != null && isHiddenPath(data)) {
                        if (!isTrashPath(data)) stale += entry()
                        continue
                    }
                    out += entry()
                }
            }
            reconcileStaleRows(stale)
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Removes MediaStore rows whose on-disk file no longer exists (e.g. after toadFILES
     * moved them to the trash — the index only catches up on the next media scan).
     * Best-effort: rows we are not allowed to touch are skipped, never crash.
     * Returns how many rows were dropped.
     */
    override fun deleteStale(entries: List<MediaEntry>): Int {
        var dropped = 0
        for (e in entries) {
            val path = e.data ?: continue
            if (File(path).exists()) continue
            try {
                if (resolver.delete(e.uri, null, null) > 0) dropped++
            } catch (_: Exception) {
                // Not ours to delete (or volume gone): leave the row; the media scanner
                // will reconcile it eventually.
            }
        }
        return dropped
    }

    /**
     * Re-indexes restored files: trashing deletes the MediaStore row ([deleteStale]),
     * so only a media scan brings a restored file back into Recent/Categories and
     * system galleries. A restored directory re-indexes through its contained files —
     * the scanner ignores bare folders — bounded to [MAX_RESCAN_PATHS] paths per call
     * so a huge tree cannot flood the scanner.
     */
    override fun rescan(paths: List<String>) {
        val toScan = mutableListOf<String>()
        val queue = ArrayDeque<File>()
        fun offer(path: String) {
            if (toScan.size < MAX_RESCAN_PATHS) toScan += path
        }
        for (p in paths) {
            if (toScan.size >= MAX_RESCAN_PATHS) break
            val f = File(p)
            if (f.isDirectory) queue += f else offer(f.absolutePath)
            while (queue.isNotEmpty() && toScan.size < MAX_RESCAN_PATHS) {
                val dir = queue.removeFirst()
                val children = dir.listFiles() ?: continue
                for (c in children) {
                    if (toScan.size >= MAX_RESCAN_PATHS) break
                    if (c.isDirectory) queue += c else offer(c.absolutePath)
                }
            }
        }
        if (toScan.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(context, toScan.toTypedArray(), null, null)
            } catch (_: Exception) {
                // The scan is best-effort by design — a missing provider or a
                // stripped-down environment (unit tests) must never crash a restore.
            }
        }
    }

    /**
     * The row-visibility rule shared by [list], [recent] and [totalsFor] for a non-null
     * DATA path: the file lives inside the trash folder, or its backing file has
     * vanished (a ghost row). Rows without a DATA path stay visible, as in the listings.
     */
    private fun isHiddenPath(data: String): Boolean = isTrashPath(data) || !File(data).exists()

    /**
     * Per-category totals for the cards, counted in VISIBLE files (audit P2.5): rows
     * [isHiddenPath] hides are excluded, so a card can never advertise an entry the
     * user cannot open. Totals stay true uncapped aggregates — the full COUNT/SUM runs
     * first and [hiddenTotals] subtracts the hidden rows afterwards, instead of capping
     * the result at a listing size. (0, 0) when unavailable.
     */
    private fun totalsFor(category: FileCategory): Pair<Int, Long> {
        val (selection, args) = selectionFor(category)
        try {
            val (count, bytes) = resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    "COUNT(${MediaStore.Files.FileColumns._ID})",
                    "SUM(${MediaStore.Files.FileColumns.SIZE})",
                ),
                selection,
                args,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    // SUM over zero rows is NULL; Cursor.getLong would hand back 0 anyway,
                    // but be explicit about the empty case.
                    c.getInt(0) to (if (c.isNull(1)) 0L else c.getLong(1))
                } else null
            } ?: return 0 to 0L
            val (hiddenCount, hiddenBytes) = hiddenTotals(selection, args)
            // coerceAtLeast: the index can change between the two queries.
            return (count - hiddenCount).coerceAtLeast(0) to (bytes - hiddenBytes).coerceAtLeast(0L)
        } catch (_: Exception) {
            // Provider without aggregate support (or index unavailable): honest zeros.
        }
        return 0 to 0L
    }

    /**
     * Count and bytes of the rows under [selection] that [isHiddenPath] hides (trash
     * paths, vanished files) — the amount [totalsFor] subtracts from the raw aggregate.
     * Probe only: unlike [reconcileStaleRows] this never writes to the index, so
     * reading summaries stays side-effect free. Errors propagate to [totalsFor]'s
     * catch, which reports honest zeros.
     */
    private fun hiddenTotals(selection: String, args: Array<String>): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATA,
            ),
            selection,
            args,
            null,
        )?.use { c ->
            val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val iData = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            while (c.moveToNext()) {
                val data = if (iData >= 0) c.getString(iData) else null
                if (data != null && isHiddenPath(data)) {
                    count++
                    bytes += c.getLong(iSize)
                }
            }
        }
        return count to bytes
    }

    /** Selection + args for a category. Shared by [list], [totalsFor] and its
     *  hidden-row probe so the category totals always count exactly what the listing
     *  would show. */
    private fun selectionFor(category: FileCategory): Pair<String, Array<String>> {
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        val name = MediaStore.Files.FileColumns.DISPLAY_NAME
        return when (category) {
            FileCategory.IMAGES ->
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" to
                    arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            FileCategory.VIDEOS ->
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" to
                    arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            FileCategory.AUDIO ->
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" to
                    arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            FileCategory.DOCS ->
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" to
                    arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT.toString())
            FileCategory.ARCHIVES -> {
                val mimes = arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/x-7z-compressed",
                    "application/x-rar-compressed",
                )
                // Some archivers write exotic or empty MIME types, so Browse's extension
                // set gets a second chance via the file name.
                val exts = listOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar")
                val extClause = exts.joinToString(" OR ") { "lower($name) LIKE '%.$it'" }
                "($mime IN (${mimes.joinToString { "?" }}) OR $extClause)" to mimes
            }
            FileCategory.APKS ->
                "($mime=? OR lower($name) LIKE '%.apk')" to
                    arrayOf("application/vnd.android.package-archive")
        }
    }

    /**
     * Best-effort index reconciliation for one query: rows collected because their
     * backing file vanished are dropped from MediaStore via [deleteStale]. Capped at
     * [MAX_STALE_DELETIONS_PER_QUERY] so a badly stale index cannot turn a single
     * scroll into hundreds of provider writes; per-row failures are ignored there.
     */
    private fun reconcileStaleRows(stale: List<MediaEntry>) {
        if (stale.isEmpty()) return
        deleteStale(stale.take(MAX_STALE_DELETIONS_PER_QUERY))
    }

    companion object {
        /** Upper bound for the automatic stale-row cleanup inside [list] and [recent]. */
        private const val MAX_STALE_DELETIONS_PER_QUERY = 50

        /** Upper bound of paths handed to the media scanner per [rescan] call. */
        private const val MAX_RESCAN_PATHS = 1000

        /** Volume roots as MediaStore DATA paths see them: "/storage/emulated/0",
         *  "/storage/0000-0001", … (see [com.toadfiles.app.data.FileRepository.roots]). */
        private val VOLUME_ROOT_PATTERN = "^/storage/(?:emulated/\\d+|[^/]+)"

        /** The trash folder of any KNOWN volume root — exactly where [Trash] creates
         *  it. Root-scoped on purpose (D11): a user's own folder named
         *  ".toadfiles-trash" anywhere else in the tree is ordinary user data and must
         *  stay visible. Pure so it can be unit tested; the trash dir name must match
         *  [com.toadfiles.app.domain.Trash.DIR_NAME]. */
        private val TRASH_PATH_REGEX = Regex("$VOLUME_ROOT_PATTERN/${Regex.escape(Trash.DIR_NAME)}(?=/|$)")

        /**
         * True when [path] lives inside the toadFILES trash folder at a storage
         * volume root ("/storage/emulated/0/.toadfiles-trash/…", "/storage/0000-0001/
         * .toadfiles-trash/…"). Paths under a same-named folder deeper in the tree are
         * the user's own files and are NOT trash paths.
         */
        fun isTrashPath(path: String): Boolean = TRASH_PATH_REGEX.containsMatchIn(path)
    }

}

/**
 * Bridge so MediaStore entries can reuse the shared [FileItem] row/sheet/grid UI Browse
 * renders (v1.7.3: the Categories drill-in and the media action sheet are the exact same
 * components). Uses the on-disk DATA path when the index reveals it (real thumbnails,
 * path-dependent actions); without one, a name-only File keeps the icon fallback honest.
 */
fun MediaRepository.MediaEntry.toFileItem(): FileItem = FileItem(
    file = data?.let(::File) ?: File(name),
    name = name,
    isDir = false,
    size = size,
    lastModified = dateModified,
)

/**
 * Builds the content URI handed out for a MediaStore row. Per-collection categories
 * target their own tables; everything else (docs/archives/apks) lives in the generic
 * Files table, whose row URI is content://media/external/file/<id> — built through
 * the platform API rather than string concatenation so volume/table naming stays
 * correct across API levels.
 */
object ContentUrisHelper {
    fun uriFor(category: FileCategory, id: Long): Uri =
        when (category) {
            FileCategory.IMAGES -> Uri.parse("content://media/external/images/media/$id")
            FileCategory.VIDEOS -> Uri.parse("content://media/external/video/media/$id")
            FileCategory.AUDIO -> Uri.parse("content://media/external/audio/media/$id")
            // MediaStore.Files.getContentUri(volume, rowId) is API 29+ and minSdk is 26;
            // this is byte-for-byte what that overload does internally.
            else -> ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
        }
}
