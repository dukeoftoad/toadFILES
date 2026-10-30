package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ToadApp
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.ui.recent.RecentScreen
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P2.5 regression: the Recent type-filter row must stay rendered while a filter shows
 * no results. Picking "Images" on an empty media library used to swallow the whole
 * filter row, leaving no way back to "All" without leaving the screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class RecentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        ToadViewModelFactory.init(ApplicationProvider.getApplicationContext())
    }

    private fun render() {
        compose.setContent {
            ToadTheme {
                RecentScreen(bottomPadding = Dp(0f), onBack = {}, onShowInFolder = {})
            }
        }
    }

    @Test
    fun `filter tabs stay visible and switchable when the filter has no results`() {
        render()
        // Initial "All" empty state (Robolectric's media library is empty).
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("No recent files reported by the system media library")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Select a filter with no results.
        compose.onNodeWithText("Images").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("No recent images found").fetchSemanticsNodes().isNotEmpty()
        }

        // The filter row must still be rendered — this is the regression.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Videos").fetchSemanticsNodes().isNotEmpty()
        }

        // …and the user can switch back to All without leaving the screen.
        compose.onNodeWithText("All").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("No recent files reported by the system media library")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
