package com.toadfiles.app

import com.toadfiles.app.domain.Format
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

    @Test
    fun `itemWord pluralizes properly - never item(s)`() {
        assertEquals("item", Format.itemWord(1))
        assertEquals("items", Format.itemWord(0))
        assertEquals("items", Format.itemWord(2))
        assertEquals("items", Format.itemWord(128))
    }

    @Test
    fun `selectionLabel stays quiet when the size is unknown`() {
        // A folder is in the selection: no honest total without a tree walk.
        assertEquals("3 selected", Format.selectionLabel(3, null))
        // Defensive: a negative size (unreadable file) is not a total either.
        assertEquals("2 selected", Format.selectionLabel(2, -1))
    }

    @Test
    fun `selectionLabel appends the honest total for file-only selections`() {
        assertEquals("1 selected · 512 B", Format.selectionLabel(1, 512))
        assertEquals("2 selected · 2.5 KB", Format.selectionLabel(2, (1024 * 2.5).toLong()))
        assertEquals("4 selected · 100 MB", Format.selectionLabel(4, 100L * 1024 * 1024))
        assertEquals("0 selected · 0 B", Format.selectionLabel(0, 0))
    }
}
