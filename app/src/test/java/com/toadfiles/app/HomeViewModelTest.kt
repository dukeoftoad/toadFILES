package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: HomeViewModel
    private lateinit var container: com.toadfiles.app.AppContainer
    private lateinit var externalDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = com.toadfiles.app.AppContainer(context)
        vm = HomeViewModel(container, ioDispatcher = dispatcher)
        externalDir = container.fileRepo.primaryRoot()
        externalDir.mkdirs()
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
    fun `storage stats load without crashing`() = runTest {
        vm.refreshStorage()
        // StatFs can report null on unusual Robolectric mounts; anything non-null must be sane.
        val s = vm.state.value.storage
        if (s != null) assertTrue(s.totalBytes >= 0)
    }

    @Test
    fun `quick links list only standard folders that exist`() = runTest {
        File(externalDir, "Download").mkdirs()
        File(externalDir, "Documents").mkdirs()
        File(externalDir, "Random").mkdirs()

        vm.refreshQuickLinks()

        val names = vm.state.value.quickLinks.map { it.first }
        assertTrue("Download" in names)
        assertTrue("Documents" in names)
        assertTrue("Random" !in names)
    }

    @Test
    fun `favorites show existing files and drop vanished ones`() = runTest {
        val keep = File(externalDir, "fav-keep.txt").apply { writeText("x") }
        val gone = File(externalDir, "fav-gone.txt").apply { writeText("x") }
        container.settingsRepo.toggleFavorite(keep.path)
        container.settingsRepo.toggleFavorite(gone.path)
        await { vm.state.value.favorites.size == 2 }

        gone.delete()
        // Any settings emission recomputes favorites; toggling a third item triggers one.
        val other = File(externalDir, "fav-other.txt").apply { writeText("x") }
        container.settingsRepo.toggleFavorite(other.path)

        await {
            val names = vm.state.value.favorites.map { it.name }
            "fav-other.txt" in names && "fav-gone.txt" !in names && "fav-keep.txt" in names
        }
    }

    @Test
    fun `categories summaries cover every category`() = runTest {
        vm.refreshCategories()
        // Robolectric MediaStore is empty, but every category must be represented.
        assertEquals(6, vm.state.value.categories.size)
        assertTrue(vm.state.value.categories.all { it.count == 0 })
        assertFalse(vm.state.value.loadingCategories)
    }
}
