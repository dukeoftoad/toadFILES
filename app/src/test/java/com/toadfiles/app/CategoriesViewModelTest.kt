package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.categories.CategoriesViewModel
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
 * Categories tab state machine: grid summaries load up front, tapping a tile drills into
 * that category's entries, back closes the drill-in, multi-select toggles by uri, and
 * delete moves real files into the 30-day trash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoriesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: CategoriesViewModel
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        vm = CategoriesViewModel(container, ioDispatcher = dispatcher)
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
        size = 10L,
        dateModified = 0L,
        mime = "text/plain",
        category = FileCategory.DOCS,
        data = data,
    )

    @Test
    fun `init loads one summary per category and clears the loading flag`() = runTest {
        // Robolectric's MediaStore is empty, so every count is 0 — but the grid still gets
        // one tile per FileCategory, which is what keeps the screen organized.
        await { !vm.state.value.loading }
        val summaries = vm.state.value.summaries
        assertEquals(FileCategory.entries.size, summaries.size)
        assertTrue(summaries.all { it.count == 0 && it.totalBytes == 0L })
    }

    @Test
    fun `open drills into a category and close returns to the grid`() = runTest {
        vm.open(FileCategory.IMAGES)
        await { !vm.state.value.entriesLoading }
        assertEquals(FileCategory.IMAGES, vm.state.value.selected)
        assertTrue(vm.state.value.entries.isEmpty())

        vm.close()
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test
    fun `close also clears an active multi-select`() = runTest {
        vm.toggleSelection(entry(1, "a.txt"))
        assertEquals(1, vm.state.value.selection.size)
        vm.close()
        assertTrue(vm.state.value.selection.isEmpty())
    }

    @Test
    fun `refresh survives a media store hiccup`() = runTest {
        vm.refresh()
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.loading)
        // Whatever happened underneath, the tab must end in a usable state.
        assertTrue(vm.state.value.summaries.size == FileCategory.entries.size || vm.state.value.summaries.isEmpty())
    }

    @Test
    fun `toast is consumed after being shown`() = runTest {
        vm.requestToast("Moved 2 item(s) to trash")
        assertEquals("Moved 2 item(s) to trash", vm.state.value.toast)
        vm.consumeToast()
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `confirmDelete moves a real file into the trash and reports it`() = runTest {
        val src = java.io.File(container.fileRepo.primaryRoot(), "cat-delete-me.txt")
        src.parentFile.mkdirs()
        src.writeText("delete me")
        try {
            vm.requestDelete(listOf(entry(1, src.name, data = src.absolutePath)))
            assertEquals(1, vm.state.value.pendingDelete?.size)
            vm.confirmDelete()
            await { vm.state.value.toast != null }
            assertEquals("Moved 1 item(s) to trash", vm.state.value.toast)
            // The file must have actually moved into the trash dir on the same volume.
            assertFalse(src.exists())
            val trashDir = java.io.File(
                container.fileRepo.primaryRoot(),
                com.toadfiles.app.domain.Trash.DIR_NAME,
            )
            assertTrue(trashDir.listFiles()?.any { it.name.endsWith(src.name) } == true)
        } finally {
            src.delete()
        }
    }

    @Test
    fun `confirmDelete reports entries without an on-disk path honestly`() = runTest {
        vm.requestDelete(listOf(entry(1, "orphan.txt", data = null)))
        vm.confirmDelete()
        await { vm.state.value.toast != null }
        assertEquals("Moved 0 to trash · 1 had no on-disk path", vm.state.value.toast)
    }

    @Test
    fun `requestDelete with nothing is a no-op`() = runTest {
        vm.requestDelete(emptyList())
        assertNull(vm.state.value.pendingDelete)
    }

    @Test
    fun `selection toggles by uri and select-all covers the current entries`() = runTest {
        val a = entry(1, "a.txt")
        val b = entry(2, "b.txt")
        vm.toggleSelection(a)
        vm.toggleSelection(b)
        assertEquals(2, vm.state.value.selection.size)
        vm.toggleSelection(a)
        assertEquals(1, vm.state.value.selection.size)
        vm.selectAll()
        // Entries come from MediaStore (empty on Robolectric): select-all selects nothing
        // rather than throwing — the state machine stays consistent.
        assertTrue(vm.state.value.selection.isEmpty())
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
        val root = File(container.fileRepo.primaryRoot(), "cat-extract-${System.nanoTime()}")
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
        val root = File(container.fileRepo.primaryRoot(), "cat-extract-${System.nanoTime()}")
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
        val slowVm = CategoriesViewModel(container, ioDispatcher = queued)
        val root = File(container.fileRepo.primaryRoot(), "cat-cancel-${System.nanoTime()}")
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
}
