package com.toadfiles.app.data

import android.os.Environment
import android.os.StatFs
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.StorageRoot
import com.toadfiles.app.domain.StorageStats
import java.io.File

/** Read access to the real file system. No startup scanning — nothing is touched until a screen asks for it. */
class FileRepository {

    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    fun roots(): List<StorageRoot> {
        val root = primaryRoot()
        val stats = statsOf(root)
        return listOf(StorageRoot("Internal storage", root, stats))
    }

    fun statsOf(dir: File): StorageStats? = try {
        val stat = StatFs(dir.absolutePath)
        StorageStats(totalBytes = stat.totalBytes, freeBytes = stat.availableBytes)
    } catch (_: Exception) {
        null
    }

    fun quickLinks(root: File = primaryRoot()): List<Pair<String, File>> {
        val names = listOf("Download", "DCIM", "Documents", "Pictures", "Music", "Movies")
        return names.mapNotNull { n ->
            val f = File(root, n)
            if (f.isDirectory) n to f else null
        }
    }

    fun list(dir: File, showHidden: Boolean): List<FileItem> {
        val children = if (dir.isDirectory && dir.canRead()) dir.listFiles() else return emptyList()
        return children.orEmpty()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                FileItem(
                    file = f,
                    name = f.name,
                    isDir = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified(),
                )
            }
    }

    fun childCount(dir: File): Int =
        if (dir.isDirectory && dir.canRead()) dir.listFiles()?.size ?: -1 else -1

    fun newFolder(parent: File, name: String): File? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) return null
        val f = File(parent, clean)
        return if (f.mkdir()) f else null
    }
}
