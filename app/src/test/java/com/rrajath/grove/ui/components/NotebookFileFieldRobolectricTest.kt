package com.rrajath.grove.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class NotebookFileFieldRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var current = ""

    private fun show(initial: String = "") {
        current = initial
        composeRule.setContent {
            GroveTheme {
                var v by remember { mutableStateOf(initial) }
                NotebookFileField(
                    value = v,
                    onValueChange = { v = it; current = it },
                    notebooks = listOf("inbox.org", "work.org"),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun field() = composeRule.onNode(hasSetTextAction())

    private fun selection(): TextRange =
        field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    @Test
    fun pickingASuggestion_movesCursorToEnd() {
        show()
        field().performClick()
        field().performTextInput("inb")
        composeRule.onNodeWithText("inbox.org", useUnmergedTree = true).let {
            // The field and the suggestion row both read "inbox.org" only after the pick; before it
            // the only "inbox.org" node is the suggestion.
            it.performClick()
        }
        composeRule.waitForIdle()
        assertEquals("inbox.org", current)
        assertEquals(TextRange("inbox.org".length), selection())
    }

    @Test
    fun clearButton_emptiesFieldFocusesItAndOpensList() {
        show("inbox.org")
        composeRule.onNodeWithText("×").performClick()
        composeRule.waitForIdle()
        assertEquals("", current)
        field().assertIsFocused()
        // Blank query lists every notebook.
        composeRule.onNodeWithText("work.org").assertExists()
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).assertExists()
    }

    @Test
    fun clearButton_hiddenWhenEmpty() {
        show()
        composeRule.onNodeWithText("×").assertDoesNotExist()
    }
}
