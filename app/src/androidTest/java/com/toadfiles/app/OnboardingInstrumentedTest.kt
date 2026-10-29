package com.toadfiles.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real device/emulator without "All files access", MainActivity must show
 * the onboarding gate before anything else.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshLaunch_showsOnboardingGate() {
        compose.onNodeWithText("Welcome to toadFILES").assertExists()
        compose.onNodeWithText("Grant all files access").assertExists()
    }
}
