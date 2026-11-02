package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
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
        category = com.toadfiles.app.domain.FileCategory.DOCS,
        data = data,
    )

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
        vm.requestToast("Moved 1 item(s) to trash")
        assertEquals("Moved 1 item(s) to trash", vm.state.value.toast)
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
    fun `confirmDelete stages the payloads for undo and undoDelete restores them`() = runTest {
        val src = java.io.File(container.fileRepo.primaryRoot(), "recent-undo-me.txt")
        src.parentFile.mkdirs()
        src.writeText("delete me")
        try {
            vm.requestDelete(listOf(entry(1, src.name, data = src.absolutePath)))
            vm.confirmDelete()

            // The payload really moved into the per-volume trash dir and is offered for
            // undo; full success is reported by the screen's Undo snackbar, so no plain
            // toast competes with it (Categories/Browse parity).
            await { vm.state.value.lastTrashed?.size == 1 }
            assertNull(vm.state.value.toast)
            assertFalse(src.exists())
            val trashDir = java.io.File(
                container.fileRepo.primaryRoot(),
                com.toadfiles.app.domain.Trash.DIR_NAME,
            )
            val payload = vm.state.value.lastTrashed!!.single()
            assertTrue(payload.absolutePath.startsWith(trashDir.absolutePath))
            assertEquals("delete me", payload.readText())

            // Undo (the one-shot offer consumed by the screen first) puts it right back.
            val trashed = vm.state.value.lastTrashed!!
            vm.consumeUndoOffer()
            assertNull(vm.state.value.lastTrashed)
            vm.undoDelete(trashed)
            await { vm.state.value.toast == "Restored 1 item(s)" }
            assertTrue(src.exists())
            assertFalse(payload.exists())
        } finally {
            src.delete()
        }
    }

    @Test
    fun `undoDelete re-scans the restored paths through the injected media source`() = runTest {
        val rec = RecordingMedia()
        val undoVm = RecentViewModel(container, ioDispatcher = dispatcher, media = rec)
        val src = java.io.File(container.fileRepo.primaryRoot(), "recent-rescan-me.txt")
        src.parentFile.mkdirs()
        src.writeText("rescan me")
        try {
            undoVm.requestDelete(listOf(entry(1, src.name, data = src.absolutePath)))
            undoVm.confirmDelete()
            await { undoVm.state.value.lastTrashed?.size == 1 }
            val trashed = undoVm.state.value.lastTrashed!!
            undoVm.consumeUndoOffer()
            undoVm.undoDelete(trashed)

            await { undoVm.state.value.toast != null }
            assertTrue(src.exists())
            // The restored file was handed back to the media source for re-indexing (D1):
            // the real repository runs the platform media scanner over exactly these paths.
            await { rec.rescanned.isNotEmpty() }
            assertEquals(listOf(src.absolutePath), rec.rescanned.single())
        } finally {
            src.delete()
        }
    }

    @Test
    fun `a newer delete replaces the pending undo offer instead of stacking onto it`() = runTest {
        val a = java.io.File(container.fileRepo.primaryRoot(), "recent-offer-a.txt")
        a.parentFile.mkdirs()
        a.writeText("a")
        val b = java.io.File(container.fileRepo.primaryRoot(), "recent-offer-b.txt")
        b.writeText("b")
        try {
            vm.requestDelete(listOf(entry(1, a.name, data = a.absolutePath)))
            vm.confirmDelete()
            await { vm.state.value.lastTrashed?.size == 1 }
            val firstSeq = vm.state.value.undoOfferSeq

            // A second delete bumps the offer sequence and swaps the payload list: the
            // snackbar about to show belongs to the newer batch, and its Undo restores
            // exactly that batch — never a stale mixture (D2 one-shot offer).
            vm.requestDelete(listOf(entry(2, b.name, data = b.absolutePath)))
            vm.confirmDelete()
            await { vm.state.value.undoOfferSeq > firstSeq }
            val secondOffer = vm.state.value.lastTrashed!!
            assertEquals(1, secondOffer.size)
            assertTrue(secondOffer.single().name.endsWith(b.name))

            vm.consumeUndoOffer()
            vm.undoDelete(secondOffer)
            await { vm.state.value.toast == "Restored 1 item(s)" }
            assertTrue(b.exists())
            assertFalse(a.exists()) // the first batch stays trashed until its own undo
        } finally {
            a.delete()
            b.delete()
        }
    }

    @Test
    fun `confirmDelete reports pathless entries honestly`() = runTest {
        vm.requestDelete(listOf(entry(1, "orphan.txt", data = null)))
        vm.confirmDelete()
        await { vm.state.value.toast != null }
        assertEquals("Moved 0 to trash · 1 had no on-disk path", vm.state.value.toast)
        assertNull("nothing moved — there is no undo to offer", vm.state.value.lastTrashed)
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
    fun `extractZip refuses an existing destination folder with a toast`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "recent-extract-${System.nanoTime()}")
        root.mkdirs()
        try {
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))
            File(root, "bundle").mkdirs()

            vm.extractZip(zip)

            assertEquals(
                "A folder named \"bundle\" already exists. Rename or move it before extracting.",
                vm.state.value.toast,
            )
            assertNull(vm.state.value.result)
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

    /**
     * Media source that records [rescan] calls (restore-undo re-indexing) and answers
     * everything else empty — the same injectable pattern the race test's GatedMedia
     * uses, so undo can be asserted without a live MediaStore.
     */
    private class RecordingMedia : MediaQuerySource {
        val rescanned = mutableListOf<List<String>>()

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> = emptyList()

        override fun recent(limit: Int): List<MediaRepository.MediaEntry> = emptyList()

        override fun summaries(): List<CategorySummary> = emptyList()

        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0

        override fun rescan(paths: List<String>) {
            rescanned += paths
        }
    }
}
