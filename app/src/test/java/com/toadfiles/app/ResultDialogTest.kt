package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.toadfiles.app.ui.components.ResultDialog
import com.toadfiles.app.ui.components.resultDoneTitle
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ResultDialog title test on the JVM — isolated compose content (AlertDialog, no nav
 * shell, no scrolling), following the repo's established primitives (see
 * FileActionSheetTest / ProgressOverlayTest): text existence via fetchSemanticsNode
 * counts, because assertIsDisplayed is unreliable on dialog windows under Robolectric.
 *
 * Contract under test: the finished dialog is named after the operation ("Move
 * finished", "Extract finished"…) instead of a generic "Operation finished" that
 * never said WHAT finished; summary and failure details are unchanged; unknown or
 * blank titles keep the generic fallback.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ResultDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private fun hasText(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun render(summary: String, failures: List<String> = emptyList(), resultTitle: String = "") {
        compose.setContent {
            ToadTheme {
                ResultDialog(
                    summary = summary,
                    failures = failures,
                    onDismiss = {},
                    resultTitle = resultTitle,
                )
            }
        }
    }

    @Test
    fun `title maps every op word to its finished form`() {
        assertEquals("Copy finished", resultDoneTitle("Copying"))
        assertEquals("Move finished", resultDoneTitle("Moving"))
        assertEquals("Extract finished", resultDoneTitle("Extracting"))
        assertEquals("Zip finished", resultDoneTitle("Zipping"))
        assertEquals("Compression finished", resultDoneTitle("Compressing"))
        // Defensible fallbacks: whitespace is tolerated, unknown/blank stay generic.
        assertEquals("Move finished", resultDoneTitle(" Moving "))
        assertEquals("Operation finished", resultDoneTitle(""))
        assertEquals("Operation finished", resultDoneTitle("Teleporting"))
    }

    @Test
    fun `dialog title names the operation`() {
        render(summary = "Moving: 3 done, 0 skipped", resultTitle = "Moving")

        assertTrue(hasText("Move finished"))
        assertFalse(hasText("Operation finished"))
        assertTrue(hasText("Moving: 3 done, 0 skipped"))
    }

    @Test
    fun `summary and failure details are unchanged and blank title stays generic`() {
        render(summary = "Extracting: 1 done, 1 skipped", failures = listOf("could not write b.txt"))

        assertTrue(hasText("Operation finished"))
        assertTrue(hasText("Extracting: 1 done, 1 skipped"))
        assertTrue(hasText("Details:"))
        assertTrue(hasText("• could not write b.txt"))
    }
}
