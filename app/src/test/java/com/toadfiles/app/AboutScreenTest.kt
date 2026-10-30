package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ToadApp
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.ui.about.AboutScreen
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * About screen UI test on the JVM. The project already uses createComposeRule + Robolectric,
 * so the same setup renders the real Compose tree (with the real ToadViewModelFactory).
 *
 * Coverage:
 *  - the screen renders with the app name and version label
 *  - the storage, category-count, updates, changelog and credits sections appear
 *  - the update panel shows its "Check for updates" action in the Idle state
 *  - the changelog "What's new" heading renders
 *  - the OSS credits "Open-source credits" heading renders
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        ToadViewModelFactory.init(ApplicationProvider.getApplicationContext())
    }

    private fun render() {
        compose.setContent { ToadTheme { AboutScreen(bottomPadding = Dp(0f), onBack = {}) } }
    }

    @Test
    fun `About renders the app name and identity block`() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("toadFILES").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `About shows the storage and files-on-this-device sections`() {
        render()
        // The "Version" row is always rendered.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Version").fetchSemanticsNodes().isNotEmpty()
        }
        // Use onAllNodes + fetchSemanticsNodes().isNotEmpty rather than onNodeWithText(...).assertExists
        // because assertExists isn't available in the compose-ui-test version this project pins.
        // The "isNotEmpty" check on the semantics-node list is the equivalent assertion.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Storage on this device").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Files on this device").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `About shows the changelog and OSS credits headings`() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("What's new").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Open-source credits").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `About shows the Updates card with the user-initiated check action`() {
        render()
        // The update panel starts in the Idle state: card title + check button. No
        // network happens here — checking only ever runs when the button is tapped.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Updates").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Check for updates").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Installed").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
