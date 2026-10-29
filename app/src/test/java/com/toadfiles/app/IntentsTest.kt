package com.toadfiles.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.util.Intents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * Share/open handoff checks on the JVM: the FileProvider must resolve real paths
 * (including SD-card / OTG volumes) and share intents must carry read grants plus
 * ClipData, otherwise receiving apps silently get nothing. This is the regression
 * net for the "share doesn't work" class of bugs — they never show up until an
 * intent leaves the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun lastStartedIntent(): Intent =
        Shadows.shadowOf(context as Application).nextStartedActivity

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
            val uri = Intents.contentUri(context, f)
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
        val uri = Intents.contentUri(context, File("/storage/0000-0001/DCIM/photo.jpg"))
        assertTrue(uri.toString().startsWith("content://"))
        assertTrue(uri.toString().endsWith("/storage/0000-0001/DCIM/photo.jpg"))
    }

    @Test
    fun `sharing one file sends a granted read permission and clip data`() {
        val f = File("/storage/emulated/0/Download/note.txt")
        assertTrue(Intents.shareFiles(context, listOf(f)))

        val chooser = lastStartedIntent()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(send.clipData)
        assertEquals(1, send.clipData!!.itemCount)
        assertTrue(send.clipData!!.getItemAt(0).uri.toString().startsWith("content://"))
    }

    @Test
    fun `sharing several files uses send multiple with every uri`() {
        val files = listOf(
            File("/storage/emulated/0/a.txt"),
            File("/storage/emulated/0/b.txt"),
        )
        assertTrue(Intents.shareFiles(context, files))

        @Suppress("DEPRECATION")
        val send = lastStartedIntent().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        @Suppress("DEPRECATION")
        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(2, uris.size)
        // Mixed extensions both map to text/plain, so the intent stays precise
        assertEquals("text/plain", send.type)
    }

    @Test
    fun `sharing nothing is refused instead of launching an empty chooser`() {
        assertFalse(Intents.shareFiles(context, emptyList()))
    }

    @Test
    fun `openFile hands a chooser a granted, typed content uri`() {
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val f = File(root, "intents_open_me.txt").apply { writeText("open me") }
        try {
            assertTrue(Intents.openFile(context, f))
            val chooser = lastStartedIntent()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_VIEW, view.action)
            assertEquals("text/plain", view.type)
            assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `openFile refuses missing files instead of crashing on the provider`() {
        assertFalse(Intents.openFile(context, File("/storage/emulated/0/definitely-missing.bin")))
        assertFalse(Intents.openWith(context, File("/storage/emulated/0/definitely-missing.bin")))
        assertFalse(Intents.installApk(context, File("/storage/emulated/0/definitely-missing.apk")))
    }

    @Test
    fun `copyPathToClipboard puts the absolute path on the clipboard`() {
        val f = File("/storage/emulated/0/Download/clip.txt")
        assertTrue(Intents.copyPathToClipboard(context, f))
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals(f.absolutePath, cm.primaryClip!!.getItemAt(0).text.toString())
    }
}
