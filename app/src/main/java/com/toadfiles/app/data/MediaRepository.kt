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
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.util.Intents
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
                        // Same honest mime as recent(): a blank/generic MediaStore mime
                        // hides the real handler (the installer for an .apk).
                        mime = recentMime(c.getString(iMime), c.getString(iName)),
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
     * Per-category totals for the grid cards, counted in VISIBLE files — exactly
     * the rows [list] and [recent] would show (audit P2.5). The old implementation
     * listed up to 2000 entries and reported the capped size as the total; uncapped
     * COUNT/SUM aggregates fixed that, and audit A20 folds the whole thing into ONE
     * index pass: the previous shape ran six aggregate queries plus six per-category
     * row scans, each scanning every row with an exists() stat ([hiddenTotals]) —
     * 6×N stats per call. One scan now classifies every row into every category it
     * matches (the per-category predicates below mirror [selectionFor]'s SQL, so
     * the counts are identical) and hides trash/ghost rows exactly once.
     */
    override fun summaries(): List<CategorySummary> {
        val counts = IntArray(FileCategory.entries.size)
        val bytes = LongArray(FileCategory.entries.size)
        try {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATA,
                ),
                null,
                null,
                null,
            )?.use { c ->
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iData = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext()) {
                    val data = if (iData >= 0) c.getString(iData) else null
                    // The row-visibility rule shared with the listings: trash paths and
                    // vanished ghost rows are hidden from every category at once — one
                    // visibility decision per row instead of one per category.
                    if (data != null && isHiddenPath(data)) continue
                    val mediaType = c.getInt(iType)
                    val mime = c.getString(iMime)
                    val name = c.getString(iName)
                    val size = c.getLong(iSize)
                    for (category in FileCategory.entries) {
                        if (matchesSelection(category, mediaType, mime, name)) {
                            counts[category.ordinal]++
                            bytes[category.ordinal] += size
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Provider without row access (or index unavailable): honest zeros.
            return FileCategory.entries.map { CategorySummary(it, 0, 0L) }
        }
        return FileCategory.entries.map { c -> CategorySummary(c, counts[c.ordinal], bytes[c.ordinal]) }
    }

    /** Archive MIME types of [selectionFor]'s ARCHIVES clause — shared with the
     *  single-scan classifier so both stay in lockstep. */
    private val archiveMimes = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-7z-compressed",
        "application/x-rar-compressed",
    )

    /** Archive extensions of [selectionFor]'s name fallback — shared with the
     *  single-scan classifier. */
    private val archiveExts = listOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar")

    /**
     * Kotlin mirror of [selectionFor]'s SQL for one row (audit A20): a row counts in
     * a category exactly when that category's SQL selection would have matched it —
     * SQL-NULL mime/name never matches, extension fallbacks compare lowercased — so
     * the single-scan totals equal the old per-category aggregates exactly.
     */
    private fun matchesSelection(
        category: FileCategory,
        mediaType: Int,
        mime: String?,
        name: String?,
    ): Boolean = when (category) {
        FileCategory.IMAGES -> mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        FileCategory.VIDEOS -> mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        FileCategory.AUDIO -> mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
        FileCategory.DOCS -> mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT
        FileCategory.ARCHIVES ->
            (mime != null && mime in archiveMimes) ||
                (name != null && archiveExts.any { name.lowercase().endsWith(".$it") })
        FileCategory.APKS ->
            mime == "application/vnd.android.package-archive" ||
                (name != null && name.lowercase().endsWith(".apk"))
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
                    val name = c.getString(iName) ?: "?"
                    // v1.11: rows MediaStore leaves untyped (APKs, archives) used to be
                    // lumped into DOCS, so the All tab and the type chips disagreed —
                    // an APK matched no filter it belongs to ("unrecognised").
                    val category = categoryForRecent(c.getInt(iType), name)
                    val data = if (iData >= 0) c.getString(iData) else null
                    fun entry(): MediaEntry = MediaEntry(
                        id = id,
                        uri = ContentUrisHelper.uriFor(category, id),
                        name = name,
                        size = c.getLong(iSize),
                        dateModified = c.getLong(iDate) * 1000L,
                        mime = recentMime(c.getString(iMime), name),
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
     * Drops MediaStore rows whose backing file is really gone — row hygiene after a
     * trash batch and the capped ghost cleanup in [list]/[recent].
     *
     * Two-path guard (trash-reliability fix): a row is only deleted when BOTH the
     * entry's cached DATA path AND the row's CURRENT DATA value (re-queried per row,
     * one projection query, best-effort) name missing files. The cached path can be
     * stale — e.g. this app renamed the file via the File API and the provider updated
     * the row — so deleting on the cached path alone used to remove rows pointing at
     * real, live files (and with all-files access, `resolver.delete` destroyed that
     * file: data loss). When the re-query fails, the row is gone or its DATA is unset,
     * the outcome is inconclusive and the row is KEPT — the media scanner reconciles
     * it eventually. Rows whose file lives inside the trash folder always survive:
     * that file still exists.
     *
     * Best-effort: rows we are not allowed to touch are skipped, never crash.
     * Returns how many rows were dropped.
     */
    override fun deleteStale(entries: List<MediaEntry>): Int {
        var dropped = 0
        for (e in entries) {
            val path = e.data ?: continue
            if (File(path).exists()) continue
            // Re-check the row's CURRENT path before touching it (see KDoc): deleting a
            // row makes the provider drop the file it currently points at.
            val current = runCatching {
                resolver.query(e.uri, arrayOf(MediaStore.Files.FileColumns.DATA), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            if (current == null || File(current).exists()) continue // inconclusive or live: keep the row
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

    /** Selection + args for a category. Shared by [list]; [matchesSelection] is its
     *  single-scan Kotlin mirror and must stay in lockstep with this SQL, so the
     *  category totals always count exactly what the listing would show. */
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
                // Some archivers write exotic or empty MIME types, so Browse's extension
                // set gets a second chance via the file name. The constants are shared
                // with [matchesSelection] so the SQL and its single-scan mirror agree.
                val extClause = archiveExts.joinToString(" OR ") { "lower($name) LIKE '%.$it'" }
                "($mime IN (${archiveMimes.joinToString { "?" }}) OR $extClause)" to archiveMimes.toTypedArray()
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

        /**
         * Recent-tab typing for rows MediaStore does not put in a media bucket
         * (MEDIA_TYPE_NONE — APKs, archives, plain files). The old "everything else
         * is Docs" bucket made an APK list under no type filter it actually belongs
         * to (owner report: an APK in Recent looked "unrecognised"). Reuses
         * [FilterMode]'s extension sets, so a row the APKs/Archives FILTER matches is
         * typed the same way here — one source of truth for both surfaces.
         */
        fun categoryForRecent(mediaType: Int, name: String): FileCategory = when (mediaType) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> FileCategory.IMAGES
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> FileCategory.VIDEOS
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> FileCategory.AUDIO
            else -> when {
                FilterMode.APKS.matches(name) -> FileCategory.APKS
                FilterMode.ARCHIVES.matches(name) -> FileCategory.ARCHIVES
                else -> FileCategory.DOCS
            }
        }

        /**
         * MediaStore leaves sideloaded files (APKs, archives written by some tools)
         * with a blank or generic mime; handing that to the system resolver hides the
         * real handler — the package installer for an .apk (owner report: an APK in
         * Recent opened as an "unrecognised" file). Blank/generic values fall back to
         * the same extension mapping every open in the app uses ([Intents.mimeOf]).
         */
        fun recentMime(raw: String?, name: String): String {
            val mime = raw?.trim()
            return if (!mime.isNullOrEmpty() && !mime.equals("application/octet-stream", ignoreCase = true)) {
                mime.lowercase()
            } else {
                Intents.mimeOf(File(name))
            }
        }

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
