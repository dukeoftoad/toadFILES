package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.home.HomeViewModel
import com.toadfiles.app.ui.home.QuickAccessEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Cancel the ViewModel scope BEFORE resetting Main: the VM launches eager
        // Unconfined jobs (storage stats, quick access, folder uses) that outlive
        // runTest otherwise. Under CI disk contention one of them would straddle
        // the test boundary and crash into the disposed test Main dispatcher
        // (IllegalStateException at resetMain) — the flake seen on hosted runners.
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }


    @Test
    fun `storage stats load without crashing`() = runTest {
        vm.refreshStorage()
        // Audit A27: the old assertion sat inside `if (s != null)` and passed trivially
        // whenever the stats were missing. Robolectric's tmpfs-backed external storage
        // does report usable StatFs numbers, so the sane-invariant is now unconditional.
        val s = vm.state.value.storage
        assertNotNull("storage stats should load on the Robolectric mount", s)
        assertTrue("total bytes must be non-negative", s!!.totalBytes >= 0)
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

    // ---- v1.8.x: recent filter chips + quick access cap guard ----

    /** Counts every call per lane so filter switching can be asserted precisely. */
    private class RecordingMedia : MediaQuerySource {
        val recentCalls = mutableListOf<Int>()
        val listCalls = mutableListOf<Pair<FileCategory, Int>>()

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> {
            listCalls += category to limit
            return emptyList()
        }

        override fun recent(limit: Int): List<MediaRepository.MediaEntry> {
            recentCalls += limit
            return emptyList()
        }

        override fun summaries(): List<CategorySummary> =
            FileCategory.entries.map { CategorySummary(it, 0, 0L) }

        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0
    }

    @Test
    fun `recent filter switches the query lane and same-filter re-sets are no-ops`() = runTest {
        val media = RecordingMedia()
        val filtered = HomeViewModel(container, ioDispatcher = dispatcher, media = media)
        await { !filtered.state.value.loadingRecent }
        assertNull(filtered.state.value.recentFilter)
        val recentBaseline = media.recentCalls.size

        filtered.setRecentFilter(FileCategory.IMAGES)
        await { !filtered.state.value.loadingRecent }
        assertEquals(FileCategory.IMAGES, filtered.state.value.recentFilter)
        // The filter routes the query through list(IMAGES, RECENT_LIMIT)…
        assertTrue(media.listCalls.contains(FileCategory.IMAGES to HomeViewModel.RECENT_LIMIT))
        val listsAfterFirstSet = media.listCalls.size

        // …setting the same filter again is a no-op — no reload churn.
        filtered.setRecentFilter(FileCategory.IMAGES)
        assertEquals(listsAfterFirstSet, media.listCalls.size)

        // …and back to All the unfiltered recent() lane answers again.
        filtered.setRecentFilter(null)
        await { !filtered.state.value.loadingRecent }
        assertNull(filtered.state.value.recentFilter)
        assertEquals(recentBaseline + 1, media.recentCalls.size)
        // Same hygiene as tearDown: this VM outlives the test body unless cancelled.
        filtered.viewModelScope.cancel()
    }

    @Test
    fun `a wall of pins never starves the default quick links`() = runTest {
        // Standard folders must exist for quickLinks() to offer them.
        listOf("Download", "DCIM", "Documents", "Pictures", "Music", "Movies").forEach {
            File(externalDir, it).mkdirs()
        }
        val pins = (1..10).map { File(externalDir, "pin-$it-${System.nanoTime()}").apply { mkdirs() } }
        val pinPaths = pins.map { it.path }.toSet()
        pins.forEach { container.settingsRepo.toggleQuickPin(it.path) }

        // The cap keeps at least two standard folders on the grid…
        await {
            val qa = vm.state.value.quickAccess
            qa.size <= HomeViewModel.QUICK_CAP &&
                qa.count { it.source == QuickAccessEntry.Source.DEFAULT } >= HomeViewModel.QUICK_MIN_DEFAULTS
        }
        val qa = vm.state.value.quickAccess
        assertTrue(qa.size <= HomeViewModel.QUICK_CAP)
        assertTrue(qa.count { it.source == QuickAccessEntry.Source.DEFAULT } >= HomeViewModel.QUICK_MIN_DEFAULTS)
        // …and the pins → defaults ordering survives the trim.
        val lastPin = qa.indexOfLast { it.pinned }
        val firstNonPin = qa.indexOfFirst { !it.pinned }
        assertTrue(firstNonPin == -1 || lastPin < firstNonPin)

        // Leave the shared class-wide DataStore clean for the other tests.
        pins.forEach { container.settingsRepo.toggleQuickPin(it.path) }
        await { vm.state.value.quickAccess.none { it.dir.path in pinPaths } }
    }
}
