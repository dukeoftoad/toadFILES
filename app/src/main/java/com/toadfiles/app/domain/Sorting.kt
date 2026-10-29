package com.toadfiles.app.domain

import java.io.File

/**
 * Deterministic sorting used everywhere lists of files are shown.
 * Directories always come before files; within each group the chosen mode applies.
 */
object Sorting {

    /** Natural comparison: "file2" < "file10", case-insensitive, digit runs compared numerically. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        val an = a.lowercase()
        val bn = b.lowercase()
        while (i < an.length && j < bn.length) {
            val ca = an[i]
            val cb = bn[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i
                while (ie < an.length && an[ie].isDigit()) ie++
                var je = j
                while (je < bn.length && bn[je].isDigit()) je++
                val na = an.substring(i, ie).trimStart('0')
                val nb = bn.substring(j, je).trimStart('0')
                val byLen = na.length.compareTo(nb.length)
                if (byLen != 0) return byLen
                val byVal = na.compareTo(nb)
                if (byVal != 0) return byVal
                i = ie
                j = je
            } else {
                val c = ca.compareTo(cb)
                if (c != 0) return c
                i++
                j++
            }
        }
        return an.length - bn.length
    }

    private fun dirsFirst(a: FileItem, b: FileItem): Int =
        (!a.isDir).compareTo(!b.isDir)

    fun comparator(mode: SortMode, order: SortOrder): Comparator<FileItem> {
        val asc = Comparator<FileItem> { a, b ->
            val d = dirsFirst(a, b)
            if (d != 0) return@Comparator d
            when (mode) {
                SortMode.NAME -> naturalCompare(a.name, b.name)
                SortMode.SIZE -> a.size.compareTo(b.size)
                SortMode.DATE -> a.lastModified.compareTo(b.lastModified)
                SortMode.TYPE -> {
                    val t = a.name.substringAfterLast('.', "").lowercase()
                        .compareTo(b.name.substringAfterLast('.', "").lowercase())
                    if (t != 0) t else naturalCompare(a.name, b.name)
                }
            }
        }
        if (order == SortOrder.ASC) return asc
        // DESC: keep directories first, reverse the chosen key only.
        return Comparator<FileItem> { a, b ->
            val d = dirsFirst(a, b)
            if (d != 0) return@Comparator d
            -asc.compare(a, b)
        }
    }
}
