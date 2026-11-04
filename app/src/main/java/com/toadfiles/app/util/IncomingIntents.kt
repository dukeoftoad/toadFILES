package com.toadfiles.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Incoming "Open with toadFILES" handoff (v1.10.2).
 *
 * The manifest registers MainActivity for ACTION_VIEW on exactly the archive types
 * the app can extract (zip family + 7z — see [ARCHIVE_MIMES], keep in sync with the
 * manifest filter), so toadFILES finally appears in the system "Open with" sheet for
 * archives. This object turns the handed-over uri into a real [File] the existing
 * archive flow can act on.
 *
 * Resolution order (most accurate first):
 *  1. `file:` scheme — the path itself.
 *  2. `content:` uri whose `_data` column resolves to a real file (MediaStore rows
 *     carry it for file-backed items; the app holds All-files access, so the path
 *     is directly readable — the common case for "Open with" out of Downloads).
 *  3. Stream copy into `cacheDir/incoming` — the honest fallback for cloud or
 *     attachment streams that exist nowhere on disk. [ResolvedArchive.fromStream]
 *     is reported so the caller routes extraction to a user-visible destination
 *     (Download) instead of the copy's cache parent.
 *
 * Every step fails soft: a vanished file, a blocked stream or an unusable provider
 * row returns null and the caller says so — nothing pretends to succeed.
 */
object IncomingIntents {

    /** The archive mimes MainActivity declares in its VIEW intent-filter. */
    val ARCHIVE_MIMES = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-zip",
        "application/x-compressed",
        "multipart/x-zip",
        "application/x-7z-compressed",
    )

    /** True when [mime] is one of the archive types the app advertises. */
    fun isArchiveMime(mime: String?): Boolean = mime?.lowercase() in ARCHIVE_MIMES

    /** The VIEW data uri this activity was launched with; null for plain launches. */
    fun extractViewUri(intent: Intent?): Uri? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        return intent.data
    }

    /** Where the incoming uri came from after [resolveArchive] succeeded. */
    data class ResolvedArchive(val file: File, val fromStream: Boolean)

    /**
     * Resolves a VIEW uri to a real, readable file — see the class doc for the order.
     * Returns null only when nothing could be read (vanished file, blocked stream).
     * Blocking work (query + possible stream copy) belongs on a dispatcher.
     */
    fun resolveArchive(context: Context, uri: Uri): ResolvedArchive? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val f = File(path)
            return if (f.isFile) ResolvedArchive(f, fromStream = false) else null
        }
        if (uri.scheme == "content") {
            dataColumn(context, uri)?.let { path ->
                val f = File(path)
                if (f.isFile && f.canRead()) return ResolvedArchive(f, fromStream = false)
            }
            return copyToCache(context, uri)
        }
        return null
    }

    /**
     * User-visible default destination for stream-copied archives: the public
     * Download folder. The copy itself never leaves the cache; only the extraction
     * lands here, inside a folder named after the archive.
     */
    fun defaultExtractDest(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /**
     * [displayName] as a safe single-path-segment file name. Falls back to a
     * mime-implied "archive.<ext>" when the provider gives nothing usable —
     * traversal fragments, empty strings and dot names never become files.
     */
    fun sanitizeName(displayName: String?, mime: String?): String {
        val cleaned = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        if (cleaned != null) return cleaned
        val ext = if (mime?.lowercase() == "application/x-7z-compressed") "7z" else "zip"
        return "archive.$ext"
    }

    /** Best-effort `_data` read; null on every failure mode (no row, no column, veto). */
    private fun dataColumn(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun copyToCache(context: Context, uri: Uri): ResolvedArchive? = try {
        val safe = sanitizeName(
            queryDisplayName(context, uri),
            context.contentResolver.getType(uri),
        )
        val incoming = File(context.cacheDir, "incoming").apply { mkdirs() }
        // Open-ended, the incoming dir would grow without bound (every attachment is
        // a fresh copy); clear copies older than an hour. An in-flight extraction of
        // an old copy keeps reading through its own open handle on Linux, so a same-
        // name hit is harmless.
        val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
        incoming.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        val target = File(incoming, if (File(incoming, safe).exists()) "open-${System.nanoTime()}-$safe" else safe)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        } ?: return null
        if (target.length() == 0L) {
            // A zip is never empty (EOCD alone is 22 bytes); 0 bytes = the provider
            // handed us a dead stream — delete the husk and report failure.
            target.delete()
            null
        } else {
            ResolvedArchive(target, fromStream = true)
        }
    } catch (_: Exception) {
        null
    }
}
