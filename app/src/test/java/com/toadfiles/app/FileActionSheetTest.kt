package com.toadfiles.app

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.ui.components.FileActionSheet
import com.toadfiles.app.ui.theme.ToadTheme
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
        // query the tree; assertExists then checks the node is present (the sheet's
        // visible-bounds check is unreliable in Robolectric, so we don't use assertIsDisplayed).
        compose.waitForIdle()
    }

    @Test
    fun `file sheet shows the core actions plus file-only actions`() {
        renderSheet(fileItem("report.pdf", isDir = false))
        // Core actions every item gets
        compose.onNodeWithText("Open").assertExists()
        compose.onNodeWithText("Share (copy locally)").assertExists()
        compose.onNodeWithText("Rename").assertExists()
        compose.onNodeWithText("Copy to…").assertExists()
        compose.onNodeWithText("Move to…").assertExists()
        compose.onNodeWithText("Delete").assertExists()
        compose.onNodeWithText("Details").assertExists()
        compose.onNodeWithText("Add favorite").assertExists()
        compose.onNodeWithText("Select more").assertExists()
        // File-only actions
        compose.onNodeWithText("Copy path").assertExists()
        compose.onNodeWithText("Open with…").assertExists()
        // For a non-zip file the zip row reads "Zip this file" (not "Extract here")
        compose.onNodeWithText("Zip this file").assertExists()
    }

    @Test
    fun `folder sheet hides Copy path and Open with, shows Zip this folder`() {
        renderSheet(fileItem("Pictures", isDir = true))
        compose.onNodeWithText("Open").assertExists()
        compose.onNodeWithText("Zip this folder").assertExists()
        // File-only actions must NOT appear for a folder — the sheet hides them.
        // assertExists would throw if the node was present, so we use the inverse:
        // there should be zero nodes with this text.
        assert(compose.onAllNodesWithText("Copy path").fetchSemanticsNodes().isEmpty()) {
            "Copy path should not appear for a folder"
        }
        assert(compose.onAllNodesWithText("Open with…").fetchSemanticsNodes().isEmpty()) {
            "Open with… should not appear for a folder"
        }
        assert(compose.onAllNodesWithText("Extract here").fetchSemanticsNodes().isEmpty()) {
            "Extract here should not appear for a folder"
        }
    }

    @Test
    fun `zip file sheet shows Extract here instead of Zip this file`() {
        renderSheet(fileItem("archive.zip", isDir = false))
        compose.onNodeWithText("Extract here").assertExists()
        // The file-zip row must be hidden when the extract row is shown.
        assert(compose.onAllNodesWithText("Zip this file").fetchSemanticsNodes().isEmpty()) {
            "Zip this file should not appear for a .zip"
        }
        assert(compose.onAllNodesWithText("Zip this folder").fetchSemanticsNodes().isEmpty()) {
            "Zip this folder should not appear for a file"
        }
    }

    @Test
    fun `favorite state toggles the favorite row label`() {
        renderSheet(fileItem("x.txt", isDir = false), isFavorite = true)
        compose.onNodeWithText("Remove favorite").assertExists()
        // The "Add favorite" copy should be gone when isFavorite is true.
        assert(compose.onAllNodesWithText("Add favorite").fetchSemanticsNodes().isEmpty()) {
            "Add favorite should not appear when the item is already a favorite"
        }
    }
}
