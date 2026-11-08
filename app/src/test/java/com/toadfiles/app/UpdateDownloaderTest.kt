package com.toadfiles.app

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.DownloadProgress
import com.toadfiles.app.data.DownloadRequest
import com.toadfiles.app.data.SystemUpdateDownloader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Audit A11 (v2.1.1): the self-update install path — [SystemUpdateDownloader] — had
 * zero tests; only its interface was faked elsewhere. These pin the enqueue contract
 * (request fields, the delete-before-enqueue retry rule, the blank-URL guard) and the
 * awaitDownload status mapping, using Robolectric's ShadowDownloadManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateDownloaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val downloader = SystemUpdateDownloader()

    private fun request() = DownloadRequest(
        url = "https://github.com/dukeoftoad/toadFILES/releases/download/v2.1.0/toadfiles-v2.1.0.apk",
        fileName = "toadfiles-v2.1.0.apk",
        title = "toadFILES v2.1.0",
        description = "Self-update download",
    )

    private fun dm(): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    @Test
    fun `enqueue maps the request onto the system DownloadManager`() {
        val id = downloader.enqueue(context, request())
        assertTrue("enqueue must return a usable download id", id >= 0)

        val req = Shadows.shadowOf(Shadows.shadowOf(dm()).getRequest(id))
        assertEquals("toadFILES v2.1.0", req.title)
        assertEquals("Self-update download", req.description)
        assertEquals("application/vnd.android.package-archive", req.mimeType)
        // User-initiated: metered networks are allowed on purpose.
        assertTrue(req.allowedOverMetered)
        assertEquals(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            req.notificationVisibility,
        )
        assertEquals(
            "https://github.com/dukeoftoad/toadFILES/releases/download/v2.1.0/toadfiles-v2.1.0.apk",
            req.uri.toString(),
        )
        assertTrue(
            "the destination must be the requested file name",
            req.destination.toString().endsWith("toadfiles-v2.1.0.apk"),
        )
    }

    @Test
    fun `enqueue deletes a stale destination so a retry re-downloads`() {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        val stale = File(dir, "toadfiles-v2.1.0.apk")
        stale.parentFile?.mkdirs()
        stale.writeText("stale partial from a previous attempt")
        assertTrue(stale.exists())

        downloader.enqueue(context, request())

        assertFalse(
            "DownloadManager refuses to overwrite — the stale file must be deleted first",
            stale.exists(),
        )
    }

    @Test
    fun `enqueue refuses a blank url honestly`() {
        try {
            downloader.enqueue(context, request().copy(url = "   "))
            fail("a release with no downloadable APK URL must throw IOException")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test
    fun `awaitDownload surfaces the uri when the system job reports success`() = runTest {
        val id = downloader.enqueue(context, request())
        Shadows.shadowOf(Shadows.shadowOf(dm()).getRequest(id))
            .setStatus(DownloadManager.STATUS_SUCCESSFUL)

        val uri = downloader.awaitDownload(context, id)
        assertNotNull("a successful download must hand its uri to the installer", uri)
    }

    @Test
    fun `awaitDownload returns null when the system job reports failure`() = runTest {
        val id = downloader.enqueue(context, request())
        Shadows.shadowOf(Shadows.shadowOf(dm()).getRequest(id))
            .setStatus(DownloadManager.STATUS_FAILED)

        assertNull("a failed download must not surface an installer uri", downloader.awaitDownload(context, id))
    }

    @Test
    fun `awaitDownload does not report progress on an already-finished job`() = runTest {
        val id = downloader.enqueue(context, request())
        Shadows.shadowOf(Shadows.shadowOf(dm()).getRequest(id))
            .setStatus(DownloadManager.STATUS_SUCCESSFUL)

        var ticks = 0
        downloader.awaitDownload(context, id) { ticks++ }
        assertEquals("success/failure ticks carry no progress event", 0, ticks)
    }

    // ---------- v2.6.0: the progress math the in-app bar is built on ----------

    @Test
    fun `progress fraction maps bytes onto a 0-1 range`() {
        assertEquals(0f, DownloadProgress(0, 10_000).fraction!!, 0.0001f)
        assertEquals(0.25f, DownloadProgress(2_500, 10_000).fraction!!, 0.0001f)
        assertEquals(1f, DownloadProgress(10_000, 10_000).fraction!!, 0.0001f)
    }

    @Test
    fun `progress fraction is clamped when the counters disagree`() {
        // DownloadManager can briefly report more-so-far-than-total mid-restart.
        assertEquals(1f, DownloadProgress(11_000, 10_000).fraction!!, 0.0001f)
    }

    @Test
    fun `progress fraction is null whenever the numbers are unknown`() {
        assertNull("total -1 (no Content-Length) must be indeterminate", DownloadProgress(1_024, -1).fraction)
        assertNull("total 0 is never a division denominator", DownloadProgress(1_024, 0).fraction)
        assertNull("bytes -1 is unknown too", DownloadProgress(-1, 10_000).fraction)
        assertNull("all-unknown row", DownloadProgress(-1, -1).fraction)
    }
}
