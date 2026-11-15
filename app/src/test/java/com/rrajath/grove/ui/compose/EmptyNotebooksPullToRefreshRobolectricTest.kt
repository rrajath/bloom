package com.rrajath.grove.ui.compose

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.screens.CenterMessage
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Case 13: an empty notebooks list can be pulled to refresh (the empty state must be scrollable). */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class EmptyNotebooksPullToRefreshRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun content(pullable: Boolean, onRefresh: () -> Unit) {
        composeRule.setContent {
            GroveTheme {
                PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh, modifier = Modifier) {
                    CenterMessage("✦", "No .org files here yet", "Capture a note or make a notebook", pullable = pullable)
                }
            }
        }
    }

    @Test
    fun `pulling down on the empty state requests a refresh`() {
        var refreshes = 0
        content(pullable = true) { refreshes++ }

        composeRule.onNodeWithText("No .org files here yet").assertIsDisplayed()
        composeRule.onRoot().performTouchInput { swipeDown(startY = top + 100f, endY = bottom - 100f) }
        composeRule.waitForIdle()

        assertTrue("a pull on the empty state must refresh", refreshes >= 1)
    }

    @Test
    fun `without a scrollable child the pull does nothing`() {
        var refreshes = 0
        content(pullable = false) { refreshes++ }

        composeRule.onRoot().performTouchInput { swipeDown(startY = top + 100f, endY = bottom - 100f) }
        composeRule.waitForIdle()

        assertEquals(0, refreshes)
    }
}
