package com.rrajath.grove.ui.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.ui.support.ScreenTestEnv
import com.rrajath.grove.ui.support.setGroveContent
import com.rrajath.grove.ui.vault.NoteRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 UI coverage for [EditNoteScreen].
 */
@RunWith(AndroidJUnit4::class)
class EditNoteScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var env: ScreenTestEnv
    private val editorVm by lazy { env.editorViewModel() }
    private val refileVm by lazy { env.documentViewModel() }

    private val shipLine =
        OrgParser.parse(OrgFixtures.PROJECTS).headlines.first { it.title.startsWith("Ship v2") }.lineIndex

    private fun lineOf(fixture: String, titlePrefix: String): Int =
        OrgParser.parse(fixture).headlines.first { it.title.startsWith(titlePrefix) }.lineIndex

    @Before
    fun setUp() {
        env = ScreenTestEnv()
        // The idle auto-save timer now lives in EditorViewModel (PERFORMANCE_AUDIT
        // 2026-09-16 C3), keyed on this setting rather than a screen parameter.
        env.fakeSettings.update { it.copy(autoSaveNotes = false) }
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun content(
        noteRef: NoteRef = NoteRef("projects.org", shipLine),
        onBack: () -> Unit = {},
    ) {
        composeRule.setGroveContent {
            EditNoteScreen(
                noteRef = noteRef,
                onBack = onBack,
                onSwitchToRead = {},
                viewModel = editorVm,
                refileViewModel = refileVm,
            )
        }
    }

    @Test
    fun loadsTheSubtreeIntoTheField() {
        content()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Ship v2 release", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun showsRawOrgTableSourceNotTheRenderedGrid() {
        content(noteRef = NoteRef("table.org", lineOf(OrgFixtures.TABLE, "Quarterly numbers")))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Quarterly numbers", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Edit mode keeps the pipe-delimited source; the grid is a Read-mode view.
        composeRule.onNodeWithTag("edit_note_field")
            .assertTextContains("| Quarter | Revenue | Growth |", substring = true)
    }

    @Test
    fun typingRevealsTheUnsavedIndicator() {
        content()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Ship v2 release", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // No save icon until the note is dirty or has been saved once.
        assertTrue(
            composeRule.onAllNodesWithContentDescription("Unsaved changes, tap to save")
                .fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithTag("edit_note_field").performTextInput(" EDIT")

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithContentDescription("Unsaved changes, tap to save")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tappingSaveWritesTheBufferBackToDisk() {
        content()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Ship v2 release", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("edit_note_field").performTextInput(" EDIT")
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithContentDescription("Unsaved changes, tap to save")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Unsaved changes, tap to save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { env.store.read("projects.org") }.contains("EDIT")
        }
    }
}
