package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.components.MediaActionSheet
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Media sheet visibility rules on the JVM (same primitive as [FileActionSheetTest]: the
 * ModalBottomSheet content can't be assertIsDisplayed reliably under Robolectric, so we
 * count semantics nodes). The load-bearing promise: "Extract here" appears only for .zip
 * entries with a wired extract callback, and never for other media — the sheet hides
 * non-applicable actions instead of rendering them disabled.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaActionSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(name: String, category: FileCategory = FileCategory.ARCHIVES, data: String? = null) =
        MediaRepository.MediaEntry(
            id = 1L,
            uri = android.net.Uri.parse("content://test/1"),
            name = name,
            size = 1024L,
            dateModified = 1L,
            mime = "application/zip",
            category = category,
            data = data,
        )

    private fun renderSheet(e: MediaRepository.MediaEntry, onExtract: (() -> Unit)? = null) {
        compose.setContent {
            ToadTheme {
                MediaActionSheet(
                    entry = e,
                    onOpen = {}, onOpenWith = {}, onShare = {},
                    onShowInFolder = null, onDelete = null,
                    onDetails = {}, onCopyPath = null,
                    onExtract = onExtract,
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
    fun `zip entry with an extract callback shows Extract here`() {
        renderSheet(entry("bundle.zip", data = "/tmp/toadfiles-test/bundle.zip"), onExtract = {})
        assertRowShown("Open")
        assertRowShown("Details")
        assertRowShown("Extract here")
    }

    @Test
    fun `non-zip entries never show Extract here`() {
        renderSheet(entry("photo.jpg", category = FileCategory.IMAGES), onExtract = {})
        assertRowShown("Open")
        assertRowHidden("Extract here")
    }

    @Test
    fun `zip entry without a callback hides Extract here`() {
        // No on-disk path / no extract wiring: the row must not hint at the feature.
        renderSheet(entry("bundle.zip"), onExtract = null)
        assertRowHidden("Extract here")
    }
}
