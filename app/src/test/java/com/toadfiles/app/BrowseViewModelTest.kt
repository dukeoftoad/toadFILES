package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.ui.browse.BrowseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
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
 * BrowseViewModel end-to-end on the JVM: real files, real DataStore, real trash —
 * with coroutine dispatchers controlled by the test scheduler so everything is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: BrowseViewModel
    private lateinit var container: com.toadfiles.app.AppContainer
    private lateinit var dir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = com.toadfiles.app.AppContainer(context)
        vm = BrowseViewModel(container, ioDispatcher = dispatcher)
        dir = File(context.cacheDir, "browse-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun await(block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!block()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within timeout" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `openDir lists real files with folders first and natural order`() {
        File(dir, "file10.txt").writeText("x")
        File(dir, "file2.txt").writeText("x")
        File(dir, "zeta").mkdirs()

        vm.openDir(dir)

        val names = vm.state.value.items.map { it.name }
        assertEquals(listOf("zeta", "file2.txt", "file10.txt"), names)
        assertTrue(vm.state.value.items[0].isDir)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `hidden files are hidden until setting enables them`() = runTest {
        File(dir, "visible.txt").writeText("x")
        File(dir, ".secret").writeText("x")
        container.settingsRepo.setShowHidden(true)

        vm.openDir(dir)
        await { vm.state.value.items.size == 2 }
        assertEquals(2, vm.state.value.items.size)
    }

    @Test
    fun `unreadable or missing folder surfaces an honest error`() {
        vm.openDir(File(dir, "nope"))
        assertEquals("Folder is not readable", vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `breadcrumbs start at the first opened folder`() {
        val sub = File(dir, "sub").apply { mkdirs() }
        vm.openDir(sub)
        vm.openDir(File(sub, "deeper").apply { mkdirs() })
        assertEquals(listOf(sub, File(sub, "deeper")), vm.state.value.breadcrumbs.map { it })
    }

    @Test
    fun `toggleSelection adds then removes an item`() {
        val f = File(dir, "a.txt").apply { writeText("x") }
        vm.openDir(dir)
        val item = vm.state.value.items.first()

        vm.toggleSelection(item)
        assertEquals(setOf(f), vm.state.value.selection)

        vm.toggleSelection(item)
        assertTrue(vm.state.value.selection.isEmpty())
    }

    @Test
    fun `clearSelection empties the selection`() {
        vm.openDir(dir)
        File(dir, "one.txt").writeText("x")
        vm.openDir(dir)
        vm.state.value.items.forEach { vm.toggleSelection(it) }
        assertFalse(vm.state.value.selection.isEmpty())

        vm.clearSelection()
        assertTrue(vm.state.value.selection.isEmpty())
    }

    @Test
    fun `selectAll only selects what the current filter shows`() {
        File(dir, "photo.jpg").writeText("x")
        File(dir, "notes.txt").writeText("x")
        vm.openDir(dir)
        vm.setFilter(FilterMode.IMAGES)

        vm.selectAll()

        assertEquals(setOf(File(dir, "photo.jpg")), vm.state.value.selection)
    }

    @Test
    fun `opening a new folder clears selection and query`() {
        File(dir, "a.txt").writeText("x")
        vm.openDir(dir)
        vm.toggleSelection(vm.state.value.items.first())

        val other = File(dir, "other").apply { mkdirs() }
        vm.openDir(other)

        assertTrue(vm.state.value.selection.isEmpty())
        assertEquals("", vm.state.value.query)
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `setQuery debounces then finds matches in nested folders`() = runTest {
        val nested = File(dir, "nested").apply { mkdirs() }
        File(nested, "report-final.docx").writeText("x")
        File(dir, "unrelated.bin").writeText("x")
        vm.openDir(dir)

        vm.setQuery("report")
        assertTrue(vm.state.value.searching)
        testScheduler.advanceTimeBy(301)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.searching)
        assertEquals(listOf("report-final.docx"), state.items.map { it.name })
    }

    @Test
    fun `setQuery with blank text does not launch a search`() = runTest {
        File(dir, "a.txt").writeText("x")
        vm.openDir(dir)
        testScheduler.advanceUntilIdle()

        vm.setQuery("   ")
        testScheduler.advanceTimeBy(1000)
        testScheduler.advanceUntilIdle()

        assertFalse(vm.state.value.searching)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun `createFolder makes a real folder and reports success`() {
        vm.openDir(dir)

        vm.createFolder("New Stuff")

        assertTrue(File(dir, "New Stuff").isDirectory)
        assertEquals("Folder \"New Stuff\" created", vm.state.value.toast)
        assertFalse(vm.state.value.pendingNewFolder)
    }

    @Test
    fun `createFolder reports failure for invalid names`() {
        vm.openDir(dir)

        vm.createFolder("bad/name")

        assertFalse(File(dir, "bad/name").exists())
        assertTrue(vm.state.value.toast!!.startsWith("Could not create folder"))
    }

    @Test
    fun `rename renames the file on disk`() = runTest {
        val f = File(dir, "old.txt").apply { writeText("content") }
        vm.openDir(dir)

        vm.rename(f, "new.txt")

        await { File(dir, "new.txt").exists() && vm.state.value.pendingRename == null }
        assertTrue(File(dir, "new.txt").exists())
        assertFalse(File(dir, "old.txt").exists())
        assertEquals("Renamed to \"new.txt\"", vm.state.value.toast)
    }

    @Test
    fun `rename with a taken name fails honestly`() = runTest {
        val f = File(dir, "one.txt").apply { writeText("x") }
        File(dir, "two.txt").writeText("y")
        vm.openDir(dir)

        vm.rename(f, "two.txt")

        await { vm.state.value.toast != null }
        assertTrue(vm.state.value.toast!!.startsWith("Rename failed"))
        assertTrue(f.exists()) // untouched
    }

    @Test
    fun `renaming a favorited file rewrites the stored favorite`() = runTest {
        val f = File(dir, "fav.txt").apply { writeText("x") }
        container.settingsRepo.toggleFavorite(f.path)
        vm.openDir(dir)
        await { vm.state.value.favoritePaths.contains(f.path) }

        vm.rename(f, "fav-renamed.txt")

        await { vm.state.value.favoritePaths.contains(File(dir, "fav-renamed.txt").path) }
        assertFalse(vm.state.value.favoritePaths.contains(f.path))
    }

    @Test
    fun `confirmDelete trashes and undoDelete restores`() = runTest {
        val f = File(dir, "doomed.txt").apply { writeText("precious") }
        vm.openDir(dir)

        vm.requestDelete(listOf(f))
        vm.confirmDelete()

        await { !f.exists() && vm.state.value.lastTrashed != null }
        assertFalse(f.exists())
        val trashed = vm.state.value.lastTrashed!!
        assertEquals(1, trashed.size)

        vm.undoDelete()
        await { f.exists() }
        assertEquals("precious", f.readText())
        assertNull(vm.state.value.lastTrashed)
    }

    @Test
    fun `copyToClipboard sets the shared clipboard and clears selection`() {
        val a = File(dir, "a.txt").apply { writeText("x") }
        vm.openDir(dir)
        vm.toggleSelection(vm.state.value.items.first())

        vm.copyToClipboard(Clipboard.Mode.COPY, listOf(a))

        assertEquals(Clipboard.Mode.COPY, container.clipboard.clip.value?.mode)
        assertTrue(vm.state.value.selection.isEmpty())
    }

    @Test
    fun `paste detects name conflicts and shows the conflict dialog`() = runTest {
        val srcDir = File(dir, "src").apply { mkdirs() }
        val srcFile = File(srcDir, "dup.txt").apply { writeText("source") }
        val dstDir = File(dir, "dst").apply { mkdirs() }
        File(dstDir, "dup.txt").writeText("existing")
        vm.openDir(dstDir)
        container.clipboard.set(Clipboard.Mode.COPY, listOf(srcFile))
        vm.openDir(dstDir)
        await { vm.state.value.clipboard != null }

        vm.pasteFromClipboard()

        await { vm.state.value.pendingConflict != null }
        assertEquals(listOf("dup.txt"), vm.state.value.pendingConflict!!.conflicts)
        assertEquals("existing", File(dstDir, "dup.txt").readText()) // nothing written yet
    }

    @Test
    fun `conflict REPLACE overwrites the destination file`() = runTest {
        val srcFile = File(dir, "src").apply { mkdirs() }.let { File(it, "dup.txt") }
        srcFile.writeText("source")
        val dstDir = File(dir, "dst").apply { mkdirs() }
        File(dstDir, "dup.txt").writeText("existing")
        vm.openDir(dstDir)
        container.clipboard.set(Clipboard.Mode.COPY, listOf(srcFile))
        await { vm.state.value.clipboard != null }
        vm.pasteFromClipboard()
        await { vm.state.value.pendingConflict != null }

        vm.resolveConflict(com.toadfiles.app.domain.ConflictStrategy.REPLACE)

        await { vm.state.value.result != null }
        assertEquals("source", File(dstDir, "dup.txt").readText())
        assertNull(container.clipboard.clip.value) // clipboard consumed
    }

    @Test
    fun `paste without conflicts copies immediately`() = runTest {
        val srcFile = File(dir, "src").apply { mkdirs() }.let { File(it, "clean.txt") }
        srcFile.writeText("data")
        val dstDir = File(dir, "dst").apply { mkdirs() }
        vm.openDir(dstDir)
        container.clipboard.set(Clipboard.Mode.MOVE, listOf(srcFile))
        await { vm.state.value.clipboard != null }

        vm.pasteFromClipboard()

        await { vm.state.value.result != null }
        assertTrue(File(dstDir, "clean.txt").exists())
        assertEquals("data", File(dstDir, "clean.txt").readText())
        assertFalse(srcFile.exists()) // MOVE removes the source
    }

    @Test
    fun `zipSelected writes a real archive into the current folder`() = runTest {
        val f = File(dir, "text.txt").apply { writeText("zip me") }
        vm.openDir(dir)

        vm.zipSelected(listOf(f))

        await { vm.state.value.result != null }
        val zip = vm.state.value.items.firstOrNull { it.name.endsWith(".zip") }
        assertNotNull("expected archive.zip to appear after zipping", zip)
        assertTrue(zip!!.size > 0)
    }

    @Test
    fun `dismissResult clears the result dialog`() = runTest {
        val f = File(dir, "t.txt").apply { writeText("x") }
        vm.openDir(dir)
        vm.zipSelected(listOf(f))
        await { vm.state.value.result != null }

        vm.dismissResult()

        assertNull(vm.state.value.result)
    }

    @Test
    fun `settings changes propagate into browse state`() = runTest {
        container.settingsRepo.setSortMode(com.toadfiles.app.domain.SortMode.SIZE)
        vm.openDir(dir)
        await { vm.state.value.settings.sortMode == com.toadfiles.app.domain.SortMode.SIZE }
    }
}
