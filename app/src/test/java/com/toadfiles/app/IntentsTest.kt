package com.toadfiles.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
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
import java.io.File

/**
 * Open/share/install handoff checks on the JVM: the FileProvider must resolve real paths
 * (including SD-card / OTG volumes), and the intents that leave the app must carry a
 * content URI, the right mime, and read grants — otherwise receiving apps silently
 * get nothing. Started from a real Activity context because Application-context
 * startActivity is not a realistic harness on Robolectric.
 *
 * v1.5 semantics: open uses a DIRECT ACTION_VIEW launch (the system resolver provides
 * "Just once / Always"), share uses ACTION_SEND / ACTION_SEND_MULTIPLE behind a chooser,
 * and install gates on the "Install unknown apps" grant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntentsTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.setupActivity(Activity::class.java)
    }

    private fun lastStartedIntent(): Intent = Shadows.shadowOf(activity).nextStartedActivity

    @Test
    fun `mime mapping covers apk zip and unknown extensions`() {
        assertEquals("application/vnd.android.package-archive", Intents.mimeOf(File("/x/app.apk")))
        assertEquals("application/zip", Intents.mimeOf(File("/x/archive.zip")))
        assertEquals("image/png", Intents.mimeOf(File("/x/pic.png")))
        assertEquals("application/octet-stream", Intents.mimeOf(File("/x/blob.weirdext")))
        assertEquals("application/octet-stream", Intents.mimeOf(File("/x/noext")))
    }

    @Test
    fun `content uri resolves real files under external storage`() {
        // A file that actually exists inside the Robolectric external-storage root —
        // the same path the external-path entry in file_paths.xml maps to.
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val f = File(root, "DCIM/intents_uri_test.jpg").apply { parentFile.mkdirs(); writeText("x") }
        try {
            val uri = Intents.contentUri(appContext, f)
            assertTrue(uri.toString().startsWith("content://"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `content uri resolves files on secondary volumes too`() {
        // /storage/XXXX-XXXX is an SD card / OTG root. file_paths.xml carries a root-path
        // catch-all exactly so this doesn't throw "Failed to find configured root" —
        // tapping a photo on an SD card used to crash the app before it existed.
        val uri = Intents.contentUri(appContext, File("/storage/0000-0001/DCIM/photo.jpg"))
        assertTrue(uri.toString().startsWith("content://"))
        assertTrue(uri.toString().endsWith("/storage/0000-0001/DCIM/photo.jpg"))
    }

    @Test
    fun `openFile launches a direct ACTION_VIEW with grants for just-once-always`() {
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val f = File(root, "intents_open_me.txt").apply { writeText("open me") }
        try {
            assertTrue(Intents.openFile(activity, f))
            // Direct launch — NOT a chooser. The system resolver shows "Just once / Always".
            val view = lastStartedIntent()
            assertEquals(Intent.ACTION_VIEW, view.action)
            assertEquals("text/plain", view.type)
            assertTrue(view.data.toString().startsWith("content://"))
            assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertNotNull(view.clipData)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `openWith still wraps in a chooser`() {
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val f = File(root, "intents_open_with.txt").apply { writeText("open with me") }
        try {
            assertTrue(Intents.openWith(activity, f))
            val chooser = lastStartedIntent()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_VIEW, view.action)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `shareFiles sends one file via ACTION_SEND with a granted stream`() {
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val f = File(root, "intents_share_me.txt").apply { writeText("share me") }
        try {
            assertTrue(Intents.shareFiles(activity, listOf(f)))
            val chooser = lastStartedIntent()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            @Suppress("DEPRECATION")
            val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertTrue(stream.toString().startsWith("content://"))
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertNotNull(send.clipData)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `shareFiles sends several files via ACTION_SEND_MULTIPLE`() {
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val a = File(root, "share_a.png").apply { writeText("a") }
        val b = File(root, "share_b.png").apply { writeText("b") }
        try {
            assertTrue(Intents.shareFiles(activity, listOf(a, b)))
            val chooser = lastStartedIntent()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
            assertEquals("image/png", send.type) // both png -> the exact common type
            @Suppress("DEPRECATION")
            val streams = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(2, streams.size)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            a.delete(); b.delete()
        }
    }

    @Test
    fun `shareUris shares a media-store uri behind the chooser`() {
        val uri = Uri.parse("content://media/external/images/media/42")
        assertTrue(Intents.shareUris(activity, listOf(uri), "image/jpeg"))
        val chooser = lastStartedIntent()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `shareFiles with nothing shareable returns false instead of crashing`() {
        assertFalse(Intents.shareFiles(activity, emptyList()))
        assertFalse(Intents.shareUris(activity, emptyList(), "*/*"))
    }

    @Test
    fun `installApk gates on the unknown-apps permission`() {
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val apk = File(root, "intents_installer.apk").apply { writeText("apk bytes") }
        try {
            val result = Intents.installApk(activity, apk)
            if (Intents.hasInstallPermission(activity)) {
                // Permission granted: the installer must have been launched directly.
                assertEquals(Intents.InstallResult.STARTED, result)
                val view = lastStartedIntent()
                assertEquals(Intent.ACTION_VIEW, view.action)
                assertEquals("application/vnd.android.package-archive", view.type)
                assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            } else {
                // Permission missing: the user is walked to the exact settings toggle.
                assertEquals(Intents.InstallResult.NEEDS_PERMISSION, result)
                val started = lastStartedIntent()
                assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.action)
                assertTrue(started.data.toString().endsWith(activity.packageName))
            }
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `openFile refuses missing files instead of crashing on the provider`() {
        assertFalse(Intents.openFile(activity, File("/storage/emulated/0/definitely-missing.bin")))
        assertFalse(Intents.openWith(activity, File("/storage/emulated/0/definitely-missing.bin")))
        assertEquals(
            Intents.InstallResult.FAILED,
            Intents.installApk(activity, File("/storage/emulated/0/definitely-missing.apk")),
        )
    }

    @Test
    fun `copyPathToClipboard puts the absolute path on the clipboard`() {
        val f = File("/storage/emulated/0/Download/clip.txt")
        assertTrue(Intents.copyPathToClipboard(appContext, f))
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals(f.absolutePath, cm.primaryClip!!.getItemAt(0).text.toString())
    }
}
