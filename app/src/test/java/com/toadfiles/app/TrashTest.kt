package com.toadfiles.app

import com.toadfiles.app.domain.Trash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun volume(): File = tmp.newFolder()

    @Test
    fun `trash moves a file and restore puts it back`() {
        val vol = volume()
        val src = File(vol, "a.txt").apply { writeText("hello") }

        val trashed = Trash.trash(listOf(src), listOf(vol))
        assertEquals(1, trashed.size)
        assertFalse(src.exists())

        val entries = Trash.entries(listOf(vol))
        assertEquals(1, entries.size)
        assertEquals(src.absolutePath, entries[0].originPath)

        val restored = Trash.restore(trashed[0])
        assertNotNull(restored)
        assertEquals(src.absolutePath, restored!!.absolutePath)
        assertEquals("hello", src.readText())
        assertTrue(Trash.entries(listOf(vol)).isEmpty())
    }

    @Test
    fun `folders go to trash and come back whole`() {
        val vol = volume()
        val dir = File(vol, "pics").apply { mkdirs() }
        File(dir, "1.txt").writeText("x")

        val trashed = Trash.trash(listOf(dir), listOf(vol))
        assertEquals(1, trashed.size)
        assertFalse(dir.exists())

        val restored = Trash.restore(trashed[0])
        assertNotNull(restored)
        assertTrue(File(restored!!, "1.txt").exists())
    }

    @Test
    fun `trashing keeps same-named files apart`() {
        val vol = volume()
        val a = File(vol, "a.txt").apply { writeText("one") }
        val first = Trash.trash(listOf(a), listOf(vol))
        val b = File(vol, "a.txt").apply { writeText("two") }
        val second = Trash.trash(listOf(b), listOf(vol))

        assertEquals(2, Trash.entries(listOf(vol)).size)
        assertEquals("one", first[0].readText())
        assertEquals("two", second[0].readText())
    }

    @Test
    fun `restore never overwrites an existing file`() {
        val vol = volume()
        val src = File(vol, "a.txt").apply { writeText("old") }
        val trashed = Trash.trash(listOf(src), listOf(vol))
        File(vol, "a.txt").writeText("new")

        val restored = Trash.restore(trashed[0])
        assertNotNull(restored)
        assertFalse(restored!!.absolutePath == src.absolutePath)
        assertEquals("old", restored.readText())
        assertEquals("new", File(vol, "a.txt").readText())
    }

    @Test
    fun `purge removes only items past retention`() {
        val vol = volume()
        val old = File(vol, "old.txt").apply { writeText("x") }
        val trashedOld = Trash.trash(listOf(old), listOf(vol))
        // Fake the sidecar: this item was trashed 40 days ago.
        val meta = File(trashedOld[0].parentFile, trashedOld[0].name + ".tfmeta")
        meta.writeText("origin=${old.absolutePath}\ntrashedAt=${System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000}")
        val fresh = File(vol, "new.txt").apply { writeText("y") }
        Trash.trash(listOf(fresh), listOf(vol))

        assertEquals(1, Trash.purge(listOf(vol)))
        val left = Trash.entries(listOf(vol))
        assertEquals(1, left.size)
        assertEquals("new.txt", left[0].originPath.substringAfterLast('/'))
    }

    @Test
    fun `empty wipes everything`() {
        val vol = volume()
        val files = (1..3).map { i -> File(vol, "f$i.txt").apply { writeText("x$i") } }
        Trash.trash(files, listOf(vol))

        assertEquals(3, Trash.empty(listOf(vol)))
        assertTrue(Trash.entries(listOf(vol)).isEmpty())
    }

    @Test
    fun `volumeRootOf picks the longest matching root`() {
        val vol = volume()
        val sub = File(vol, "sub").apply { mkdirs() }
        assertEquals(sub, Trash.volumeRootOf(File(sub, "a.txt"), listOf(vol, sub)))
        assertEquals(vol, Trash.volumeRootOf(File(vol, "other/b.txt"), listOf(vol, sub)))
    }
}
