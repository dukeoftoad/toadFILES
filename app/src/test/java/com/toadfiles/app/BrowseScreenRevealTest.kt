package com.toadfiles.app

import android.os.Environment
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ui.browse.BrowseScreen
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Show in folder" pinpoint (owner report): Browse must not merely open the folder —
 * it scrolls the revealed file into view and flashes it (the blink cadence itself is
 * locked by BlinkTwiceTest). 21 files put the target (index 20) far below the fold on
 * Robolectric's short default screen, and a LazyColumn composes only on-screen rows —
 * so the target's text appearing IS the proof the auto-scroll ran.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class BrowseScreenRevealTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: File

    @Before
    fun setUp() {
        ToadViewModelFactory.init(ApplicationProvider.getApplicationContext())
        root = File(Environment.getExternalStorageDirectory(), "reveal_test").apply {
            mkdirs()
            repeat(21) { i -> File(this, "reveal_%02d.txt".format(i)).writeText("body $i") }
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun render(reveal: String?) {
        compose.setContent {
            ToadTheme {
                BrowseScreen(
                    initialDir = root.absolutePath,
                    bottomPadding = Dp(0f),
                    onNavigateUp = {},
                    revealName = reveal,
                )
            }
        }
    }

    private fun awaitRevealedRow(name: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `revealed file is scrolled into view`() {
        render("reveal_20.txt")
        // The reveal effect fires the moment the listing lands, and animateScrollToItem
        // outruns any poll for row 0 — the scrolled-away row 0 leaves composition before
        // the first poll succeeds (CI-caught: awaiting row 0 timed out while the scroll
        // was working). So the ONLY stable readiness signal is the revealed row itself:
        // it starts far below the fold, and a LazyColumn composes only on-screen rows —
        // its text appearing IS the proof the listing loaded AND the auto-scroll ran.
        awaitRevealedRow("reveal_20.txt")
    }

    @Test
    fun `a vanished reveal target degrades to a plain folder open`() {
        render("ghost.txt")
        // No reveal scroll happens for a missing target, so row 0 never moves.
        awaitRevealedRow("reveal_00.txt")
        compose.onNodeWithText("reveal_00.txt").assertExists()
    }
}
