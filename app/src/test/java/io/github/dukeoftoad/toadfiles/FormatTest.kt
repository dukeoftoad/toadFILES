package io.github.dukeoftoad.toadfiles

import io.github.dukeoftoad.toadfiles.domain.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `bytes under 1KB stay raw`() {
        assertEquals("0 B", Format.size(0))
        assertEquals("512 B", Format.size(512))
        assertEquals("1023 B", Format.size(1023))
    }

    @Test
    fun `kilobytes`() {
        assertEquals("1.0 KB", Format.size(1024))
        assertEquals("2.5 KB", Format.size((1024 * 2.5).toLong()))
    }

    @Test
    fun `megabytes`() {
        assertEquals("1.0 MB", Format.size(1024 * 1024))
        assertEquals("100 MB", Format.size(100L * 1024 * 1024))
    }

    @Test
    fun `gigabytes`() {
        assertEquals("1.0 GB", Format.size(1024L * 1024 * 1024))
        assertEquals("1.5 GB", Format.size((1024L * 1024 * 1024 * 1.5).toLong()))
    }

    @Test
    fun `negative size is unknown`() {
        assertEquals("?", Format.size(-1))
    }
}
