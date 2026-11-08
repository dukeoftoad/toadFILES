package com.toadfiles.app

import android.os.Environment
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ui.browse.BrowseScreen
import com.toadfiles.app.ui.theme.ToadTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Browse top bar's ⋮ overflow menu: search and the hidden-files toggle moved
 * behind it, so the toolbar stays minimal and the search field is hidden until the
 * user asks for it (the always-visible field permanently took a row of space).
 * Same harness style as RecentScreenTest; the folder is real Robolectric storage,
 * like the "browse lists files" e2e test.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class BrowseScreenOverflowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: File

    @Before
    fun setUp() {
        ToadViewModelFactory.init(ApplicationProvider.getApplicationContext())
        root = File(Environment.getExternalStorageDirectory(), "overflow_menu_test").apply {
            mkdirs()
            File(this, "overflow_note.txt").writeText("hello")
            File(this, ".overflow_secret.txt").writeText("secret")
        }
        resetShowHidden()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        resetShowHidden()
    }

    /** The hidden-files toggle persists via DataStore — never leak a flip into other tests. */
    private fun resetShowHidden() {
        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        runBlocking { app.container.settingsRepo.setShowHidden(false) }
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

    /** Waits until the listing is on screen — i.e. the non-selection top bar is composed. */
    private fun awaitListing() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(
                "overflow_note.txt",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openOverflow() {
        compose.onNodeWithContentDescription("More").performClick()
    }

    @Test
    fun `overflow menu offers Search and the hidden-files toggle, search field starts hidden`() {
        render()
        awaitListing()

        // Hidden by default — reclaiming the field's row is the point of the menu.
        compose.onNodeWithText("Search in this folder tree").assertDoesNotExist()

        openOverflow()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Show hidden files").assertExists()
    }

    @Test
    fun `tapping Search in the menu reveals the field and tapping it again hides it`() {
        render()
        awaitListing()
        compose.onNodeWithText("Search in this folder tree").assertDoesNotExist()

        // Reveal: ⋮ → Search.
        openOverflow()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Search").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Search in this folder tree").fetchSemanticsNodes().isNotEmpty()
        }

        // Hide: ⋮ → Search again.
        openOverflow()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Search").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Search in this folder tree").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `hidden-files menu item flips the setting and lists dot-files`() {
        render()
        awaitListing()

        // Default (showHidden = false): the dot-file is filtered out of the listing.
        compose.onNodeWithContentDescription(".overflow_secret.txt", useUnmergedTree = true)
            .assertDoesNotExist()

        openOverflow()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Show hidden files").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Show hidden files").performClick()

        // The persisted flip makes the ViewModel re-list the folder with hidden entries.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(
                ".overflow_secret.txt",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
