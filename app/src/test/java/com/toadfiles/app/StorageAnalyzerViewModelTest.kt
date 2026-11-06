package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.ui.analyzer.StorageAnalyzerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageAnalyzerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: StorageAnalyzerViewModel
    private lateinit var container: AppContainer
    private lateinit var volume: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        vm = StorageAnalyzerViewModel(container, ioDispatcher = dispatcher)
        volume = container.fileRepo.primaryRoot()
        volume.mkdirs()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }


    /** A small deterministic tree: seed/a.jpg (5 bytes) + seed/sub/b.mp4 (100 bytes). */
    private fun seedTree(): File = File(volume, "analyzer-seed").apply {
        mkdirs()
        File(this, "a.jpg").writeText("12345")
        val sub = File(this, "sub").apply { mkdirs() }
        File(sub, "b.mp4").writeText("x".repeat(100))
    }

    @Test
    fun `scan aggregates a real tree into cached results`() {
        val dir = seedTree()
        vm.scan(dir)

        await { vm.state.value.result != null && !vm.state.value.scanning }

        val state = vm.state.value
        assertNull(state.error)
        assertEquals(dir, state.root)
        assertEquals(2, state.result?.fileCount)
        assertEquals(105L, state.result?.totalBytes)
        assertEquals(2, state.scannedFiles)
        assertEquals(105L, state.scannedBytes)
        // Biggest file first, folders carry recursive sizes.
        assertEquals("b.mp4", state.result?.biggestFiles?.first()?.name)
        assertEquals(listOf("sub" to 100L), state.result?.largestFolders?.map { it.dir.name to it.sizeBytes })
    }

    @Test
    fun `scan of a missing folder shows a friendly error instead of results`() {
        vm.scan(File(volume, "does-not-exist"))

        await { vm.state.value.error != null }

        val state = vm.state.value
        assertFalse(state.scanning)
        assertNull(state.result)
        assertTrue(state.error!!.contains("not a folder"))
    }

    @Test
    fun `autoScan does not restart a completed scan of the same root`() {
        val dir = seedTree()
        vm.scan(dir)
        await { vm.state.value.result != null }
        val first = vm.state.value.result

        vm.autoScan(dir)

        assertSame(first, vm.state.value.result)
        assertFalse(vm.state.value.scanning)
    }

    @Test
    fun `autoScan of a fresh root starts a scan`() {
        val dir = seedTree()
        vm.autoScan(dir)

        await { vm.state.value.result != null && !vm.state.value.scanning }

        assertEquals(dir, vm.state.value.root)
        assertEquals(2, vm.state.value.result?.fileCount)
    }

    @Test
    fun `autoScan treats the same path as one root regardless of spelling`() {
        val dir = seedTree()
        vm.scan(dir)
        await { vm.state.value.result != null }
        val first = vm.state.value.result

        // Trailing separator must not fool the "already scanned" guard into a re-walk.
        vm.autoScan(File("${dir.absolutePath}/"))

        assertSame(first, vm.state.value.result)
        assertFalse(vm.state.value.scanning)
    }

    @Test
    fun `a superseded scan cannot clobber its replacement's state`() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val gated = AtomicBoolean(false)
        val gatedVm = StorageAnalyzerViewModel(
            container,
            ioDispatcher = Dispatchers.IO, // real threads: the gate can park mid-scan
            scanGate = {
                if (gated.compareAndSet(false, true)) {
                    entered.countDown()
                    proceed.await()
                }
            },
        )
        try {
            val first = seedTree()
            gatedVm.scan(first)
            assertTrue(entered.await(10, TimeUnit.SECONDS)) // first scan parked mid-walk

            val second = File(volume, "analyzer-seed-2").apply {
                mkdirs()
                File(this, "c.zip").writeText("z".repeat(42))
            }
            gatedVm.scan(second) // supersedes the parked scan (cancel + generation bump)
            await { gatedVm.state.value.result?.fileCount == 1 && !gatedVm.state.value.scanning }

            proceed.countDown() // let the stale scan unwind through its finally
            Thread.sleep(500)   // a regression would publish its stale counters within ms

            assertEquals(second, gatedVm.state.value.root)
            assertEquals(1, gatedVm.state.value.scannedFiles)
            assertEquals(42L, gatedVm.state.value.scannedBytes)
            assertEquals(1, gatedVm.state.value.result?.fileCount)
            assertFalse(gatedVm.state.value.scanning)
        } finally {
            proceed.countDown()
            gatedVm.cancelScan()
        }
    }

    @Test
    fun `scanning a different root replaces the previous results`() {
        val first = seedTree()
        vm.scan(first)
        await { vm.state.value.result != null }

        val second = File(volume, "analyzer-seed-2").apply {
            mkdirs()
            File(this, "c.zip").writeText("z".repeat(42))
        }
        vm.scan(second)
        await { vm.state.value.root == second && vm.state.value.result != null }

        val state = vm.state.value
        assertEquals(second, state.root)
        assertEquals(1, state.result?.fileCount)
        assertEquals(42L, state.result?.totalBytes)
    }

    @Test
    fun `reset clears results and errors and keeps the volume list`() {
        val dir = seedTree()
        vm.scan(dir)
        await { vm.state.value.result != null }

        vm.reset()
        await { !vm.state.value.volumesLoading }

        val state = vm.state.value
        assertNull(state.root)
        assertNull(state.result)
        assertNull(state.error)
        assertFalse(state.scanning)
        assertTrue(state.volumes.isNotEmpty()) // falls back to internal storage at least
    }

    @Test
    fun `volumes load on init`() {
        await { !vm.state.value.volumesLoading }
        assertTrue(vm.state.value.volumes.isNotEmpty())
        assertNotNull(vm.state.value.volumes.first().dir)
    }
}
