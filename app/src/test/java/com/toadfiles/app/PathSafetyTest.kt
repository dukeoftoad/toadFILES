package com.toadfiles.app

import com.toadfiles.app.domain.PathSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PathSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `isInside detects direct children`() {
        val root = tmp.newFolder("root")
        val child = File(root, "child.txt").apply { writeText("x") }
        assertTrue(PathSafety.isInside(child, root))
        assertTrue(PathSafety.isInside(root, root))
    }

    @Test
    fun `isInside rejects siblings with similar names`() {
        val a = tmp.newFolder("a")
        val ab = File(tmp.root, "ab").apply { mkdirs() }
        assertFalse(PathSafety.isInside(a, ab))
        assertFalse(PathSafety.isInside(ab, a))
    }

    @Test
    fun `isInside detects deep nesting`() {
        val root = tmp.newFolder("deep")
        val nested = File(File(File(root, "l1"), "l2"), "l3").apply { mkdirs() }
        assertTrue(PathSafety.isInside(nested, root))
    }

    @Test
    fun `rejectSelfTarget throws when target is inside source`() {
        val src = tmp.newFolder("src")
        val target = File(src, "sub").apply { mkdirs() }
        var threw = false
        try {
            PathSafety.rejectSelfTarget(listOf(src), target)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `rejectSelfTarget allows moving into unrelated folder`() {
        val src = tmp.newFolder("src2")
        val target = tmp.newFolder("dst")
        PathSafety.rejectSelfTarget(listOf(src), target) // must not throw
    }

    @Test
    fun `uniqueName appends numbered suffix`() {
        val dir = tmp.newFolder("names")
        File(dir, "report.pdf").writeText("1")
        assertEquals("report (1).pdf", PathSafety.uniqueName(dir, "report.pdf"))
        File(dir, "report (1).pdf").writeText("2")
        assertEquals("report (2).pdf", PathSafety.uniqueName(dir, "report.pdf"))
    }

    @Test
    fun `uniqueName returns original when free`() {
        val dir = tmp.newFolder("names2")
        assertEquals("fresh.txt", PathSafety.uniqueName(dir, "fresh.txt"))
    }

    @Test
    fun `zip entry names - slip vectors rejected`() {
        assertFalse(PathSafety.isSafeEntryName("../evil.txt"))
        assertFalse(PathSafety.isSafeEntryName("a/../../evil.txt"))
        assertFalse(PathSafety.isSafeEntryName("/etc/passwd"))
        assertFalse(PathSafety.isSafeEntryName("C:\\evil.txt"))
        assertFalse(PathSafety.isSafeEntryName(""))
        assertFalse(PathSafety.isSafeEntryName("a\\..\\b"))
        assertFalse(PathSafety.isSafeEntryName("a/../b"))
        assertFalse(PathSafety.isSafeEntryName(".."))
        assertFalse(PathSafety.isSafeEntryName("x/.."))
        assertFalse(PathSafety.isSafeEntryName("..\\evil.txt"))
        assertFalse(PathSafety.isSafeEntryName("\u0000evil.txt"))
        assertFalse(PathSafety.isSafeEntryName("."))      // resolves to the destination root itself
        assertFalse(PathSafety.isSafeEntryName("./"))
        assertTrue(PathSafety.isSafeEntryName("docs/readme.txt"))
        assertTrue(PathSafety.isSafeEntryName("folder/"))
        assertTrue(PathSafety.isSafeEntryName("normal_file-name (1).jpg"))
    }

    @Test
    fun `zip entry names - harmless double dots are kept`() {
        // P3.1: only whole ".." components escape; embedded double dots are ordinary names.
        assertTrue(PathSafety.isSafeEntryName("release..notes.txt"))
        assertTrue(PathSafety.isSafeEntryName("a..b/c.txt"))
        assertTrue(PathSafety.isSafeEntryName("...file.txt"))
        assertTrue(PathSafety.isSafeEntryName("dir/..dots"))
    }

    @Test
    fun `ensureInside allows nested children and rejects escapes`() {
        val root = tmp.newFolder("guard")
        PathSafety.ensureInside(root, File(root, "a.txt"))
        PathSafety.ensureInside(root, File(File(root, "sub"), "deep.txt"))
        var threw = false
        try {
            PathSafety.ensureInside(root, File(root, "../outside.txt"))
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `ensureInside catches symlink escape when links are supported`() {
        val root = tmp.newFolder("links")
        val outside = tmp.newFolder("outside")
        val link = File(root, "sneaky")
        val supported = try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (_: Exception) {
            false
        }
        if (!supported) return // filesystem without symlink support: nothing to prove here
        assertFalse(PathSafety.isInside(link, root))
        var threw = false
        try {
            PathSafety.ensureInside(root, link)
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
    }
}
