package com.rrajath.grove.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachKind
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.attach.AttachScope
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

    private fun str(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private val scopesPicked = mutableListOf<AttachScope>()
    private var loadMoreTaps = 0

    private val picked = mutableListOf<AttachKind>()
    private val inserted = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val hints = mutableListOf<String>()

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
                    onSelectScope = { scopesPicked += it },
                    onLoadMore = { loadMoreTaps++ },
                    onHint = { hints += it },
                )
            }
        }
    }

    @Test
    fun `an inherited folder shows where it comes from under the header`() {
        show(sheetModel(lunch, "**", "Lunch", "trip", files).copy(folderFrom = "Folder from * Trip"))
        composeRule.onNodeWithTag("attach_folder_from").assertTextEquals("Folder from * Trip")
    }

    @Test
    fun `an own folder shows no folder-from line`() {
        show(sheetModel(lunch, "**", "Lunch", "data/8B/41D2F0-1C9E", files))
        composeRule.onNodeWithTag("attach_folder_from").assertDoesNotExist()
    }

    @Test
    fun `heading owner shows stars, title, dir label and rows without sizes`() {
        show(sheetModel(lunch, "**", "Lunch", "data/8B/41D2F0-1C9E", files))
        composeRule.onNodeWithText("Attach to").assertIsDisplayed()
        composeRule.onNodeWithText("**").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_owner_title").assertTextEquals("Lunch")
        composeRule.onNodeWithText(str(R.string.attach_scope_label)).assertIsDisplayed()
        composeRule.onNodeWithText("182 KB").assertDoesNotExist()
        composeRule.onNodeWithText("2.4 MB").assertDoesNotExist()
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
    fun `long-pressing row actions names them without running them`() {
        show(sheetModel(lunch, "**", "Lunch", "d", files))
        composeRule.onNodeWithTag("attach_insert_receipt.pdf").performTouchInput { longClick() }
        composeRule.onNodeWithTag("attach_open_receipt.pdf").performTouchInput { longClick() }
        composeRule.onNodeWithTag("attach_delete_receipt.pdf").performTouchInput { longClick() }
        assertEquals(
            listOf(
                str(R.string.attach_action_insert_link),
                str(R.string.attach_action_open_with),
                str(R.string.attach_action_delete),
            ),
            hints,
        )
        assertTrue(inserted.isEmpty() && opened.isEmpty() && deleted.isEmpty())
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
        composeRule.onNodeWithText(str(R.string.attach_scope_label)).assertIsDisplayed()
        composeRule.onNodeWithTag("attach_empty")
            .assertTextEquals("No attachments yet. $appName adds an :ID: to this file the first time you attach.")
    }

    @Test
    fun `heading owner empty state is short`() {
        show(sheetModel(lunch, "**", "Lunch", null, emptyList()))
        composeRule.onNodeWithTag("attach_empty").assertTextEquals(str(R.string.attach_empty_heading))
    }

    @Test
    fun `header shows the scope control without a dir label`() {
        show(sheetModel(lunch, "**", "Lunch", "data/8B/41D2F0-1C9E", files))
        composeRule.onNodeWithText(str(R.string.attach_scope_label)).assertIsDisplayed()
        composeRule.onNodeWithTag("attach_dir_label").assertDoesNotExist()
        composeRule.onNodeWithTag("attach_scope").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.attach_scope_heading)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.attach_scope_file)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.attach_scope_vault)).assertIsDisplayed()
    }

    @Test
    fun `file owner shows two scopes`() {
        show(sheetModel(AttachOwner.File("F1", null), "#+", "travel.org", null, emptyList()))
        composeRule.onNodeWithText(str(R.string.attach_scope_file)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.attach_scope_vault)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.attach_scope_heading)).assertDoesNotExist()
    }

    @Test
    fun `tapping vault reports the scope`() {
        show(sheetModel(lunch, "**", "Lunch", null, emptyList()))
        composeRule.onNodeWithText(str(R.string.attach_scope_vault)).performClick()
        assertEquals(listOf(AttachScope.VAULT), scopesPicked)
    }

    @Test
    fun `rows outside this heading show their source and no delete`() {
        val rows = files.map { ScopedAttachment(it, "data/8B/41D2F0-1C9E", "travel.org", "Dinner", "Dinner") }
        val scopes = scopesFor(lunch)
        show(AttachSheetModel(lunch, "**", "Lunch", "data/AA/other", scopes, AttachScope.FILE, rows, AttachController.PAGE_SIZE))
        composeRule.onAllNodesWithText("Dinner")[0].assertIsDisplayed()
        composeRule.onNodeWithText("182 KB · Dinner").assertDoesNotExist()
        composeRule.onNodeWithTag("attach_insert_receipt.pdf").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_delete_receipt.pdf").assertDoesNotExist()
        composeRule.onNodeWithTag("attach_row_data/8B/41D2F0-1C9E/receipt.pdf").assertIsDisplayed()
    }

    @Test
    fun `load more shows only when more remain and reports taps`() {
        val many = (1..11).map { FileEntry("data/8B/41D2F0-1C9E/f$it.txt", 0, 10) }
        show(sheetModel(lunch, "**", "Lunch", "data/8B/41D2F0-1C9E", many))
        composeRule.onNodeWithTag("attach_load_more").assertTextEquals(str(R.string.attach_load_more))
        composeRule.onNodeWithTag("attach_load_more").performClick()
        assertEquals(1, loadMoreTaps)
    }

    @Test
    fun `no load more when everything is shown`() {
        show(sheetModel(lunch, "**", "Lunch", "data/8B/41D2F0-1C9E", files))
        composeRule.onNodeWithTag("attach_load_more").assertDoesNotExist()
    }

    @Test
    fun `vault and file empty states`() {
        val scopes = scopesFor(lunch)
        show(AttachSheetModel(lunch, "**", "Lunch", null, scopes, AttachScope.VAULT, emptyList(), AttachController.PAGE_SIZE))
        composeRule.onNodeWithTag("attach_empty").assertTextEquals(str(R.string.attach_empty_vault))
    }

    @Test
    fun `file scope empty state`() {
        val scopes = scopesFor(lunch)
        show(AttachSheetModel(lunch, "**", "Lunch", null, scopes, AttachScope.FILE, emptyList(), AttachController.PAGE_SIZE))
        composeRule.onNodeWithTag("attach_empty").assertTextEquals(str(R.string.attach_empty_file))
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
