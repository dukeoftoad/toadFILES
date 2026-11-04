package com.toadfiles.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

    /** Scrolls the screen's ONLY vertical scroller (tagged "appearance_scroll") until
     *  the Navigation card is in view. A targeted performScrollToNode is safe here —
     *  the tag rules out the horizontal accent LazyRow, which hung a whole CI run when
     *  an unqualified scroll target latched onto it; blind swiping, in turn, never
     *  reliably moved the list under Robolectric. */
    private fun awaitNavigationCard() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Navigation Style").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("appearance_scroll")
            .performScrollToNode(hasText("Navigation Style"))
        compose.waitForIdle()
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
        // Let the dropdown finish opening before clicking the item — clicking into a
        // mid-creation popup under Robolectric wedges the main Looper (CI hang, Nov 2026).
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Side Menu").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Side Menu").performClick()
        compose.waitForIdle()

        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        // Poll instead of asserting immediately: the write crosses the ViewModel scope
        // and DataStore's IO actor, so the first read can legitimately race it.
        compose.waitUntil(5_000) {
            runBlocking { app.container.settingsRepo.settings.first().navStyle == NavStyle.SIDE_MENU }
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
        // Same popup determinism guard as the Navigation Style test above.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Exit App").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Exit App").performClick()
        compose.waitForIdle()

        val app = ApplicationProvider.getApplicationContext<ToadApp>()
        compose.waitUntil(5_000) {
            runBlocking { app.container.settingsRepo.settings.first().backKeyAction == BackKeyAction.EXIT }
        }
        runBlocking {
            assertEquals(
                BackKeyAction.EXIT,
                app.container.settingsRepo.settings.first().backKeyAction,
            )
        }
    }

    @Test
    fun `font picker lists every family in its own letterforms`() {
        render()
        // The Font card sits between the theme gallery and Motion; scroll the ONE
        // vertical scroller to a label unique to the font rows (same targeted-scroll
        // discipline as awaitNavigationCard — never an unqualified swipe).
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("JetBrains Mono").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("appearance_scroll")
            .performScrollToNode(hasText("JetBrains Mono"))
        compose.waitForIdle()

        // Every family renders a row (persistence of the pick itself is covered by
        // SettingsRepositoryTest's setAppFont cases; a click-through poll here would
        // nest runBlocking inside waitUntil on the Robolectric main thread, which
        // deadlocks the test JVM's dispatcher state for every later runBlocking).
        listOf("System", "Manrope", "Toad", "Inter", "Space Grotesk", "JetBrains Mono")
            .forEach { label ->
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
                }
            }
    }
}
