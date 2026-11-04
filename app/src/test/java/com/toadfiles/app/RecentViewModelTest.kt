package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.recent.RecentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Recent tab state machine: entries load from the media library, multi-select toggles by
 * uri, delete-to-trash moves real files, and stale selections are dropped on refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecentViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: RecentViewModel
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        vm = RecentViewModel(container, ioDispatcher = dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun await(block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!block()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within timeout" }
            Thread.sleep(10)
        }
    }

    private fun entry(id: Long, name: String, data: String? = null) = MediaRepository.MediaEntry(
        id = id,
        uri = android.net.Uri.parse("content://test/$id"),
        name = name,
        size = 5L,
        dateModified = 0L,
        mime = "text/plain",
        category = FileCategory.DOCS,
        data = data,
    )

    /** Records row-hygiene args and rescan paths so delete/undo honesty is assertable. */
    private class RecordingMedia : MediaQuerySource {
        val deleteStaleArgs = mutableListOf<List<MediaRepository.MediaEntry>>()
        val rescanned = mutableListOf<String>()

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> = emptyList()
        override fun recent(limit: Int): List<MediaRepository.MediaEntry> = emptyList()
        override fun summaries(): List<CategorySummary> =
            FileCategory.entries.map { CategorySummary(it, 0, 0L) }

        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int {
            deleteStaleArgs += entries
            return entries.size
        }

        override fun rescan(paths: List<String>) {
            rescanned += paths
        }
    }

    @Test
    fun `init loads and clears the loading flag`() = runTest {
        await { !vm.state.value.loading }
        // Robolectric media library is empty — an honest empty state, not an error.
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test
    fun `filter tabs switch the query and reload`() = runTest {
        await { !vm.state.value.loading }
        assertNull(vm.state.value.filter)

        vm.setFilter(com.toadfiles.app.domain.FileCategory.IMAGES)
        assertEquals(com.toadfiles.app.domain.FileCategory.IMAGES, vm.state.value.filter)
        await { !vm.state.value.loading }

        // Back to "All" — the filter clears and the list settles again.
        vm.setFilter(null)
        assertNull(vm.state.value.filter)
        await { !vm.state.value.loading }

        // Setting the same filter twice is a no-op (no reload churn).
        vm.setFilter(null)
        assertNull(vm.state.value.filter)
    }

    @Test
    fun `toast is consumed after being shown`() = runTest {
        vm.requestToast("Moved 1 item to trash")
        assertEquals("Moved 1 item to trash", vm.state.value.toast)
        vm.consumeToast()
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `selection toggles by uri and clears on demand`() = runTest {
        val a = entry(1, "a.txt")
        vm.toggleSelection(a)
        assertEquals(1, vm.state.value.selection.size)
        vm.toggleSelection(a)
        assertTrue(vm.state.value.selection.isEmpty())
        vm.toggleSelection(entry(2, "b.txt"))
        vm.clearSelection()
        assertTrue(vm.state.value.selection.isEmpty())
    }

    @Test
    fun `confirmDelete then undo restores the file, rescans it and stages the offer`() = runTest {
        val fake = RecordingMedia()
        val undoVm = RecentViewModel(container, ioDispatcher = dispatcher, media = fake)
        val src = java.io.File(container.fileRepo.primaryRoot(), "recent-delete-me.txt")
        src.parentFile.mkdirs()
        src.writeText("delete me")
        try {
            undoVm.requestDelete(listOf(entry(1, src.name, data = src.absolutePath)))
            undoVm.confirmDelete()
            // Full success is reported by the screen's Undo snackbar (Browse/Categories
            // parity), not a toast; the trashed payloads are staged with a fresh offer id.
            await { undoVm.state.value.lastTrashed != null }
            assertNull(undoVm.state.value.toast)
            assertEquals(1L, undoVm.state.value.undoOfferSeq)
            assertFalse(src.exists())
            // Row hygiene sees ONLY the successfully trashed entry.
            assertEquals(listOf(1L), fake.deleteStaleArgs.single().map { it.id })

            // Undo: the file goes straight back and is re-scanned into the media index.
            val trashed = undoVm.state.value.lastTrashed!!
            undoVm.consumeUndoOffer()
            assertNull(undoVm.state.value.lastTrashed)
            undoVm.undoDelete(trashed)
            await { undoVm.state.value.toast == "Restored 1 item" }
            assertTrue(src.exists())
            assertEquals(listOf(src.absolutePath), fake.rescanned)
        } finally {
            src.delete()
        }
    }

    @Test
    fun `confirmDelete skips stale entries, reports them and never row-drops them`() = runTest {
        val fake = RecordingMedia()
        val undoVm = RecentViewModel(container, ioDispatcher = dispatcher, media = fake)
        val live = java.io.File(container.fileRepo.primaryRoot(), "recent-live.txt")
        live.parentFile.mkdirs()
        live.writeText("trash me")
        // The cached path is gone: the file was moved or renamed OUTSIDE the app.
        val staleEntry = entry(2, "renamed-away.jpg", data = "/storage/emulated/0/DCIM/renamed-away.jpg")
        try {
            undoVm.requestDelete(listOf(entry(1, live.name, data = live.absolutePath), staleEntry))
            undoVm.confirmDelete()
            await { undoVm.state.value.toast != null }
            assertEquals(
                "Moved 1 to trash · 1 were moved or renamed outside the app and were skipped",
                undoVm.state.value.toast,
            )
            // The stale row (whose CURRENT data may point at a real file) is never
            // handed to row hygiene — only the entry that really moved is.
            assertEquals(listOf(1L), fake.deleteStaleArgs.single().map { it.id })
            // The undo offer covers only what really moved, and the offer id advanced.
            assertEquals(1, undoVm.state.value.lastTrashed?.size)
            assertEquals(1L, undoVm.state.value.undoOfferSeq)
            assertFalse(live.exists())
        } finally {
            live.delete()
        }
    }

    @Test
    fun `confirmDelete reports pathless entries honestly and touches no rows`() = runTest {
        val fake = RecordingMedia()
        val undoVm = RecentViewModel(container, ioDispatcher = dispatcher, media = fake)
        undoVm.requestDelete(listOf(entry(1, "orphan.txt", data = null)))
        undoVm.confirmDelete()
        await { undoVm.state.value.toast != null }
        assertEquals("Moved 0 to trash · 1 had no on-disk path", undoVm.state.value.toast)
        // Nothing moved → nothing handed to row hygiene, nothing staged for undo —
        // but the offer id still advances so the screen cannot show a stale snackbar.
        assertTrue(fake.deleteStaleArgs.single().isEmpty())
        assertNull(undoVm.state.value.lastTrashed)
        assertEquals(1L, undoVm.state.value.undoOfferSeq)
    }

    @Test
    fun `requestDelete with nothing is a no-op`() = runTest {
        vm.requestDelete(emptyList())
        assertNull(vm.state.value.pendingDelete)
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
    fun `extractZip runs the real op and surfaces progress then result`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "recent-extract-${System.nanoTime()}")
        root.mkdirs()
        try {
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))

            vm.extractZip(zip)

            // The op shows progress, then finishes with an honest result and the files on disk.
            await { vm.state.value.result != null }
            assertNull(vm.state.value.progress)
            assertEquals("Extracting", vm.state.value.resultTitle)
            assertEquals(1, vm.state.value.result!!.succeeded)
            assertEquals(0, vm.state.value.result!!.failed)
            assertEquals("zipbody", File(root, "bundle/inner.txt").readText())

            vm.dismissResult()
            assertNull(vm.state.value.result)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `extractZip to an existing destination folder asks via the conflict dialog, not a dead-end toast`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "recent-extract-${System.nanoTime()}")
        root.mkdirs()
        try {
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))
            File(root, "bundle").mkdirs()

            vm.extractZip(zip)

            // Same collision flow as Browse/Categories: the dialog decides, nothing is
            // extracted yet and no toast sends the user away to rename things.
            val pc = vm.state.value.pendingExtractConflict
            assertNotNull(pc)
            assertEquals("bundle", pc!!.target.name)
            assertNull(vm.state.value.result)
            assertNull(vm.state.value.toast)
            assertFalse("nothing may be extracted before the user decides", File(root, "bundle (1)").exists())

            vm.resolveExtractConflict(ConflictStrategy.RENAME)

            await { vm.state.value.result != null }
            assertEquals(0, vm.state.value.result!!.failed)
            assertEquals("zipbody", File(root, "bundle (1)/inner.txt").readText())
            // Keep both leaves the pre-existing folder untouched.
            assertFalse(File(root, "bundle/inner.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancelOp cancels the shared op job before the extract runs`() = runTest {
        // A queued (not unconfined) dispatcher freezes the op before it starts, so the
        // cancel below is deterministic: the helper's job is cancelled before the extract
        // body can do any work — overlay dismissed, no result, nothing extracted.
        val queued = StandardTestDispatcher(testScheduler)
        val slowVm = RecentViewModel(container, ioDispatcher = queued)
        val root = File(container.fileRepo.primaryRoot(), "recent-cancel-${System.nanoTime()}")
        root.mkdirs()
        try {
            testScheduler.advanceUntilIdle() // settle init's refresh
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))

            slowVm.extractZip(zip)
            assertTrue(slowVm.state.value.progress != null) // overlay up pre-op (seed is synchronous)

            slowVm.cancelOp()
            // Audit A1: cancel clears the overlay only after the op job fully stopped
            // (join-before-clear). Virtual time drains the join and the state update
            // deterministically, so idling once covers both the cancel and the clear.
            testScheduler.advanceUntilIdle()
            assertNull(slowVm.state.value.progress)
            testScheduler.advanceUntilIdle() // the cancelled job never starts the extract
            assertNull(slowVm.state.value.result)
            assertFalse(File(root, "bundle").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- v1.8.0: extract password prompt ----------

    /**
     * TODO(integration): needs Agent 1's domain (AES-256 7z creation + password-aware
     * extraction). Covers the Recent extract password-prompt flow: a passwordRequired
     * result opens the prompt (never a failure dialog), and Retry re-runs the same
     * extract with the typed password. Enable when the domain contract has landed.
     */
    @Test
    fun `passwordRequired opens the prompt and retry extracts with the password`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "recent-prompt-${System.nanoTime()}")
        root.mkdirs()
        try {
            val src = File(root, "a.txt").apply { writeText("secret stuff") }
            val vault = File(root, "vault.7z")
            com.toadfiles.app.domain.ArchiveOps.compress(
                listOf(src), vault, com.toadfiles.app.domain.ArchiveFormat.SEVEN_Z,
                password = "hunter2".toCharArray(),
            )

            vm.extractZip(vault)
            await { vm.state.value.pendingPasswordFor != null }
            assertNull("a password ask is not a failure", vm.state.value.result)
            assertEquals(vault, vm.state.value.pendingPasswordFor)
            assertEquals(vault, vm.state.value.pendingPasswordRetry!!.archive)
            assertEquals(File(root, "vault"), vm.state.value.pendingPasswordRetry!!.target)

            vm.retryExtractWithPassword("hunter2")
            await { vm.state.value.result != null }
            assertNull(vm.state.value.pendingPasswordFor)
            assertEquals(0, vm.state.value.result!!.failed)
            assertEquals("secret stuff", File(root, "vault/a.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `layout toggle flips list and grid and persists across view models`() {
        // v1.8.1: Recent's list/grid choice is real state backed by the settings store.
        assertEquals(com.toadfiles.app.domain.BrowseLayout.LIST, vm.state.value.layout)
        vm.toggleLayout()
        await { vm.state.value.layout == com.toadfiles.app.domain.BrowseLayout.GRID }
        // A fresh ViewModel (new process / relaunch) must read the persisted value.
        val relaunched = RecentViewModel(container, ioDispatcher = dispatcher)
        await { relaunched.state.value.layout == com.toadfiles.app.domain.BrowseLayout.GRID }
        // Flip back so the shared DataStore file is left in the default state.
        relaunched.toggleLayout()
        await { relaunched.state.value.layout == com.toadfiles.app.domain.BrowseLayout.LIST }
    }
}
