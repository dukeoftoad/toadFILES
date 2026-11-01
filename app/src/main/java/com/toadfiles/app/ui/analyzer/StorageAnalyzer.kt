package com.toadfiles.app.ui.analyzer

import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FilterMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue

/** A single large file surfaced by the analyzer. */
data class BigFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** A folder with its recursive subtree size and the number of files inside (MI Files style). */
data class FolderUsage(
    val dir: File,
    val sizeBytes: Long,
    val fileCount: Int,
)

/**
 * Per-category size total over the scanned tree. [category] is null for the "Other"
 * bucket — everything the app's classifier ([FilterMode]) does not recognise.
 */
data class CategoryUsage(
    val category: FileCategory?,
    val sizeBytes: Long,
    val count: Int,
) {
    val label: String get() = category?.label ?: "Other"
}

/** Aggregated answer to "what is eating this volume's space". */
data class AnalyzerResult(
    val totalBytes: Long,
    val fileCount: Int,
    /** Category totals sorted by size, descending. */
    val categories: List<CategoryUsage>,
    /** Top-N largest files, sorted by size descending. */
    val biggestFiles: List<BigFile>,
    /** Top-N largest folders by recursive size, sorted descending, never nested. */
    val largestFolders: List<FolderUsage>,
)

/**
 * Pure size aggregation for the storage analyzer: category totals, top-N largest files
 * and top-N largest folders. No Android dependencies — plain java.io plus the app's
 * existing [FilterMode] classifier (reused, not duplicated), so the selection logic is
 * unit-testable on the JVM (see StorageAnalyzerTest).
 *
 * [build] may be called mid-scan for a progressive snapshot: categories and biggest
 * files are always current, folders appear as their subtrees complete.
 */
class SizeAccumulator(
    private val maxFiles: Int = 20,
    private val maxFolders: Int = 10,
) {
    private val categoryBytes = HashMap<FileCategory?, Long>()
    private val categoryCounts = HashMap<FileCategory?, Int>()

    /** Min-heap capped at [maxFiles]: the current top-N biggest files. */
    private val biggest = PriorityQueue<BigFile>(compareBy { it.sizeBytes })
    private val folders = mutableListOf<FolderUsage>()

    var totalBytes: Long = 0L
        private set
    var fileCount: Int = 0
        private set

    fun addFile(file: File, sizeBytes: Long, lastModified: Long = file.lastModified()) {
        val category = categoryOf(file.name)
        categoryBytes[category] = (categoryBytes[category] ?: 0L) + sizeBytes
        categoryCounts[category] = (categoryCounts[category] ?: 0) + 1
        totalBytes += sizeBytes
        fileCount++
        val entry = BigFile(file, file.name, sizeBytes, lastModified)
        if (biggest.size < maxFiles) {
            biggest.add(entry)
        } else if (entry.sizeBytes > biggest.peek().sizeBytes) {
            biggest.poll()
            biggest.add(entry)
        }
    }

    /** Records a completed directory subtree (called post-order by the walker). */
    fun addFolder(dir: File, sizeBytes: Long, fileCount: Int) {
        folders += FolderUsage(dir, sizeBytes, fileCount)
    }

    fun build(): AnalyzerResult {
        val categories = mutableListOf<CategoryUsage>()
        for (c in FileCategory.entries) {
            val bytes = categoryBytes[c] ?: continue
            categories += CategoryUsage(c, bytes, categoryCounts[c] ?: 0)
        }
        val otherBytes = categoryBytes[null] ?: 0L
        val otherCount = categoryCounts[null] ?: 0
        if (otherBytes > 0L || otherCount > 0) {
            categories += CategoryUsage(null, otherBytes, otherCount)
        }
        categories.sortByDescending { it.sizeBytes }
        return AnalyzerResult(
            totalBytes = totalBytes,
            fileCount = fileCount,
            categories = categories,
            biggestFiles = biggest.sortedByDescending { it.sizeBytes },
            largestFolders = topFolders(),
        )
    }

    /**
     * Top-N folders by recursive size, never nesting: once a folder is kept, its
     * descendants are skipped, so the list reads like MI Files' "largest folders"
     * instead of a chain of DCIM / DCIM/Camera / DCIM/Camera/2023.
     */
    private fun topFolders(): List<FolderUsage> {
        val kept = mutableListOf<FolderUsage>()
        for (f in folders.sortedByDescending { it.sizeBytes }) {
            if (kept.size >= maxFolders) break
            if (kept.any { isAncestor(it.dir, f.dir) }) continue
            kept += f
        }
        return kept
    }

    private fun isAncestor(ancestor: File, dir: File): Boolean {
        val ancPath = ancestor.absolutePath
        var p: File? = dir.parentFile
        while (p != null) {
            if (p.absolutePath == ancPath) return true
            p = p.parentFile
        }
        return false
    }

    companion object {
        private val CATEGORY_BY_FILTER = listOf(
            FilterMode.IMAGES to FileCategory.IMAGES,
            FilterMode.VIDEOS to FileCategory.VIDEOS,
            FilterMode.AUDIO to FileCategory.AUDIO,
            FilterMode.DOCS to FileCategory.DOCS,
            FilterMode.ARCHIVES to FileCategory.ARCHIVES,
            FilterMode.APKS to FileCategory.APKS,
        )

        /** The app's single file classifier, reused: first matching [FilterMode] wins, else Other. */
        fun categoryOf(name: String): FileCategory? =
            CATEGORY_BY_FILTER.firstOrNull { it.first.matches(name) }?.second
    }
}

