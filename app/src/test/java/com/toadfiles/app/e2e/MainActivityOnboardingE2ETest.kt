package com.toadfiles.app.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.MainActivity
import com.toadfiles.app.ToadApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The first-launch gate: with no "All files access" granted (Robolectric default),
 * MainActivity must show onboarding, and the grant button must send the user to
 * the system storage-permission screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class MainActivityOnboardingE2ETest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `fresh launch shows the onboarding gate`() {
        compose.onNodeWithText("Welcome to toadFILES").assertExists()
        compose.onNodeWithText("Grant all files access").assertExists()
        compose.onNodeWithText("Open app settings").assertExists()
    }

    @Test
    fun `open settings button opens the app details screen`() {
        compose.onNodeWithText("Open app settings").performClick()
        compose.waitForIdle()

        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", started.action)
        assertEquals("package:com.toadfiles.app", started.data.toString())
    }

    @Test
    fun `grant button opens the system all-files-access screen`() {
        compose.onNodeWithText("Grant all files access").performClick()
        compose.waitForIdle()

        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", started.action)
        assertEquals("package:com.toadfiles.app", started.data.toString())
    }
}
