package com.rrajath.grove.ui.screens.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opening a page with a `highlight` argument scrolls the named row into view. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h640dp")
class SettingsRevealRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showNotes(highlight: String?, settings: GroveSettings = GroveSettings()) {
        composeRule.setContent {
            GroveTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides highlight) {
                    SettingsNotesScreen(settings, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withoutHighlightTheBottomRowStaysOffScreen() {
        showNotes(highlight = null)
        composeRule.onNodeWithTag(SettingSpecs.NotesAutoArchive.testTag, useUnmergedTree = true).assertIsNotDisplayed()
    }

    @Test
    fun highlightScrollsTheRowIntoView() {
        showNotes(highlight = SettingSpecs.NotesAutoArchive.id)
        composeRule.onNodeWithTag(SettingSpecs.NotesAutoArchive.testTag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun hiddenRowRevealsItsFallback() {
        // Archive location only shows while Auto-archive is on.
        showNotes(highlight = SettingSpecs.NotesArchiveLocation.id, settings = GroveSettings(autoArchiveDoneItems = false))
        composeRule.onNodeWithTag(SettingSpecs.NotesAutoArchive.testTag, useUnmergedTree = true).assertIsDisplayed()
    }
}
