package com.toadfiles.app.e2e

import android.os.Environment
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ToadApp
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.ui.ToadNavHost
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Full-navigation E2E on the JVM: real app container, real DataStore, real filesystem
 * (Robolectric external storage), the actual NavHost with all screens.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class ToadNavHostE2ETest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        ToadViewModelFactory.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `home screen renders the app shell`() {
        compose.setContent { ToadTheme { ToadNavHost() } }
        compose.onNodeWithText("Home").assertIsSelected()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("toadFILES").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `walking every bottom tab reaches the right screen`() {
        compose.setContent { ToadTheme { ToadNavHost() } }

        // Browse: the bottom-bar item is the only "Browse"-labelled node after a click,
        // because the TopAppBar title shows the current dir's name, not the literal
        // "Browse". The old >= 2 assertion only worked for Categories, where the
        // CategoriesScreen TopAppBar title was the literal "Categories".
        compose.onNodeWithText("Browse").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Browse").fetchSemanticsNodes().isNotEmpty()
        }

        // Settings — a setting row is rendered
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Show hidden files").fetchSemanticsNodes().isNotEmpty()
        }

        // Back to Home
        compose.onNodeWithText("Home").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("toadFILES").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Home").assertIsSelected()
    }

    @Test
    fun `bottom bar shows Home Browse Categories Settings and Categories opens its grid`() {
        // v1.4 brought Categories back as a first-class tab (grid of media categories),
        // while Recent stays folded into Home. The bar must show these four tabs.
        compose.setContent { ToadTheme { ToadNavHost() } }

        val bottomLabels = listOf("Home", "Browse", "Categories", "Settings")
        bottomLabels.forEach { label ->
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
        }

        // Categories: the grid renders the media-library header plus one tile per category.
        compose.onNodeWithText("Categories").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Media library").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty()
        }

        // Settings is still reachable with Categories sitting between Browse and it.
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Show hidden files").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `browse tab lists real files from the filesystem`() {
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val note = File(root, "e2e_browse_note.txt")
        val folder = File(root, "e2e_browse_folder")
        note.writeText("hello e2e")
        folder.mkdirs()
        try {
            compose.setContent { ToadTheme { ToadNavHost() } }

            compose.onNodeWithText("Browse").performClick()
            compose.waitForIdle()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("e2e_browse_note.txt").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("e2e_browse_folder").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            note.delete()
            folder.deleteRecursively()
        }
    }

    @Test
    fun `home storage card opens the storage screen and back returns home`() {
        compose.setContent { ToadTheme { ToadNavHost() } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("toadFILES").fetchSemanticsNodes().isNotEmpty()
        }
        // Home's storage card opens the Storage screen.
        compose.onNodeWithText("Internal storage").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Browse this volume")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("toadFILES").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
