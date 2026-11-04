package com.rrajath.grove.ui.attach

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rrajath.grove.attach.AttachDirEntry
import com.rrajath.grove.attach.AttachmentInventory
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, qualifiers = "w360dp-h800dp")
class AttachmentLifecycleSheetsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun appName(): String =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(com.rrajath.grove.R.string.app_name)

    private val inventory = AttachmentInventory(
        dirs = listOf(
            AttachDirEntry("AA11", "Notes/data/AA/11", 20, 40L * 1024 * 1024, shared = false),
            AttachDirEntry("BB22", "Notes/data/BB/22", 3, 1L * 1024 * 1024, shared = true),
        ),
        dirProperties = listOf("../Photos/kyoto"),
    )

    @Test
    fun `move sheet defaults to moving attachments and can pick notebook only`() {
        var picked: Boolean? = null
        compose.setContent {
            GroveTheme {
                NotebookMoveAttachmentsSheet("Notes/travel.org", "Archive", inventory, { picked = it }, {})
            }
        }
        compose.onNodeWithText("This notebook has attachments. Move them too?").assertIsDisplayed()
        compose.onNodeWithText("travel.org → Archive/ · 2 folders · 41 MB").assertIsDisplayed()
        compose.onNodeWithText(
            "Folders move into Archive/data/ and merge with what's there. " +
                "1 folder shared with notes in Notes/ is copied instead.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Attachments stay in Notes/data/. Links in travel.org will show as missing.")
            .assertIsDisplayed()
        compose.onNodeWithTag("attach_move_confirm").performScrollTo().performClick()
        assertEquals(true, picked)

        compose.onNodeWithTag("attach_move_choice_only").performScrollTo().performClick()
        compose.onNodeWithTag("attach_move_confirm").performScrollTo().performClick()
        assertEquals(false, picked)
    }

    @Test
    fun `notebook delete sheet starts checked and the label follows the checkbox`() {
        var picked: Boolean? = null
        compose.setContent {
            GroveTheme { NotebookDeleteAttachmentsSheet("Notes/travel.org", inventory, { picked = it }, {}) }
        }
        compose.onNodeWithText(
            "Deleting is permanent. ${appName()} removes files directly and there's no trash to restore from.",
        ).assertIsDisplayed()
        compose.onNodeWithText("2 folders · 23 files · 41 MB. 1 folder shared with other notes is kept.")
            .assertIsDisplayed()
        compose.onNodeWithText("Delete notebook and attachments").assertIsDisplayed()
        compose.onNodeWithTag("attach_delete_checkbox").performScrollTo().performClick()
        compose.onNodeWithText("Delete notebook").performScrollTo().performClick()
        assertEquals(false, picked)
    }

    @Test
    fun `heading delete sheet starts unchecked`() {
        var picked: Boolean? = null
        compose.setContent {
            GroveTheme { HeadingDeleteAttachmentsSheet("Kyoto", 1, inventory, { picked = it }, {}) }
        }
        compose.onNodeWithText("Delete “Kyoto”?").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithTag("attach_delete_checkbox").performScrollTo().performClick()
        compose.onNodeWithText("Delete note and attachments").performScrollTo().performClick()
        assertEquals(true, picked)
    }
}
