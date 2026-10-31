package com.toadfiles.app

import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.analyzer.AnalyzerResult
import com.toadfiles.app.ui.analyzer.CategoryUsage
import com.toadfiles.app.ui.analyzer.SizeAccumulator
import com.toadfiles.app.ui.analyzer.StorageAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for the storage analyzer's aggregation and top-N selection logic.
 * No Android dependencies: everything works on plain java.io files.
 */
class StorageAnalyzerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(parent: File, name: String, bytes: Int): File =
        File(parent, name).apply { writeBytes(ByteArray(bytes)) }

    private fun usageOf(result: AnalyzerResult, category: FileCategory?): CategoryUsage? =
        result.categories.firstOrNull { it.category == category }

    @Test
    fun `scan aggregates category totals through the shared FilterMode classifier`() = runBlocking {
        val root = tmp.newFolder("root")
        file(root, "a.jpg", 10)
        file(root, "b.png", 20)
        file(root, "c.mp4", 30)
        file(root, "d.mp3", 40)
        file(root, "e.pdf", 50)
        file(root, "f.zip", 60)
        file(root, "g.apk", 70)
        file(root, "h.zzz", 80) // unknown extension → Other

        val result = StorageAnalyzer.scan(root, SizeAccumulator())

        assertEquals(8, result.fileCount)
        assertEquals(360L, result.totalBytes)

        val images = usageOf(result, FileCategory.IMAGES)
        assertEquals(30L, images?.sizeBytes)
        assertEquals(2, images?.count)

        assertEquals(30L, usageOf(result, FileCategory.VIDEOS)?.sizeBytes)
        assertEquals(40L, usageOf(result, FileCategory.AUDIO)?.sizeBytes)
        assertEquals(50L, usageOf(result, FileCategory.DOCS)?.sizeBytes)
        assertEquals(60L, usageOf(result, FileCategory.ARCHIVES)?.sizeBytes)
        assertEquals(70L, usageOf(result, FileCategory.APKS)?.sizeBytes)

        val other = usageOf(result, null)
        assertEquals(80L, other?.sizeBytes)
        assertEquals(1, other?.count)
        assertEquals("Other", other?.label)
    }

    @Test
    fun `categories are sorted by size descending`() = runBlocking {
        val root = tmp.newFolder("root")
        file(root, "small.jpg", 10)
        file(root, "big.mp4", 500)
        file(root, "mid.zip", 100)

        val result = StorageAnalyzer.scan(root, SizeAccumulator())

        assertEquals(
            listOf(500L, 100L, 10L),
            result.categories.map { it.sizeBytes },
        )
    }

    @Test
    fun `other bucket is omitted when nothing falls into it`() = runBlocking {
        val root = tmp.newFolder("root")
        file(root, "a.jpg", 10)
        file(root, "b.png", 20)

        val result = StorageAnalyzer.scan(root, SizeAccumulator())

        assertTrue(result.categories.all { it.category != null })
        assertEquals(1, result.categories.size) // both files are Images
    }

    @Test
    fun `biggest files keeps the top N sorted descending`() {
        val acc = SizeAccumulator(maxFiles = 3)
        for (size in 1..5) acc.addFile(File(tmp.root, "f$size.bin"), size.toLong())

        val result = acc.build()

        assertEquals(listOf(5L, 4L, 3L), result.biggestFiles.map { it.sizeBytes })
        assertEquals(listOf("f5.bin", "f4.bin", "f3.bin"), result.biggestFiles.map { it.name })
    }

    @Test
    fun `biggest files evicts the smallest when the heap is full`() {
        val acc = SizeAccumulator(maxFiles = 2)
        listOf(5L, 3L, 5L, 7L).forEachIndexed { i, size ->
            acc.addFile(File(tmp.root, "f$i.bin"), size)
        }

        val result = acc.build()

        assertEquals(listOf(7L, 5L), result.biggestFiles.map { it.sizeBytes })
    }

    @Test
    fun `largest folders carry recursive sizes and file counts`() = runBlocking {
        val root = tmp.newFolder("root")
        val a = File(root, "A").apply { mkdirs() }
        val ab = File(a, "B").apply { mkdirs() }
        val c = File(root, "C").apply { mkdirs() }
        file(a, "x.jpg", 100)
        file(ab, "y.mp4", 200)
        file(c, "z.txt", 50)

        val result = StorageAnalyzer.scan(root, SizeAccumulator())

        // A = 100 + 200 (B is inside A), C = 50; the root itself is never listed.
        assertEquals(listOf("A" to 300L, "C" to 50L), result.largestFolders.map { it.dir.name to it.sizeBytes })
        assertEquals(2, result.largestFolders.first().fileCount)
        assertEquals(1, result.largestFolders[1].fileCount)
    }

    @Test
    fun `largest folders never nest kept folders`() = runBlocking {
        val root = tmp.newFolder("root")
        val big = File(root, "Big").apply { mkdirs() }
        val inner = File(big, "inner").apply { mkdirs() }
        val other = File(root, "Other").apply { mkdirs() }
        file(inner, "y.bin", 400)
        file(big, "small.bin", 10)
        file(other, "o.bin", 200)

        val result = StorageAnalyzer.scan(root, SizeAccumulator())

        // Big (410) is kept; its child inner (400) is skipped so the list does not
        // read as a chain Big / Big/inner. Other (200) is kept.
        assertEquals(listOf("Big" to 410L, "Other" to 200L), result.largestFolders.map { it.dir.name to it.sizeBytes })
    }

    @Test
    fun `largest folders respects the maxFolders cap`() = runBlocking {
        val root = tmp.newFolder("root")
        for (i in 1..3) {
            val d = File(root, "d$i").apply { mkdirs() }
            file(d, "x$i.bin", i * 100)
        }

        val result = StorageAnalyzer.scan(root, SizeAccumulator(maxFolders = 1))

        assertEquals(listOf("d3" to 300L), result.largestFolders.map { it.dir.name to it.sizeBytes })
    }

    @Test
    fun `build on an unused accumulator yields an empty result`() {
        val result = SizeAccumulator().build()

        assertEquals(0, result.fileCount)
        assertEquals(0L, result.totalBytes)
        assertTrue(result.categories.isEmpty())
        assertTrue(result.biggestFiles.isEmpty())
        assertTrue(result.largestFolders.isEmpty())
    }

    @Test
    fun `scan of an empty folder reports nothing`() = runBlocking {
        val result = StorageAnalyzer.scan(tmp.newFolder("empty"), SizeAccumulator())

        assertEquals(0, result.fileCount)
        assertEquals(0L, result.totalBytes)
        assertTrue(result.largestFolders.isEmpty())
    }

    @Test
    fun `onFile is invoked once per visited file`() = runBlocking {
        val root = tmp.newFolder("root")
        val sub = File(root, "sub").apply { mkdirs() }
        file(root, "a.jpg", 1)
        file(root, "b.png", 2)
        file(sub, "c.mp4", 3)

        var seen = 0
        var seenBytes = 0L
        val result = StorageAnalyzer.scan(root, SizeAccumulator()) { size ->
            seen++
            seenBytes += size
        }

        assertEquals(result.fileCount, seen)
        assertEquals(result.totalBytes, seenBytes)
        assertEquals(6L, seenBytes)
    }

    @Test
    fun `build snapshots are consistent while accumulating`() {
        // Simulates the ViewModel's progressive publishing: the same accumulator is
        // read mid-scan and after completion without any reset in between.
        val acc = SizeAccumulator(maxFiles = 2)
        acc.addFile(File(tmp.root, "a.bin"), 10)
        val partial = acc.build()

        assertEquals(1, partial.fileCount)
        assertEquals(10L, partial.totalBytes)

        acc.addFile(File(tmp.root, "b.bin"), 90)
        acc.addFile(File(tmp.root, "c.bin"), 50)
        val final = acc.build()

        assertEquals(3, final.fileCount)
        assertEquals(150L, final.totalBytes)
        assertEquals(listOf(90L, 50L), final.biggestFiles.map { it.sizeBytes })
        // The earlier snapshot was not retroactively mutated (immutable data classes).
        assertEquals(1, partial.fileCount)
    }
}
