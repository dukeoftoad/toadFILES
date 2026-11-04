package com.toadfiles.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.util.IncomingIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Incoming "Open with toadFILES" handoff (v1.10.2): the resolver must turn the
 * handed-over VIEW uri into a real, readable file — file paths and MediaStore
 * `_data` rows directly, unknown providers as a cache stream copy — and never
 * invent a file it cannot read. The mime set here is the contract the manifest
 * filter advertises: it must stay honest (only what ArchiveOps can extract), no
 * octet-stream catch-all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingIntentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ---------- mime contract ----------

    @Test
    fun `archive mimes match the manifest filter`() {
        assertTrue(IncomingIntents.isArchiveMime("application/zip"))
        assertTrue(IncomingIntents.isArchiveMime("application/x-zip-compressed"))
        assertTrue(IncomingIntents.isArchiveMime("application/x-zip"))
        assertTrue(IncomingIntents.isArchiveMime("application/x-compressed"))
        assertTrue(IncomingIntents.isArchiveMime("multipart/x-zip"))
        assertTrue(IncomingIntents.isArchiveMime("application/x-7z-compressed"))
        // Providers hand the sheet's mime through in any casing.
        assertTrue(IncomingIntents.isArchiveMime("APPLICATION/ZIP"))
    }

    @Test
    fun `non-archive mimes are rejected`() {
        assertFalse(IncomingIntents.isArchiveMime("application/vnd.android.package-archive"))
        assertFalse(IncomingIntents.isArchiveMime("image/png"))
        assertFalse(IncomingIntents.isArchiveMime("application/octet-stream"))
        assertFalse(IncomingIntents.isArchiveMime(""))
        assertFalse(IncomingIntents.isArchiveMime(null))
    }

    // ---------- intent extraction ----------

    @Test
    fun `view data uri is extracted`() {
        val uri = Uri.parse("content://media/external/file/42")
        assertEquals(uri, IncomingIntents.extractViewUri(Intent(Intent.ACTION_VIEW, uri)))
    }

    @Test
    fun `plain launches yield no uri`() {
        assertNull(IncomingIntents.extractViewUri(null))
        assertNull(IncomingIntents.extractViewUri(Intent(Intent.ACTION_MAIN)))
        assertNull(IncomingIntents.extractViewUri(Intent()))
    }

    // ---------- name sanitizing ----------

    @Test
    fun `display names are reduced to a safe file name`() {
        assertEquals("a.zip", IncomingIntents.sanitizeName("/etc/passwd/../a.zip", "application/zip"))
        assertEquals("a.zip", IncomingIntents.sanitizeName("..\\a.zip", "application/zip"))
        assertEquals("a.zip", IncomingIntents.sanitizeName(" a.zip ", "application/zip"))
        assertEquals("fallback.zip", IncomingIntents.sanitizeName("fallback.zip", "application/zip"))
    }

    @Test
    fun `unusable display names fall back per mime`() {
        assertEquals("archive.zip", IncomingIntents.sanitizeName(null, "application/zip"))
        assertEquals("archive.zip", IncomingIntents.sanitizeName("", "application/zip"))
        assertEquals("archive.zip", IncomingIntents.sanitizeName("..", "application/zip"))
        assertEquals("archive.7z", IncomingIntents.sanitizeName(null, "application/x-7z-compressed"))
        assertEquals("archive.zip", IncomingIntents.sanitizeName(null, null))
    }

    // ---------- uri resolution ----------

    @Test
    fun `file scheme resolves a real file and rejects a missing one`() {
        val dir = File(context.cacheDir, "resolve-test").apply { mkdirs() }
        val f = File(dir, "real.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ok = IncomingIntents.resolveArchive(context, Uri.fromFile(f))
        assertEquals(f.absolutePath, ok?.file?.absolutePath)
        assertEquals(false, ok?.fromStream)
        assertNull(IncomingIntents.resolveArchive(context, Uri.fromFile(File(dir, "gone.zip"))))
    }

    @Test
    fun `unsupported schemes are refused`() {
        assertNull(IncomingIntents.resolveArchive(context, Uri.parse("ftp://host/x.zip")))
    }

    @Test
    fun `content uri with a resolvable _data row maps to the real file`() {
        val dir = File(context.cacheDir, "resolve-test").apply { mkdirs() }
        val f = File(dir, "from-media.zip").apply { writeBytes(ByteArray(16) { 7 }) }
        val uri = Uri.parse("content://media/external/file/99")
        // Simulate the MediaStore file provider the way real "Open with" handoffs
        // arrive: a content uri whose row carries the on-disk path in _data.
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            "media",
            object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun query(
                    uri: Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    args: Array<out String>?,
                    order: String?,
                ): android.database.Cursor =
                    android.database.MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf(f.absolutePath)) }
                override fun getType(uri: Uri): String? = "application/zip"
                override fun insert(uri: Uri, values: android.content.ContentValues?) = null
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
                override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
            },
        )
        val resolved = IncomingIntents.resolveArchive(context, uri)
        assertEquals(f.absolutePath, resolved?.file?.absolutePath)
        assertEquals(false, resolved?.fromStream)
    }

    @Test
    fun `content uri without a file row is copied into the cache`() {
        val uri = Uri.parse("content://some/attachment/1")
        val payload = "PK fake zip bytes".toByteArray()
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(payload))
        val resolved = IncomingIntents.resolveArchive(context, uri)
        assertTrue(resolved?.fromStream == true)
        assertTrue(resolved?.file?.canonicalPath?.startsWith(context.cacheDir.canonicalPath) == true)
        assertTrue(payload.contentEquals(resolved!!.file.readBytes()))
        // No display-name row registered — mime-implied fallback naming.
        assertEquals("archive.zip", resolved.file.name)
    }

    @Test
    fun `a dead stream copy is reported as failure, not as an empty file`() {
        val uri = Uri.parse("content://some/attachment/2")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(0)))
        assertNull(IncomingIntents.resolveArchive(context, uri))
        // The husk is gone from the incoming dir.
        assertTrue(File(context.cacheDir, "incoming").listFiles().isNullOrEmpty())
    }

    @Test
    fun `default extract destination is the public Download folder`() {
        assertTrue(IncomingIntents.defaultExtractDest().absolutePath.endsWith("/Download"))
    }
}
