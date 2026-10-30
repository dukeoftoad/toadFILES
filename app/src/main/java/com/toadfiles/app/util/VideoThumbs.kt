package com.toadfiles.app.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Tiny in-memory video thumbnail cache. Frames come from MediaMetadataRetriever on
 * Dispatchers.IO — no extra dependency, no disk cache, nothing persisted or uploaded.
 *
 * Entries are keyed by path plus the file's identity stamp (mtime + size), so a video
 * that is replaced, edited or overwritten in place no longer serves its stale frame
 * (audit finding P3.2): the old stamp stops matching, the new frame is decoded, and
 * the superseded entry ages out through the LRU.
 */
object VideoThumbs {

    private const val MAX_EDGE = 320
    private val cache = LruCache<String, Bitmap>(48)

    /**
     * Current identity of the file at [path]: "<path>#<mtime>:<size>", or a stable
     * "missing" stamp when the file does not exist. Cheap (one stat) and safe to
     * call from composition code.
     */
    fun stampFor(path: String): String = VideoThumbKeys.cacheKey(path, VideoThumbKeys.identityStamp(File(path)))

    fun peek(path: String, stamp: String = stampFor(path)): Bitmap? =
        cache.get(VideoThumbKeys.cacheKey(path, stamp))

    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        val key = VideoThumbKeys.cacheKey(file.path, VideoThumbKeys.identityStamp(file))
        cache.get(key)?.let { return@withContext it }
        val frame = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val scaled = scaleDown(frame)
        cache.put(key, scaled)
        scaled
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        if (maxEdge <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / maxEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}

/**
 * Pure key math for [VideoThumbs], kept free of Android classes so it can be unit
 * tested on the plain JVM (the object itself drags in android.util.LruCache).
 */
internal object VideoThumbKeys {

    fun cacheKey(path: String, stamp: String): String = "$path#$stamp"

    /** mtime + size in a single stat; anything unreadable counts as "missing". */
    fun identityStamp(file: File): String = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        "${attrs.lastModifiedTime().toMillis()}:${attrs.size()}"
    } catch (_: Exception) {
        "missing"
    }
}
