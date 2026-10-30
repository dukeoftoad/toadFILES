package com.toadfiles.app

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.toadfiles.app.data.ContentUrisHelper
import com.toadfiles.app.domain.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Content URIs built for MediaStore entries must target the right collection per
 * category. The generic branch is asserted against the platform API — not a
 * hardcoded string — so the exact row URI is content://media/external/file/<id>
 * (MediaStore.Files.getContentUri(volume, rowId)); the old, wrong shape was
 * content://media/external/files/media/<id>.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentUrisHelperTest {

    @Test
    fun `images map to the images collection`() {
        assertEquals(
            Uri.parse("content://media/external/images/media/42"),
            ContentUrisHelper.uriFor(FileCategory.IMAGES, 42),
        )
    }

    @Test
    fun `videos map to the video collection`() {
        assertEquals(
            Uri.parse("content://media/external/video/media/7"),
            ContentUrisHelper.uriFor(FileCategory.VIDEOS, 7),
        )
    }

    @Test
    fun `audio maps to the audio collection`() {
        assertEquals(
            Uri.parse("content://media/external/audio/media/9"),
            ContentUrisHelper.uriFor(FileCategory.AUDIO, 9),
        )
    }

    @Test
    fun `docs archives and apks map to a row of the generic files table`() {
        for (c in listOf(FileCategory.DOCS, FileCategory.ARCHIVES, FileCategory.APKS)) {
            assertEquals(
                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), 123),
                ContentUrisHelper.uriFor(c, 123),
            )
        }
    }

    @Test
    fun `generic branch produces file row uris not the old files-media shape`() {
        val uri = ContentUrisHelper.uriFor(FileCategory.DOCS, 123).toString()
        assertTrue("unexpected files row uri: $uri", uri.startsWith("content://media/external/file/123"))
        assertFalse("old wrong shape leaked through: $uri", uri.contains("files/media"))
    }
}
