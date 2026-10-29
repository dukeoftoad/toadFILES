package com.toadfiles.app

import android.net.Uri
import com.toadfiles.app.data.ContentUrisHelper
import com.toadfiles.app.domain.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Content URIs built for MediaStore entries must target the right collection per category. */
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
    fun `docs archives and apks fall back to the generic files collection`() {
        for (c in listOf(FileCategory.DOCS, FileCategory.ARCHIVES, FileCategory.APKS)) {
            assertEquals(
                Uri.parse("content://media/external/files/media/123"),
                ContentUrisHelper.uriFor(c, 123),
            )
        }
    }
}
