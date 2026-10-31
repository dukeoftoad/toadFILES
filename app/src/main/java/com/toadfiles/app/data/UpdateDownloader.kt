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
 * Download backend for the self-update flow, behind an interface so AboutViewModel is
 * unit-testable with a fake — the real DownloadManager is an Android system service
 * and is deliberately not unit-tested here.
 *
 * PRIVACY STANCE: used only when the user taps "Download & install" on the About
 * screen; the request goes to the GitHub release asset URL and nowhere else. Progress
 * is shown by DownloadManager's own notification — toadFILES builds no progress engine
 * of its own and does not poll the network itself beyond watching the system job.
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
     */
    suspend fun awaitDownload(context: Context, id: Long): Uri?
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

    override suspend fun awaitDownload(context: Context, id: Long): Uri? {
        val dm = manager(context) ?: return null
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (statusOf(dm, id)) {
                DownloadManager.STATUS_SUCCESSFUL -> return dm.getUriForDownloadedFile(id)
                DownloadManager.STATUS_FAILED -> return null
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun manager(context: Context): DownloadManager? =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    private fun statusOf(dm: DownloadManager, id: Long): Int? =
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (idx >= 0) c.getInt(idx) else null
            } else {
                null
            }
        }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val DEFAULT_FILE_NAME = "toadfiles-update.apk"

        /** Watch-loop bound; DownloadManager keeps downloading regardless if we give up. */
        const val POLL_INTERVAL_MS = 500L
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}
