package com.toadfiles.app.data

import android.content.ContentResolver
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

    @Suppress("DEPRECATION") // DATA is deprecated but is the only way back to a real path
    fun list(category: FileCategory, limit: Int = 1000): List<MediaEntry> {
        val selection: String
        val args: Array<String>
        when (category) {
            FileCategory.IMAGES -> {
                selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
                args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            }
            FileCategory.VIDEOS -> {
                selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
                args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            }
            FileCategory.AUDIO -> {
                selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
                args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            }
            FileCategory.DOCS -> {
                selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
                args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT.toString())
            }
            FileCategory.ARCHIVES -> {
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IN (?, ?, ?, ?)"
                args = arrayOf("application/zip", "application/x-zip-compressed", "application/x-7z-compressed", "application/x-rar-compressed")
            }
            FileCategory.APKS -> {
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE}=?"
                args = arrayOf("application/vnd.android.package-archive")
            }
        }
        val out = mutableListOf<MediaEntry>()
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
                    // Same trash-hiding rule as recent(): renamed-away files can linger
                    // in the media index and would otherwise appear inside categories.
                    if (data != null && (isTrashPath(data) || !File(data).exists())) continue
                    out += MediaEntry(
                        id = id,
                        uri = ContentUrisHelper.uriFor(category, id),
                        name = c.getString(iName) ?: "?",
                        size = c.getLong(iSize),
                        dateModified = c.getLong(iDate) * 1000L,
                        mime = c.getString(iMime) ?: "application/octet-stream",
                        category = category,
                        data = data,
                    )
                }
            }
        } catch (_: Exception) {
            // MediaStore unavailable/permission gap: honest empty result, UI shows empty state.
        }
        return out
    }

    fun summaries(): List<CategorySummary> =
        FileCategory.entries.map { c ->
            val items = list(c, limit = 2000)
            CategorySummary(c, items.size, items.sumOf { it.size })
        }

    fun recent(limit: Int = 60): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
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
                    // Renaming a file into the trash does not always update the media index
                    // immediately, so trashed files can still surface as "recent". Hide them.
                    if (data != null && (isTrashPath(data) || !File(data).exists())) continue
                    out += MediaEntry(
                        id = id,
                        uri = ContentUrisHelper.uriFor(category, id),
                        name = c.getString(iName) ?: "?",
                        size = c.getLong(iSize),
                        dateModified = c.getLong(iDate) * 1000L,
                        mime = c.getString(iMime) ?: "application/octet-stream",
                        category = category,
                        data = data,
                    )
                }
            }
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

    companion object {
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

object ContentUrisHelper {
    fun uriFor(category: FileCategory, id: Long): Uri = Uri.parse(
        when (category) {
            FileCategory.IMAGES -> "content://media/external/images/media/$id"
            FileCategory.VIDEOS -> "content://media/external/video/media/$id"
            FileCategory.AUDIO -> "content://media/external/audio/media/$id"
            else -> "content://media/external/files/media/$id"
        }
    )
}
