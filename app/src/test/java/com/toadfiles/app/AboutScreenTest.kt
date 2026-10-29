package com.toadfiles.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * About screen UI test on the JVM. We can't run an instrumented test here, but the project
 * already uses createComposeRule + Robolectric, so the same setup renders the real
 * Compose tree (with the real ToadViewModelFactory) and lets us assert on it.
 *
 * Coverage:
 *  - the screen renders with the app name and version label
 *  - the diagnostics toggle row is present
 *  - the "Locally shared copies" section appears
 *  - the changelog "What's new" heading renders
 *  - the OSS credits "Open-source credits" heading renders
 *
 * We don't drive the diagnostics switch by clicking — Robolectric switches don't reliably
 * emit the onCheckedChange event without an animator. The Settings repository has a unit
 * test that proves the toggle persists; here we only assert the UI shows the right labels.
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

    @Test
    fun `About renders the app name and identity block`() {
        compose.setContent { ToadTheme { AboutScreen(bottomPadding = 0.dp(), onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("toadFILES").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `About shows the version label and a privacy promise`() {
        compose.setContent { ToadTheme { AboutScreen(bottomPadding = 0.dp(), onBack = {}) } }
        // The "Version" row is always rendered.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Version").fetchSemanticsNodes().isNotEmpty()
        }
        // The privacy promise copy is unique to About and never appears elsewhere.
        compose.onNodeWithText("Storage on this device").assertIsDisplayed()
        compose.onNodeWithText("Files on this device").assertIsDisplayed()
    }

    @Test
    fun `About shows the diagnostics toggle with the privacy disclaimer`() {
        compose.setContent { ToadTheme { AboutScreen(bottomPadding = 0.dp(), onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Diagnostics").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Off by default. Even when on, no data leaves the device").assertIsDisplayed()
    }

    @Test
    fun `About shows the locally-shared section with a Clear button`() {
        compose.setContent { ToadTheme { AboutScreen(bottomPadding = 0.dp(), onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Locally shared copies").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Clear all shared copies").assertIsDisplayed()
    }

    @Test
    fun `About shows the changelog and OSS credits headings`() {
        compose.setContent { ToadTheme { AboutScreen(bottomPadding = 0.dp(), onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("What's new").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Open-source credits").assertIsDisplayed()
    }

    private fun dp() = androidx.compose.ui.unit.Dp(0f)
}
