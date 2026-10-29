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

        // Categories
        compose.onNodeWithText("Categories").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Categories").fetchSemanticsNodes().size >= 2
        }

        // Settings — a setting row is rendered
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Show hidden files").fetchSemanticsNodes().isNotEmpty()
        }

        // Recent
        compose.onNodeWithText("Recent").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Recent").fetchSemanticsNodes().size >= 2
        }

        // Back to Home
        compose.onNodeWithText("Home").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("toadFILES").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Home").assertIsSelected()
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
