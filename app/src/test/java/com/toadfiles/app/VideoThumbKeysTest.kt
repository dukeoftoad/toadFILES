package com.toadfiles.app

import com.toadfiles.app.util.VideoThumbKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Video thumbnails (audit P3.2): the cache key must be bound to the file's actual
 * content identity (mtime + size), so a video replaced in place produces a new key
 * and the old cached frame can never be served for the new content.
 */
class VideoThumbKeysTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `identityStamp is stable while the file is untouched`() {
        val f = tmp.newFile("clip.mp4").apply { writeBytes(ByteArray(128)) }
        assertEquals(VideoThumbKeys.identityStamp(f), VideoThumbKeys.identityStamp(f))
    }

    @Test
    fun `identityStamp changes when the file grows or shrinks`() {
        val f = tmp.newFile("clip.mp4").apply { writeBytes(ByteArray(64)) }
        val before = VideoThumbKeys.identityStamp(f)
        f.writeBytes(ByteArray(256))
        assertNotEquals(before, VideoThumbKeys.identityStamp(f))
        f.writeBytes(ByteArray(0))
        assertNotEquals(before, VideoThumbKeys.identityStamp(f))
    }

    @Test
    fun `identityStamp changes when only the mtime changes`() {
        val f = tmp.newFile("clip.mp4").apply { writeBytes(ByteArray(64)) }
        val before = VideoThumbKeys.identityStamp(f)
        // Same size, timestamp pushed a minute into the future so the difference
        // survives even coarse (1-second) filesystem mtime resolution.
        f.setLastModified(System.currentTimeMillis() + 60_000)
        assertNotEquals(before, VideoThumbKeys.identityStamp(f))
    }

    @Test
    fun `identityStamp reports missing for a nonexistent file`() {
        assertEquals("missing", VideoThumbKeys.identityStamp(File(tmp.root, "gone.mp4")))
    }

    @Test
    fun `cacheKey separates replaced files with the same path`() {
        val path = "/sdcard/DCIM/clip.mp4"
        val oldKey = VideoThumbKeys.cacheKey(path, "1710000000000:512")
        val newKey = VideoThumbKeys.cacheKey(path, "1710000060000:1024")
        val missingKey = VideoThumbKeys.cacheKey(path, "missing")
        assertNotEquals(oldKey, newKey)
        assertNotEquals(oldKey, missingKey)
        // Identical inputs must map to one stable key so hits actually hit.
        assertEquals(oldKey, VideoThumbKeys.cacheKey(path, "1710000000000:512"))
    }

    @Test
    fun `cacheKey keeps distinct paths distinct`() {
        val stamp = "1710000000000:512"
        assertNotEquals(
            VideoThumbKeys.cacheKey("/a/clip.mp4", stamp),
            VideoThumbKeys.cacheKey("/b/clip.mp4", stamp),
        )
    }
}
