package com.toadfiles.app.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/**
 * One OTA download, described without Android types so ViewModel tests can fake the
 * whole backend without touching the real system service.
 */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val title: String,
    val description: String,
)

/**
 * A snapshot of an in-flight OTA download, reported on every poll of the system job.
 * [bytesSoFar]/[totalBytes] come straight from DownloadManager's cursor, where -1
 * means "unknown" — normalized here to 0 so arithmetic stays honest. [fraction] is
 * null whenever the total size is unknown (GitHub always sends Content-Length, but
 * a proxy may strip it): the UI then shows an indeterminate bar instead of a lie.
 */
data class DownloadProgress(val bytesSoFar: Long, val totalBytes: Long) {
    /** 0..1, or null when the total is unknown / the numbers don't add up. */
    val fraction: Float?
        get() {
            if (totalBytes <= 0 || bytesSoFar < 0) return null
            return (bytesSoFar.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }

    companion object {
        val UNKNOWN = DownloadProgress(bytesSoFar = 0, totalBytes = -1)
    }
}

/**
 * Download backend for the self-update flow, behind an interface so AboutViewModel is
 * unit-testable with a fake — the real DownloadManager is an Android system service
 * and is deliberately not unit-tested here.
 *
 * PRIVACY STANCE: used only when the user taps "Download & install" on the About
 * screen; the request goes to the GitHub release asset URL and nowhere else. Progress
 * is shown by DownloadManager's own notification AND, since v2.6.0, by the About
 * panel itself: the watch loop below already polls the system job every 500 ms, and
 * now also reads the byte counters off the same cursor — no second poller, no extra
 * network visibility, no progress engine of our own.
 */
interface UpdateDownloader {
    /**
     * Enqueues the download and returns its DownloadManager id. Throws [IOException]
     * when the download service or external storage is unavailable.
     */
    fun enqueue(context: Context, request: DownloadRequest): Long

    /**
     * Suspends until download [id] completes, then returns its content URI
     * (content://downloads/…) ready to be handed to the package installer.
     * Returns null when the download failed or did not finish within the timeout
     * (the system job itself keeps running — its notification still reports progress).
     *
     * [onProgress] fires on every poll tick while the download runs (at most every
     * [SystemUpdateDownloader.POLL_INTERVAL_MS], never on the completion tick); it is
     * invoked on the caller's dispatcher, so ViewModel callers can update state directly.
     */
    suspend fun awaitDownload(
        context: Context,
        id: Long,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Uri?
}

/**
 * Real backend: the system [DownloadManager]. Needs no storage permission — the APK
 * lands in the app-private external files dir, which is exactly why it later reaches
 * the installer as a DownloadManager content URI (a path in Android/data must not and
 * cannot go through the FileProvider/UriGuard route used for user-visible files).
 *
 * The destination is deleted before enqueueing because DownloadManager refuses to
 * overwrite an existing file — this is what makes a retry actually re-download.
 */
class SystemUpdateDownloader : UpdateDownloader {

    override fun enqueue(context: Context, request: DownloadRequest): Long {
        val dm = manager(context)
            ?: throw IOException("The system download service is unavailable on this device.")
        if (request.url.isBlank()) throw IOException("The release has no downloadable APK URL.")
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("External storage is not available for the update download.")
        val name = request.fileName.takeLastWhile { it != '/' && it != '\\' }.ifBlank { DEFAULT_FILE_NAME }
        val dest = File(dir, name)
        if (dest.exists() && !dest.delete()) throw IOException("Could not prepare the download destination.")
        val req = DownloadManager.Request(Uri.parse(request.url)).apply {
            setTitle(request.title)
            setDescription(request.description)
            setMimeType(APK_MIME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(dest))
            // User-initiated: honor the tap on whatever network the user is on.
            setAllowedOverMetered(true)
        }
        return dm.enqueue(req)
    }

    override suspend fun awaitDownload(
        context: Context,
        id: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): Uri? {
        val dm = manager(context) ?: return null
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val snapshot = statusOf(dm, id)
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> return dm.getUriForDownloadedFile(id)
                DownloadManager.STATUS_FAILED -> return null
                // RUNNING / PAUSED / PENDING: report the byte counters so the in-app
                // bar tracks the same job the notification shade mirrors.
                else -> onProgress(snapshot.progress)
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun manager(context: Context): DownloadManager? =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    /**
     * One poll = one cursor row = status AND byte counters read together, so the UI
     * never sees a status older than its bytes. (status, bytes, total) maps 1:1 onto
     * [Snapshot], whose math is pinned by UpdateDownloaderTest without any service.
     */
    private fun statusOf(dm: DownloadManager, id: Long): Snapshot =
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                Snapshot(
                    status = c.columnInt(DownloadManager.COLUMN_STATUS),
                    bytesSoFar = c.columnLong(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    totalBytes = c.columnLong(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
            } else {
                // Row vanished (job cancelled underneath us): report a running-but-empty
                // tick so the UI keeps its indeterminate bar until the next poll decides.
                Snapshot(DownloadManager.STATUS_RUNNING, 0, -1)
            }
        } ?: Snapshot(DownloadManager.STATUS_RUNNING, 0, -1)

    /** Cursor row of a download job, without android.database types in the signature. */
    private data class Snapshot(val status: Int, val bytesSoFar: Long, val totalBytes: Long) {
        val progress: DownloadProgress
            get() = DownloadProgress(
                bytesSoFar = bytesSoFar.coerceAtLeast(0),
                totalBytes = totalBytes,
            )
    }

    private fun android.database.Cursor.columnInt(name: String): Int {
        val idx = getColumnIndex(name)
        return if (idx >= 0) getInt(idx) else 0
    }

    private fun android.database.Cursor.columnLong(name: String): Long {
        val idx = getColumnIndex(name)
        return if (idx >= 0) getLong(idx) else -1
    }

    internal companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val DEFAULT_FILE_NAME = "toadfiles-update.apk"

        /** Watch-loop bound; DownloadManager keeps downloading regardless if we give up. */
        const val POLL_INTERVAL_MS = 500L
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}
