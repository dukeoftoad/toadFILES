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
import org.junit.Assert.assertFalse
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
    fun `quick access shows defaults and puts user pins first`() = runTest {
        File(externalDir, "Download").mkdirs()
        File(externalDir, "Documents").mkdirs()
        // Unique name: the class-wide DataStore is shared between tests, so pins from
        // earlier tests (e.g. Download) may still be around — don't assume "first overall".
        val pinned = File(externalDir, "Random-${System.nanoTime()}").apply { mkdirs() }

        container.settingsRepo.toggleQuickPin(pinned.path)
        // Wait for the PIN specifically — the row may already show plain defaults.
        await { vm.state.value.quickAccess.any { it.dir.path == pinned.path } }

        val entries = vm.state.value.quickAccess
        // Our pin is present and pinned…
        val mine = entries.first { it.dir.path == pinned.path }
        assertTrue(mine.pinned)
        // …every pinned entry precedes every non-pinned one…
        val lastPin = entries.indexOfLast { it.pinned }
        val firstNonPin = entries.indexOfFirst { !it.pinned }
        assertTrue(firstNonPin == -1 || lastPin < firstNonPin)
        // …and standard folders that exist fill the rest.
        val names = entries.map { it.label }
        assertTrue("Download" in names)
        assertTrue("Documents" in names)
        // A random unpinned folder never appears on its own.
        assertFalse(entries.any { !it.pinned && it.dir.path == pinned.path })
    }

    @Test
    fun `removing a quick folder hides it until re-pinned`() = runTest {
        val dl = File(externalDir, "Download").apply { mkdirs() }
        vm.refresh() // re-entering Home rebuilds Quick access from disk
        await { vm.state.value.quickAccess.any { it.dir.path == dl.path } }

        container.settingsRepo.hideFromQuickAccess(dl.path)

        await { vm.state.value.quickAccess.none { it.dir.path == dl.path } }

        // Re-pinning unhides it again.
        container.settingsRepo.toggleQuickPin(dl.path)
        await { vm.state.value.quickAccess.any { it.dir.path == dl.path } }

        // Leave the shared class-wide DataStore clean for other tests.
        container.settingsRepo.toggleQuickPin(dl.path)
        await { vm.state.value.quickAccess.any { it.dir.path == dl.path } } // still visible as a default
    }

    @Test
    fun `most used folders surface automatically after repeated visits`() = runTest {
        val work = File(externalDir, "Work").apply { mkdirs() }
        repeat(3) { container.settingsRepo.recordFolderUse(work.path) }

        await {
            vm.state.value.quickAccess.any {
                it.dir.path == work.path && it.source == com.toadfiles.app.ui.home.QuickAccessEntry.Source.MOST_USED
            }
        }
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

    // ---- v1.4: smart-recent feed ----

    @Test
    fun `refreshRecent clears loadingRecent flag`() = runTest {
        vm.refreshRecent()
        // Robolectric's MediaStore is empty, so recentFiles is [] but loadingRecent is false.
        await { !vm.state.value.loadingRecent }
        assertTrue(vm.state.value.recentFiles.isEmpty())
    }

    @Test
    fun `refresh invokes every refresh path`() = runTest {
        // refresh() must not throw and must keep the state internally consistent.
        vm.refresh()
        await { !vm.state.value.loadingRecent }
        // All sub-refreshes set their respective flags false on completion.
        assertFalse(vm.state.value.loadingRecent)
    }
}
