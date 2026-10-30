package com.toadfiles.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.Trash
import java.io.File

/**
 * MediaStore-backed category and recent-file queries. Real and on-demand, the OS
 * already keeps the index so there is no manual filesystem scanning here.
 */
class MediaRepository(private val resolver: ContentResolver) {

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
    fun list(category: FileCategory, limit: Int): List<MediaEntry> {
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
                    // Same trash-hiding rule as recent(): renamed-away files can linger
                    // in the media index and would otherwise appear inside categories.
                    if (data != null && (isTrashPath(data) || !File(data).exists())) {
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
     * True per-category totals for the grid cards. The old implementation listed up
     * to 2000 entries and reported the capped size as the total; COUNT/SUM aggregates
     * give the real, uncapped numbers without materialising any rows. Totals reflect
     * the MediaStore index as-is; ghost rows whose files vanished are reconciled by
     * [list] and [recent] as users browse.
     */
    fun summaries(): List<CategorySummary> =
        FileCategory.entries.map { c ->
            val (count, bytes) = totalsFor(c)
            CategorySummary(c, count, bytes)
        }

    fun recent(limit: Int): List<MediaEntry> {
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
                    if (data != null && (isTrashPath(data) || !File(data).exists())) {
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
    fun deleteStale(entries: List<MediaEntry>): Int {
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

    /** COUNT(_id)/SUM(_size) over the category's selection; (0, 0) when unavailable. */
    private fun totalsFor(category: FileCategory): Pair<Int, Long> {
        val (selection, args) = selectionFor(category)
        try {
            resolver.query(
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
                    val count = c.getInt(0)
                    // SUM over zero rows is NULL; Cursor.getLong would hand back 0 anyway,
                    // but be explicit about the empty case.
                    val bytes = if (c.isNull(1)) 0L else c.getLong(1)
                    return count to bytes
                }
            }
        } catch (_: Exception) {
            // Provider without aggregate support (or index unavailable): honest zeros.
        }
        return 0 to 0L
    }

    /** Selection + args for a category. Shared by [list] and [summaries] so the
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

        /**
         * True when [path] lives inside the toadFILES trash folder on any volume. Pure so it
         * can be unit tested; the trash dir name must match [com.toadfiles.app.domain.Trash.DIR_NAME].
         */
        fun isTrashPath(path: String): Boolean {
            if (path.isBlank()) return false
            var i = path.indexOf(Trash.DIR_NAME)
            while (i >= 0) {
                val before = if (i == 0) '/' else path[i - 1]
                val afterIdx = i + Trash.DIR_NAME.length
                val after = if (afterIdx >= path.length) '/' else path[afterIdx]
                if (before == '/' && after == '/') return true
                i = path.indexOf(Trash.DIR_NAME, i + 1)
            }
            return false
        }
    }

    /** Bridge so MediaStore entries can be reused with the shared [FileItem] row UI. */
    fun toItem(e: MediaEntry): FileItem =
        FileItem(file = File(e.name), name = e.name, isDir = false, size = e.size, lastModified = e.dateModified)
}

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
