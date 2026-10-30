package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.data.FileRepository
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.PropertiesBuilder
import com.toadfiles.app.ui.browse.BrowseViewModel
import com.toadfiles.app.ui.browse.OpKind
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
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
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

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
        assertFalse(state.searchCapped)
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
    fun `setQuery reports capped when the configured limit is reached`() = runTest {
        // 15 files all named with the prefix "match-" → one query, 15 hits, limit 10.
        repeat(15) { i -> File(dir, "match-$i.txt").writeText("x") }
        container.settingsRepo.setSearchResultLimit(10) // SEARCH_LIMIT_MIN, smallest legal value
        vm.openDir(dir)
        await { vm.state.value.settings.searchResultLimit == 10 }

        vm.setQuery("match")
        testScheduler.advanceTimeBy(301)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.searching)
        assertEquals(10, state.items.size)
        assertTrue("expected searchCapped to be true", state.searchCapped)
    }

    @Test
    fun `setQuery content search matches text inside small files`() = runTest {
        val f = File(dir, "notes.txt").apply { writeText("buy milk, fix the kitchen sink") }
        File(dir, "other.bin").apply { writeBytes(ByteArray(200)) } // wrong extension
        container.settingsRepo.setContentSearch(true)
        container.settingsRepo.setSearchResultLimit(10)
        vm.openDir(dir)
        await { vm.state.value.settings.contentSearch }

        // The query does NOT appear in the filename, only inside the file body.
        vm.setQuery("kitchen")
        testScheduler.advanceTimeBy(301)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.searching)
        assertEquals(listOf("notes.txt"), state.items.map { it.name })
    }

    @Test
    fun `setQuery content search is a no-op when the setting is off`() = runTest {
        File(dir, "notes.txt").apply { writeText("kitchen sink content here") }
        // contentSearch defaults to false
        vm.openDir(dir)

        vm.setQuery("kitchen")
        testScheduler.advanceTimeBy(301)
        testScheduler.advanceUntilIdle()

        // No matches: filename does not contain "kitchen", and content search is off.
        assertTrue(vm.state.value.items.isEmpty())
        assertFalse(vm.state.value.searchCapped)
    }

    @Test
    fun `setQuery content search skips files larger than the size cap`() = runTest {
        // A ~2 MiB text file: bigger than the 1 MiB content-search ceiling.
        val big = File(dir, "big.txt").apply {
            writeText("kitchen ".repeat(256 * 1024)) // ~2 MiB of "kitchen kitchen ..."
        }
        assertTrue(big.length() > 1024 * 1024)
        container.settingsRepo.setContentSearch(true)
        vm.openDir(dir)
        await { vm.state.value.settings.contentSearch }

        vm.setQuery("kitchen")
        testScheduler.advanceTimeBy(301)
        testScheduler.advanceUntilIdle()

        // Filename doesn't contain "kitchen", and the file body is too large to peek.
        // The result is empty (capped stays false because we never reached the limit).
        val state = vm.state.value
        assertTrue(state.items.isEmpty())
        assertFalse(state.searchCapped)
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

        // Await the toast, not just the file: the rename lands on disk microseconds
        // before the state update is committed, and polling the file alone raced on CI.
        await { vm.state.value.toast == "Renamed to \"new.txt\"" && !File(dir, "old.txt").exists() }
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
    fun `renaming a pinned folder keeps its Quick access pin and usage history`() = runTest {
        val folder = File(dir, "pinned").apply { mkdirs() }
        container.settingsRepo.toggleQuickPin(folder.path)
        container.settingsRepo.recordFolderUse(folder.path)
        vm.openDir(dir)
        await { vm.state.value.quickPins.contains(folder.path) }

        vm.rename(folder, "pinned-renamed")

        val renamed = File(dir, "pinned-renamed").path
        await { vm.state.value.quickPins.contains(renamed) }
        assertFalse(vm.state.value.quickPins.contains(folder.path))
        // Usage history follows the folder too (P2.8).
        assertEquals(1, container.settingsRepo.folderUses.value[renamed])
        assertNull(container.settingsRepo.folderUses.value[folder.path])
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

        // Await the archive in the listing, not just the result dialog: result is set
        // while the refreshed folder listing is still in flight on a real IO thread,
        // and asserting on items right after the dialog raced on CI.
        await { vm.state.value.result != null && vm.state.value.items.any { it.name.endsWith(".zip") } }
        val zip = vm.state.value.items.firstOrNull { it.name.endsWith(".zip") }
        assertNotNull("expected archive.zip to appear after zipping", zip)
        assertTrue(zip!!.size > 0)
    }

    @Test
    fun `zip flow asks for a name and zips under the typed name`() = runTest {
        val f = File(dir, "doc.txt").apply { writeText("zip me") }
        vm.openDir(dir)

        vm.requestZip(listOf(f))
        assertEquals(listOf(f), vm.state.value.pendingZipFiles)
        assertEquals("doc.zip", vm.state.value.pendingZipName)

        vm.confirmZip("holiday pack") // no .zip suffix — appended automatically

        // Same race as zipSelected: await the archive showing up in the listing,
        // not just the result dialog.
        await { vm.state.value.result != null && vm.state.value.items.any { it.name == "holiday pack.zip" } }
        assertNull(vm.state.value.pendingZipFiles)
        assertNotNull(
            "archive should use the typed name",
            vm.state.value.items.firstOrNull { it.name == "holiday pack.zip" },
        )
    }

    @Test
    fun `confirmZip rejects names that try to escape the folder`() = runTest {
        val f = File(dir, "x.txt").apply { writeText("x") }
        vm.openDir(dir)

        vm.requestZip(listOf(f))
        vm.confirmZip("../evil.zip")

        // Dialog stays open, nothing is written, the user is told why.
        assertNotNull(vm.state.value.pendingZipFiles)
        assertEquals("Enter a valid archive name", vm.state.value.toast)
        assertTrue(vm.state.value.items.none { it.name.endsWith(".zip") })
    }

    /** Minimal archive builder (same shape as ZipSupportTest's private helper). */
    private fun makeZip(out: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `extractZip without a target keeps extracting into a sibling folder`() = runTest {
        val zip = File(dir, "plain.zip")
        makeZip(zip, mapOf("a.txt" to "A".toByteArray(), "sub/b.txt" to "B".toByteArray()))
        vm.openDir(dir)

        vm.extractZip(zip)

        await { vm.state.value.result != null && vm.state.value.items.any { it.name == "plain" } }
        assertEquals(0, vm.state.value.result!!.failed)
        assertEquals("A", File(dir, "plain/a.txt").readText())
        assertEquals("B", File(dir, "plain/sub/b.txt").readText())
        assertNull(vm.state.value.progress)
        assertEquals("Extracting", vm.state.value.resultTitle)
    }

    @Test
    fun `extractZip default still refuses when the sibling folder exists`() = runTest {
        val zip = File(dir, "plain.zip")
        makeZip(zip, mapOf("a.txt" to "A".toByteArray()))
        File(dir, "plain").mkdirs()
        vm.openDir(dir)

        vm.extractZip(zip)

        assertEquals(
            "A folder named \"plain\" already exists. Rename or move it before extracting.",
            vm.state.value.toast,
        )
        assertNull(vm.state.value.result)
        assertNull(vm.state.value.progress)
        assertFalse("nothing may be extracted on refusal", File(dir, "plain/a.txt").exists())
    }

    @Test
    fun `extractZip with a target name extracts into that folder under the current directory`() = runTest {
        // The archive lives in a subfolder (a search-style row): "Extract to…" must
        // land the content under the CURRENT directory, not the archive's parent.
        val nested = File(dir, "nested").apply { mkdirs() }
        val zip = File(nested, "pack.zip")
        makeZip(zip, mapOf("one.txt" to "hello".toByteArray()))
        vm.openDir(dir)

        vm.extractZip(zip, targetName = "unpacked")

        await { vm.state.value.result != null && vm.state.value.items.any { it.name == "unpacked" } }
        assertEquals(0, vm.state.value.result!!.failed)
        assertEquals("hello", File(dir, "unpacked/one.txt").readText())
    }

    @Test
    fun `extractZip to an existing folder name asks via the conflict dialog and Replace overwrites`() = runTest {
        val zip = File(dir, "pack.zip")
        makeZip(zip, mapOf("same.txt" to "NEW".toByteArray()))
        File(dir, "unpacked").apply { mkdirs() }.resolve("same.txt").writeText("OLD")
        vm.openDir(dir)

        vm.extractZip(zip, targetName = "unpacked")

        // Same collision flow as paste: no silent overwrite, the dialog decides.
        assertNotNull(vm.state.value.pendingConflict)
        assertEquals(OpKind.ZIP_EXTRACT, vm.state.value.pendingConflict!!.op)
        assertNull(vm.state.value.result)
        assertNull(vm.state.value.progress)

        vm.resolveConflict(ConflictStrategy.REPLACE)

        await { vm.state.value.result != null && vm.state.value.items.isNotEmpty() }
        assertEquals("NEW", File(dir, "unpacked/same.txt").readText())
        assertNull(vm.state.value.pendingConflict)
    }

    @Test
    fun `extractZip conflict Keep both extracts into a uniquely named sibling folder`() = runTest {
        val zip = File(dir, "pack.zip")
        makeZip(zip, mapOf("n.txt" to "N".toByteArray()))
        File(dir, "unpacked").apply { mkdirs() }.resolve("n.txt").writeText("OLD")
        vm.openDir(dir)

        vm.extractZip(zip, targetName = "unpacked")
        assertNotNull(vm.state.value.pendingConflict)
        vm.resolveConflict(ConflictStrategy.RENAME)

        await { vm.state.value.result != null }
        assertEquals(0, vm.state.value.result!!.failed)
        assertEquals("N", File(dir, "unpacked (1)/n.txt").readText())
        // The pre-existing folder is untouched.
        assertEquals("OLD", File(dir, "unpacked/n.txt").readText())
    }

    @Test
    fun `clearing the search restores the folder listing`() = runTest {
        val nested = File(dir, "nested").apply { mkdirs() }
        File(nested, "needle.txt").writeText("x")
        File(dir, "regular.txt").writeText("x")
        vm.openDir(dir)
        testScheduler.advanceUntilIdle()

        vm.setQuery("needle")
        testScheduler.advanceTimeBy(301)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("needle.txt"), vm.state.value.items.map { it.name })

        vm.setQuery("")
        testScheduler.advanceUntilIdle()

        // Folder contents come back — no stale search results.
        val names = vm.state.value.items.map { it.name }
        assertTrue("regular.txt" in names)
        assertFalse("needle.txt" in names) // it lives in a subfolder, not this listing
        assertFalse(vm.state.value.searching)
        assertEquals("", vm.state.value.query)
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

    @Test
    fun `confirmDelete bumps trashCount and undoDelete brings it back down`() = runTest {
        // Bring trashCount to a known 0 baseline.
        val f = File(dir, "trashme.txt").apply { writeText("x") }
        vm.openDir(dir)
        await { vm.state.value.trashCount >= 0 }

        vm.requestDelete(listOf(f))
        vm.confirmDelete()
        await { !f.exists() && vm.state.value.lastTrashed != null }
        await { vm.state.value.trashCount >= 1 }

        vm.undoDelete()
        await { f.exists() }
        await { vm.state.value.trashCount == 0 }
    }

    @Test
    fun `refreshTrashCount updates the state with the current in-trash count`() = runTest {
        // Directly drop two files into the trash folder so the next refresh picks them up.
        val roots = container.fileRepo.roots().map { it.dir }
        val trashDir = com.toadfiles.app.domain.Trash.dirFor(roots.first())
        trashDir.mkdirs()
        File(trashDir, "manually-trashed.txt").writeText("x")
        File(trashDir, "another.txt").writeText("y")

        vm.refreshTrashCount()

        await { vm.state.value.trashCount >= 2 }
        assertTrue("trashCount should be >= 2 (got ${vm.state.value.trashCount})",
            vm.state.value.trashCount >= 2)
    }

    // -------------------------------------------------------------------------
    // P2.1 / P2.8 audit follow-ups: search-cycle and stale-load regressions.
    // These use a daemon-thread IO dispatcher plus a gated FileRepository double
    // (same pattern as the GatedMedia race tests) so the interleaving below is
    // deterministic, not timing-dependent.
    // -------------------------------------------------------------------------

    private val raceExecutors = mutableListOf<ExecutorService>()

    /** Daemon threads: a parked latch must never keep the test JVM alive after a failure. */
    private fun raceIoDispatcher(): CoroutineDispatcher =
        Executors.newFixedThreadPool(2) { r -> Thread(r, "browse-race-io").apply { isDaemon = true } }
            .also { raceExecutors += it }
            .asCoroutineDispatcher()

    @After
    fun tearDownRaceExecutors() {
        raceExecutors.forEach { it.shutdownNow() }
    }

    /**
     * FileRepository double that can park one list() call on a latch so a load or a
     * search can be held mid-flight deterministically. The snapshot is taken BEFORE
     * parking, so a released stale load carries older data than a newer load that ran
     * while it was parked.
     */
    private class GatedFileRepo(context: Context) : FileRepository(context) {
        val listCalls = AtomicInteger(0)
        private val armed = AtomicBoolean(false)
        /** Fires when the gated list() call is parked. */
        val parked = CountDownLatch(1)
        private val release = CountDownLatch(1)

        /** Parks the NEXT list() call (exactly one) until [releaseGate] is called. */
        fun armGate() = armed.set(true)

        fun releaseGate() = release.countDown()

        override fun list(dir: File, showHidden: Boolean): List<FileItem> {
            val result = super.list(dir, showHidden)
            listCalls.incrementAndGet()
            if (armed.compareAndSet(true, false)) {
                parked.countDown()
                release.await()
            }
            return result
        }
    }

    private fun listingOf(vm: BrowseViewModel) = vm.state.value.items.map { it.name }

    @Test
    fun `search in a tree with a symlink back to an ancestor terminates`() = runTest {
        val nested = File(dir, "nested").apply { mkdirs() }
        File(nested, "needle.txt").writeText("x")
        val link = File(dir, "loop")
        try {
            Files.createSymbolicLink(link.toPath(), dir.toPath())
        } catch (_: Exception) {
            assumeTrue("filesystem does not support symbolic links", false)
        }
        // Real background IO thread: if the traversal ever spun forever, the awaited
        // condition below fails with a clear timeout instead of freezing the runner.
        val vm = BrowseViewModel(container, ioDispatcher = raceIoDispatcher())
        vm.openDir(dir)
        await { listingOf(vm).isNotEmpty() }

        vm.setQuery("needle")
        // Without the cycle guard this search never returns: the "loop" symlink points
        // back at the root and the cap is never reached (1 match < the configured limit).
        await { !vm.state.value.searching && listingOf(vm) == listOf("needle.txt") }
        assertFalse(vm.state.value.searchCapped)
    }

    @Test
    fun `directory symlinks surface in search by name but are never recursed into`() = runTest {
        val nested = File(dir, "nested").apply { mkdirs() }
        File(nested, "buried.txt").writeText("x")
        val link = File(dir, "loop")
        try {
            Files.createSymbolicLink(link.toPath(), dir.toPath())
        } catch (_: Exception) {
            assumeTrue("filesystem does not support symbolic links", false)
        }
        val vm = BrowseViewModel(container, ioDispatcher = raceIoDispatcher())
        vm.openDir(dir)
        await { listingOf(vm).isNotEmpty() }

        vm.setQuery("loop")
        // "loop" itself matches by name; recursing into it (the old behaviour) would
        // revisit the root forever and never return.
        await { !vm.state.value.searching && listingOf(vm) == listOf("loop") }
        assertTrue(vm.state.value.items.single().isDir) // still shown as a folder item

        vm.setQuery("buried")
        // Real subfolders are still traversed — the policy only skips symlinked ones.
        await { !vm.state.value.searching && listingOf(vm) == listOf("buried.txt") }
    }

    @Test
    fun `cancelling a search stops the traversal at the next checkpoint`() = runTest {
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "needle.txt").writeText("x")
        val repo = GatedFileRepo(ApplicationProvider.getApplicationContext())
        val vm = BrowseViewModel(container, ioDispatcher = raceIoDispatcher(), fileRepo = repo)
        vm.openDir(dir)
        await { listingOf(vm) == listOf("sub") }
        assertEquals(1, repo.listCalls.get())

        // Park the search inside its first directory listing — a RUNNING query, not an
        // undispatched one (same trick as the GatedMedia race tests).
        repo.armGate()
        vm.setQuery("needle")
        assertTrue("search never reached its first directory", repo.parked.await(10, TimeUnit.SECONDS))
        assertEquals(2, repo.listCalls.get())

        // Cancel by clearing the query while the traversal is parked mid-directory.
        vm.setQuery("")
        // The cancel-path load() is the 3rd list() call; wait for it to land (the listing
        // itself may already read [sub] from openDir, so count the call, not the state).
        await { repo.listCalls.get() >= 3 && !vm.state.value.loading }

        // Release the parked traversal: it must abort at its next ensureActive()
        // checkpoint — no further directories listed, no search result applied.
        repo.releaseGate()
        Thread.sleep(250) // beat: an uncheckpointed traversal would have listed `sub` by now
        assertEquals(
            "cancelled search kept traversing after release (missing checkpoint)",
            3, repo.listCalls.get(),
        )
        assertEquals(listOf("sub"), listingOf(vm)) // folder listing, not search results
        assertFalse(vm.state.value.searching)
        assertEquals("", vm.state.value.query)
    }

    @Test
    fun `a slower older load of the same directory cannot overwrite the newer listing`() = runTest {
        val repo = GatedFileRepo(ApplicationProvider.getApplicationContext())
        val vm = BrowseViewModel(container, ioDispatcher = raceIoDispatcher(), fileRepo = repo)

        // Load #1 parks inside list() holding the folder snapshot taken right now.
        File(dir, "first-only.txt").writeText("x")
        repo.armGate()
        vm.openDir(dir)
        assertTrue("first load never parked", repo.parked.await(10, TimeUnit.SECONDS))

        // Load #2 — same directory, rapid refresh — completes while #1 is still parked.
        File(dir, "second-only.txt").writeText("x")
        vm.openDir(dir)
        await { !vm.state.value.loading && listingOf(vm) == listOf("first-only.txt", "second-only.txt") }

        // Release the stale load: its older snapshot (missing second-only.txt) must be
        // dropped — the newer request's listing is the one that stays.
        repo.releaseGate()
        Thread.sleep(250) // beat: a wrongly-applied stale result would have landed by now
        assertEquals(listOf("first-only.txt", "second-only.txt"), listingOf(vm))
        assertFalse(vm.state.value.loading)
    }

    // -------------------------------------------------------------------------
    // Properties sheet: state wiring, real folder computation, cancel-on-dismiss.
    // -------------------------------------------------------------------------

    @Test
    fun `requestProperties sets state and computes file properties`() = runTest {
        val f = File(dir, "note.txt").apply { writeText("x".repeat(2048)) }

        vm.requestProperties(f)

        assertEquals(f, vm.state.value.pendingProperties)
        await { vm.state.value.propertiesDetail != null }
        val d = vm.state.value.propertiesDetail!!
        assertEquals(Format.size(2048), d.sizeText)
        assertEquals(2048L, d.sizeBytes)
        assertEquals(0, d.fileCount) // files carry no contents counts
        assertEquals(0, d.dirCount)
        assertNull(d.error)
    }

    @Test
    fun `requestProperties computes folder size and contents with the real helpers`() = runTest {
        val sub = File(dir, "library").apply { mkdirs() }
        File(sub, "a.txt").writeText("x".repeat(1024))
        File(sub, "b.txt").writeText("x".repeat(1024))
        File(sub, "nested").mkdirs()
        File(sub, "nested/c.bin").writeText("x".repeat(512))

        vm.requestProperties(sub)

        assertEquals(sub, vm.state.value.pendingProperties)
        await { vm.state.value.propertiesDetail != null }
        val d = vm.state.value.propertiesDetail!!
        // Real FileOps.sizeOf + PropertiesBuilder counts over the temp tree.
        assertEquals(2560L, d.sizeBytes)
        assertEquals(Format.size(2560), d.sizeText)
        assertEquals(3, d.fileCount)
        assertEquals(1, d.dirCount)
        assertNull(d.error)
    }

    /**
     * Gated counts walk: parks the properties computation mid-flight like [GatedFileRepo]
     * parks a list(), but as a cancellable suspension — so a dismissal that actually
     * cancels the job can never reach the post-park code, even after the gate is released.
     */
    private class GatedPropertiesBuilder : PropertiesBuilder() {
        val entered = CountDownLatch(1)
        private var parked: CancellableContinuation<Unit>? = null
        /** Set only when the walk was released and ran to completion (i.e. NOT cancelled). */
        val finished = AtomicBoolean(false)

        /** Releases the parked computation; a no-op when dismiss already cancelled the job. */
        fun release() {
            parked?.resume(Unit)
        }

        override suspend fun countEntries(root: File): PropertiesBuilder.Counts {
            entered.countDown()
            suspendCancellableCoroutine<Unit> { cont -> parked = cont }
            finished.set(true)
            return super.countEntries(root)
        }
    }

    @Test
    fun `dismissProperties cancels the in-flight folder computation`() = runTest {
        val gated = GatedPropertiesBuilder()
        val vm = BrowseViewModel(container, ioDispatcher = dispatcher, propertiesBuilder = gated)
        val sub = File(dir, "big").apply { mkdirs() }
        File(sub, "f.bin").writeText("x")

        vm.requestProperties(sub)
        assertTrue("computation never reached the counts walk", gated.entered.await(10, TimeUnit.SECONDS))
        assertEquals(sub, vm.state.value.pendingProperties)
        assertNull(vm.state.value.propertiesDetail)

        // Dismiss while parked mid-computation, then release the gate: the cancelled job
        // must never resume past its parking point (no walk completion, no detail applied).
        vm.dismissProperties()
        gated.release()
        assertFalse("cancelled computation kept running after release", gated.finished.get())
        assertNull(vm.state.value.pendingProperties)
        assertNull(vm.state.value.propertiesDetail)
    }
}
