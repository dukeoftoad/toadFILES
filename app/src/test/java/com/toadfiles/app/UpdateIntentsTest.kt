package com.toadfiles.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.toadfiles.app.util.Intents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The two Intents helpers the self-update flow leans on:
 *  - [Intents.installApkFromUri] must behave exactly like the tapped-APK path
 *    ([Intents.installApk]): same "Install unknown apps" gate, same ACTION_VIEW
 *    installer intent with a read grant on the DownloadManager content URI;
 *  - [Intents.openUrl] must launch a plain browser ACTION_VIEW for web URLs only.
 *
 * Started from a real Activity context like [IntentsTest] (Application-context
 * startActivity is not a realistic harness on Robolectric); the permission-granted
 * vs. missing branches mirror the existing install tests because the shadow's
 * default grant is an implementation detail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateIntentsTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.setupActivity(Activity::class.java)
    }

    private fun lastStartedIntent(): Intent = Shadows.shadowOf(activity).nextStartedActivity

    @Test
    fun `installApkFromUri hands a download-manager style uri to the installer or walks to settings`() {
        val uri = Uri.parse("content://downloads/all_downloads/42")
        val result = Intents.installApkFromUri(activity, uri)
        if (Intents.hasInstallPermission(activity)) {
            // Permission granted: the installer must be launched directly, no chooser.
            assertEquals(Intents.InstallResult.STARTED, result)
            val view = lastStartedIntent()
            assertEquals(Intent.ACTION_VIEW, view.action)
            assertEquals(uri, view.data)
            assertEquals("application/vnd.android.package-archive", view.type)
            assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertNotNull(view.clipData)
        } else {
            // Permission missing: the user is walked to the exact settings toggle.
            assertEquals(Intents.InstallResult.NEEDS_PERMISSION, result)
            val started = lastStartedIntent()
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.action)
            assertTrue(started.data.toString().endsWith(activity.packageName))
        }
    }

    @Test
    fun `installApkFromUri keeps the same outcome contract as installApk for missing files`() {
        // A missing file short-circuits in installApk before any intent; the URI twin
        // has no file to check, so it must still honor the permission gate honestly.
        val result = Intents.installApkFromUri(activity, Uri.parse("content://downloads/all_downloads/7"))
        assertTrue(
            result == Intents.InstallResult.NEEDS_PERMISSION ||
                result == Intents.InstallResult.STARTED,
        )
    }

    @Test
    fun `openUrl launches a plain browser ACTION_VIEW for https links`() {
        val url = "https://github.com/dukeoftoad/toadFILES/releases"
        assertTrue(Intents.openUrl(activity, url))
        val view = lastStartedIntent()
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(Uri.parse(url), view.data)
    }

    @Test
    fun `openUrl refuses non-web schemes instead of launching them`() {
        assertFalse(Intents.openUrl(activity, "file:///data/data/com.toadfiles.app/secret.db"))
        assertFalse(Intents.openUrl(activity, "content://media/external/images/media/1"))
        assertFalse(Intents.openUrl(activity, "not a url"))
        assertFalse(Intents.openUrl(activity, ""))
    }
}
