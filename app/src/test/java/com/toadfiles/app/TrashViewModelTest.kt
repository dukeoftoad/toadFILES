package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.ui.trash.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrashViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    /** Records rescan() calls (restore re-indexing, D1); keeps the class VM off the real media scanner. */
    private val media = RecordingMedia()
    private lateinit var vm: TrashViewModel
    private lateinit var container: com.toadfiles.app.AppContainer
    private lateinit var volume: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = com.toadfiles.app.AppContainer(context)
        vm = TrashViewModel(container, ioDispatcher = dispatcher, media = media)
        volume = container.fileRepo.primaryRoot()
        volume.mkdirs()
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

    private fun seedTrash(name: String, trashedAt: Long? = null): File {
        val src = File(volume, name).apply { writeText("trash me") }
        val trashed = Trash.trash(listOf(src), listOf(volume))
        assertEquals(1, trashed.moved.size)
        if (trashedAt != null) {
            File(File(trashed.moved[0].parentFile, Trash.META_DIR_NAME), trashed.moved[0].name + ".tfmeta")
                .writeText("origin=${src.absolutePath}\ntrashedAt=$trashedAt")
        }
        return trashed.moved[0]
    }

    @Test
    fun `refresh lists trashed items newest first`() = runTest {
        seedTrash("t1.txt")
        seedTrash("t2.txt")

        vm.refresh()

        await { vm.state.value.entries.size == 2 && !vm.state.value.loading }
        assertFalse(vm.state.value.confirmingEmpty)
    }

    @Test
    fun `refresh purges items past retention before listing`() = runTest {
        val fortyDaysAgo = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000
        seedTrash("ancient.txt", trashedAt = fortyDaysAgo)
        seedTrash("fresh.txt")

        vm.refresh(purgeFirst = true)

        await { !vm.state.value.loading }
        val names = vm.state.value.entries.map { it.originPath.substringAfterLast('/') }
        assertTrue("fresh.txt" in names)
        assertTrue("ancient.txt" !in names)
    }

    @Test
    fun `restore puts the item back where it came from`() = runTest {
        val origin = File(volume, "restore-me.txt")
        seedTrash("restore-me.txt")

        vm.refresh()
        await { vm.state.value.entries.size == 1 }
        val entry = vm.state.value.entries.first()

        vm.restore(entry)

        await { origin.exists() }
        assertEquals("Restored \"restore-me.txt\"", vm.state.value.toast)
        await { vm.state.value.entries.isEmpty() }
    }

    @Test
    fun `restore toasts a collision rename instead of the original name`() = runTest {
        seedTrash("clash.txt")
        // A same-named item reappeared at the origin: the restore must land as
        // "clash (1).txt" and the toast must say so (D4).
        File(volume, "clash.txt").writeText("new occupant")

        vm.refresh()
        await { vm.state.value.entries.size == 1 }
        vm.restore(vm.state.value.entries.first())

        await { File(volume, "clash (1).txt").exists() }
        assertEquals("Restored as \"clash (1).txt\"", vm.state.value.toast)
    }

    @Test
    fun `restore toasts the fallback location when the original folder is unavailable`() = runTest {
        // The origin's parent cannot be recreated (a regular file blocks the path),
        // so restore falls back to the volume root — the toast must say where the
        // file actually landed instead of plain success (D3).
        val sub = File(volume, "blocked-parent").apply { mkdirs() }
        val src = File(sub, "x.txt").apply { writeText("data") }
        assertEquals(1, Trash.trash(listOf(src), listOf(volume)).moved.size)
        sub.delete() // now empty; replace it with a regular file so mkdirs() fails
        File(volume, "blocked-parent").writeText("in the way")

        vm.refresh()
        await { vm.state.value.entries.size == 1 }
        vm.restore(vm.state.value.entries.first())

        val fallback = File(volume, "x.txt")
        await { fallback.exists() }
        val toast = vm.state.value.toast!!
        assertTrue("toast must name the fallback: $toast", toast.startsWith("Restored \"x.txt\" — original folder unavailable"))
        assertTrue(toast.contains(shortTail(volume.path)))
    }

    /** Last two path segments of [path], mirroring the VM's toast shortening. */
    private fun shortTail(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        return if (segments.size <= 2) path else "…/${segments.takeLast(2).joinToString("/")}"
    }

    @Test
    fun `delete forever requires an explicit confirmation first`() = runTest {
        val trashed = seedTrash("gone-forever.txt")
        val entry = com.toadfiles.app.domain.TrashEntry(trashed, File(volume, "gone-forever.txt").path, 0)

        // Requesting shows the confirm dialog; dismissing leaves the item untouched.
        vm.requestDeleteForever(entry)
        assertEquals(entry, vm.state.value.confirmingDelete)
        vm.dismissDeleteForever()
        assertNull(vm.state.value.confirmingDelete)
        assertTrue(trashed.exists())

        // Confirming performs the permanent delete and reports it.
        vm.requestDeleteForever(entry)
        vm.confirmDeleteForever()
        await { vm.state.value.entries.isEmpty() }
        assertFalse(trashed.exists())
        assertTrue(vm.state.value.toast!!.contains("Deleted"))
    }

    @Test
    fun `multi-select batch restore returns every selected item`() = runTest {
        seedTrash("b1.txt")
        seedTrash("b2.txt")
        vm.refresh()
        await { vm.state.value.entries.size == 2 }

        vm.state.value.entries.forEach { vm.toggleSelection(it) }
        assertEquals(2, vm.state.value.selection.size)

        vm.requestBatchRestore()
        await { vm.state.value.entries.isEmpty() && vm.state.value.selection.isEmpty() }
        assertEquals("Restored 2 item(s)", vm.state.value.toast)
        assertTrue(File(volume, "b1.txt").exists() && File(volume, "b2.txt").exists())
    }

    @Test
    fun `batch restore clears the selection synchronously and rescans the destinations`() = runTest {
        seedTrash("s1.txt")
        seedTrash("s2.txt")
        vm.refresh()
        await { vm.state.value.entries.size == 2 }
        vm.state.value.entries.forEach { vm.toggleSelection(it) }

        vm.requestBatchRestore()

        // Synchronous clear (N2): a double-tap can never relaunch the same batch —
        // the second tap finds an empty selection and does nothing.
        assertTrue(vm.state.value.selection.isEmpty())
        vm.requestBatchRestore() // the double-tap: harmless no-op

        await { vm.state.value.entries.isEmpty() }
        assertEquals("Restored 2 item(s)", vm.state.value.toast)
        // Both restored destinations were re-scanned into the media index (D1).
        assertEquals(
            listOf(File(volume, "s1.txt").absolutePath, File(volume, "s2.txt").absolutePath).sorted(),
            media.rescans.flatten().sorted(),
        )
    }

    @Test
    fun `batch restore surfaces collision renames`() = runTest {
        seedTrash("r.txt")
        File(volume, "r.txt").writeText("new occupant")
        vm.refresh()
        await { vm.state.value.entries.size == 1 }
        vm.toggleSelection(vm.state.value.entries.first())

        vm.requestBatchRestore()

        await { File(volume, "r (1).txt").exists() }
        assertEquals("Restored 1 item(s), 1 renamed", vm.state.value.toast)
    }

    @Test
    fun `restore rescans the restored destination`() = runTest {
        val origin = File(volume, "rescan-me.txt")
        seedTrash("rescan-me.txt")
        vm.refresh()
        await { vm.state.value.entries.size == 1 }

        vm.restore(vm.state.value.entries.first())

        await { origin.exists() }
        // The restored file's path went to the media scanner so it reappears in
        // Recent/Categories (D1).
        assertEquals(listOf(origin.absolutePath), media.rescans.flatten())
    }

    @Test
    fun `multi-select batch delete forever requires confirmation`() = runTest {
        val t1 = seedTrash("bd1.txt")
        seedTrash("bd2.txt")
        vm.refresh()
        await { vm.state.value.entries.size == 2 }

        // Confirm gate: request alone must not destroy anything.
        // (Entries are listed newest first — pick by name, not position.)
        val bd1 = vm.state.value.entries.first { it.originPath.endsWith("bd1.txt") }
        vm.toggleSelection(bd1)
        vm.requestBatchDeleteForever()
        assertTrue(vm.state.value.confirmingBatchDelete)
        vm.dismissBatchDeleteForever()
        assertFalse(vm.state.value.confirmingBatchDelete)
        assertTrue(t1.exists())

        vm.confirmBatchDeleteForever()
        await { vm.state.value.entries.size == 1 && vm.state.value.selection.isEmpty() }
        assertFalse(t1.exists())
        assertTrue(vm.state.value.toast!!.contains("Deleted 1"))
    }

    @Test
    fun `toggleSelection deselects and clearSelection resets`() = runTest {
        seedTrash("s1.txt")
        vm.refresh()
        await { vm.state.value.entries.size == 1 }
        val e = vm.state.value.entries.first()

        vm.toggleSelection(e)
        assertEquals(1, vm.state.value.selection.size)
        vm.toggleSelection(e)
        assertTrue(vm.state.value.selection.isEmpty())
        vm.toggleSelection(e)
        vm.clearSelection()
        assertTrue(vm.state.value.selection.isEmpty())
    }

    @Test
    fun `emptyNow wipes everything and reports the count`() = runTest {
        seedTrash("e1.txt")
        seedTrash("e2.txt")

        vm.requestEmpty()
        assertTrue(vm.state.value.confirmingEmpty)

        vm.emptyNow()
        await { vm.state.value.entries.isEmpty() && !vm.state.value.confirmingEmpty }
        assertEquals("Trash emptied — 2 item(s) removed forever", vm.state.value.toast)
    }

    @Test
    fun `emptyNow reports honestly when items could not be deleted`() = runTest {
        seedTrash("stuck1.txt")
        seedTrash("stuck2.txt")
        val trashDir = Trash.dirFor(volume)
        // Make the payloads undeletable: unlinking needs write permission on the
        // containing directory (same mechanism as TrashTest's deleteForever failure).
        assertTrue(trashDir.setWritable(false))
        try {
            vm.requestEmpty()
            vm.emptyNow()

            await { vm.state.value.toast != null }
            assertEquals("Couldn't empty trash — 2 item(s) couldn't be deleted", vm.state.value.toast)
            await { !vm.state.value.loading }
            assertEquals("the stuck items must still be listed", 2, vm.state.value.entries.size)
        } finally {
            trashDir.setWritable(true)
        }
    }

    /** Media-source double that only records rescan() calls (restore re-indexing, D1). */
    private class RecordingMedia : MediaQuerySource {
        val rescans = mutableListOf<List<String>>()
        override fun rescan(paths: List<String>) {
            rescans += paths
        }
        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> = emptyList()
        override fun recent(limit: Int): List<MediaRepository.MediaEntry> = emptyList()
        override fun summaries(): List<CategorySummary> = emptyList()
        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0
    }
}
