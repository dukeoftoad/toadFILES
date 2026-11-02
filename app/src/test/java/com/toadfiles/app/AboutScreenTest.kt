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
 *  - the Capabilities, Permissions and Source & project cards appear, with the repo link
 *  - the storage / files-on-device data cards are GONE (v1.8.1: they live on Home and the
 *    Storage page instead)
 *  - the What's new card renders the changelog parsed from the bundled CHANGELOG.md asset
 *    (the "See all changes" action only exists once the parse succeeded) — no version
 *    strings are pinned here, so the test survives every release bump
 *  - the update panel shows its "Check for updates" action in the Idle state
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
    fun `About hides the removed storage and files data cards`() {
        render()
        // The "Version" row is always rendered.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Version").fetchSemanticsNodes().isNotEmpty()
        }
        // v1.8.1: the storage / per-category data cards were removed from About.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Updates").fetchSemanticsNodes().isNotEmpty()
        }
        assert(compose.onAllNodesWithText("Storage on this device").fetchSemanticsNodes().isEmpty())
        assert(compose.onAllNodesWithText("Files on this device").fetchSemanticsNodes().isEmpty())
        // The What's new card previews the bundled changelog: "See all changes" renders
        // only after the asset was parsed, so this doubles as an asset-bundling check.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("See all changes").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `About shows the Capabilities, Permissions and Source cards`() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Capabilities").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Permissions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Source & project").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Source code · github.com/dukeoftoad/toadFILES")
                .fetchSemanticsNodes().isNotEmpty()
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
