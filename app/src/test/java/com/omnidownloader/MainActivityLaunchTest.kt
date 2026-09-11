package com.omnidownloader

import androidx.lifecycle.Lifecycle
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import com.omnidownloader.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class MainActivityLaunchTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Before fun setup() = hilt.inject()

    @Test fun freshInstallLaunchReachesResumedState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test fun recreationAndShareLaunchRemainStable() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            setClassName("com.omnidownloader", MainActivity::class.java.name)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/archive.zip")
        }
        ActivityScenario.launch<MainActivity>(shareIntent).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
