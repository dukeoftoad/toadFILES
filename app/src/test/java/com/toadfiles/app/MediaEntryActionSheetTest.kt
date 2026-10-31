package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.components.MediaEntryActionSheet
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shared per-media-entry sheet wiring (Recent + Categories) on the JVM — same
 * primitive as [FileActionSheetTest]: the ModalBottomSheet content can't be
 * assertIsDisplayed reliably under Robolectric, so we count semantics nodes. Since
 * v1.7.3 the wiring renders the SAME [com.toadfiles.app.ui.components.FileActionSheet]
 * Browse uses, so the load-bearing promises are: "Extract here" appears only for
 * extractable archives with an on-disk path, path-dependent rows (rename/zip/delete)
 * hide without one, and the Browse-parity rows (Rename / Properties) appear once wired.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaEntryActionSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(
        name: String,
        category: FileCategory = FileCategory.ARCHIVES,
        data: String? = null,
    ) = MediaRepository.MediaEntry(
        id = 1L,
        uri = android.net.Uri.parse("content://test/1"),
        name = name,
        size = 1024L,
        dateModified = 1L,
        mime = "application/zip",
        category = category,
        data = data,
    )

    private fun renderSheet(
        e: MediaRepository.MediaEntry,
        onExtract: (java.io.File) -> Unit = {},
        onRename: ((MediaRepository.MediaEntry) -> Unit)? = null,
        onZip: ((java.io.File) -> Unit)? = null,
        onExtractTo: ((java.io.File) -> Unit)? = null,
    ) {
        compose.setContent {
            ToadTheme {
                MediaEntryActionSheet(
                    entry = e,
                    onDismiss = {},
                    open = { true },
                    onShowInFolder = {},
                    toast = {},
                    requestDelete = {},
                    onDetails = {},
                    onExtract = onExtract,
                    onRename = onRename,
                    onZip = onZip,
                    onExtractTo = onExtractTo,
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
    fun `zip entry with an on-disk path shows Extract here and Properties`() {
        renderSheet(entry("bundle.zip", data = "/tmp/toadfiles-test/bundle.zip"))
        assertRowShown("Open")
        assertRowShown("Properties")
        assertRowShown("Extract here")
    }

    @Test
    fun `non-zip entries never show Extract here`() {
        renderSheet(entry("photo.jpg", category = FileCategory.IMAGES, data = "/tmp/photo.jpg"))
        assertRowShown("Open")
        assertRowHidden("Extract here")
    }

    @Test
    fun `zip entry without an on-disk path hides path-dependent actions`() {
        // No DATA path / no extract target: the rows must not hint at the features.
        renderSheet(entry("bundle.zip"))
        assertRowHidden("Extract here")
        assertRowHidden("Extract to…")
        assertRowHidden("Rename")
        assertRowHidden("Zip this file")
        assertRowHidden("Delete")
        assertRowHidden("Copy path")
        assertRowHidden("Show in folder")
    }

    @Test
    fun `wired Browse-parity rows appear for entries with a path`() {
        // Categories wires rename/zip/extract-to — those rows must render, plus the
        // fallback "Zip this file" for a non-archive.
        renderSheet(
            entry("song.mp3", category = FileCategory.AUDIO, data = "/tmp/song.mp3"),
            onRename = {},
            onZip = {},
            onExtractTo = {},
        )
        assertRowShown("Rename")
        assertRowShown("Zip this file")
        assertRowShown("Properties")
        // Extract to… only ever rides along "Extract here" (archives only).
        assertRowHidden("Extract to…")
        assertRowHidden("Extract here")
    }

    @Test
    fun `wired extract-to rides along Extract here for archives`() {
        renderSheet(
            entry("bundle.zip", data = "/tmp/toadfiles-test/bundle.zip"),
            onExtractTo = {},
        )
        assertRowShown("Extract here")
        assertRowShown("Extract to…")
    }
}
