package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ui.components.PropertiesDetail
import com.toadfiles.app.ui.components.PropertiesSheet
import com.toadfiles.app.ui.components.fileTypeLabel
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Properties sheet UI test on the JVM (same approach as FileActionSheetTest): the sheet is
 * purely presentational, so we assert its row visibility rules and the extension→type label
 * map. onAllNodesWithText(...).fetchSemanticsNodes() is the assertion primitive because
 * ModalBottomSheet bounds are unreliable under Robolectric (see FileActionSheetTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PropertiesSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertShown(text: String) {
        val nodes = compose.onAllNodesWithText(text).fetchSemanticsNodes()
        assertTrue("Expected '$text' in the properties sheet", nodes.isNotEmpty())
    }

    private fun assertNotShown(text: String) {
        val nodes = compose.onAllNodesWithText(text).fetchSemanticsNodes()
        assertTrue("'$text' should NOT appear in the properties sheet", nodes.isEmpty())
    }

    private fun render(name: String, isDir: Boolean = false, detail: PropertiesDetail?, file: File? = null) {
        // Path doesn't need to exist for most rows (only the name + flags matter), but the
        // folder-vs-file branch reads file.isDirectory, so folder tests pass a real dir.
        compose.setContent {
            ToadTheme {
                PropertiesSheet(
                    file = file ?: File("/tmp/toadfiles-test/$name"),
                    detail = detail,
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun detail(
        sizeText: String = "1 B",
        error: String? = null,
    ) = PropertiesDetail(
        sizeText = sizeText, sizeBytes = 1L, fileCount = 0, dirCount = 0, error = error,
    )

    @Test
    fun `type labels cover folders, common extensions and the uppercase fallback`() {
        assertEquals("Folder", fileTypeLabel("Pictures", isDir = true))
        assertEquals("ZIP archive", fileTypeLabel("archive.zip", isDir = false))
        assertEquals("JPEG image", fileTypeLabel("photo.jpg", isDir = false))
        assertEquals("PDF document", fileTypeLabel("contract.PDF", isDir = false))
        assertEquals("XYZ", fileTypeLabel("data.xyz", isDir = false))
        assertEquals("File", fileTypeLabel("README", isDir = false))
        // A dotfile has no real extension.
        assertEquals("File", fileTypeLabel(".gitignore", isDir = false))
    }

    @Test
    fun `sheet shows the read-only rows and hides the hidden row for normal names`() {
        render("archive.zip", detail = detail(sizeText = "2.0 KB"))
        assertShown("Type")
        assertShown("ZIP archive")
        assertShown("Location")
        assertShown("Size")
        assertShown("Modified")
        assertShown("Close")
        // Not a dotfile: no hidden row. Detail is ready: no calculating placeholder.
        assertNotShown("Hidden")
        assertNotShown("Calculating…")
    }

    @Test
    fun `folder without computed detail shows the calculating placeholder`() {
        render("Pictures", isDir = true, detail = null)
        assertShown("Calculating…")
        assertShown("Type")
    }

    @Test
    fun `hidden row appears only for dot-prefixed names`() {
        render(".secret", detail = detail())
        assertShown("Hidden")
        assertShown("Yes")
    }

    @Test
    fun `unreadable folder error copy is surfaced`() {
        // PropertiesSheet branches on File.isDirectory, so the "locked" folder must exist.
        val locked = java.nio.file.Files.createTempDirectory("toad-locked").toFile()
        assertTrue(locked.setWritable(false))
        try {
            render("locked", isDir = true, detail = detail(error = "Folder is not readable"), file = locked)
            assertShown("Folder is not readable")
        } finally {
            locked.setWritable(true)
            locked.delete()
        }
    }
}
