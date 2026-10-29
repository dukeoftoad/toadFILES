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
 *  - the diagnostics toggle row is present
 *  - the "Locally shared copies" section appears
 *  - the changelog "What's new" heading renders
 *  - the OSS credits "Open-source credits" heading renders
 *
 * We don't drive the diagnostics switch by clicking — Robolectric switches don't reliably
 * emit onCheckedChange without an animator. The Settings repository has a unit test that
 * proves the toggle persists; here we only assert the UI shows the right labels.
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
    fun `About shows the diagnostics toggle with the privacy disclaimer`() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Diagnostics").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            // The diagnostics disclaimer is one long Text node — substringEquals = true so we
            // match the lead substring instead of requiring the whole paragraph verbatim.
            compose.onAllNodesWithText(
                "Off by default. Even when on, toadFILES has no internet",
                substringEquals = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `About shows the locally-shared section with a Clear button`() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Locally shared copies").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Clear all shared copies").fetchSemanticsNodes().isNotEmpty()
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
}
