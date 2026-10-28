package io.github.dukeoftoad.toadfiles.domain

import java.io.File

/** A browsable file-system entry. */
data class FileItem(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
)

enum class SortMode { NAME, SIZE, DATE, TYPE }
enum class SortOrder { ASC, DESC }

enum class FilterMode(val label: String) {
    ALL("All"),
    DOCS("Documents"),
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    ARCHIVES("Archives"),
    APKS("APKs");

    fun matches(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (this) {
            ALL -> true
            DOCS -> ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub", "rtf", "odt")
            IMAGES -> ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
            VIDEOS -> ext in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
            AUDIO -> ext in setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "amr")
            ARCHIVES -> ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
            APKS -> ext == "apk" || ext == "apkm" || ext == "xapk"
        }
    }
}

enum class ConflictStrategy { RENAME, REPLACE, SKIP }

data class StorageStats(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float
        get() = if (totalBytes <= 0) 0f else usedBytes.toFloat() / totalBytes
}

data class StorageRoot(val label: String, val dir: File, val stats: StorageStats?)

enum class FileCategory(val label: String) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCS("Documents"),
    ARCHIVES("Archives"),
    APKS("APKs"),
}

data class CategorySummary(val category: FileCategory, val count: Int, val totalBytes: Long)