/**
 * One recursive, cancellable walk that fills a [SizeAccumulator]. Traversal policy
 * mirrors [com.toadfiles.app.domain.FileOps] and
 * [com.toadfiles.app.domain.PropertiesBuilder]: symbolic links are leaves counted at
 * their own size (never followed, so link cycles terminate), unreadable directories
 * contribute nothing, and nothing is ever written. Cancellation is checkpointed per
 * directory and per entry.
 */
object StorageAnalyzer {

    /**
     * Walks [root] recursively into [acc] and returns the final [AnalyzerResult].
     * [onFile] is invoked once per visited file (non-suspend, cheap) so the caller can
     * drive a progress counter and publish [SizeAccumulator.build] snapshots.
     */
    suspend fun scan(
        root: File,
        acc: SizeAccumulator,
        onFile: (sizeBytes: Long) -> Unit = {},
    ): AnalyzerResult {
        walk(root, acc, onFile)
        return acc.build()
    }

    /** Returns (recursive size, file count) of [dir]'s contents; the dir itself is excluded. */
    private suspend fun walk(dir: File, acc: SizeAccumulator, onFile: (Long) -> Unit): Pair<Long, Int> {
        currentCoroutineContext().ensureActive() // cancellation checkpoint per directory
        val children = dir.listFiles() ?: return 0L to 0
        var size = 0L
        var count = 0
        for (c in children) {
            currentCoroutineContext().ensureActive() // cancellation checkpoint per entry
            if (Files.isSymbolicLink(c.toPath())) {
                // A link is a leaf: counted at its own size, never expanded (same policy
                // as FileOps.sizeOf) — a link back to an ancestor cannot loop the walk.
                val s = linkSize(c)
                acc.addFile(c, s)
                onFile(s)
                size += s
                count++
            } else if (c.isDirectory) {
                val (subSize, subCount) = walk(c, acc, onFile)
                acc.addFolder(c, subSize, subCount)
                size += subSize
                count += subCount
            } else {
                // One metadata read per regular file: BasicFileAttributes yields size AND
                // mtime together — addFile's default lastModified would otherwise stat
                // every file a second time, doubling the syscalls on large trees.
                val attrs = try {
                    Files.readAttributes(
                        c.toPath(),
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: Exception) {
                    null
                }
                val s = attrs?.size() ?: try {
                    c.length()
                } catch (_: Exception) {
                    0L
                }
                val mtime = attrs?.lastModifiedTime()?.toMillis() ?: 0L
                acc.addFile(c, s, mtime)
                onFile(s)
                size += s
                count++
            }
        }
        return size to count
    }

    /** The size of the link itself, never of the thing it points at. */
    private fun linkSize(file: File): Long = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
    } catch (_: Exception) {
        0L
    }
}
