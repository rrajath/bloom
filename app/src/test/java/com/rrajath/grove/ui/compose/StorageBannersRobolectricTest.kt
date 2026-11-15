package com.rrajath.grove.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.components.OfflineBannerContent
import com.rrajath.grove.ui.components.StorageNoticeBanner
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The amber storage banners: the offline notice and its Retry, and the action-less variant. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class StorageBannersRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the offline banner shows its message and Retry calls back`() {
        var retries = 0
        composeRule.setContent {
            GroveTheme {
                OfflineBannerContent(message = "Can't reach your storage provider.", onRetry = { retries++ })
            }
        }

        composeRule.onNodeWithText("Can't reach your storage provider.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `a notice without an action shows no action label`() {
        composeRule.setContent {
            GroveTheme { StorageNoticeBanner(message = "Your storage provider refused the change.") }
        }

        composeRule.onNodeWithText("Your storage provider refused the change.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }
}
