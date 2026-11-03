package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.ui.components.FileActionSheet
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Long-press action sheet UI test on the JVM. The sheet is purely presentational — it takes
 * a FileItem and a bunch of callbacks, and renders rows based on the item's shape (file vs
 * folder, zip vs not). We assert those visibility rules because they're the load-bearing
 * "Mi Files style" promise: the right actions appear for the right item.
 *
 * We use onAllNodesWithText(...).fetchSemanticsNodes().isNotEmpty/isEmpty as the assertion
 * primitive because assertExists/assertIsDisplayed are unreliable on ModalBottomSheet
 * content under Robolectric (no animation pipeline, no viewport bounds) and the
 * compose-ui-test version pinned by this project doesn't expose assertExists as a
 * top-level import. The semantics-node-count check is the equivalent contract.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileActionSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun fileItem(name: String, isDir: Boolean = false): FileItem {
        // Path doesn't need to exist for the sheet UI; only the name + flags matter.
        val f = File("/tmp/toadfiles-test/$name")
        return FileItem(file = f, name = name, isDir = isDir, size = 1024L, lastModified = 1L)
    }

    private fun renderSheet(
        item: FileItem,
        isFavorite: Boolean = false,
        onExtract: (() -> Unit)? = null,
        onExtractTo: (() -> Unit)? = null,
        wired: Boolean = true,
    ) {
        compose.setContent {
            ToadTheme {
                FileActionSheet(
                    item = item,
                    isFavorite = isFavorite,
                    onOpen = {}, onShare = {}, onDelete = {}, onDetails = {},
                    onRename = if (wired) ({}) else null,
                    onCopy = if (wired) ({}) else null,
                    onMove = if (wired) ({}) else null,
                    onZip = if (wired) ({}) else null,
                    onExtract = onExtract,
                    onExtractTo = onExtractTo,
                    onToggleFavorite = if (wired) ({}) else null,
                    onCopyPath = if (wired) ({}) else null,
                    onOpenWith = if (wired) ({}) else null,
                    onDismiss = {},
                )
            }
        }
        // ModalBottomSheet animates open. waitForIdle lets the animation settle before we
        // query the tree.
        compose.waitForIdle()
    }

    private fun assertRowShown(label: String) {
        val nodes = compose.onAllNodesWithText(label).fetchSemanticsNodes()
        assertTrue("Expected row '$label' to be present in the sheet", nodes.isNotEmpty())
    }

    private fun assertRowHidden(label: String) {
        val nodes = compose.onAllNodesWithText(label).fetchSemanticsNodes()
        assertTrue("Row '$label' should NOT appear in the sheet", nodes.isEmpty())
    }

    @Test
    fun `file sheet shows the core actions plus file-only actions`() {
        renderSheet(fileItem("report.pdf", isDir = false))
        // Core actions every item gets
        assertRowShown("Open")
        assertRowShown("Share")
        assertRowShown("Rename")
        assertRowShown("Copy to…")
        assertRowShown("Move to…")
        assertRowShown("Move to trash")
        // The row must never promise bare "Delete": it trashes (30-day undo), it does
        // not destroy — the confirm dialog and the Trash page use "Delete forever".
        assertRowHidden("Delete")
        assertRowShown("Properties")
        assertRowShown("Add favorite")
        // "Select more" is gone: long-press starts multi-select now.
        // File-only actions
        assertRowShown("Copy path")
        assertRowShown("Open with…")
        // For a non-zip file the compress row reads "Compress this file" (not "Extract here")
        assertRowShown("Compress this file")
    }

    @Test
    fun `folder sheet hides Copy path and Open with, shows Compress this folder`() {
        renderSheet(fileItem("Pictures", isDir = true))
        assertRowShown("Open")
        assertRowShown("Compress this folder")
        // File-only actions must NOT appear for a folder — the sheet hides them.
        assertRowHidden("Copy path")
        assertRowHidden("Open with…")
        assertRowHidden("Extract here")
    }

    @Test
    fun `zip file sheet shows Extract here instead of Compress this file`() {
        renderSheet(fileItem("archive.zip", isDir = false), onExtract = {})
        assertRowShown("Extract here")
        // The file-compress row must be hidden when the extract row is shown.
        assertRowHidden("Compress this file")
        assertRowHidden("Compress this folder")
    }

    @Test
    fun `7z file sheet shows Extract here instead of Compress this file`() {
        // The 7z twin keeps the same honest-visibility promise.
        renderSheet(fileItem("archive.7z", isDir = false), onExtract = {})
        assertRowShown("Extract here")
        assertRowHidden("Compress this file")
    }

    @Test
    fun `Extract here is hidden when the screen cannot extract`() {
        // v1.7.3: an unwired extract (e.g. a media row without an on-disk path) must not
        // hint at the action — the row hides, and a zippable file falls back to "Compress this file".
        renderSheet(fileItem("archive.zip", isDir = false), onExtract = null)
        assertRowHidden("Extract here")
        assertRowShown("Compress this file")
    }

    @Test
    fun `unwired actions stay hidden so screens can offer a subset`() {
        // The media screens (Categories drill-in, Recent) wire only the actions that make
        // sense for a MediaStore row — the unwired ones must not render, Browse-style.
        renderSheet(fileItem("song.mp3", isDir = false), wired = false)
        assertRowShown("Open")
        assertRowShown("Share")
        assertRowShown("Move to trash")
        assertRowShown("Properties")
        assertRowHidden("Rename")
        assertRowHidden("Copy to…")
        assertRowHidden("Move to…")
        assertRowHidden("Compress this file")
        assertRowHidden("Add favorite")
        assertRowHidden("Copy path")
        assertRowHidden("Open with…")
    }

    @Test
    fun `zip sheet shows Extract to alongside Extract here when the screen can pick a destination`() {
        renderSheet(fileItem("archive.zip", isDir = false), onExtract = {}, onExtractTo = {})
        assertRowShown("Extract here")
        assertRowShown("Extract to…")
    }

    @Test
    fun `Extract to is hidden when no destination callback is wired`() {
        // Screens without destination picking pass null — the row must not hint at it.
        renderSheet(fileItem("archive.zip", isDir = false), onExtract = {}, onExtractTo = null)
        assertRowShown("Extract here")
        assertRowHidden("Extract to…")
    }

    @Test
    fun `favorite state toggles the favorite row label`() {
        renderSheet(fileItem("x.txt", isDir = false), isFavorite = true)
        assertRowShown("Remove favorite")
        // The "Add favorite" copy should be gone when isFavorite is true.
        assertRowHidden("Add favorite")
    }

    @Test
    fun `properties row shows for a file`() {
        // The old "Details" label is gone: the row opens the read-only properties sheet.
        renderSheet(fileItem("report.pdf", isDir = false))
        assertRowShown("Properties")
        assertRowHidden("Details")
    }

    @Test
    fun `properties row shows for a folder`() {
        renderSheet(fileItem("Pictures", isDir = true))
        assertRowShown("Properties")
        assertRowHidden("Details")
    }
}
