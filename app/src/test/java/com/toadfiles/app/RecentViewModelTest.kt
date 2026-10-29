package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.ui.recent.RecentViewModel
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
    fun `confirmDelete moves a real file into the trash`() = runTest {
        val src = java.io.File(container.fileRepo.primaryRoot(), "recent-delete-me.txt")
        src.parentFile.mkdirs()
        src.writeText("delete me")
        try {
            vm.requestDelete(listOf(entry(1, src.name, data = src.absolutePath)))
            vm.confirmDelete()
            await { vm.state.value.toast != null }
            assertEquals("Moved 1 item(s) to trash", vm.state.value.toast)
            assertFalse(src.exists())
        } finally {
            src.delete()
        }
    }

    @Test
    fun `confirmDelete reports pathless entries honestly`() = runTest {
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
}
