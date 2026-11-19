package com.rrajath.grove.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.ui.support.ScreenTestEnv
import com.rrajath.grove.ui.support.setGroveContent
import com.rrajath.grove.ui.vault.NoteRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 UI coverage for [ReadNoteScreen].
 * Renders the note AST
 * to Compose text/blocks — no WebView. Fixtures: `reading-list.org`,
 * `table.org`, `large-subtree.org`, plus an inline markup note written straight
 * into the fake store.
 */
@RunWith(AndroidJUnit4::class)
class ReadNoteScreenTest {

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

    private fun lineOf(fixture: String, titlePrefix: String): Int =
        OrgParser.parse(fixture).headlines.first { it.title.startsWith(titlePrefix) }.lineIndex

    private fun content(
        noteRef: NoteRef,
        onOpenNote: (NoteRef) -> Unit = {},
    ) {
        composeRule.setGroveContent {
            ReadNoteScreen(
                noteRef = noteRef,
                onBack = {},
                onOpenNote = onOpenNote,
                onEdit = {},
                viewModel = vm,
            )
        }
    }

    @Test
    fun rendersTheHeadingAndBodyText() {
        content(NoteRef("reading-list.org", lineOf(OrgFixtures.READING_LIST, "How leaves work")))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("read_note_title").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("read_note_title").assertIsDisplayed()
        composeRule.onNodeWithText("photosynthesis", substring = true).assertIsDisplayed()
    }

    @Test
    fun inlineMarkupAndCodeBlocksRender() {
        runBlocking {
            env.store.write(
                "markup.org",
                """
                #+TITLE: Markup

                * Styling
                  This has *bold text* and /italic text/ and a [[https://example.com][labelled link]].

                  #+BEGIN_SRC kotlin
                  val answer = 42
                  #+END_SRC
                """.trimIndent() + "\n",
            )
        }
        content(NoteRef("markup.org", lineOf(runBlocking { env.store.read("markup.org") }, "Styling")))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("bold text", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("italic text", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("labelled link", substring = true).assertIsDisplayed()
        // `#+BEGIN_SRC` blocks open expanded — their body is note content.
        composeRule.onNodeWithText("val answer = 42", substring = true).assertIsDisplayed()
    }

    @Test
    fun orgTableRendersAsAGrid() {
        content(NoteRef("table.org", lineOf(OrgFixtures.TABLE, "Quarterly numbers")))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("org_table").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("org_table").assertIsDisplayed()
        // "Revenue" / "135" are table cells only (unlike "Quarter", which also
        // appears in the "Quarterly numbers" heading and breadcrumb).
        composeRule.onNodeWithText("Revenue", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("135", substring = true).assertIsDisplayed()
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
        content(NoteRef("events.org", lineOf(runBlocking { env.store.read("events.org") }, "Team offsite")))

        // The dedicated `<2099-01-15 Fri>` line collapses to the chip only: the
        // humanised label ("Jan 15, 2099"), never the raw stamp, appears.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Jan 15, 2099", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Jan 15, 2099", substring = true).assertIsDisplayed()
    }

    @Test
    fun aLargeSubtreeScrollsWithoutCrashing() {
        content(NoteRef("large-subtree.org", lineOf(OrgFixtures.LARGE_SUBTREE, "Everything")))

        // Wait for the list, not "read_note_screen": that root Box is already
        // there while the document is Loading, and the load runs on real
        // dispatchers Compose's idle sync doesn't track, so a slow emulator
        // could swipe before the LazyColumn exists.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("read_note_scroll").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(3) {
            composeRule.onNodeWithTag("read_note_scroll").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("read_note_screen").assertIsDisplayed()
    }
}
