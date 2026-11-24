package com.rrajath.grove.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rrajath.grove.attach.PerNoteRoots
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.attach.AttachFolderMoveSheet
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings › Attachments sheet offering to move per-note attachment folders into a vault-root one. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class)
class AttachFolderMoveSheetRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var moved = 0
    private var kept = 0
    private var moving by mutableStateOf(false)

    private fun content() {
        composeRule.setContent {
            GroveTheme {
                AttachFolderMoveSheet(
                    from = "attach/",
                    to = "/attach/",
                    found = PerNoteRoots(listOf("projects/attach", "deep/attach"), files = 12, bytes = 2048),
                    moving = moving,
                    onMove = { moved++; moving = true },
                    onKeep = { kept++ },
                )
            }
        }
    }

    @Test
    fun showsWhatWasFoundAndMovesOnConfirm() {
        content()
        composeRule.onNodeWithText("Move existing attachments?").assertIsDisplayed()
        composeRule.onNodeWithText("attach/ → /attach/ · 2 folders · 12 files · ", substring = true).assertExists()
        composeRule.onNodeWithTag("attach_folder_move_confirm").performClick()
        assertEquals(1, moved)
        composeRule.onNodeWithText("Moving…").assertExists()
        // While moving, neither button responds.
        composeRule.onNodeWithTag("attach_folder_move_confirm").performClick()
        composeRule.onNodeWithText("Keep where they are").performClick()
        assertEquals(1, moved)
        assertEquals(0, kept)
    }

    @Test
    fun keepLeavesTheFiles() {
        content()
        composeRule.onNodeWithText("Keep where they are").performClick()
        assertEquals(1, kept)
        assertEquals(0, moved)
    }
}
