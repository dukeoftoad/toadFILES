package com.toadfiles.app.e2e

import android.os.Environment
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.toadfiles.app.domain.NavStyle
import com.toadfiles.app.ui.ToadNavHost
import com.toadfiles.app.ui.theme.ToadTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    /** Restores the shipped default so the persisted navStyle never leaks across tests. */
    @After
    fun tearDown() {
        setNavStyleBlocking(NavStyle.BOTTOM_BAR)
    }

    @Test
    fun `home screen renders the app shell`() {
        compose.setContent { ToadTheme { ToadNavHost() } }
        compose.onNodeWithTag("tab_Home").assertIsSelected()
        // v1.7.4: the Home branding header was removed (dead space); "Quick access"
        // is the stable first content marker for Home now.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Quick access").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `walking every bottom tab reaches the right screen`() {
        compose.setContent { ToadTheme { ToadNavHost() } }

        // Browse: the bottom-bar item is the only "Browse"-labelled node after a click,
        // because the TopAppBar title shows the current dir's name, not the literal
        // "Browse". The old >= 2 assertion only worked for Categories, where the
        // CategoriesScreen TopAppBar title was the literal "Categories".
        compose.onNodeWithTag("tab_Browse").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Browse").fetchSemanticsNodes().isNotEmpty()
        }

        // Settings — a setting row is rendered
        compose.onNodeWithTag("tab_Settings").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Show hidden files").fetchSemanticsNodes().isNotEmpty()
        }

        // Back to Home
        compose.onNodeWithTag("tab_Home").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Quick access").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("tab_Home").assertIsSelected()
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
        compose.onNodeWithTag("tab_Categories").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Media library").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty()
        }

        // Settings is still reachable with Categories sitting between Browse and it.
        compose.onNodeWithTag("tab_Settings").performClick()
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

            compose.onNodeWithTag("tab_Browse").performClick()
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
            compose.onAllNodesWithText("Quick access").fetchSemanticsNodes().isNotEmpty()
        }
        // Home's storage card opens the Storage screen.
        compose.onNodeWithText("Internal storage").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Browse this volume")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Quick access").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---------- v1.7.4: navigation styles are mutually exclusive ----------

    /**
     * Flips the persisted Navigation style setting and waits until the change is
     * observable through the SettingsViewModel flow that ToadNavHost reads.
     */
    private fun setNavStyleBlocking(style: NavStyle) {
        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        runBlocking { app.container.settingsRepo.setNavStyle(style) }
    }

    @Test
    fun `bottom bar mode has no drawer and no hamburger`() {
        setNavStyleBlocking(NavStyle.BOTTOM_BAR)
        compose.setContent { ToadTheme { ToadNavHost() } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Quick access").fetchSemanticsNodes().isNotEmpty()
        }
        // Bottom bar present…
        compose.onNodeWithTag("tab_Home").assertExists()
        // …drawer items and hamburger absent everywhere.
        compose.onNodeWithTag("drawer_Home").assertDoesNotExist()
        compose.onNodeWithTag("drawer_Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation menu").assertDoesNotExist()
        // On sub-screens the drawer fallback is a Back arrow, not a hamburger.
        compose.onNodeWithText("See all").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Recent").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Open navigation menu").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun `side menu mode has no bottom bar and carries every destination`() {
        setNavStyleBlocking(NavStyle.SIDE_MENU)
        compose.setContent { ToadTheme { ToadNavHost() } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Open navigation menu")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // No bottom bar at all.
        compose.onNodeWithTag("tab_Home").assertDoesNotExist()
        compose.onNodeWithTag("tab_Settings").assertDoesNotExist()
        // The drawer carries ALL primary destinations (Home and Categories were
        // missing in v1.7.3).
        listOf("Home", "Browse", "Categories", "Recent", "Trash", "Settings", "About")
            .forEach { label -> compose.onNodeWithTag("drawer_$label").assertExists() }
        // And it navigates: drawer Home -> the dashboard.
        compose.onNodeWithTag("drawer_Home").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Quick access").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
