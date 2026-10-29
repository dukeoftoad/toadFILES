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

    private fun renderSheet(item: FileItem, isFavorite: Boolean = false) {
        compose.setContent {
            ToadTheme {
                FileActionSheet(
                    item = item,
                    isFavorite = isFavorite,
                    onOpen = {}, onShare = {}, onRename = {}, onCopy = {}, onMove = {},
                    onDelete = {}, onDetails = {}, onZip = {},
                    onExtract = null,
                    onToggleFavorite = {}, onCopyPath = {}, onOpenWith = {},
                    onSelect = {}, onDismiss = {},
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
        assertRowShown("Delete")
        assertRowShown("Details")
        assertRowShown("Add favorite")
        assertRowShown("Select more")
        // File-only actions
        assertRowShown("Copy path")
        assertRowShown("Open with…")
        // For a non-zip file the zip row reads "Zip this file" (not "Extract here")
        assertRowShown("Zip this file")
    }

    @Test
    fun `folder sheet hides Copy path and Open with, shows Zip this folder`() {
        renderSheet(fileItem("Pictures", isDir = true))
        assertRowShown("Open")
        assertRowShown("Zip this folder")
        // File-only actions must NOT appear for a folder — the sheet hides them.
        assertRowHidden("Copy path")
        assertRowHidden("Open with…")
        assertRowHidden("Extract here")
    }

    @Test
    fun `zip file sheet shows Extract here instead of Zip this file`() {
        renderSheet(fileItem("archive.zip", isDir = false))
        assertRowShown("Extract here")
        // The file-zip row must be hidden when the extract row is shown.
        assertRowHidden("Zip this file")
        assertRowHidden("Zip this folder")
    }

    @Test
    fun `favorite state toggles the favorite row label`() {
        renderSheet(fileItem("x.txt", isDir = false), isFavorite = true)
        assertRowShown("Remove favorite")
        // The "Add favorite" copy should be gone when isFavorite is true.
        assertRowHidden("Add favorite")
    }
}
