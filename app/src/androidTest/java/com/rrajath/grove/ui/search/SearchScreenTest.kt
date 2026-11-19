package com.rrajath.grove.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.ui.support.ScreenTestEnv
import com.rrajath.grove.ui.support.setGroveContent
import com.rrajath.grove.ui.vault.NoteRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 UI coverage for [SearchScreen].
 * Fixture:
 * `reading-list.org` has the unique body word "photosynthesis" under the
 * heading "How leaves work".
 */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var env: ScreenTestEnv
    private val vm by lazy { env.searchViewModel() }

    @Before
    fun setUp() {
        env = ScreenTestEnv()
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun content(
        initialQuery: String? = null,
        onOpenNote: (NoteRef) -> Unit = {},
    ) {
        composeRule.setGroveContent {
            SearchScreen(
                initialQuery = initialQuery,
                onBack = {},
                onOpenNote = onOpenNote,
                viewModel = vm,
            )
        }
    }

    @Test
    fun blankQueryShowsQuickStart() {
        content()
        composeRule.onNodeWithText("QUICK START").assertIsDisplayed()
    }

    @Test
    fun typingATermRendersMatchingResultRows() {
        content()
        composeRule.onNodeWithTag("search_field").performTextInput("photosynthesis")

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("search_result_row", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("search_results_list").assertIsDisplayed()
        composeRule.onNodeWithText("converts light into chemical energy", substring = true).assertIsDisplayed()
    }

    @Test
    fun aTermWithNoMatchShowsTheEmptyState() {
        content()
        composeRule.onNodeWithTag("search_field").performTextInput("zzzznotawordzzzz")

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("search_empty").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tappingAResultOpensThatNote() {
        var opened: NoteRef? = null
        content(initialQuery = "photosynthesis", onOpenNote = { opened = it })

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("search_result_row", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("converts light into chemical energy", substring = true).performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { opened != null }
        assertEquals("reading-list.org", opened?.fileName)
    }
}
