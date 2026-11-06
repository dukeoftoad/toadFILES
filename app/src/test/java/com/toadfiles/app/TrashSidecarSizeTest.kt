package com.toadfiles.app

import com.toadfiles.app.domain.Trash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Audit A20: the trash sidecar records the payload size at trash time (one cheap
 * stat taken while the pending sidecar is written), so Storage's trash-usage card
 * answers from metadata instead of walking every payload tree on every refresh.
 * File payloads carry a size; directory payloads and legacy (pre-field) sidecars
 * report -1 and the read side keeps its walk fallback.
 */
class TrashSidecarSizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun resetTrashSeams() {
        Trash.clock = { System.currentTimeMillis() }
        Trash.onAfterPendingWrite = null
    }

    @Test
    fun `file payloads record their size in the sidecar`() {
        val vol = tmp.newFolder()
        val src = File(vol, "song.mp3").apply { writeText("x".repeat(4321)) }
        // Capture the size BEFORE trashing: the payload is renamed away, so a later
        // src.length() would read 0 on the vacated path.
        val expected = src.length()

        val trashed = Trash.trash(listOf(src), listOf(vol))
        assertEquals(1, trashed.moved.size)
        val entries = Trash.entries(listOf(vol))
        assertEquals(1, entries.size)
        assertEquals(expected, entries[0].sizeBytes)
        assertEquals(4321L, entries[0].sizeBytes)
    }

    @Test
    fun `directory payloads keep the walk fallback`() {
        val vol = tmp.newFolder()
        val dir = File(vol, "album").apply { mkdirs() }
        File(dir, "t1.mp3").writeText("a")
        File(dir, "t2.mp3").writeText("bb")

        val trashed = Trash.trash(listOf(dir), listOf(vol))
        assertEquals(1, trashed.moved.size)
        val entries = Trash.entries(listOf(vol))
        assertEquals(1, entries.size)
        assertEquals(-1L, entries[0].sizeBytes)
        // The item is still restorable as a whole folder.
        val restored = Trash.restore(trashed.moved[0])
        assertTrue(restored!!.isDirectory)
    }

    @Test
    fun `legacy sidecars without a size line report -1 and still restore`() {
        val vol = tmp.newFolder()
        val src = File(vol, "legacy.txt").apply { writeText("old trash") }
        val trashed = Trash.trash(listOf(src), listOf(vol))
        val payload = trashed.moved[0]
        val entries = Trash.entries(listOf(vol))
        assertEquals(src.absolutePath, entries[0].originPath)

        // Downgrade the sidecar to the pre-A20 two-line schema.
        val meta = File(File(payload.parentFile!!, Trash.META_DIR_NAME), payload.name + ".tfmeta")
        val lines = meta.readText().lineSequence().filter { !it.startsWith("size=") }.joinToString("\n")
        meta.writeText(lines)

        val legacy = Trash.entries(listOf(vol))
        assertEquals(1, legacy.size)
        assertEquals(-1L, legacy[0].sizeBytes)
        assertEquals(src.absolutePath, legacy[0].originPath)
        // And the restore path is unaffected by the missing field.
        val restored = Trash.restore(payload)
        assertEquals(src.absolutePath, restored!!.absolutePath)
    }
}
