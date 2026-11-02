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

/**
 * Read access to the real file system. Nothing is touched until a screen asks for it.
 * [open] only so tests can inject a gated subclass (see BrowseViewModelTest).
 */
open class FileRepository(private val context: Context) {

    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    /**
     * Every mounted volume: internal storage plus SD cards and USB drives when present.
     * Only MEDIA_MOUNTED volumes show up here; anything unmounted or still preparing
     * is not browsable.
     *
     * Known limitation: below API 30 a volume's directory comes from a best-effort
     * reflective call to the hidden StorageVolume.getPath() getter (see [volumeDir]).
     * When that fails, the volume is skipped — pre-R removable storage may therefore
     * be missing from the list, with only primary storage offered. API-26..29 device
     * validation of this path is tracked in docs/RELEASE_CHECKLIST.md.
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

    /**
     * Real directory of a volume. The public getter only exists from API 30; older
     * devices go through the hidden StorageVolume.getPath() reflectively. That is
     * best-effort by design: if the reflection fails on a pre-R device (OEM
     * restrictions, framework changes), this returns null and the volume is
     * silently omitted from [roots], which then falls back to primary-only —
     * enumeration never crashes. API-26..29 device validation of this fallback
     * is tracked in docs/RELEASE_CHECKLIST.md.
     */
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

    /** [open] only so tests can park/gate the listing for deterministic race regressions. */
    open fun list(dir: File, showHidden: Boolean): List<FileItem> {
        val children = if (dir.isDirectory && dir.canRead()) dir.listFiles() else return emptyList()
        // The trash folder is managed by the Trash screen; keep it out of Browse — but
        // only the real one at a volume root (D11): a user's own folder with the same
        // name deeper in the tree is ordinary data and must stay listed.
        val volumeRoots = roots().mapTo(mutableSetOf()) { it.dir }
        return children.orEmpty()
            .filter { !(it.name == Trash.DIR_NAME && dir in volumeRoots) }
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
