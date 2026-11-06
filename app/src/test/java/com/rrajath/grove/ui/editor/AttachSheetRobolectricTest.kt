package com.rrajath.grove.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachKind
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.attach.ScopedAttachment
import com.rrajath.grove.attach.scopesFor
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.vault.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w384dp-h832dp")
class AttachSheetRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val lunch = AttachOwner.Heading(4, listOf("Kyoto", "Lunch"), "8B41D2F0-1C9E", null)
    private val files = listOf(
        FileEntry("data/8B/41D2F0-1C9E/receipt.pdf", 0, 182 * 1024),
        FileEntry("data/8B/41D2F0-1C9E/soba-menu.jpg", 0, (2.4 * 1024 * 1024).toLong()),
    )

    private val appName: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.app_name)

    private val picked = mutableListOf<AttachKind>()
    private val inserted = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    /** A sheet model listing [files] as the owner's own rows. */
    private fun sheetModel(owner: AttachOwner, stars: String, title: String, dir: String?, files: List<FileEntry>): AttachSheetModel {
        val scopes = scopesFor(owner)
        val rows = files.map { ScopedAttachment(it, dir ?: "d", "travel.org", (owner as? AttachOwner.Heading)?.title, "") }
        return AttachSheetModel(owner, stars, title, dir, scopes, scopes.first(), rows, AttachController.PAGE_SIZE)
    }

    private fun show(model: AttachSheetModel, camera: Boolean = true) {
        composeRule.setContent {
            GroveTheme {
                AttachSheetContent(
                    model = model,
                    cameraAvailable = camera,
                    onPick = { picked += it },
                    onInsert = { inserted += it.file.name },
                    onOpen = { opened += it.name },
                    onDelete = { deleted += it.name },
                )
            }
        }
    }

    @Test
    fun `heading owner shows stars, title, dir label and rows with sizes`() {
        show(sheetModel(lunch, "**", "Lunch", "data/8B/41D2F0-1C9E", files))
        composeRule.onNodeWithText("Attach to").assertIsDisplayed()
        composeRule.onNodeWithText("**").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_owner_title").assertTextEquals("Lunch")
        composeRule.onNodeWithText("In this heading").assertIsDisplayed()
        composeRule.onNodeWithText("182 KB").assertIsDisplayed()
        composeRule.onNodeWithText("2.4 MB").assertIsDisplayed()
        composeRule.onNodeWithText("PDF").assertIsDisplayed()
        composeRule.onNodeWithText("JPG").assertIsDisplayed()
    }

    @Test
    fun `row actions report the file`() {
        show(sheetModel(lunch, "**", "Lunch", "d", files))
        composeRule.onNodeWithTag("attach_insert_receipt.pdf").performClick()
        composeRule.onNodeWithTag("attach_open_soba-menu.jpg").performClick()
        composeRule.onNodeWithTag("attach_delete_receipt.pdf").performClick()
        assertEquals(listOf("data/8B/41D2F0-1C9E/receipt.pdf"), inserted)
        assertEquals(listOf("data/8B/41D2F0-1C9E/soba-menu.jpg"), opened)
        assertEquals(listOf("data/8B/41D2F0-1C9E/receipt.pdf"), deleted)
    }

    @Test
    fun `source pills pick their kind`() {
        show(sheetModel(lunch, "**", "Lunch", null, emptyList()))
        composeRule.onNodeWithTag("attach_camera").performClick()
        composeRule.onNodeWithTag("attach_photos").performClick()
        composeRule.onNodeWithTag("attach_files").performClick()
        assertEquals(listOf(AttachKind.Camera, AttachKind.Photos, AttachKind.Files), picked)
    }

    @Test
    fun `camera pill is disabled when no camera app exists`() {
        show(sheetModel(lunch, "**", "Lunch", null, emptyList()), camera = false)
        composeRule.onNodeWithContentDescription("Camera · No camera app found").performClick()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `file owner without an ID explains the ID it will get`() {
        show(sheetModel(AttachOwner.File(null, null), "#+", "travel.org", null, emptyList()))
        composeRule.onNodeWithText("#+").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_owner_title").assertTextEquals("travel.org")
        composeRule.onNodeWithText("In this file").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_empty")
            .assertTextEquals("No attachments yet. $appName adds an :ID: to this file the first time you attach.")
    }

    @Test
    fun `heading owner empty state is short`() {
        show(sheetModel(lunch, "**", "Lunch", null, emptyList()))
        composeRule.onNodeWithTag("attach_empty").assertTextEquals("No attachments yet.")
    }

    @Test
    fun `delete dialog confirms`() {
        var confirmed = false
        composeRule.setContent {
            GroveTheme {
                DeleteAttachmentDialog("receipt.pdf", fileOwner = false, onCancel = {}, onConfirm = { confirmed = true })
            }
        }
        composeRule.onNodeWithText("Delete receipt.pdf?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The file is removed from this heading's attachment folder. Links to it stay in your notes and will show as missing.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("attach_delete_confirm").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `toolbar shows the paperclip only when attach is wired`() {
        var attached = 0
        composeRule.setContent {
            GroveTheme {
                EditorToolbar(onWrap = {}, onInsert = {}, onLink = {}, onHeading = {}, onIndent = {}, onAttach = { attached++ })
            }
        }
        composeRule.onNodeWithTag("editor_attach").performClick()
        assertEquals(1, attached)
    }

    @Test
    fun `stars helper`() {
        val doc = OrgParser.parse("* Kyoto\n** Lunch\n")
        assertEquals("**", starsFor(doc, AttachOwner.Heading(1, listOf("Kyoto", "Lunch"), null, null)))
        assertEquals("#+", starsFor(doc, AttachOwner.File(null, null)))
    }
}
