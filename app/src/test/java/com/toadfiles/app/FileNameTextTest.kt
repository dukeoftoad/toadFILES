package com.toadfiles.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ui.components.FileNameText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FileNameText layout contract: when a file name is too long for its row, the BASE
 * name is cut at whatever fits and the extension (.zip, .txt, .pdf, …) stays fully
 * visible — so the file's type survives truncation. The full untruncated name is
 * always exposed as the content description for TalkBack and tests.
 *
 * Layout assertions go through the UNMERGED tree: FileNameText merges its two text
 * nodes for a11y, and only the unmerged tree keeps them as individually measurable
 * nodes. The layout result is read through the standard GetTextLayoutResult semantics
 * action that every Text node installs — the same channel TalkBack uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileNameTextTest {

    @get:Rule
    val compose = createComposeRule()

    private fun SemanticsNodeInteraction.layoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        checkNotNull(action) { "node does not expose a text layout result action" }
        check(action.invoke(results)) { "text layout result not available (node not laid out?)" }
        return results.single()
    }

    @Test
    fun `long name truncates the base but the extension stays whole and inside the row`() {
        val name = "year_end_financial_report_2026_final_version.pdf"
        val base = name.removeSuffix(".pdf")
        compose.setContent {
            MaterialTheme {
                FileNameText(name, modifier = Modifier.width(140.dp))
            }
        }

        // The full name is always available to TalkBack / tests via content description.
        compose.onNodeWithContentDescription(name, useUnmergedTree = true).assertExists()

        // The base really is visually truncated in the narrow row…
        val baseLayout = compose.onNodeWithText(base, useUnmergedTree = true).layoutResult()
        assertTrue("base should be visually truncated", baseLayout.hasVisualOverflow)

        // …while the extension renders whole, on one line, inside the row bounds.
        val extInteraction = compose.onNodeWithText(".pdf", useUnmergedTree = true)
        val extLayout = extInteraction.layoutResult()
        assertFalse("extension must never clip", extLayout.hasVisualOverflow)
        assertEquals("extension stays on one line", 1, extLayout.lineCount)
        val extNode = extInteraction.fetchSemanticsNode()
        val rowRight = compose.onNodeWithContentDescription(name, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.right
        val extRight = extNode.positionInRoot.x + extLayout.size.width.toFloat()
        assertTrue(
            "extension must sit inside the row bounds",
            extRight <= rowRight + 1f,
        )
    }

    @Test
    fun `short name shows everything without any truncation`() {
        compose.setContent {
            MaterialTheme {
                FileNameText("cv.pdf", modifier = Modifier.width(300.dp))
            }
        }
        val baseLayout = compose.onNodeWithText("cv", useUnmergedTree = true).layoutResult()
        assertFalse("short base must not truncate", baseLayout.hasVisualOverflow)
        val extLayout = compose.onNodeWithText(".pdf", useUnmergedTree = true).layoutResult()
        assertFalse(extLayout.hasVisualOverflow)
    }

    @Test
    fun `hidden files and extension-less names render as one plain text node`() {
        compose.setContent {
            MaterialTheme {
                Column {
                    FileNameText(".gitignore", modifier = Modifier.width(200.dp))
                    FileNameText("README", modifier = Modifier.width(200.dp))
                }
            }
        }
        // Only the no-extension branch renders the FULL name as a single text node —
        // finding it proves no split happened.
        compose.onNodeWithText(".gitignore", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("README", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `directories are never split on their dots`() {
        compose.setContent {
            MaterialTheme {
                FileNameText(
                    "My.Project",
                    modifier = Modifier.width(80.dp),
                    isDirectory = true,
                )
            }
        }
        compose.onNodeWithText("My.Project", useUnmergedTree = true).assertExists()
    }
}
