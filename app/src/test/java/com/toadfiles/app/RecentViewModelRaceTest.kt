package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.recent.RecentViewModel
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
import org.junit.Assert.assertTrue
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
 * P2.6 regression: quickly switching the Recent filter must never let the slower,
 * older query finish last and replace the newer result. The audit's exact scenario —
 * switching Images → Videos and having the Images query land afterwards — reproduced
 * here with a fake media source whose Images call is parked on a latch until the
 * Videos query has already been applied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecentViewModelRaceTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    /** Daemon threads: a parked latch must never keep the test JVM alive after a failure. */
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "recent-race-io").apply { isDaemon = true }
    }
    private val ioDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private lateinit var container: AppContainer
    private lateinit var media: GatedMedia
    private lateinit var vm: RecentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        media = GatedMedia()
        vm = RecentViewModel(container, ioDispatcher = ioDispatcher, media = media)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    /** Applies only when told to; everything else answers instantly and empty. */
    private class GatedMedia : MediaQuerySource {
        val imagesGate = CountDownLatch(1)
        val imagesReturned = AtomicInteger()
        val videosEntry = entry(2, "video.mp4", FileCategory.VIDEOS)

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> =
            when (category) {
                FileCategory.IMAGES -> {
                    imagesGate.await()
                    imagesReturned.incrementAndGet()
                    listOf(entry(1, "image.jpg", FileCategory.IMAGES))
                }
                FileCategory.VIDEOS -> listOf(videosEntry)
                else -> emptyList()
            }

        override fun recent(limit: Int): List<MediaRepository.MediaEntry> = emptyList()

        override fun summaries(): List<CategorySummary> =
            FileCategory.entries.map { CategorySummary(it, 0, 0L) }

        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0

        private fun entry(id: Long, name: String, category: FileCategory) =
            MediaRepository.MediaEntry(
                id = id,
                uri = android.net.Uri.parse("content://test/$id"),
                name = name,
                size = 5L,
                dateModified = 0L,
                mime = "application/octet-stream",
                category = category,
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
    fun `a slow older filter query cannot overwrite the newer result`() = runTest {
        await { !vm.state.value.loading } // init "All" query settles (empty)

        // User picks Images — that query parks on the latch.
        vm.setFilter(FileCategory.IMAGES)
        // User gives up and picks Videos — fast query lands while Images is still pending.
        vm.setFilter(FileCategory.VIDEOS)

        await { vm.state.value.entries.map { it.name } == listOf("video.mp4") }
        assertEquals(FileCategory.VIDEOS, vm.state.value.filter)
        assertTrue(!vm.state.value.loading)

        // Now the stale Images query finally finishes — it must be discarded.
        media.imagesGate.countDown()
        await { media.imagesReturned.get() == 1 }
        // Give the stale apply (if it were wrongly allowed) a beat to land.
        Thread.sleep(250)

        assertEquals(FileCategory.VIDEOS, vm.state.value.filter)
        assertEquals(listOf("video.mp4"), vm.state.value.entries.map { it.name })
        assertTrue(!vm.state.value.loading)
    }
}
