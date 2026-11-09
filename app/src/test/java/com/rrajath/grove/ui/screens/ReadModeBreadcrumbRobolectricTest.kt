package com.rrajath.grove.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Read-mode breadcrumb's optional leading Notebooks segment. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, qualifiers = "w384dp-h832dp")
class ReadModeBreadcrumbRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val path = OrgParser.parse("* Japan\n** Day 9\n").headlines
    private val taps = mutableListOf<String>()

    private fun show(withNotebooks: Boolean) {
        composeRule.setContent {
            GroveTheme {
                ReadModeBreadcrumb(
                    fileName = "travel.org",
                    path = path,
                    onOpenBreadcrumb = { taps += "crumb:$it" },
                    onOpenNotebooks = if (withNotebooks) ({ taps += "notebooks" }) else null,
                )
            }
        }
    }

    @Test
    fun `the Notebooks segment leads the trail and opens Notebooks`() {
        show(withNotebooks = true)

        composeRule.onNodeWithText("Notebooks").performClick()
        composeRule.onNodeWithText("travel.org").performClick()

        assertEquals(listOf("notebooks", "crumb:null"), taps)
    }

    @Test
    fun `without a Notebooks handler the trail starts at the file`() {
        show(withNotebooks = false)

        composeRule.onNodeWithText("Notebooks").assertDoesNotExist()
        composeRule.onNodeWithText("travel.org").performClick()

        assertEquals(listOf("crumb:null"), taps)
    }
}
