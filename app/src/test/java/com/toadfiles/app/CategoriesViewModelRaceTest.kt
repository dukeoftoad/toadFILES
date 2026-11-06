package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.categories.CategoriesViewModel
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
 * P2.6 regression for the Categories drill-in: rapidly opening category B while A's
 * query is still in flight must apply B's entries when they arrive and discard A's
 * whenever A eventually finishes — the selected category may never show another
 * category's files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoriesViewModelRaceTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    /** Daemon threads: a parked latch must never keep the test JVM alive after a failure. */
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "categories-race-io").apply { isDaemon = true }
    }
    private val ioDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private lateinit var container: AppContainer
    private lateinit var media: GatedMedia
    private lateinit var vm: CategoriesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        media = GatedMedia()
        vm = CategoriesViewModel(container, ioDispatcher = ioDispatcher, media = media)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private class GatedMedia : MediaQuerySource {
        val docsParked = CountDownLatch(1)
        val docsGate = CountDownLatch(1)
        val docsReturned = AtomicInteger()
        val apksParked = CountDownLatch(1)
        val apksGate = CountDownLatch(1)
        val apksEntry = entry(3, "app.apk", FileCategory.APKS)
        /** Set to park only the NEXT APKs query (refresh's content leg), not open()'s. */
        @Volatile var parkApksList = false

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> =
            when (category) {
                FileCategory.DOCS -> {
                    // Prove the query really reached the gate before the test moves on,
                    // otherwise cancel-before-start makes the release below never happen.
                    docsParked.countDown()
                    docsGate.await()
                    docsReturned.incrementAndGet()
                    listOf(entry(1, "slow-doc.pdf", FileCategory.DOCS))
                }
                FileCategory.APKS -> {
                    if (parkApksList) {
                        // Same reach-the-gate proof as the DOCS lane above.
                        apksParked.countDown()
                        apksGate.await()
                    }
                    listOf(apksEntry)
                }
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


    @Test
    fun `a slow older category query cannot overwrite the newer drill-in`() = runTest {
        await { !vm.state.value.loading } // grid summaries settle

        // User opens Documents — the query parks on the latch.
        vm.open(FileCategory.DOCS)
        // Deterministic: wait until the DOCS query is actually parked, so the newer
        // open() below cancels a RUNNING query (the stale-result case), not an
        // undispatched one.
        assertTrue(media.docsParked.await(10, TimeUnit.SECONDS))
        // User backs out and opens APKs — fast query lands while Documents is pending.
        vm.open(FileCategory.APKS)

        await { vm.state.value.entries.map { it.name } == listOf("app.apk") }
        assertEquals(FileCategory.APKS, vm.state.value.selected)
        assertTrue(!vm.state.value.entriesLoading)

        // Now the stale Documents query finally finishes — it must be discarded.
        media.docsGate.countDown()
        await { media.docsReturned.get() == 1 }
        // Give the stale apply (if it were wrongly allowed) a beat to land.
        Thread.sleep(250)

        assertEquals(FileCategory.APKS, vm.state.value.selected)
        assertEquals(listOf("app.apk"), vm.state.value.entries.map { it.name })
        assertTrue(!vm.state.value.entriesLoading)
    }

    @Test
    fun `summaries refresh keeps the newest result when two refreshes overlap`() = runTest {
        await { !vm.state.value.loading }
        vm.refresh()
        vm.refresh()
        await { !vm.state.value.loading }
        // The grid always ends consistent: one tile per category, newest refresh applied.
        assertEquals(FileCategory.entries.size, vm.state.value.summaries.size)
    }

    @Test
    fun `refresh's stale content result must not clobber a newer open`() = runTest {
        await { !vm.state.value.loading } // grid summaries settle

        // APKs is open and its rows are on screen.
        vm.open(FileCategory.APKS)
        await { vm.state.value.entries.map { it.name } == listOf("app.apk") }

        // Pull-to-refresh while APKs is open — its content re-query parks on the gate.
        media.parkApksList = true
        vm.refresh()
        assertTrue(media.apksParked.await(10, TimeUnit.SECONDS))

        // While the refresh re-query is parked the user opens Documents: open() bumps
        // entriesSeq, so the stale APK refresh result must be discarded when released —
        // both legs share the one entriesSeq/entriesJob race mechanism.
        vm.open(FileCategory.DOCS)
        assertTrue(media.docsParked.await(10, TimeUnit.SECONDS))

        media.apksGate.countDown() // the stale APK re-query finishes — it must NOT land
        media.docsGate.countDown() // Documents lands
        await { media.docsReturned.get() == 1 }
        await { vm.state.value.entries.map { it.name } == listOf("slow-doc.pdf") }
        // Give a wrongly-applied stale result a beat to land.
        Thread.sleep(250)

        assertEquals(FileCategory.DOCS, vm.state.value.selected)
        assertEquals(listOf("slow-doc.pdf"), vm.state.value.entries.map { it.name })
        assertTrue(!vm.state.value.entriesLoading)
    }
}
