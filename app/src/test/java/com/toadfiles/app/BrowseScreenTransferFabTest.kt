package com.toadfiles.app

import android.os.Environment
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ui.browse.BrowseScreen
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Owner report: while a move/copy transfer is armed, the new-folder FAB sat right on
 * top of the TransferBar's Paste/Cancel buttons and the item-count message (Scaffold
 * draws the FAB over content, and the bar spans the same bottom edge). The fix: the
 * FAB yields the bottom edge while the bar is up and returns once the transfer is
 * canceled or pasted. Same harness style as BrowseScreenOverflowTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class BrowseScreenTransferFabTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: File

    @Before
    fun setUp() {
        ToadViewModelFactory.init(ApplicationProvider.getApplicationContext())
        root = File(Environment.getExternalStorageDirectory(), "transfer_fab_test").apply {
            mkdirs()
            File(this, "notes.txt").writeText("hello")
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun render() {
        compose.setContent {
            ToadTheme {
                BrowseScreen(
                    initialDir = root.absolutePath,
                    bottomPadding = Dp(0f),
                    onNavigateUp = {},
                )
            }
        }
    }

    private fun awaitListing() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Arms a transfer the way a user does: long-press a row → ⋮ → Copy to… */
    private fun armCopy() {
        compose.onNodeWithText("notes.txt").performTouchInput { longClick() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("1 selected").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Copy to…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Copy to…").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Copy to here").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `new-folder FAB yields the bottom edge to the armed transfer bar and returns after cancel`() {
        render()
        awaitListing()

        // Baseline: with nothing armed, the FAB is the bottom-edge action.
        compose.onNodeWithContentDescription("New folder").assertExists()

        armCopy()

        // While the move/copy bar owns the bottom edge, the FAB must not be composed
        // on top of its Paste/Cancel buttons and count message.
        compose.onNodeWithContentDescription("New folder").assertDoesNotExist()

        // Cancel disarms the transfer — the FAB comes back.
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Copy to here").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("New folder").fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
