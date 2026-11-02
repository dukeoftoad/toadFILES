package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Regression for the pull-to-refresh blind spot: refresh() re-queried only the overview
 * counts, so an open drill-in showed a spinner but kept stale content until the user
 * left and re-entered. Covers the new non-destructive entries lane — in-place update
 * without the full-screen spinner swap (entriesLoading stays down), selection pruning,
 * failure keeps for both lanes (no wipe), open ⇄ refresh race discipline, the
 * summaries-only path with no category open, and the session sort re-applying to fresh
 * results. Harness mirrors CategoriesViewModelRaceTest (gated fake + executor IO).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoriesRefreshTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    /** Daemon threads: a parked latch must never keep the test JVM alive after a failure. */
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "categories-refresh-io").apply { isDaemon = true }
    }
    private val ioDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private lateinit var container: AppContainer
    private lateinit var media: RefreshMedia
    private lateinit var vm: CategoriesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        media = RefreshMedia()
        vm = CategoriesViewModel(container, ioDispatcher = ioDispatcher, media = media)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun await(block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!block()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within timeout" }
            Thread.sleep(10)
        }
    }

    /**
     * Controllable MediaStore: the DOCS library grows/mutates on demand, the next DOCS
     * query can be parked on a latch (one shot — after the gate opens later calls pass
     * through), and either lane can be made to throw, like a transient MediaStore hiccup.
     */
    private class RefreshMedia : MediaQuerySource {
        val docsCalls = AtomicInteger()
        @Volatile var parkNextDocs = false
        val docsParked = CountDownLatch(1)
        val docsGate = CountDownLatch(1)
        @Volatile var failDocs = false
        @Volatile var failSummaries = false
        @Volatile var docs: List<MediaRepository.MediaEntry> = listOf(doc(1, "old-doc.pdf"))

        val apksEntry = MediaRepository.MediaEntry(
            id = 3L,
            uri = android.net.Uri.parse("content://test/3"),
            name = "app.apk",
            size = 5L,
            dateModified = 0L,
            mime = "application/octet-stream",
            category = FileCategory.APKS,
            data = null,
        )

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> =
            when (category) {
                FileCategory.DOCS -> {
                    docsCalls.incrementAndGet()
                    if (parkNextDocs) {
                        docsParked.countDown()
                        docsGate.await()
                    }
                    if (failDocs) throw IllegalStateException("MediaStore hiccup")
                    docs
                }
                FileCategory.APKS -> listOf(apksEntry)
                else -> emptyList()
            }

        override fun recent(limit: Int): List<MediaRepository.MediaEntry> = emptyList()

        override fun summaries(): List<CategorySummary> {
            if (failSummaries) throw IllegalStateException("MediaStore hiccup")
            return FileCategory.entries.map { CategorySummary(it, 0, 0L) }
        }

        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0
    }

    @Test
    fun `refresh with an open category updates entries in place without the spinner swap`() = runTest {
        await { !vm.state.value.loading } // grid summaries settle

        vm.open(FileCategory.DOCS)
        await { vm.state.value.entries.map { it.name } == listOf("old-doc.pdf") }
        assertEquals(1, media.docsCalls.get())

        // The library grew after the drill-in opened; the next DOCS query parks so the
        // mid-flight state can be inspected deterministically.
        media.docs = listOf(doc(1, "old-doc.pdf"), doc(2, "new-doc.pdf"))
        media.parkNextDocs = true
        vm.refresh()
        assertTrue(media.docsParked.await(10, TimeUnit.SECONDS))

        // Mid-flight: the old rows stay visible (no clear) and the swap-to-full-screen-
        //-spinner flag is never raised — only the new pull-refresh lane is spinning.
        assertTrue(vm.state.value.entriesRefreshing)
        assertFalse(vm.state.value.entriesLoading)
        assertEquals(listOf("old-doc.pdf"), vm.state.value.entries.map { it.name })

        media.docsGate.countDown()
        await { vm.state.value.entries.map { it.name } == listOf("old-doc.pdf", "new-doc.pdf") }
        await { !vm.state.value.loading } // the summaries lane settles independently
        assertFalse(vm.state.value.entriesRefreshing)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.entriesLoading)
        assertEquals(2, media.docsCalls.get()) // open + refresh both really re-queried DOCS
    }

    @Test
    fun `refresh prunes vanished entries from selection and keeps survivors`() = runTest {
        await { !vm.state.value.loading }
        media.docs = listOf(doc(1, "stays.pdf"), doc(2, "vanishes.pdf"))
        vm.open(FileCategory.DOCS)
        await { vm.state.value.entries.size == 2 }
        vm.toggleSelection(vm.state.value.entries[0])
        vm.toggleSelection(vm.state.value.entries[1])
        assertEquals(2, vm.state.value.selection.size)

        // "vanishes.pdf" is gone from the library by the time refresh re-queries.
        media.docs = listOf(doc(1, "stays.pdf"))
        vm.refresh()
        await { vm.state.value.entries.map { it.name } == listOf("stays.pdf") }
        assertEquals(setOf("content://test/1"), vm.state.value.selection)
        assertFalse(vm.state.value.entriesRefreshing)
    }

    @Test
    fun `a failed refresh keeps the previous entries and summaries instead of wiping them`() = runTest {
        await { !vm.state.value.loading }
        media.docs = listOf(doc(1, "kept.pdf"))
        vm.open(FileCategory.DOCS)
        await { vm.state.value.entries.map { it.name } == listOf("kept.pdf") }
        val summariesBefore = vm.state.value.summaries
        assertEquals(FileCategory.entries.size, summariesBefore.size)

        // Both lanes fail this round — nothing on screen may be wiped.
        media.failDocs = true
        media.failSummaries = true
        vm.refresh()
        await { !vm.state.value.loading && !vm.state.value.entriesRefreshing }
        assertEquals(listOf("kept.pdf"), vm.state.value.entries.map { it.name })
        assertEquals(summariesBefore, vm.state.value.summaries)
        assertFalse(vm.state.value.entriesLoading)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.entriesRefreshing)
    }

    @Test
    fun `a slow refresh cannot overwrite a newer drill-in open`() = runTest {
        await { !vm.state.value.loading }
        vm.open(FileCategory.DOCS)
        await { vm.state.value.entries.map { it.name } == listOf("old-doc.pdf") }

        // Pull-to-refresh parks on the gate; meanwhile the user backs out and opens APKs.
        media.parkNextDocs = true
        vm.refresh()
        assertTrue(media.docsParked.await(10, TimeUnit.SECONDS))
        vm.open(FileCategory.APKS)
        await { vm.state.value.entries.map { it.name } == listOf("app.apk") }

        // The stale DOCS refresh finally lands — it must be discarded.
        media.docsGate.countDown()
        await { media.docsCalls.get() == 2 }
        Thread.sleep(250) // give a wrong apply (if it were allowed) a beat to land

        assertEquals(FileCategory.APKS, vm.state.value.selected)
        assertEquals(listOf("app.apk"), vm.state.value.entries.map { it.name })
        assertFalse(vm.state.value.entriesRefreshing) // the newer open took the lane back
        assertFalse(vm.state.value.entriesLoading)
    }

    @Test
    fun `refresh supersedes an in-flight open and the fresh result applies`() = runTest {
        await { !vm.state.value.loading }
        // Opening Documents parks its query; the library grew in the meantime.
        media.parkNextDocs = true
        vm.open(FileCategory.DOCS)
        assertTrue(media.docsParked.await(10, TimeUnit.SECONDS))
        assertTrue(vm.state.value.entriesLoading)
        media.docs = listOf(doc(1, "old-doc.pdf"), doc(2, "new-doc.pdf"))

        // Pull-to-refresh while that open is still loading: the non-destructive lane
        // takes over as the newest token, and the open's own result becomes stale
        // (the same hand-off confirmDelete/undoDelete trigger with their open+refresh).
        vm.refresh()
        assertTrue(vm.state.value.entriesRefreshing)
        media.docsGate.countDown() // both parked DOCS queries resume

        await { vm.state.value.entries.map { it.name } == listOf("old-doc.pdf", "new-doc.pdf") }
        assertEquals(FileCategory.DOCS, vm.state.value.selected)
        assertFalse(vm.state.value.entriesLoading) // cleared by the winning refresh lane
        assertFalse(vm.state.value.entriesRefreshing)
        assertEquals(2, media.docsCalls.get()) // the parked open + the superseding refresh
    }

    @Test
    fun `refresh with no open category only touches the summaries lane`() = runTest {
        await { !vm.state.value.loading }
        assertNull(vm.state.value.selected)
        assertEquals(0, media.docsCalls.get()) // init's refresh never lists entries

        vm.refresh()
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.entriesRefreshing)
        assertNull(vm.state.value.selected)
        assertEquals(emptyList<MediaRepository.MediaEntry>(), vm.state.value.entries)
        // The entries query never fired: with no drill-in open, refresh is summaries-only.
        assertEquals(0, media.docsCalls.get())
    }

    @Test
    fun `refresh re-applies the session sort to the fresh entries`() = runTest {
        await { !vm.state.value.loading }
        media.docs = listOf(doc(1, "b.pdf"), doc(2, "a.pdf"))
        vm.open(FileCategory.DOCS)
        // DATE DESC with equal dates keeps MediaStore's own source order.
        await { vm.state.value.entries.map { it.name } == listOf("b.pdf", "a.pdf") }

        vm.setSort(SortMode.NAME)
        vm.setSortOrder(SortOrder.ASC)
        assertEquals(listOf("a.pdf", "b.pdf"), vm.state.value.entries.map { it.name })

        // The library grew; a pull-to-refresh must re-query AND re-apply NAME ASC.
        media.docs = listOf(doc(1, "b.pdf"), doc(2, "a.pdf"), doc(3, "c.pdf"))
        vm.refresh()
        await { vm.state.value.entries.map { it.name } == listOf("a.pdf", "b.pdf", "c.pdf") }
        assertFalse(vm.state.value.entriesRefreshing)
    }
}

/**
 * Deterministic DOCS row: every row shares dateModified=0, so the default DATE/DESC
 * sort keeps source order on ties (stable sort — the existing sort test relies on
 * the same property), keeping every assertion deterministic without MediaStore.
 * File-level (not a member of the nested fake) so the fake's defaults and the test
 * bodies share it.
 */
private fun doc(id: Long, name: String) = MediaRepository.MediaEntry(
    id = id,
    uri = android.net.Uri.parse("content://test/$id"),
    name = name,
    size = 10L,
    dateModified = 0L,
    mime = "application/pdf",
    category = FileCategory.DOCS,
    data = null,
)
