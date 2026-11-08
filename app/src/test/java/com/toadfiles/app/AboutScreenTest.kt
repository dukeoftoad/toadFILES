package com.toadfiles.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ToadApp
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.ui.about.AboutScreen
import com.toadfiles.app.ui.about.UPDATE_DOWNLOAD_ERROR
import com.toadfiles.app.ui.about.UPDATE_PROGRESS_BAR
import com.toadfiles.app.ui.about.UPDATE_PROGRESS_TEXT
import com.toadfiles.app.ui.about.UPDATE_RETRY_BUTTON
import com.toadfiles.app.ui.about.UpdatePanel
import com.toadfiles.app.ui.about.UpdateState
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

    // ---------- v2.6.0: in-app download progress + failed-download retry ----------

    /** A release fixture; only the download-phase fields differ between tests. */
    private fun available(
        downloadInProgress: Boolean = false,
        downloadProgress: Float? = null,
        downloadError: String? = null,
    ) = UpdateState.Available(
        version = "v9.9.9",
        apkUrl = "https://github.com/dukeoftoad/toadFILES/releases/download/v9.9.9/toadfiles-v9.9.9.apk",
        fileName = "toadfiles-v9.9.9.apk",
        sizeBytes = 4_828_342L,
        notes = "Faster unzip and an update check.",
        htmlUrl = "https://github.com/dukeoftoad/toadFILES/releases/tag/v9.9.9",
        downloadInProgress = downloadInProgress,
        downloadProgress = downloadProgress,
        downloadError = downloadError,
    )

    private fun renderPanel(update: UpdateState, onDownloadInstall: () -> Unit = {}) {
        compose.setContent {
            ToadTheme {
                // Column wrapper mirrors the real usage (AboutScreen's card column):
                // UpdatePanel emits multiple top-level children and must not be
                // measured bare by the test host — its rows would all land at (0,0),
                // and a touch at the button's center would hit another node instead.
                androidx.compose.foundation.layout.Column {
                    UpdatePanel(update = update, onCheck = {}, onDownloadInstall = onDownloadInstall, onDismiss = {})
                }
            }
        }
    }

    @Test
    fun `downloading with a known size shows a determinate bar and a percent label`() {
        renderPanel(available(downloadInProgress = true, downloadProgress = 0.43f))

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UPDATE_PROGRESS_BAR).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UPDATE_PROGRESS_BAR).assertExists()
        compose.onNodeWithTag(UPDATE_PROGRESS_TEXT).assertExists()
        compose.onNodeWithText("Downloading… 43% — also visible in the notification shade.")
            .assertExists()
        // The download button is disabled while the download runs (second tap = no-op).
        compose.onNodeWithTag(UPDATE_RETRY_BUTTON).assertExists().assertIsDisplayed()
    }

    @Test
    fun `downloading with an unknown total shows the bar without a fake percent`() {
        renderPanel(available(downloadInProgress = true, downloadProgress = null))

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UPDATE_PROGRESS_BAR).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UPDATE_PROGRESS_BAR).assertExists()
        compose.onNodeWithText("Downloading… — also visible in the notification shade.")
            .assertExists()
    }

    @Test
    fun `a failed download shows the retry message and a Retry download button`() {
        val retryTaps = java.util.concurrent.atomic.AtomicInteger(0)
        renderPanel(
            available(downloadError = "The APK download failed — you can retry."),
            onDownloadInstall = { retryTaps.incrementAndGet() },
        )

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("The APK download failed — you can retry.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UPDATE_DOWNLOAD_ERROR).assertExists()
        // The retry label replaces "Download & install" — and it re-runs the download
        // flow, NOT the update check (that was the pre-v2.6.0 wrong-retry behavior).
        compose.onNodeWithTag(UPDATE_RETRY_BUTTON).assertExists().performClick()
        compose.waitUntil(5_000) { retryTaps.get() == 1 }
    }

    @Test
    fun `a fresh available release shows the plain Download & install label`() {
        renderPanel(available())

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Download & install").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Download & install").assertExists()
        compose.onAllNodesWithTag(UPDATE_PROGRESS_BAR).fetchSemanticsNodes().isEmpty()
        compose.onAllNodesWithTag(UPDATE_DOWNLOAD_ERROR).fetchSemanticsNodes().isEmpty()
    }
}
