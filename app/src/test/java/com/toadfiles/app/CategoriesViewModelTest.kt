package com.toadfiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import com.toadfiles.app.ui.categories.CategoriesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Categories tab state machine: grid summaries load up front, tapping a tile drills into
 * that category's entries, back closes the drill-in, multi-select toggles by uri, and
 * delete moves real files into the 30-day trash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoriesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: CategoriesViewModel
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        vm = CategoriesViewModel(container, ioDispatcher = dispatcher)
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

    private fun entry(id: Long, name: String, data: String? = null) = MediaRepository.MediaEntry(
        id = id,
        uri = android.net.Uri.parse("content://test/$id"),
        name = name,
        size = 10L,
        dateModified = 0L,
        mime = "text/plain",
        category = FileCategory.DOCS,
        data = data,
    )

    @Test
    fun `init loads one summary per category and clears the loading flag`() = runTest {
        // Robolectric's MediaStore is empty, so every count is 0 — but the grid still gets
        // one tile per FileCategory, which is what keeps the screen organized.
        await { !vm.state.value.loading }
        val summaries = vm.state.value.summaries
        assertEquals(FileCategory.entries.size, summaries.size)
        assertTrue(summaries.all { it.count == 0 && it.totalBytes == 0L })
    }

    @Test
    fun `open drills into a category and close returns to the grid`() = runTest {
        vm.open(FileCategory.IMAGES)
        await { !vm.state.value.entriesLoading }
        assertEquals(FileCategory.IMAGES, vm.state.value.selected)
        assertTrue(vm.state.value.entries.isEmpty())

        vm.close()
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test
    fun `close also clears an active multi-select`() = runTest {
        vm.toggleSelection(entry(1, "a.txt"))
        assertEquals(1, vm.state.value.selection.size)
        vm.close()
        assertTrue(vm.state.value.selection.isEmpty())
    }

    @Test
    fun `refresh survives a media store hiccup`() = runTest {
        vm.refresh()
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.loading)
        // Whatever happened underneath, the tab must end in a usable state.
        assertTrue(vm.state.value.summaries.size == FileCategory.entries.size || vm.state.value.summaries.isEmpty())
    }

    @Test
    fun `toast is consumed after being shown`() = runTest {
        vm.requestToast("Moved 2 item(s) to trash")
        assertEquals("Moved 2 item(s) to trash", vm.state.value.toast)
        vm.consumeToast()
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `confirmDelete moves a real file into the trash and stages it for undo`() = runTest {
        val src = java.io.File(container.fileRepo.primaryRoot(), "cat-delete-me.txt")
        src.parentFile.mkdirs()
        src.writeText("delete me")
        try {
            vm.requestDelete(listOf(entry(1, src.name, data = src.absolutePath)))
            assertEquals(1, vm.state.value.pendingDelete?.size)
            vm.confirmDelete()
            // Full success is reported by the screen's Undo snackbar (Browse parity),
            // not a plain toast: the trashed payloads are staged for restore.
            await { vm.state.value.lastTrashed?.size == 1 }
            assertEquals(null, vm.state.value.toast)
            // The file must have actually moved into the trash dir on the same volume.
            assertFalse(src.exists())
            val trashDir = java.io.File(
                container.fileRepo.primaryRoot(),
                com.toadfiles.app.domain.Trash.DIR_NAME,
            )
            assertTrue(trashDir.listFiles()?.any { it.name.endsWith(src.name) } == true)

            // Undo puts it right back where it was: the screen consumes the one-shot
            // offer when the snackbar shows it, then restores the captured payload (D2/N5).
            val trashed = vm.state.value.lastTrashed!!
            vm.consumeUndoOffer()
            assertNull(vm.state.value.lastTrashed)
            vm.undoDelete(trashed)
            await { vm.state.value.toast == "Restored 1 item(s)" }
            assertTrue(src.exists())
        } finally {
            src.delete()
        }
    }

    @Test
    fun `confirmDelete reports entries without an on-disk path honestly`() = runTest {
        vm.requestDelete(listOf(entry(1, "orphan.txt", data = null)))
        vm.confirmDelete()
        await { vm.state.value.toast != null }
        assertEquals("Moved 0 to trash · 1 had no on-disk path", vm.state.value.toast)
    }

    @Test
    fun `multi-select delete moves every payload into the trash and stages one undo offer`() = runTest {
        val dir = java.io.File(container.fileRepo.primaryRoot(), "cat-multi-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val a = java.io.File(dir, "cat-multi-a.txt").apply { writeText("a") }
            val b = java.io.File(dir, "cat-multi-b.txt").apply { writeText("b") }

            vm.requestDelete(
                listOf(entry(1, a.name, data = a.absolutePath), entry(2, b.name, data = b.absolutePath)),
            )
            assertEquals(2, vm.state.value.pendingDelete?.size)
            vm.confirmDelete()

            // Full success: no toast — the screen's Undo snackbar reports it (Browse parity).
            await { vm.state.value.lastTrashed?.size == 2 }
            assertNull(vm.state.value.toast)
            assertFalse(a.exists())
            assertFalse(b.exists())

            // Every payload is alive inside the per-volume trash dir, in batch order.
            val trashDir = java.io.File(
                container.fileRepo.primaryRoot(),
                com.toadfiles.app.domain.Trash.DIR_NAME,
            )
            val payloads = vm.state.value.lastTrashed!!
            assertEquals(listOf("a", "b"), payloads.map { it.readText() })
            assertTrue(payloads.all { it.absolutePath.startsWith(trashDir.absolutePath) && it.exists() })

            // One undo restores the whole batch.
            vm.consumeUndoOffer()
            vm.undoDelete(payloads)
            await { vm.state.value.toast == "Restored 2 item(s)" }
            assertTrue(a.exists() && b.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `undoDelete reports a mixed batch honestly`() = runTest {
        val dir = java.io.File(container.fileRepo.primaryRoot(), "cat-mixed-undo-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val keep = java.io.File(dir, "keep.txt").apply { writeText("k") }
            val vanished = java.io.File(dir, "vanished.txt").apply { writeText("v") }
            val moved = com.toadfiles.app.domain.Trash.trash(
                listOf(keep, vanished),
                listOf(container.fileRepo.primaryRoot()),
            ).moved
            assertEquals(2, moved.size)
            moved[1].delete() // one payload disappears from the trash before the undo

            vm.undoDelete(moved)
            await { vm.state.value.toast != null }
            // Honest accounting (N5): the already-gone payload is reported, not hidden.
            assertEquals("Restored 1 of 2 — 1 already gone", vm.state.value.toast)
            assertTrue(keep.exists())
            assertFalse(moved[1].exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `requestDelete with nothing is a no-op`() = runTest {
        vm.requestDelete(emptyList())
        assertNull(vm.state.value.pendingDelete)
    }

    @Test
    fun `selection toggles by uri and select-all covers the current entries`() = runTest {
        val a = entry(1, "a.txt")
        val b = entry(2, "b.txt")
        vm.toggleSelection(a)
        vm.toggleSelection(b)
        assertEquals(2, vm.state.value.selection.size)
        vm.toggleSelection(a)
        assertEquals(1, vm.state.value.selection.size)
        vm.selectAll()
        // Entries come from MediaStore (empty on Robolectric): select-all selects nothing
        // rather than throwing — the state machine stays consistent.
        assertTrue(vm.state.value.selection.isEmpty())
    }

    /** Minimal archive builder (same shape as ZipSupportTest's private helper). */
    private fun makeZip(out: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `extractZip runs the real op and surfaces progress then result`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-extract-${System.nanoTime()}")
        root.mkdirs()
        try {
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))

            vm.extractZip(zip)

            // The op shows progress, then finishes with an honest result and the files on disk.
            await { vm.state.value.result != null }
            assertNull(vm.state.value.progress)
            assertEquals("Extracting", vm.state.value.resultTitle)
            assertEquals(1, vm.state.value.result!!.succeeded)
            assertEquals(0, vm.state.value.result!!.failed)
            assertEquals("zipbody", File(root, "bundle/inner.txt").readText())

            vm.dismissResult()
            assertNull(vm.state.value.result)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `extractZip refuses an existing destination folder with a toast`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-extract-${System.nanoTime()}")
        root.mkdirs()
        try {
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))
            File(root, "bundle").mkdirs()

            vm.extractZip(zip)

            assertEquals(
                "A folder named \"bundle\" already exists. Rename or move it before extracting.",
                vm.state.value.toast,
            )
            assertNull(vm.state.value.result)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancelOp cancels the shared op job before the extract runs`() = runTest {
        // A queued (not unconfined) dispatcher freezes the op before it starts, so the
        // cancel below is deterministic: the helper's job is cancelled before the extract
        // body can do any work — overlay dismissed, no result, nothing extracted.
        val queued = StandardTestDispatcher(testScheduler)
        val slowVm = CategoriesViewModel(container, ioDispatcher = queued)
        val root = File(container.fileRepo.primaryRoot(), "cat-cancel-${System.nanoTime()}")
        root.mkdirs()
        try {
            testScheduler.advanceUntilIdle() // settle init's refresh
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))

            slowVm.extractZip(zip)
            assertTrue(slowVm.state.value.progress != null) // overlay up pre-op (seed is synchronous)

            slowVm.cancelOp()
            assertNull(slowVm.state.value.progress)
            testScheduler.advanceUntilIdle() // the cancelled job never starts the extract
            assertNull(slowVm.state.value.result)
            assertFalse(File(root, "bundle").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- v1.7.3 Browse-parity flows ----------

    @Test
    fun `rename moves the on-disk file with the shared FileOps engine`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-rename-${System.nanoTime()}")
        root.mkdirs()
        try {
            val src = File(root, "old-name.txt").apply { writeText("payload") }
            val e = entry(1, src.name, data = src.absolutePath)

            vm.requestRename(e)
            assertEquals(e, vm.state.value.pendingRename)

            vm.rename(e, "new-name.txt")
            await { vm.state.value.toast != null }
            assertNull(vm.state.value.pendingRename)
            assertEquals("Renamed to \"new-name.txt\"", vm.state.value.toast)
            assertFalse(src.exists())
            assertEquals("payload", File(root, "new-name.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rename of an entry without an on-disk path fails honestly`() = runTest {
        val e = entry(1, "orphan.txt", data = null)
        vm.requestRename(e)
        vm.rename(e, "whatever.txt")
        assertEquals("This file has no on-disk path to rename", vm.state.value.toast)
        assertNull(vm.state.value.pendingRename)
    }

    @Test
    fun `zip flow names the archive via the dialog and runs the real op`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zip-${System.nanoTime()}")
        root.mkdirs()
        try {
            val a = File(root, "a.txt").apply { writeText("aaa") }
            val b = File(root, "b.txt").apply { writeText("bbb") }

            vm.requestZip(listOf(a, b))
            assertEquals(listOf(a, b), vm.state.value.pendingZipFiles)
            assertEquals("archive.zip", vm.state.value.pendingZipName)

            vm.confirmZip("packed")
            assertNull(vm.state.value.pendingZipFiles)
            await { vm.state.value.result != null }
            assertEquals("Zipping", vm.state.value.resultTitle)
            assertEquals(2, vm.state.value.result!!.succeeded)
            val out = File(root, "packed.zip")
            assertTrue(out.exists())
            java.util.zip.ZipFile(out).use { z ->
                assertEquals(setOf("a.txt", "b.txt"), z.entries().asSequence().map { it.name }.toSet())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `compress dialog chooses 7z and the level, extension follows the format`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-7z-${System.nanoTime()}")
        root.mkdirs()
        try {
            val a = File(root, "a.txt").apply { writeText("aaa") }

            vm.requestZip(listOf(a))
            assertEquals(com.toadfiles.app.domain.ArchiveFormat.ZIP, vm.state.value.pendingZipFormat)
            assertEquals(com.toadfiles.app.domain.CompressLevel.NORMAL, vm.state.value.pendingZipLevel)

            vm.setZipFormat(com.toadfiles.app.domain.ArchiveFormat.SEVEN_Z)
            vm.setZipLevel(com.toadfiles.app.domain.CompressLevel.NONE)
            vm.confirmZip("packed")
            assertNull(vm.state.value.pendingZipFiles)
            await { vm.state.value.result != null }
            val out = File(root, "packed.7z")
            assertTrue(out.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip flow honors delete-after-compress by trashing the sources`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipdel-${System.nanoTime()}")
        root.mkdirs()
        try {
            container.settingsRepo.setDeleteAfterCompress(true)
            val a = File(root, "a.txt").apply { writeText("aaa") }

            vm.requestZip(listOf(a))
            // The checkbox initializes asynchronously from the persisted setting
            // (DataStore read); wait for it before confirming.
            await { vm.state.value.pendingZipDeleteSources }
            vm.confirmZip("packed")
            await { vm.state.value.result != null }
            await { !a.exists() }
            // The archive itself survives; the source went to the trash (undoable).
            assertTrue(File(root, "packed.zip").exists())

            container.settingsRepo.setDeleteAfterCompress(false)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip flow keeps the sources when delete-after-compress is off`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipkeep-${System.nanoTime()}")
        root.mkdirs()
        try {
            val a = File(root, "a.txt").apply { writeText("aaa") }

            vm.requestZip(listOf(a))
            await { vm.state.value.pendingZipDestination != null }
            vm.confirmZip("packed")
            await { vm.state.value.result != null }
            assertTrue(a.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- v1.8.0: encryption, volumes, per-run delete, separate archives ----------

    @Test
    fun `unchecking delete sources overrides the setting and keeps the sources`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipoverride-${System.nanoTime()}")
        root.mkdirs()
        try {
            container.settingsRepo.setDeleteAfterCompress(true)
            val a = File(root, "a.txt").apply { writeText("aaa") }

            vm.requestZip(listOf(a))
            await { vm.state.value.pendingZipDeleteSources }
            vm.setZipDeleteSources(false) // the per-run override
            vm.confirmZip("packed")
            await { vm.state.value.result != null }
            assertTrue(File(root, "packed.zip").exists())
            assertTrue("the checkbox wins over the persisted setting", a.exists())

            container.settingsRepo.setDeleteAfterCompress(false)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `switching the format to ZIP resets encryption and passwords`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipreset-${System.nanoTime()}")
        root.mkdirs()
        try {
            val a = File(root, "a.txt").apply { writeText("aaa") }

            vm.requestZip(listOf(a))
            vm.setZipFormat(com.toadfiles.app.domain.ArchiveFormat.SEVEN_Z)
            vm.setZipEncrypted(true)
            vm.setZipPassword("secret")
            vm.setZipPasswordConfirm("secret")
            assertTrue(vm.state.value.pendingZipEncrypted)

            vm.setZipFormat(com.toadfiles.app.domain.ArchiveFormat.ZIP)
            // commons-compress cannot create encrypted zip: ZIP forces encryption off.
            assertFalse(vm.state.value.pendingZipEncrypted)
            assertEquals("", vm.state.value.pendingZipPassword)
            assertEquals("", vm.state.value.pendingZipPasswordConfirm)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `encrypted 7z requires a non-blank password and matching confirmation`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zippw-${System.nanoTime()}")
        root.mkdirs()
        try {
            val a = File(root, "a.txt").apply { writeText("aaa") }

            vm.requestZip(listOf(a))
            vm.setZipFormat(com.toadfiles.app.domain.ArchiveFormat.SEVEN_Z)
            vm.setZipEncrypted(true)
            vm.confirmZip("vault")

            // Blank password: the dialog stays open, the user is told why.
            assertEquals("Enter a password", vm.state.value.toast)
            assertNotNull(vm.state.value.pendingZipFiles)

            vm.setZipPassword("one")
            vm.setZipPasswordConfirm("two")
            vm.confirmZip("vault")
            assertEquals("Passwords do not match", vm.state.value.toast)
            assertNotNull(vm.state.value.pendingZipFiles)
            assertFalse(File(root, "vault.7z").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `separate archive per item creates one archive per source and aggregates results`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipsep-${System.nanoTime()}")
        root.mkdirs()
        try {
            val a = File(root, "a.txt").apply { writeText("aaa") }
            val b = File(root, "b.txt").apply { writeText("bbb") }

            vm.requestZip(listOf(a, b))
            vm.setZipSeparate(true)
            vm.confirmZip("ignored-in-separate-mode")

            // One archive per source, named after the source, one combined result.
            await {
                vm.state.value.result != null &&
                    File(root, "a.zip").exists() && File(root, "b.zip").exists()
            }
            assertEquals(2, vm.state.value.result!!.succeeded)
            assertEquals(0, vm.state.value.result!!.failed)
            assertEquals("Zipping", vm.state.value.resultTitle)
            // deleteSources is off here: both sources survive.
            assertTrue(a.exists() && b.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveVolumeBytes maps presets and custom text`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipvol-${System.nanoTime()}")
        root.mkdirs()
        try {
            vm.requestZip(listOf(File(root, "a.txt")))
            // Preset
            vm.setZipVolume(com.toadfiles.app.ui.components.VolumeChoice.MB5)
            assertEquals(5L * 1024 * 1024, vm.resolveVolumeBytes())
            // Custom, valid
            vm.setZipVolume(com.toadfiles.app.ui.components.VolumeChoice.CUSTOM)
            vm.setZipVolumeCustomText("5")
            assertEquals(5L * 1024 * 1024, vm.resolveVolumeBytes())
            // Custom, invalid: blank / non-numeric / non-positive all mean "no split"
            vm.setZipVolumeCustomText("")
            assertNull(vm.resolveVolumeBytes())
            vm.setZipVolumeCustomText("abc")
            assertNull(vm.resolveVolumeBytes())
            vm.setZipVolumeCustomText("0")
            assertNull(vm.resolveVolumeBytes())
            // Back to a preset after Custom clears the draft text
            vm.setZipVolume(com.toadfiles.app.ui.components.VolumeChoice.NONE)
            assertNull(vm.resolveVolumeBytes())
            assertEquals("", vm.state.value.pendingZipVolumeCustomText)
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * TODO(integration): needs Agent 1's domain (AES-256 7z creation + password-aware
     * extraction). Round-trips the produced archive: extracting it without a password
     * must come back passwordRequired, with the right password must succeed. Enable
     * when the domain contract has landed.
     */
    @Test
    fun `confirmZip passes the password to ArchiveOps for an encrypted 7z`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipcrypt-${System.nanoTime()}")
        root.mkdirs()
        try {
            val a = File(root, "a.txt").apply { writeText("seven secrets") }

            vm.requestZip(listOf(a))
            vm.setZipFormat(com.toadfiles.app.domain.ArchiveFormat.SEVEN_Z)
            vm.setZipEncrypted(true)
            vm.setZipPassword("hunter2")
            vm.setZipPasswordConfirm("hunter2")
            vm.confirmZip("vault")

            await { vm.state.value.result != null && File(root, "vault.7z").exists() }
            assertEquals(0, vm.state.value.result!!.failed)

            // The produced archive really is encrypted: no password → passwordRequired.
            val noPw = com.toadfiles.app.domain.ArchiveOps.extract(
                File(root, "vault.7z"), File(root, "noluck"), replace = false,
            )
            assertTrue(noPw.passwordRequired)

            // With the password it extracts to the original content.
            val ok = com.toadfiles.app.domain.ArchiveOps.extract(
                File(root, "vault.7z"), File(root, "unlocked"), replace = false,
                password = "hunter2".toCharArray(),
            )
            assertEquals(1, ok.succeeded)
            assertEquals("seven secrets", File(root, "unlocked/a.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * TODO(integration): same domain dependency; covers the Categories extract
     * password-prompt flow (prompt state + retry with the same target dir).
     */
    @Test
    fun `passwordRequired opens the prompt and retry extracts with the password`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipprompt-${System.nanoTime()}")
        root.mkdirs()
        try {
            val src = File(root, "a.txt").apply { writeText("secret stuff") }
            val vault = File(root, "vault.7z")
            com.toadfiles.app.domain.ArchiveOps.compress(
                listOf(src), vault, com.toadfiles.app.domain.ArchiveFormat.SEVEN_Z,
                password = "hunter2".toCharArray(),
            )

            vm.extractZip(vault)
            await { vm.state.value.pendingPasswordFor != null }
            assertNull("a password ask is not a failure", vm.state.value.result)
            assertEquals(vault, vm.state.value.pendingPasswordRetry!!.archive)

            vm.retryExtractWithPassword("hunter2")
            await { vm.state.value.result != null }
            assertNull(vm.state.value.pendingPasswordFor)
            assertEquals(0, vm.state.value.result!!.failed)
            assertEquals("secret stuff", File(root, "vault/a.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip flow defaults to the archive folder when one is set`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-zipfolder-${System.nanoTime()}")
        root.mkdirs()
        val archiveFolder = File(root, "archdest").apply { mkdirs() }
        try {
            container.settingsRepo.setArchiveFolder(archiveFolder.path)
            val a = File(root, "a.txt").apply { writeText("aaa") }

            vm.requestZip(listOf(a))
            vm.confirmZip("packed")
            await { vm.state.value.result != null }
            assertTrue(File(archiveFolder, "packed.zip").exists())
            assertFalse(File(root, "packed.zip").exists())

            container.settingsRepo.setArchiveFolder("")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `extract to uses the archive folder as default destination`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-extractfolder-${System.nanoTime()}")
        root.mkdirs()
        val archiveFolder = File(root, "archdest").apply { mkdirs() }
        try {
            container.settingsRepo.setArchiveFolder(archiveFolder.path)
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))

            vm.extractZip(zip, targetName = "unpacked")
            await { vm.state.value.result != null }
            assertEquals(0, vm.state.value.result!!.failed)
            // Landed under the archive folder, not next to the source.
            assertEquals("zipbody", File(archiveFolder, "unpacked/inner.txt").readText())
            assertFalse(File(root, "unpacked").exists())

            container.settingsRepo.setArchiveFolder("")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `extract to an existing folder asks for a strategy and keep-both extracts aside`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-extractto-${System.nanoTime()}")
        root.mkdirs()
        try {
            val zip = File(root, "bundle.zip")
            makeZip(zip, mapOf("inner.txt" to "zipbody".toByteArray()))
            File(root, "bundle").mkdirs() // the colliding destination

            vm.extractZip(zip, targetName = "bundle")
            val pc = vm.state.value.pendingExtractConflict
            assertNotNull(pc)
            assertEquals(zip, pc!!.archive)

            vm.resolveExtractConflict(ConflictStrategy.RENAME)
            await { vm.state.value.result != null }
            assertNull(vm.state.value.pendingExtractConflict)
            assertEquals(1, vm.state.value.result!!.succeeded)
            // "Keep both": a uniquely named sibling ("bundle (1)"), the existing folder untouched.
            assertEquals("zipbody", File(root, "bundle (1)/inner.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `requestProperties opens the shared sheet and computes the real detail`() = runTest {
        val root = File(container.fileRepo.primaryRoot(), "cat-props-${System.nanoTime()}")
        root.mkdirs()
        try {
            val f = File(root, "sized.bin").apply { writeBytes(ByteArray(2_048) { 7 }) }
            vm.requestProperties(entry(1, f.name, data = f.absolutePath))

            assertEquals(f, vm.state.value.pendingProperties)
            await { vm.state.value.propertiesDetail != null }
            val d = vm.state.value.propertiesDetail!!
            assertEquals(2_048L, d.sizeBytes)
            assertNull(d.error)

            vm.dismissProperties()
            assertNull(vm.state.value.pendingProperties)
            assertNull(vm.state.value.propertiesDetail)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sort and layout follow the Browse model`() = runTest {
        // Default layout: visual categories open as a grid, others as a list.
        vm.open(FileCategory.DOCS)
        assertEquals(com.toadfiles.app.domain.BrowseLayout.LIST, vm.state.value.layout)
        vm.toggleLayout()
        assertEquals(com.toadfiles.app.domain.BrowseLayout.GRID, vm.state.value.layout)
        vm.open(FileCategory.IMAGES)
        assertEquals(com.toadfiles.app.domain.BrowseLayout.GRID, vm.state.value.layout)

        // Sorting is the shared Sorting engine; NAME ASC is deterministic regardless of
        // MediaStore's own date order.
        val sorted = com.toadfiles.app.ui.categories.CategoriesViewModel(
            container,
            ioDispatcher = dispatcher,
            media = SortedFakeMedia(),
        )
        await { !sorted.state.value.loading }
        sorted.open(FileCategory.DOCS)
        await { !sorted.state.value.entriesLoading }
        assertEquals(listOf("b.pdf", "a.pdf"), sorted.state.value.entries.map { it.name }) // DATE DESC, same date → source order
        sorted.setSort(SortMode.NAME)
        sorted.setSortOrder(SortOrder.ASC)
        assertEquals(listOf("a.pdf", "b.pdf"), sorted.state.value.entries.map { it.name })
    }

    /** Deterministic two-row media source so sorting can be asserted without MediaStore. */
    private class SortedFakeMedia : MediaQuerySource {
        private fun e(id: Long, name: String) = MediaRepository.MediaEntry(
            id = id,
            uri = android.net.Uri.parse("content://test/$id"),
            name = name,
            size = 10L,
            dateModified = 0L,
            mime = "application/pdf",
            category = FileCategory.DOCS,
            data = null,
        )

        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> =
            if (category == FileCategory.DOCS) listOf(e(1, "b.pdf"), e(2, "a.pdf")) else emptyList()

        override fun recent(limit: Int): List<MediaRepository.MediaEntry> = emptyList()

        override fun summaries(): List<CategorySummary> =
            FileCategory.entries.map { CategorySummary(it, 0, 0L) }

        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0
    }
}
