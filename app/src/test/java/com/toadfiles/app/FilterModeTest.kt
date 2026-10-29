package com.toadfiles.app

import com.toadfiles.app.domain.FilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterModeTest {

    @Test
    fun `ALL matches everything including files without extension`() {
        assertTrue(FilterMode.ALL.matches("README"))
        assertTrue(FilterMode.ALL.matches("photo.jpg"))
        assertTrue(FilterMode.ALL.matches(".hidden"))
        assertTrue(FilterMode.ALL.matches("no-extension-file"))
    }

    @Test
    fun `IMAGES matches known image extensions case-insensitively`() {
        for (name in listOf("a.jpg", "b.jpeg", "c.png", "d.gif", "e.webp", "f.bmp", "g.heic", "h.heif", "I.JPG", "J.Png")) {
            assertTrue("expected IMAGES to match $name", FilterMode.IMAGES.matches(name))
        }
        assertFalse(FilterMode.IMAGES.matches("notes.txt"))
        assertFalse(FilterMode.IMAGES.matches("clip.mp4"))
    }

    @Test
    fun `VIDEOS matches video extensions only`() {
        for (name in listOf("clip.mp4", "movie.mkv", "vid.avi", "rec.mov", "anim.webm", "old.3gp", "M4V FILE.m4v")) {
            assertTrue("expected VIDEOS to match $name", FilterMode.VIDEOS.matches(name))
        }
        assertFalse(FilterMode.VIDEOS.matches("song.mp3"))
    }

    @Test
    fun `AUDIO matches audio extensions only`() {
        for (name in listOf("song.mp3", "lossless.flac", "track.wav", "tone.ogg", "book.m4a", "ring.aac", "voice.opus", "memo.amr")) {
            assertTrue("expected AUDIO to match $name", FilterMode.AUDIO.matches(name))
        }
        assertFalse(FilterMode.AUDIO.matches("video.mp4"))
    }

    @Test
    fun `DOCS matches document extensions only`() {
        for (name in listOf("cv.pdf", "essay.doc", "essay2.docx", "budget.xls", "budget2.xlsx", "deck.ppt", "deck2.pptx", "notes.txt", "readme.md", "data.csv", "book.epub", "file.rtf", "letter.odt")) {
            assertTrue("expected DOCS to match $name", FilterMode.DOCS.matches(name))
        }
        assertFalse(FilterMode.DOCS.matches("photo.jpg"))
    }

    @Test
    fun `ARCHIVES matches archive extensions including tar gz multi-dot names`() {
        for (name in listOf("bundle.zip", "pack.rar", "set.7z", "src.tar", "log.gz", "backup.bz2", "pkg.xz")) {
            assertTrue("expected ARCHIVES to match $name", FilterMode.ARCHIVES.matches(name))
        }
        // Multi-dot: extension is everything after the last dot.
        assertTrue(FilterMode.ARCHIVES.matches("archive.tar.gz"))
        assertTrue(FilterMode.ARCHIVES.matches("archive.gz.zip")) // ext = zip
        assertFalse(FilterMode.ARCHIVES.matches("notes.txt"))
    }

    @Test
    fun `APKS matches apk apkm and xapk`() {
        for (name in listOf("app.apk", "bundle.xapk", "split.apkm", "App.APK")) {
            assertTrue("expected APKS to match $name", FilterMode.APKS.matches(name))
        }
        assertFalse(FilterMode.APKS.matches("app.zip"))
    }

    @Test
    fun `files without extension only match ALL`() {
        for (mode in FilterMode.entries - FilterMode.ALL) {
            assertFalse("expected $mode not to match extensionless file", mode.matches("LICENSE"))
        }
    }

    @Test
    fun `every mode has a non-blank label`() {
        for (mode in FilterMode.entries) {
            assertTrue(mode.label.isNotBlank())
        }
        assertEquals(7, FilterMode.entries.size)
    }
}
