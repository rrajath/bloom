package com.rrajath.grove.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.screens.SettingsScreen
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h4000dp")
class SettingsSearchRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var toggled: Boolean? = null
    private var opened: SettingSpec? = null

    private fun showHub() {
        val controls = mapOf<String, InlineControl>(
            SettingSpecs.NotesLineNumbers.id to InlineControl.Toggle({ it.showLineNumbers }) { toggled = it },
        )
        composeRule.setContent {
            GroveTheme {
                SettingsScreen(
                    onBack = {}, onOpenAppearance = {}, onOpenNotebooks = {}, onOpenCaptureTemplates = {},
                    onOpenSync = {}, onOpenWebDav = {}, onOpenAi = {}, onOpenNotes = {}, onOpenAgenda = {},
                    onOpenWidget = {}, onOpenReminders = {},
                    onOpenRoam = {}, onOpenAttachments = {}, onOpenSharing = {}, onOpenBackup = {},
                    onOpenBugReport = {}, onOpenTips = {}, onOpenWhatsNew = {}, onOpenDeveloper = {},
                    settings = GroveSettings(showLineNumbers = false),
                    inlineControls = controls,
                    onOpenSetting = { opened = it },
                )
            }
        }
    }

    private fun search(text: String) {
        composeRule.onNode(hasSetTextAction().and(hasAnyAncestor(hasTestTag("settings_search_field")))).performTextInput(text)
        composeRule.waitForIdle()
    }

    private fun hasSetTextAction() = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetText)

    @Test
    fun inlineToggleChangesTheValueWithoutNavigating() {
        showHub()
        search("LINE NUMBERS")
        val row = "settings_search_result:${SettingSpecs.NotesLineNumbers.id}"
        composeRule.onNodeWithTag(row).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState).and(hasAnyAncestor(hasTestTag(row))),
        ).performClick()
        assertEquals(true, toggled)
        assertEquals(null, opened)
    }

    @Test
    fun tappingANavigateOnlyResultOpensItsSetting() {
        showHub()
        search("todo keywords")
        composeRule.onNodeWithTag("settings_search_result:${SettingSpecs.NotesTodoKeywords.id}").performClick()
        assertEquals(SettingSpecs.NotesTodoKeywords, opened)
    }

    @Test
    fun pageTitleMatchesEveryOneOfItsSettings() {
        showHub()
        search("widget")
        SettingSpecs.all.filter { it.page == SettingsPageId.WIDGET }.forEach {
            composeRule.onNodeWithTag("settings_search_result:${it.id}").assertExists()
        }
    }

    @Test
    fun noMatchesSaysSo() {
        showHub()
        search("zzqx")
        composeRule.onNodeWithText("No settings match", substring = true).assertIsDisplayed()
    }
}
