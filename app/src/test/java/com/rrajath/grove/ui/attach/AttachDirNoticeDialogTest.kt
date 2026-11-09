package com.rrajath.grove.ui.attach

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rrajath.grove.attach.AttachDirChange
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class)
class AttachDirNoticeDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val trip = AttachOwner.Heading(0, listOf("Trip"), "AA11", "photos")

    private fun show(changes: List<AttachDirChange>, onOk: () -> Unit = {}) {
        composeRule.setContent { GroveTheme { AttachDirNoticeDialog(changes, onOk) } }
    }

    @Test
    fun `DIR added names the DIR value and OK reports back`() {
        var oks = 0
        show(
            listOf(AttachDirChange.Move(trip, "Notes/n.org", "Notes/data/AA/11", "Notes/photos", AttachDirChange.Kind.DIR_ADDED, copy = false)),
            onOk = { oks++ },
        )
        composeRule.onNodeWithText(":DIR: property detected. Attachment(s) will be moved to photos").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_dir_notice_ok").performClick()
        assertEquals(1, oks)
    }

    @Test
    fun `DIR removed names the ID folder`() {
        show(listOf(AttachDirChange.Move(trip, "Notes/n.org", "Notes/photos", "Notes/data/AA/11", AttachDirChange.Kind.DIR_REMOVED, copy = false)))
        composeRule.onNodeWithText(":DIR: property removed. Attachment(s) will be moved to data/AA/11").assertIsDisplayed()
    }

    @Test
    fun `several changes are labeled by heading or file`() {
        show(
            listOf(
                AttachDirChange.Broken(AttachOwner.File(null, null), "Notes/n.org", "Notes/files"),
                AttachDirChange.Move(trip, "Notes/n.org", "Notes/photos", "Notes/kyoto", AttachDirChange.Kind.DIR_CHANGED, copy = false),
            ),
        )
        composeRule.onNodeWithText("n.org").assertIsDisplayed()
        composeRule.onNodeWithText("Image link broken. Couldn't find :ID: or :DIR: property").assertIsDisplayed()
        composeRule.onNodeWithText("Trip").assertIsDisplayed()
        composeRule.onNodeWithText(":DIR: property changed. Attachment(s) will be moved to kyoto").assertIsDisplayed()
    }
}
