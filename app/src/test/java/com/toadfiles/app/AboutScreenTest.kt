package com.toadfiles.app

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
        compose.onNodeWithText("Storage on this device").assertExists()
        compose.onNodeWithText("Files on this device").assertExists()
    }

    @Test
    fun `About shows the diagnostics toggle with the privacy disclaimer`() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Diagnostics").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Off by default. Even when on, no data leaves the device").assertExists()
    }

    @Test
    fun `About shows the locally-shared section with a Clear button`() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Locally shared copies").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Clear all shared copies").assertExists()
    }

    @Test
    fun `About shows the changelog and OSS credits headings`() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("What's new").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Open-source credits").assertExists()
    }
}
