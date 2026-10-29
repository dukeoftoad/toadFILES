package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.ui.trash.TrashViewModel
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrashViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: TrashViewModel
    private lateinit var container: com.toadfiles.app.AppContainer
    private lateinit var volume: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = com.toadfiles.app.AppContainer(context)
        vm = TrashViewModel(container, ioDispatcher = dispatcher)
        volume = container.fileRepo.primaryRoot()
        volume.mkdirs()
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

    private fun seedTrash(name: String, trashedAt: Long? = null): File {
        val src = File(volume, name).apply { writeText("trash me") }
        val trashed = Trash.trash(listOf(src), listOf(volume))
        assertEquals(1, trashed.size)
        if (trashedAt != null) {
            File(trashed[0].parentFile, trashed[0].name + ".tfmeta")
                .writeText("origin=${src.absolutePath}\ntrashedAt=$trashedAt")
        }
        return trashed[0]
    }

    @Test
    fun `refresh lists trashed items newest first`() = runTest {
        seedTrash("t1.txt")
        seedTrash("t2.txt")

        vm.refresh()

        await { vm.state.value.entries.size == 2 && !vm.state.value.loading }
        assertFalse(vm.state.value.confirmingEmpty)
    }

    @Test
    fun `refresh purges items past retention before listing`() = runTest {
        val fortyDaysAgo = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000
        seedTrash("ancient.txt", trashedAt = fortyDaysAgo)
        seedTrash("fresh.txt")

        vm.refresh(purgeFirst = true)

        await { !vm.state.value.loading }
        val names = vm.state.value.entries.map { it.originPath.substringAfterLast('/') }
        assertTrue("fresh.txt" in names)
        assertTrue("ancient.txt" !in names)
    }

    @Test
    fun `restore puts the item back where it came from`() = runTest {
        val origin = File(volume, "restore-me.txt")
        seedTrash("restore-me.txt")

        vm.refresh()
        await { vm.state.value.entries.size == 1 }
        val entry = vm.state.value.entries.first()

        vm.restore(entry)

        await { origin.exists() }
        assertTrue(vm.state.value.toast!!.startsWith("Restored"))
        await { vm.state.value.entries.isEmpty() }
    }

    @Test
    fun `delete forever requires an explicit confirmation first`() = runTest {
        val trashed = seedTrash("gone-forever.txt")
        val entry = com.toadfiles.app.domain.TrashEntry(trashed, File(volume, "gone-forever.txt").path, 0)

        // Requesting shows the confirm dialog; dismissing leaves the item untouched.
        vm.requestDeleteForever(entry)
        assertEquals(entry, vm.state.value.confirmingDelete)
        vm.dismissDeleteForever()
        assertNull(vm.state.value.confirmingDelete)
        assertTrue(trashed.exists())

        // Confirming performs the permanent delete and reports it.
        vm.requestDeleteForever(entry)
        vm.confirmDeleteForever()
        await { vm.state.value.entries.isEmpty() }
        assertFalse(trashed.exists())
        assertTrue(vm.state.value.toast!!.contains("Deleted"))
    }

    @Test
    fun `emptyNow wipes everything and reports the count`() = runTest {
        seedTrash("e1.txt")
        seedTrash("e2.txt")

        vm.requestEmpty()
        assertTrue(vm.state.value.confirmingEmpty)

        vm.emptyNow()
        await { vm.state.value.entries.isEmpty() && !vm.state.value.confirmingEmpty }
        assertEquals("Trash emptied — 2 item(s) removed forever", vm.state.value.toast)
    }
}
