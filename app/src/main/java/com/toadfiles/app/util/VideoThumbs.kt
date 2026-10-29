package com.toadfiles.app.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tiny in-memory video thumbnail cache. Frames come from MediaMetadataRetriever on
 * Dispatchers.IO — no extra dependency, no disk cache, nothing persisted or uploaded.
 */
object VideoThumbs {

    private const val MAX_EDGE = 320
    private val cache = LruCache<String, Bitmap>(48)

    fun peek(path: String): Bitmap? = cache.get(path)

    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(file.path)?.let { return@withContext it }
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
        cache.put(file.path, scaled)
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
