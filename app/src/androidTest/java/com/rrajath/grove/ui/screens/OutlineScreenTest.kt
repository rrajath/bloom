package com.rrajath.grove.ui.screens

import androidx.activity.ComponentActivity
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.org.OrgParser
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.ui.support.ScreenTestEnv
import com.rrajath.grove.ui.support.setGroveContent
import com.rrajath.grove.ui.vault.NoteRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 UI coverage for [OutlineScreen] (see
 * internal/test-suite-02-ui-compose.md § OutlineScreen). Fixture: `projects.org`
 * (multi-level TODO tree, see [com.rrajath.grove.testing.OrgFixtures.PROJECTS]).
 */
@RunWith(AndroidJUnit4::class)
class OutlineScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var env: ScreenTestEnv
    private val vm by lazy { env.documentViewModel() }

    @Before
    fun setUp() {
        env = ScreenTestEnv()
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun content(
        notebookId: String = "projects.org",
        onOpenNote: (NoteRef) -> Unit = {},
        onCreateNote: (NoteRef) -> Unit = {},
        narrowLineIndex: Int? = null,
        onNarrow: (Int) -> Unit = {},
    ) {
        composeRule.setGroveContent {
            OutlineScreen(
                notebookId = notebookId,
                onBack = {},
                onOpenNote = onOpenNote,
                onCreateNote = onCreateNote,
                narrowLineIndex = narrowLineIndex,
                onNarrow = onNarrow,
                viewModel = vm,
            )
        }
    }

    @Test
    fun rendersTopLevelHeadings() {
        content()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Ship v2 release", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Backlog", substring = true).assertIsDisplayed()
    }

    @Test
    fun expandingASubtreeRevealsItsChildren() {
        content()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("outline_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        // Freshly opened notebooks start fully collapsed.
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Cut the changelog", substring = true)
                .fetchSemanticsNodes().size,
        )

        composeRule.onAllNodesWithTag("outline_toggle")[0].performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("Cut the changelog", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun scrollingReachesAFarHeadingInALargeOutline() {
        content(notebookId = "large-subtree.org")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("outline_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        // "Everything" opens collapsed; expand it to mount the 80 section rows.
        composeRule.onAllNodesWithTag("outline_toggle")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("Section 01", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("outline_list")
            .performScrollToNode(hasText("Section 72", substring = true))
        composeRule.onNodeWithText("Section 72", substring = true).assertIsDisplayed()
    }

    @Test
    fun bareActiveTimestampRendersAsAnEventChip() {
        runBlocking {
            env.store.write(
                "events.org",
                """
                #+TITLE: Events

                * Team offsite
                  <2099-01-15 Fri>
                  Annual planning session.
                """.trimIndent() + "\n",
            )
        }
        content(notebookId = "events.org")

        // Humanised chip label ("Jan 15, 2099") — the raw stamp never appears in
        // the outline, so this proves the violet event chip drew.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Jan 15, 2099", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Jan 15, 2099", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingTheFabCreatesANote() {
        var created: NoteRef? = null
        content(onCreateNote = { created = it })
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("outline_fab").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("outline_fab")[0].performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { created != null }
        assertEquals("projects.org", created?.fileName)
    }

    @Test
    fun swipeNarrowNarrowsToThatHeading() {
        var narrowedTo: Int? = null
        content(onNarrow = { narrowedTo = it })
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Backlog", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Backlog", substring = true).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("Narrow").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { narrowedTo != null }
        val backlog = OrgParser.parse(OrgFixtures.PROJECTS).headlines.first { it.title.startsWith("Backlog") }
        assertEquals(backlog.lineIndex, narrowedTo)
    }

    @Test
    fun narrowedRootRowHasNoNarrowAction() {
        val backlog = OrgParser.parse(OrgFixtures.PROJECTS).headlines.first { it.title.startsWith("Backlog") }
        content(narrowLineIndex = backlog.lineIndex)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Backlog", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Backlog", substring = true)[0].performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("Above").assertIsDisplayed()
        composeRule.onNodeWithText("Narrow").assertDoesNotExist()
    }
}
