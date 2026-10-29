package com.toadfiles.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import java.io.File

/**
 * MediaStore-backed category and recent-file queries (real, on-demand, indexed by the OS —
 * no manual filesystem scanning).
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
                    out += MediaEntry(
                        id = id,
                        uri = ContentUrisHelper.uriFor(category, id),
                        name = c.getString(iName) ?: "?",
                        size = c.getLong(iSize),
                        dateModified = c.getLong(iDate) * 1000L,
                        mime = c.getString(iMime) ?: "application/octet-stream",
                        category = category,
                        data = if (iData >= 0) c.getString(iData) else null,
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
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    val type = c.getInt(iType)
                    val category = when (type) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> FileCategory.IMAGES
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> FileCategory.VIDEOS
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> FileCategory.AUDIO
                        else -> FileCategory.DOCS
                    }
                    out += MediaEntry(
                        id = id,
                        uri = ContentUrisHelper.uriFor(category, id),
                        name = c.getString(iName) ?: "?",
                        size = c.getLong(iSize),
                        dateModified = c.getLong(iDate) * 1000L,
                        mime = c.getString(iMime) ?: "application/octet-stream",
                        category = category,
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
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
