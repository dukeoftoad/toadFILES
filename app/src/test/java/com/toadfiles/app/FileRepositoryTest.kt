package com.toadfiles.app

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.FileRepository
import com.toadfiles.app.domain.Trash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Real-filesystem behaviour of FileRepository, executed on the JVM through Robolectric.
 * Every assertion runs against actual directories and files — no mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileRepositoryTest {

    private lateinit var repo: FileRepository
    private lateinit var base: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repo = FileRepository(context)
        base = File(context.cacheDir, "repo-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @Test
    fun `list returns items with real name size type`() {
        File(base, "alpha.txt").apply { writeText("12345") } // 5 bytes
        File(base, "subdir").mkdirs()

        val items = repo.list(base, showHidden = false)

        assertEquals(2, items.size)
        val alpha = items.first { it.name == "alpha.txt" }
        assertFalse(alpha.isDir)
        assertEquals(5L, alpha.size)
        assertTrue(alpha.lastModified > 0)
        val dir = items.first { it.name == "subdir" }
        assertTrue(dir.isDir)
        assertEquals(0L, dir.size)
    }

    @Test
    fun `list hides dotfiles unless asked otherwise`() {
        File(base, "visible.txt").writeText("x")
        File(base, ".hidden").writeText("x")

        assertEquals(listOf("visible.txt"), repo.list(base, showHidden = false).map { it.name })
        val withHidden = repo.list(base, showHidden = true).map { it.name }.sorted()
        assertEquals(listOf(".hidden", "visible.txt"), withHidden)
    }

    @Test
    fun `list keeps the trash folder out of a volume root`() {
        // The real trash dir lives directly at a volume root (see roots()); Browse
        // must not offer it even with hidden files shown (D11).
        val root = Environment.getExternalStorageDirectory()
        File(root, "normal.txt").writeText("x")
        File(root, Trash.DIR_NAME).mkdirs()
        File(root, Trash.DIR_NAME + "/payload.txt").writeText("x")
        File(root, "user_file.txt").writeText("x")

        val names = repo.list(root, showHidden = true).map { it.name }

        assertFalse("the app's trash dir must not appear at a volume root", names.contains(Trash.DIR_NAME))
        assertTrue(names.contains("normal.txt"))
        assertTrue(names.contains("user_file.txt"))
    }

    @Test
    fun `a user folder named like the trash dir stays visible below the root`() {
        // D11: a same-named folder that is NOT at a volume root is the user's own
        // data — hiding it anywhere in the tree made it vanish from Browse entirely.
        File(base, "normal.txt").writeText("x")
        File(base, Trash.DIR_NAME).mkdirs()
        File(base, Trash.DIR_NAME + "/user_file.txt").writeText("x")

        val names = repo.list(base, showHidden = true).map { it.name }

        assertTrue("a user's own folder with the trash name must stay listed", names.contains(Trash.DIR_NAME))
        assertTrue(names.contains("normal.txt"))
    }

    @Test
    fun `list on unreadable or missing dir returns empty list instead of crashing`() {
        assertEquals(emptyList<Any>().size, repo.list(File(base, "does-not-exist"), false).size)
        val fileNotDir = File(base, "plain.txt").apply { writeText("x") }
        assertTrue(repo.list(fileNotDir, false).isEmpty())
    }

    @Test
    fun `newFolder creates a real directory`() {
        val created = repo.newFolder(base, "New Photos")
        assertNotNull(created)
        assertTrue(created!!.isDirectory)
        assertEquals("New Photos", created.name)
    }

    @Test
    fun `newFolder rejects empty and path-traversal names`() {
        assertNull(repo.newFolder(base, ""))
        assertNull(repo.newFolder(base, "   "))
        assertNull(repo.newFolder(base, "a/b"))
        assertNull(repo.newFolder(base, "a\\b"))
        assertNull(repo.newFolder(base, ".."))
        assertEquals(0, base.listFiles()!!.size)
    }

    @Test
    fun `newFolder fails honestly when the name already exists`() {
        File(base, "dupe").mkdirs()
        assertNull(repo.newFolder(base, "dupe"))
    }

    @Test
    fun `childCount counts entries and reports -1 for non-directories`() {
        val dir = File(base, "counted").apply { mkdirs() }
        File(dir, "1").writeText("x")
        File(dir, "2").writeText("x")
        assertEquals(2, repo.childCount(dir))
        assertEquals(-1, repo.childCount(File(dir, "1")))
        assertEquals(-1, repo.childCount(File(base, "missing")))
    }

    @Test
    fun `quickLinks only lists standard folders that actually exist`() {
        File(base, "Download").mkdirs()
        File(base, "DCIM").mkdirs()
        File(base, "RandomFolder").mkdirs()

        val links = repo.quickLinks(root = base)

        assertEquals(listOf("Download", "DCIM"), links.map { it.first })
        assertTrue(links.all { it.second.isDirectory })
    }

    @Test
    fun `roots falls back to at least one browsable volume`() {
        val roots = repo.roots()
        assertTrue("expected at least one storage root", roots.isNotEmpty())
        assertNotNull(roots[0].dir)
    }

    @Test
    fun `statsOf reports sane volume numbers or null without crashing`() {
        // Audit A27: the sane-invariant used to hide inside `if (stats != null)`;
        // on the Robolectric mount statsOf(base) really does report numbers, so the
        // invariant is now unconditional (and the missing-path no-throw is kept).
        val stats = repo.statsOf(base)
        assertNotNull("statsOf should report numbers for an existing dir", stats)
        assertTrue(stats!!.totalBytes >= 0)
        assertTrue(stats.freeBytes >= 0)
        // Missing path must not throw.
        repo.statsOf(File(base, "nope"))
    }

}
