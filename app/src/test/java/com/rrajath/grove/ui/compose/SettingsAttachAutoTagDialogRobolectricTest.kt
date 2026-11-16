package com.rrajath.grove.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.screens.settings.SettingsAttachmentsScreen
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The attachment tag row and dialog on Settings § Attachments (no screen qualifier; see the folder dialog test). */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class)
class SettingsAttachAutoTagDialogRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stored = mutableListOf<String>()

    private fun content() {
        composeRule.setContent {
            var settings by remember { mutableStateOf(GroveSettings()) }
            GroveTheme {
                SettingsAttachmentsScreen(
                    settings = settings,
                    onBack = {},
                    onSetPhotoSize = {},
                    onSetImageWidth = {},
                    onSetImageWidthPx = {},
                    onSetAttachFolder = {},
                    onSetAttachAutoTag = { stored += it; settings = settings.copy(attachAutoTag = it) },
                )
            }
        }
    }

    private fun description() = composeRule.onNodeWithTag("attach_auto_tag_description", useUnmergedTree = true)
    private fun value() = composeRule.onNodeWithTag("attach_auto_tag_value", useUnmergedTree = true)

    @Test
    fun defaultIsAttachAndTheDescriptionNamesItWithColons() {
        content()
        value().performScrollTo().assertTextEquals("ATTACH")
        description().assertTextEquals("Headings you attach files to get :ATTACH:. Matches org-attach-auto-tag in Emacs.")
        composeRule.onNodeWithText("Attachment tag").performClick()
        composeRule.onNodeWithTag("name_dialog_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("ATTACH")))
    }

    @Test
    fun invalidTagsDisableSaveAndAValidOneUpdatesTheDescription() {
        content()
        composeRule.onNodeWithText("Attachment tag").performScrollTo().performClick()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement("my receipt")
        composeRule.onNodeWithText("Tags can't contain spaces.").assertExists()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement(":receipt:")
        composeRule.onNodeWithText("Tags can't contain colons.").assertExists()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement(" receipt ")
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf("receipt"), stored)
        value().assertTextEquals("receipt")
        description().assertTextEquals("Headings you attach files to get :receipt:. Matches org-attach-auto-tag in Emacs.")
    }

    @Test
    fun emptyMeansNoTag() {
        content()
        composeRule.onNodeWithText("Attachment tag").performScrollTo().performClick()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement("")
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf(""), stored)
        value().assertTextEquals("None")
        description().assertTextEquals("No tag is added to headings you attach files to. Matches org-attach-auto-tag in Emacs.")
    }
}
