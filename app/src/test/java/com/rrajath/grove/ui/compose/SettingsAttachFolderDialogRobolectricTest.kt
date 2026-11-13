package com.rrajath.grove.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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

/**
 * The attachment folder dialog on Settings § Attachments. Separate from
 * [SettingsAttachmentsRobolectricTest] because that class sets a screen-size
 * qualifier, and under Robolectric an AlertDialog with a text field never goes
 * idle with one (the Notebooks name dialog does the same; not seen on devices).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class)
class SettingsAttachFolderDialogRobolectricTest {

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
                    onSetAttachFolder = { stored += it; settings = settings.copy(attachFolder = it) },
                    onSetAttachAutoTag = {},
                )
            }
        }
    }

    @Test
    fun invalidFolderDisablesSaveAndAValidOneIsStoredNormalized() {
        content()
        composeRule.onNodeWithText("Attachment folder").performScrollTo().performClick()
        composeRule.onNodeWithText("Relative to each note's folder. Files already in data/ keep opening.").assertExists()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement("../attach")
        composeRule.onNodeWithText("Use a folder inside each note's folder; .. isn't supported.").assertExists()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement("/attach")
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement(" media//files ")
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf("media/files/"), stored)
        composeRule.onNodeWithTag("attach_folder_value", useUnmergedTree = true).assertTextEquals("media/files/")
    }

    @Test
    fun savingTheSameFolderStoresNothing() {
        content()
        composeRule.onNodeWithText("Attachment folder").performScrollTo().performClick()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement("data")
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun cancelLeavesTheFolderAlone() {
        content()
        composeRule.onNodeWithText("Attachment folder").performScrollTo().performClick()
        composeRule.onNodeWithTag("name_dialog_field").performTextReplacement("attach")
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), stored)
        composeRule.onNodeWithTag("attach_folder_value", useUnmergedTree = true).assertTextEquals("data/")
    }
}
