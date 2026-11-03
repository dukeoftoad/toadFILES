package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import com.toadfiles.app.ui.components.SortMenu
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SortMenu selection-marker test on the JVM — isolated compose content, following the
 * repo's established primitives (see ResultDialogTest / FileActionSheetTest): text and
 * content-description existence via fetchSemanticsNode counts.
 *
 * Contract under test: the selected sort mode and order are marked with a trailing
 * Check ICON announced as "Selected" (the Material 3 idiom), never a "✓" glyph glued
 * onto the label text — TalkBack read the old "Name ✓" as one garbled word, and the
 * glyph also broke the menu's text alignment.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SortMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private fun hasText(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun selectedCount() =
        compose.onAllNodesWithContentDescription("Selected").fetchSemanticsNodes().size

    private fun render(current: SortMode, currentOrder: SortOrder) {
        compose.setContent {
            ToadTheme {
                SortMenu(
                    expanded = true,
                    current = current,
                    currentOrder = currentOrder,
                    onSelect = {},
                    onSelectOrder = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `selection is a trailing Check icon, never a checkmark glyph in the label`() {
        render(current = SortMode.NAME, currentOrder = SortOrder.ASC)

        // Labels stay clean text; the old bug rendered "Name ✓" / "Ascending ✓".
        assertTrue(hasText("Name"))
        assertTrue(hasText("Size"))
        assertTrue(hasText("Ascending"))
        assertTrue(hasText("Descending"))
        assertFalse(hasText("Name ✓"))
        assertFalse(hasText("Ascending ✓"))

        // Exactly one Check per list (mode list + order list) — two in total.
        assertEquals(2, selectedCount())
    }

    @Test
    fun `the Check icon follows the current selection`() {
        render(current = SortMode.SIZE, currentOrder = SortOrder.DESC)

        assertEquals(2, selectedCount())
        assertTrue(hasText("Size"))
        assertTrue(hasText("Descending"))
    }
}
