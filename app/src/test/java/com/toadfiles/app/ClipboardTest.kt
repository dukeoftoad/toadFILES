package com.toadfiles.app

import com.toadfiles.app.data.Clipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ClipboardTest {

    @Test
    fun `set stores mode and files`() {
        val clip = Clipboard()
        val files = listOf(File("/tmp/a.txt"), File("/tmp/b.txt"))
        clip.set(Clipboard.Mode.COPY, files)

        val c = clip.clip.value
        assertEquals(Clipboard.Mode.COPY, c?.mode)
        assertEquals(files, c?.files)
    }

    @Test
    fun `setting an empty list clears the clipboard`() {
        val clip = Clipboard()
        clip.set(Clipboard.Mode.MOVE, listOf(File("/tmp/a.txt")))
        clip.set(Clipboard.Mode.MOVE, emptyList())
        assertNull(clip.clip.value)
    }

    @Test
    fun `clear empties the clipboard`() {
        val clip = Clipboard()
        clip.set(Clipboard.Mode.MOVE, listOf(File("/tmp/a.txt")))
        clip.clear()
        assertNull(clip.clip.value)
    }

    @Test
    fun `stored file list is a defensive copy`() {
        val clip = Clipboard()
        val files = mutableListOf(File("/tmp/a.txt"), File("/tmp/b.txt"))
        clip.set(Clipboard.Mode.COPY, files)

        files.add(File("/tmp/c.txt")) // mutate after set

        assertEquals(2, clip.clip.value?.files?.size)
    }

    @Test
    fun `last set wins`() {
        val clip = Clipboard()
        clip.set(Clipboard.Mode.COPY, listOf(File("/tmp/a.txt")))
        clip.set(Clipboard.Mode.MOVE, listOf(File("/tmp/b.txt")))
        val c = clip.clip.value
        assertEquals(Clipboard.Mode.MOVE, c?.mode)
        assertEquals(listOf(File("/tmp/b.txt")), c?.files)
    }
}
