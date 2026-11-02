package com.toadfiles.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    /** Waits until the Navigation card's rows are on screen. */
    private fun awaitNavigationCard() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Navigation Style").fetchSemanticsNodes().isNotEmpty()
        }
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
        compose.onNodeWithText("Side Menu").performClick()

        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        runBlocking {
            assertEquals(NavStyle.SIDE_MENU, app.container.settingsRepo.settings.first().navStyle)
        }
    }

    @Test
    fun `choosing Exit App in Back Key Action persists back_key_action`() {
        render()
        awaitNavigationCard()

        compose.onNodeWithText("Back Key Action").performClick()
        compose.onNodeWithText("Exit App").performClick()

        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        runBlocking {
            assertEquals(
                BackKeyAction.EXIT,
                app.container.settingsRepo.settings.first().backKeyAction,
            )
        }
    }
}
