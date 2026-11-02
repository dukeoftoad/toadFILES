package com.toadfiles.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ToadApp
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.BackKeyAction
import com.toadfiles.app.domain.NavStyle
import com.toadfiles.app.ui.settings.AppearanceScreen
import com.toadfiles.app.ui.theme.ToadTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Appearance tab's Navigation card: Back Key Action and Navigation Style moved here
 * from the main Settings General section (same SettingsViewModel setters, same DataStore
 * keys). Same harness style as BrowseScreenOverflowTest: real app container, real
 * DataStore, persisted values reset after every test so nothing leaks between runs.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class AppearanceScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        ToadViewModelFactory.init(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        // The navigation prefs persist via DataStore — never leak a flip into other tests
        // (ToadNavHostE2ETest reads navStyle from the same store).
        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        runBlocking {
            app.container.settingsRepo.setNavStyle(NavStyle.BOTTOM_BAR)
            app.container.settingsRepo.setBackKeyAction(BackKeyAction.PARENT)
        }
    }

    private fun render() {
        compose.setContent {
            ToadTheme {
                AppearanceScreen(bottomPadding = Dp(0f), onBack = {})
            }
        }
    }

    /** Waits for the Navigation card, then swipes the screen up in bounded steps until
     *  the card is inside the viewport (it sits below the fold of the scrollable column;
     *  a targeted performScrollToNode cannot be used safely here — it may attach to the
     *  horizontal accent LazyRow and spin forever, which hung a whole CI run). */
    private fun awaitNavigationCard() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Navigation Style").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(30) {
            val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val row = compose.onAllNodesWithText("Navigation Style")
                .fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            if (row != null && row.left < viewport.right && row.right > viewport.left &&
                row.top >= viewport.top && row.bottom <= viewport.bottom) {
                compose.onNodeWithText("Navigation Style").assertIsDisplayed()
                compose.onNodeWithText("Back Key Action").assertIsDisplayed()
                return
            }
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        throw AssertionError("Navigation card did not become visible after 30 scroll attempts")
    }

    @Test
    fun `navigation preferences render with their current values`() {
        render()
        awaitNavigationCard()

        compose.onNodeWithText("Back Key Action").assertIsDisplayed()
        compose.onNodeWithText("Back To Parent Folder").assertIsDisplayed()
        compose.onNodeWithText("Navigation Style").assertIsDisplayed()
        compose.onNodeWithText("Bottom Bar").assertIsDisplayed()
    }

    @Test
    fun `choosing Side Menu in Navigation Style persists nav_style`() {
        render()
        awaitNavigationCard()

        compose.onNodeWithText("Navigation Style").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Side Menu").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Side Menu").performClick()

        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        compose.waitUntil(5_000) {
            runBlocking {
                app.container.settingsRepo.settings.first().navStyle == NavStyle.SIDE_MENU
            }
        }
        runBlocking {
            assertEquals(NavStyle.SIDE_MENU, app.container.settingsRepo.settings.first().navStyle)
        }
    }

    @Test
    fun `choosing Exit App in Back Key Action persists back_key_action`() {
        render()
        awaitNavigationCard()

        compose.onNodeWithText("Back Key Action").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Exit App").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Exit App").performClick()

        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        compose.waitUntil(5_000) {
            runBlocking {
                app.container.settingsRepo.settings.first().backKeyAction == BackKeyAction.EXIT
            }
        }
        runBlocking {
            assertEquals(
                BackKeyAction.EXIT,
                app.container.settingsRepo.settings.first().backKeyAction,
            )
        }
    }
}
