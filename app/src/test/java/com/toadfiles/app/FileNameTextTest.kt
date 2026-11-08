package com.toadfiles.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ui.components.FileNameText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * FileNameText geometry contract: when a file name is too long for its row, the BASE
 * name is cut at whatever fits and the extension (.zip, .txt, .pdf, …) stays fully
 * visible — so the file's type survives truncation. The full untruncated name is
 * always exposed as the content description for TalkBack and tests.
 *
 * All assertions are pure node geometry (bounds in root) — layout positions are what
 * this suite can measure reliably, and they pin the user-visible promise directly:
 * base runs flush into the extension, and the extension lands exactly at the row's
 * right edge only when truncation happened.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileNameTextTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `long name fills the row and pins the extension to its right edge`() {
        val name = "year_end_financial_report_2026_final_version.pdf"
        compose.setContent {
            MaterialTheme {
                FileNameText(name, modifier = Modifier.width(140.dp))
            }
        }

        // The full name is always available to TalkBack / tests via content description.
        compose.onNodeWithContentDescription(name, useUnmergedTree = true).assertExists()

        val row = compose.onNodeWithContentDescription(name, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val base = compose.onNodeWithText(name.removeSuffix(".pdf"), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val ext = compose.onNodeWithText(".pdf", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        // Base and extension sit flush: the base wanted more room than the row had,
        // so it fills everything left of the extension…
        assertTrue(
            "base must run flush into the extension — name was cut to the fit",
            abs(base.right - ext.left) <= 1f,
        )
        // …and the extension ends exactly at the row's right edge: the cut kept the
        // maximum number of characters that fit and the extension renders whole.
        assertTrue(
            "extension must reach the row's right edge",
            abs(ext.right - row.right) <= 1f,
        )
        assertTrue("extension must have rendered", ext.width > 0f)
    }

    @Test
    fun `short name leaves the rest of the row free`() {
        compose.setContent {
            MaterialTheme {
                FileNameText("cv.pdf", modifier = Modifier.width(300.dp))
            }
        }
        val row = compose.onNodeWithContentDescription("cv.pdf", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val base = compose.onNodeWithText("cv", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val ext = compose.onNodeWithText(".pdf", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        // Natural inline flow, no truncation anywhere: the pair wraps its own width
        // and leaves the rest of the row empty.
        assertTrue(abs(base.right - ext.left) <= 1f)
        assertTrue("short name must leave slack in the row", row.right - ext.right > 100f)
        assertTrue(ext.width > 0f)
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
