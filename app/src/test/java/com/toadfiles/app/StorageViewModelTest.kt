package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.AppContainer
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.ui.storage.StorageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Storage overview refresh (audit A20): the trash-usage card reads the payload size
 * from the sidecar metadata recorded at trash time; only entries without a recorded
 * size (directory payloads, legacy sidecars) fall back to the tree walk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: StorageViewModel
    private lateinit var container: AppContainer
    private lateinit var root: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        vm = StorageViewModel(container, ioDispatcher = dispatcher)
        root = context.getExternalFilesDir(null) ?: context.cacheDir
        root.mkdirs()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    /** Trashes [file] against the repo's real volume roots (Robolectric external storage). */
    private fun trashToRealVolume(file: File) {
        val roots = container.fileRepo.roots().map { it.dir }
        val result = Trash.trash(listOf(file), roots)
        assertEquals(1, result.moved.size)
    }

    @Test
    fun `trash usage reads the sidecar size metadata without a walk - A20`() {
        // A payload whose REAL size differs from what the trash recorded: the card
        // must trust the metadata (a walk would report the grown value).
        val payload = File(root, "podcast.mp3").apply { writeText("x".repeat(500)) }
        trashToRealVolume(payload)
        // The payload "grows" inside the trash after the fact (only possible to
        // simulate by editing the payload file directly — proving the card did not
        // re-walk it).
        val entries = Trash.entries(container.fileRepo.roots().map { it.dir })
        assertEquals(1, entries.size)
        val payloadFile = entries[0].file
        payloadFile.appendText("y".repeat(5000)) // real walk would now report 5500

        vm.refresh()

        assertEquals(1, vm.state.value.trashCount)
        assertEquals(500L, vm.state.value.trashBytes)
    }

    @Test
    fun `entries without recorded size fall back to the real walk`() {
        val payload = File(root, "recording.mp4").apply { writeText("z".repeat(700)) }
        trashToRealVolume(payload)
        val entries = Trash.entries(container.fileRepo.roots().map { it.dir })
        assertEquals(1, entries.size)
        // Downgrade the sidecar to the pre-A20 schema (no size line).
        val payloadFile = entries[0].file
        val meta = File(File(payloadFile.parentFile!!, Trash.META_DIR_NAME), payloadFile.name + ".tfmeta")
        meta.writeText(meta.readText().lineSequence().filter { !it.startsWith("size=") }.joinToString("\n"))

        vm.refresh()

        assertEquals(1, vm.state.value.trashCount)
        // Walk fallback: the actual payload size on disk.
        assertEquals(700L, vm.state.value.trashBytes)
    }
}
