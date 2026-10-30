package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.home.HomeViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2.6 regression for Home's smart-recent feed: a second refresh while the first is
 * still in flight must win, and the first must never apply its (older) result after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeViewModelRaceTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    /** Daemon threads: a parked latch must never keep the test JVM alive after a failure. */
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "home-race-io").apply { isDaemon = true }
    }
    private val ioDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private lateinit var container: AppContainer
    private lateinit var media: GatedMedia
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        media = GatedMedia()
        vm = HomeViewModel(container, ioDispatcher = ioDispatcher, media = media)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private class GatedMedia : MediaQuerySource {
        val firstGate = CountDownLatch(1)
        /** Counts every recent() call so the test can tell first and second refresh apart. */
        val recentCalls = AtomicInteger()
        val olderEntry = entry(1, "older.jpg")
        val newerEntry = entry(2, "newer.jpg")

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> = emptyList()

        override fun recent(limit: Int): List<MediaRepository.MediaEntry> =
            if (recentCalls.incrementAndGet() == 1) {
                // First (init) refresh parks forever — the test supersede it.
                firstGate.await()
                listOf(olderEntry)
            } else {
                listOf(newerEntry)
            }

        override fun summaries(): List<CategorySummary> =
            FileCategory.entries.map { CategorySummary(it, 0, 0L) }

        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0

        private fun entry(id: Long, name: String) = MediaRepository.MediaEntry(
            id = id,
            uri = android.net.Uri.parse("content://test/$id"),
            name = name,
            size = 5L,
            dateModified = 0L,
            mime = "image/jpeg",
            category = FileCategory.IMAGES,
            data = null,
        )
    }

    private fun await(block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!block()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within timeout" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `an older recent query cannot overwrite a newer refresh`() = runTest {
        // init started refresh #1, which parks on the latch.
        await { media.recentCalls.get() >= 1 }

        // A newer refresh lands while the old one is still pending.
        vm.refreshRecent()
        await { vm.state.value.recentFiles.map { it.name } == listOf("newer.jpg") }
        assertFalse(vm.state.value.loadingRecent)

        // The parked first query finishes late — its result must be discarded.
        media.firstGate.countDown()
        await { media.recentCalls.get() >= 2 } // ensure the newer refresh ran too
        Thread.sleep(250)

        assertEquals(listOf("newer.jpg"), vm.state.value.recentFiles.map { it.name })
        assertFalse(vm.state.value.loadingRecent)
    }
}
