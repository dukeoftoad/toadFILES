package com.toadfiles.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileOps
import com.toadfiles.app.domain.PathSafety
import com.toadfiles.app.domain.ZipSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Domain ops on a real Android filesystem: verifies behaviour under the actual
 * device FS (permissions, renameTo semantics, case handling) rather than the JVM.
 */
@RunWith(AndroidJUnit4::class)
class FileOpsInstrumentedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun copy_copiesFileAndDirectoryRecursively() = runBlocking {
        val appDir = File(context.cacheDir, "instr-copy-${System.nanoTime()}").apply { mkdirs() }
        val srcDir = File(appDir, "src").apply { mkdirs() }
        File(srcDir, "nested").mkdirs()
        File(srcDir, "nested/a.txt").writeText("alpha")
        val dstDir = File(appDir, "dst").apply { mkdirs() }

        val result = FileOps.copy(listOf(srcDir), dstDir, ConflictStrategy.RENAME)

        assertEquals(1, result.succeeded)
        assertEquals(0, result.failed)
        val copied = File(dstDir, "src/nested/a.txt")
        assertTrue(copied.exists())
        assertEquals("alpha", copied.readText())
        appDir.deleteRecursively()
    }

    @Test
    fun move_renamesFileWithinSameVolume() = runBlocking {
        val appDir = File(context.cacheDir, "instr-move-${System.nanoTime()}").apply { mkdirs() }
        val src = File(appDir, "start.txt").apply { writeText("payload") }
        val dstDir = File(appDir, "out").apply { mkdirs() }

        val result = FileOps.move(listOf(src), dstDir, ConflictStrategy.RENAME)

        assertEquals(1, result.succeeded)
        assertTrue(File(dstDir, "start.txt").exists())
        assertFalse(src.exists())
        appDir.deleteRecursively()
    }

    @Test
    fun rename_rejectsInvalidAndTakenNames() = runBlocking {
        val dir = File(context.cacheDir, "instr-rename-${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, "one.txt").apply { writeText("x") }
        File(dir, "two.txt").writeText("y")

        assertNull(FileOps.rename(f, "sub/dir.txt"))
        assertNull(FileOps.rename(f, "two.txt"))
        val renamed = FileOps.rename(f, "renamed.txt")
        assertTrue(renamed!!.exists())
        dir.deleteRecursively()
    }

    @Test
    fun uniqueName_neverCollidesOnRealFs() {
        val dir = File(context.cacheDir, "instr-unique-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "photo.jpg").writeText("1")
        assertEquals("photo (1).jpg", PathSafety.uniqueName(dir, "photo.jpg"))
        File(dir, "photo (1).jpg").writeText("2")
        assertEquals("photo (2).jpg", PathSafety.uniqueName(dir, "photo.jpg"))
        dir.deleteRecursively()
    }

    @Test
    fun zipExtract_rejectsZipSlipEntriesOnDevice() = runBlocking {
        val appDir = File(context.cacheDir, "instr-zip-${System.nanoTime()}").apply { mkdirs() }
        val evil = File(appDir, "evil.zip")
        ZipOutputStream(evil.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("../escaped.txt"))
            zos.write("malicious".toByteArray())
            zos.closeEntry()
        }
        val target = File(appDir, "out").apply { mkdirs() }

        val result = ZipSupport.extract(evil, target, replace = false)

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped) // the unsafe entry is skipped, never written
        assertFalse(File(appDir.parentFile, "escaped.txt").exists())
        assertFalse(File(appDir, "escaped.txt").exists())
        appDir.deleteRecursively()
    }
}
