package com.toadfiles.app

import com.toadfiles.app.domain.MAX_DISPLAY_EXTENSION_LENGTH
import com.toadfiles.app.domain.splitFileName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name/extension split behind FileNameText: long file names get their BASE cut
 * to the available space while the extension stays fully on screen. These tests pin
 * the split rules — the extension is the last dot-tail, hidden-file dots are not
 * extensions, and junk dot-tails (dates, trailing dots) stay part of the name.
 */
class FileNameSplitTest {

    @Test
    fun `plain extension splits off and keeps its dot`() {
        assertEquals("report" to ".pdf", splitFileName("report.pdf").let { it.base to it.extension })
    }

    @Test
    fun `last dot wins so compound extensions keep the tail`() {
        val parts = splitFileName("archive.tar.gz")
        assertEquals("archive.tar", parts.base)
        assertEquals(".gz", parts.extension)
    }

    @Test
    fun `multi-dot names keep the middle dots in the base`() {
        val parts = splitFileName("weird.name.file.zip")
        assertEquals("weird.name.file", parts.base)
        assertEquals(".zip", parts.extension)
    }

    @Test
    fun `leading dot is a hidden-file marker not an extension`() {
        val parts = splitFileName(".gitignore")
        assertEquals(".gitignore", parts.base)
        assertEquals("", parts.extension)
    }

    @Test
    fun `hidden file with a real extension still splits`() {
        val parts = splitFileName(".overflow_secret.txt")
        assertEquals(".overflow_secret", parts.base)
        assertEquals(".txt", parts.extension)
    }

    @Test
    fun `name without a dot has no extension`() {
        assertEquals("noext" to "", splitFileName("noext").let { it.base to it.extension })
    }

    @Test
    fun `trailing dot stays part of the name`() {
        val parts = splitFileName("file.")
        assertEquals("file.", parts.base)
        assertEquals("", parts.extension)
    }

    @Test
    fun `empty name splits trivially`() {
        assertEquals("" to "", splitFileName("").let { it.base to it.extension })
    }

    @Test
    fun `single character extension counts as one`() {
        val parts = splitFileName("a.b")
        assertEquals("a", parts.base)
        assertEquals(".b", parts.extension)
    }

    @Test
    fun `date-like dot tail longer than the cap stays part of the name`() {
        val parts = splitFileName("backup.2026-11-08")
        assertEquals("backup.2026-11-08", parts.base)
        assertEquals("", parts.extension)
    }

    @Test
    fun `dot tail exactly at the cap is still pinned`() {
        val name = "name." + "x".repeat(MAX_DISPLAY_EXTENSION_LENGTH - 1)
        val parts = splitFileName(name)
        assertEquals("name", parts.base)
        assertEquals("." + "x".repeat(MAX_DISPLAY_EXTENSION_LENGTH - 1), parts.extension)
    }

    @Test
    fun `dot tail one past the cap is not pinned`() {
        val name = "name." + "x".repeat(MAX_DISPLAY_EXTENSION_LENGTH)
        val parts = splitFileName(name)
        assertEquals(name, parts.base)
        assertEquals("", parts.extension)
    }

    @Test
    fun `reassembling base and extension restores the original name`() {
        val name = "my_camera_video_2026_11_08_final.mp4"
        val parts = splitFileName(name)
        assertEquals(name, parts.base + parts.extension)
    }
}
