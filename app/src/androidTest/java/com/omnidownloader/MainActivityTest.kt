package com.omnidownloader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest class MainActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Before fun inject() = hilt.inject()
    @Test fun navigationIsVisible() { compose.onNodeWithText("Downloads").assertIsDisplayed(); compose.onNodeWithText("Add").assertIsDisplayed(); compose.onNodeWithText("Settings").assertIsDisplayed() }
}
