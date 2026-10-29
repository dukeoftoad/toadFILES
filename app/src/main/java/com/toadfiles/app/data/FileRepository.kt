package com.toadfiles.app.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.StorageRoot
import com.toadfiles.app.domain.StorageStats
import com.toadfiles.app.domain.Trash
import java.io.File

/** Read access to the real file system. No startup scanning — nothing is touched until a screen asks for it. */
class FileRepository(private val context: Context) {

    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    /**
     * Every mounted volume: internal storage plus SD cards and USB drives when present.
     * Unmounted/preparing volumes are skipped — only MEDIA_MOUNTED is browsable.
     */
    fun roots(): List<StorageRoot> {
        val out = mutableListOf<StorageRoot>()
        try {
            val sm = context.getSystemService(StorageManager::class.java)
            if (sm != null) {
                for (v in sm.storageVolumes) {
                    if (v.state != Environment.MEDIA_MOUNTED) continue
                    val dir = volumeDir(v) ?: continue
                    out += StorageRoot(
                        label = volumeLabel(v),
                        dir = dir,
                        stats = statsOf(dir),
                        removable = v.isRemovable,
                    )
                }
            }
        } catch (_: Exception) {
            // StorageManager unavailable: fall through to the primary volume below.
        }
        if (out.isEmpty()) {
            val root = primaryRoot()
            out += StorageRoot("Internal storage", root, statsOf(root))
        }
        return out
    }

    /** Real directory of a volume. The public getter only exists from API 30; older devices need the hidden getter. */
    private fun volumeDir(v: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            v.directory
        } else {
            try {
                @Suppress("PrivateApi")
                val getPath = StorageVolume::class.java.getMethod("getPath")
                (getPath.invoke(v) as? String)?.let(::File)
            } catch (_: Exception) {
                null
            }
        }

    private fun volumeLabel(v: StorageVolume): String = try {
        if (v.isRemovable) v.getDescription(context) else "Internal storage"
    } catch (_: Exception) {
        "Storage"
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
            // The trash folder is managed by the Trash screen; keep it out of Browse.
            .filter { it.name != Trash.DIR_NAME }
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
