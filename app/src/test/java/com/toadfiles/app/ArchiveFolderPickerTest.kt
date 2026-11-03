package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ui.components.ArchiveFolderPicker
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * ArchiveFolderPicker label test on the JVM — isolated compose content (AlertDialog),
 * following the repo's established primitives (see ResultDialogTest / FileActionSheetTest):
 * text existence via fetchSemanticsNode counts, because assertIsDisplayed is unreliable
 * on dialog windows under Robolectric.
 *
 * Contract under test: the picker speaks sentence case like every other dialog in the
 * app ("Use this folder", not "Use This Folder" — the old Title Case labels were the
 * only ones left in the UI), and the three actions still fire their callbacks with the
 * right values.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArchiveFolderPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun hasText(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun render(initial: String, onSelect: (String) -> Unit = {}) {
        compose.setContent {
            ToadTheme {
                ArchiveFolderPicker(initial = initial, onSelect = onSelect, onDismiss = {})
            }
        }
    }

    @Test
    fun `labels are sentence case and old title-case strings are gone`() {
        val tmp = File("/tmp/toadfiles-test-picker/parent/here").apply { mkdirs() }
        File(tmp, "Subfolder").apply { mkdirs() }
        render(initial = tmp.absolutePath)

        assertTrue(hasText("Archive folder"))
        assertTrue(hasText("Up one level"))   // initial != storage root → the row shows
        assertTrue(hasText("Subfolder"))       // browsable subdirectory listed
        assertTrue(hasText("Same folder as source"))
        assertTrue(hasText("Use this folder"))
        assertTrue(hasText("Cancel"))

        // The old Title Case strings must not come back.
        assertEquals(0, compose.onAllNodesWithText("Use This Folder").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Same Folder As Source").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Up One Level").fetchSemanticsNodes().size)
    }

    @Test
    fun `actions fire the callbacks with the right values`() {
        val tmp = File("/tmp/toadfiles-test-picker2/parent/here").apply { mkdirs() }
        val picked = mutableListOf<String>()
        render(initial = tmp.absolutePath, onSelect = { picked += it })

        compose.onNodeWithText("Use this folder").performClick()
        assertEquals(listOf(tmp.absolutePath), picked)

        compose.setContent {
            ToadTheme {
                ArchiveFolderPicker(
                    initial = tmp.absolutePath,
                    onSelect = { picked += it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Same folder as source").performClick()
        assertEquals(listOf(tmp.absolutePath, ""), picked)
    }
}
