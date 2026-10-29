package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.ui.about.AboutViewModel
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

/**
 * About page ViewModel — version/build are static, but storage stats and category counts
 * come from real queries that should produce a populated state even on Robolectric's
 * empty media library (where every category has zero items but every category still
 * appears).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AboutViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: AboutViewModel
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(ctx)
        vm = AboutViewModel(container, ioDispatcher = dispatcher)
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
    fun `init loads storage stats and category counts without crashing`() {
        await { !vm.state.value.loading }
        val s = vm.state.value
        // StatFs may report null on unusual Robolectric mounts; if non-null it must be sane.
        s.storage?.let { stats -> assertTrue(stats.totalBytes >= 0) }
        // Every FileCategory must be represented — six categories.
        assertEquals(6, s.categoryCounts.size)
        // Robolectric media library is empty, so all counts are zero.
        assertTrue(s.categoryCounts.all { it.count == 0 })
    }

    @Test
    fun `setDiagnostics persists and updates the state`() = runTest {
        vm.setDiagnostics(true)
        await { vm.state.value.diagnosticsEnabled }
        assertEquals(true, vm.state.value.diagnosticsEnabled)

        vm.setDiagnostics(false)
        await { !vm.state.value.diagnosticsEnabled }
        assertEquals(false, vm.state.value.diagnosticsEnabled)
    }

    @Test
    fun `refresh is idempotent and keeps the state populated`() {
        await { !vm.state.value.loading }
        vm.refresh()
        await { !vm.state.value.loading }
        val s = vm.state.value
        assertNotNull(s.storage)
        assertEquals(6, s.categoryCounts.size)
    }
}
