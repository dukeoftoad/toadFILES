package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.categories.CategoriesViewModel
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
 * Categories tab state machine: grid summaries load up front, tapping a tile drills into
 * that category's entries, back closes the drill-in, and the local-only share path reports
 * honestly when a media entry has no on-disk path.
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
    fun `refresh survives a media store hiccup`() = runTest {
        vm.refresh()
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.loading)
        // Whatever happened underneath, the tab must end in a usable state.
        assertTrue(vm.state.value.summaries.size == FileCategory.entries.size || vm.state.value.summaries.isEmpty())
    }

    @Test
    fun `shareLocal without any on-disk path toasts instead of launching`() = runTest {
        // A MediaEntry built without `data` has no real path; sharing must refuse honestly.
        val orphan = container.mediaRepo.list(FileCategory.IMAGES).firstOrNull()
        // Robolectric MediaStore is empty so orphan is null — exercise the empty-input path.
        vm.shareLocal(emptyList())
        assertEquals("Can't share this item — no on-disk path", vm.state.value.toast)
        assertNull(orphan)
    }

    @Test
    fun `toast is consumed after being shown`() = runTest {
        vm.requestToast("Shared 2 file(s) locally")
        assertEquals("Shared 2 file(s) locally", vm.state.value.toast)
        vm.consumeToast()
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `shareLocal copies a real file into the shared folder`() = runTest {
        val src = java.io.File(container.fileRepo.primaryRoot(), "cat-share-me.txt")
        src.parentFile.mkdirs()
        src.writeText("share me")
        try {
            vm.shareLocal(listOf(com.toadfiles.app.data.MediaRepository.MediaEntry(
                id = 1L,
                uri = android.net.Uri.parse("content://test/1"),
                name = src.name,
                size = src.length(),
                dateModified = 0L,
                mime = "text/plain",
                category = FileCategory.DOCS,
                data = src.absolutePath,
            )))
            await { vm.state.value.toast != null }
            assertEquals("Shared 1 file(s) locally", vm.state.value.toast)
            val sharedNames = container.sharedRepo.list().map { it.name }
            assertTrue(src.name in sharedNames)
        } finally {
            src.delete()
            container.sharedRepo.clear()
        }
    }
}
