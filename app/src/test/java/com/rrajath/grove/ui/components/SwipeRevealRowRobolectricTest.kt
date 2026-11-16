package com.rrajath.grove.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each side of a [SwipeRevealRow] opens as wide as its own cells, so every cell is tappable. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, qualifiers = "w384dp-h832dp")
class SwipeRevealRowRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fired = mutableListOf<String>()

    private fun actions(vararg labels: String) =
        labels.map { SwipeAction(glyph = "·", label = it, fg = Color.Black, bg = Color.LightGray) { fired += it } }

    private fun show() {
        composeRule.setContent {
            GroveTheme {
                SwipeRevealRow(
                    leftActions = actions("L1", "L2", "L3", "L4"),
                    rightActions = actions("R1", "R2", "R3", "R4", "R5"),
                    enabled = true,
                    forceClose = false,
                    onOpenChanged = {},
                    onTap = { fired += "card" },
                    onLongPress = {},
                ) {
                    Text("Row", Modifier.fillMaxWidth().height(56.dp))
                }
            }
        }
    }

    @Test
    fun `a five-cell side opens wide enough to tap its innermost cell`() {
        show()
        composeRule.onNodeWithText("Row").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("R1").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("R1"), fired)
    }

    @Test
    fun `a four-cell side still opens to its innermost cell`() {
        show()
        composeRule.onNodeWithText("Row").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("L4").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("L4"), fired)
    }
}
