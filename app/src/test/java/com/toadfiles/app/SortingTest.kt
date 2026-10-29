package com.toadfiles.app

import com.toadfiles.app.domain.Sorting
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SortingTest {

    private fun item(name: String, isDir: Boolean = false, size: Long = 0, date: Long = 0) =
        com.toadfiles.app.domain.FileItem(File(name), name, isDir, size, date)

    @Test
    fun `natural order compares digit runs numerically`() {
        val names = listOf("file10.txt", "file2.txt", "file1.txt")
        val sorted = names.sortedWith { a, b -> Sorting.naturalCompare(a, b) }
        assertEquals(listOf("file1.txt", "file2.txt", "file10.txt"), sorted)
    }

    @Test
    fun `natural order is case insensitive`() {
        assertEquals(0, Sorting.naturalCompare("ABC.txt", "abc.txt"))
    }

    @Test
    fun `directories always come first regardless of order`() {
        val items = listOf(
            item("zeta.txt", isDir = false, size = 100),
            item("alpha", isDir = true),
            item("beta", isDir = true),
        )
        val asc = items.sortedWith(Sorting.comparator(SortMode.NAME, SortOrder.ASC))
        assertEquals(listOf("alpha", "beta", "zeta.txt"), asc.map { it.name })

        val desc = items.sortedWith(Sorting.comparator(SortMode.NAME, SortOrder.DESC))
        assertEquals(listOf("beta", "alpha", "zeta.txt"), desc.map { it.name })
    }

    @Test
    fun `size mode sorts ascending and descending by size`() {
        val items = listOf(
            item("small", size = 10),
            item("big", size = 900),
            item("mid", size = 100),
        )
        val asc = items.sortedWith(Sorting.comparator(SortMode.SIZE, SortOrder.ASC)).map { it.name }
        val desc = items.sortedWith(Sorting.comparator(SortMode.SIZE, SortOrder.DESC)).map { it.name }
        assertEquals(listOf("small", "mid", "big"), asc)
        assertEquals(listOf("big", "mid", "small"), desc)
    }

    @Test
    fun `date mode sorts descending newest first`() {
        val items = listOf(
            item("old", date = 100),
            item("new", date = 300),
            item("mid", date = 200),
        )
        val desc = items.sortedWith(Sorting.comparator(SortMode.DATE, SortOrder.DESC)).map { it.name }
        assertEquals(listOf("new", "mid", "old"), desc)
    }

    @Test
    fun `type mode groups by extension`() {
        val items = listOf(item("b.txt"), item("a.pdf"), item("c.txt"))
        val sorted = items.sortedWith(Sorting.comparator(SortMode.TYPE, SortOrder.ASC)).map { it.name }
        assertEquals(listOf("a.pdf", "b.txt", "c.txt"), sorted)
    }
}
