package com.toadfiles.app

import android.provider.MediaStore
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recent-tab type recognition (v1.11 fix). Rows MediaStore leaves untyped
 * (MEDIA_TYPE_NONE — APKs, archives) used to collapse into "Docs", so an APK
 * matched no type filter it belongs to; and with a blank/generic mime it resolved
 * through the open-with sheet WITHOUT its real handler — the package installer
 * (owner report: "APK file unrecognised in Recent"). Both helpers reuse existing
 * logic on purpose: [FilterMode.matches] is the same predicate the type filters
 * use, and [Intents.mimeOf] is the same mapping every open in the app uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaRepositoryRecentTypesTest {

    // ---------- categoryForRecent ----------

    @Test
    fun `media buckets keep their categories`() {
        assertEquals(FileCategory.IMAGES, MediaRepository.categoryForRecent(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, "x.jpg"))
        assertEquals(FileCategory.VIDEOS, MediaRepository.categoryForRecent(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, "x.mp4"))
        assertEquals(FileCategory.AUDIO, MediaRepository.categoryForRecent(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO, "x.mp3"))
        // A DOCUMENT-typed plain file stays Docs.
        assertEquals(FileCategory.DOCS, MediaRepository.categoryForRecent(MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT, "notes.pdf"))
    }

    @Test
    fun `untyped rows are typed by extension, not lumped into Docs`() {
        val none = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
        assertEquals(FileCategory.APKS, MediaRepository.categoryForRecent(none, "toadfiles.apk"))
        assertEquals(FileCategory.APKS, MediaRepository.categoryForRecent(none, "APP.XAPK"))
        assertEquals(FileCategory.ARCHIVES, MediaRepository.categoryForRecent(none, "pack.zip"))
        assertEquals(FileCategory.ARCHIVES, MediaRepository.categoryForRecent(none, "backup.tar.gz"))
        assertEquals(FileCategory.DOCS, MediaRepository.categoryForRecent(none, "notes.txt"))
    }

    // ---------- recentMime ----------

    @Test
    fun `a real MediaStore mime is kept`() {
        assertEquals("image/jpeg", MediaRepository.recentMime("image/jpeg", "x.jpg"))
        assertEquals("application/pdf", MediaRepository.recentMime("application/PDF", "x.pdf"))
    }

    @Test
    fun `blank or generic mimes fall back to the extension mapping`() {
        // The installer only resolves for package-archive — this is the exact flaw
        // behind "APK file unrecognised in Recent".
        assertEquals(
            "application/vnd.android.package-archive",
            MediaRepository.recentMime(null, "app.apk"),
        )
        assertEquals(
            "application/vnd.android.package-archive",
            MediaRepository.recentMime("application/octet-stream", "app.apk"),
        )
        assertEquals("application/zip", MediaRepository.recentMime("", "pack.ZIP"))
        assertEquals("application/zip", MediaRepository.recentMime("  ", "pack.zip"))
    }

    @Test
    fun `unknown extensions still get an honest generic mime`() {
        assertEquals("application/octet-stream", MediaRepository.recentMime(null, "blob.weirdext"))
    }
}
