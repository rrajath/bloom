package com.rrajath.grove.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SyncMode
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every [SettingSpec] must tag a row on the page it names, so Settings search can reveal
 * it and a renamed or moved row can't leave a stale result behind. Settings are chosen so
 * every row hidden behind another setting is disclosed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h6000dp")
class SettingSpecCoverageRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = GroveSettings(
        autoArchiveDoneItems = true,
        syncMode = SyncMode.PERIODIC,
        remindersEnabled = true,
        morningBriefEnabled = true,
        roamFeaturesEnabled = true,
    )

    private fun assertPageTagsItsSpecs(page: SettingsPageId, content: @Composable () -> Unit) {
        composeRule.setContent { GroveTheme { content() } }
        composeRule.waitForIdle()
        val missing = SettingSpecs.all
            .filter { it.page == page && it.kind != SettingKind.PAGE }
            .filter { composeRule.onAllNodesWithTag(it.testTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            .map { it.id }
        assertTrue("$page is missing rows for specs: $missing", missing.isEmpty())
    }

    @Test
    fun appearance() = assertPageTagsItsSpecs(SettingsPageId.APPEARANCE) {
        SettingsAppearanceScreen(settings, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun notebooks() = assertPageTagsItsSpecs(SettingsPageId.NOTEBOOKS) {
        SettingsNotebooksScreen(settings, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun captureTemplates() = assertPageTagsItsSpecs(SettingsPageId.CAPTURE_TEMPLATES) {
        SettingsCaptureTemplatesScreen(settings, {}, {}, {})
    }

    @Test
    fun sync() = assertPageTagsItsSpecs(SettingsPageId.SYNC) {
        SettingsSyncScreen(settings, {}, {}, {}, {}, {})
    }

    @Test
    fun notes() = assertPageTagsItsSpecs(SettingsPageId.NOTES) {
        SettingsNotesScreen(settings, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun agenda() = assertPageTagsItsSpecs(SettingsPageId.AGENDA) {
        SettingsAgendaScreen(settings, {}, {}, {}, {}, {})
    }

    @Test
    fun widget() = assertPageTagsItsSpecs(SettingsPageId.WIDGET) {
        SettingsWidgetScreen(settings, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun reminders() = assertPageTagsItsSpecs(SettingsPageId.REMINDERS) {
        SettingsRemindersScreen(settings, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun roam() = assertPageTagsItsSpecs(SettingsPageId.ROAM) {
        SettingsRoamScreen(settings, emptyList(), {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun attachments() = assertPageTagsItsSpecs(SettingsPageId.ATTACHMENTS) {
        SettingsAttachmentsScreen(settings, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun sharing() = assertPageTagsItsSpecs(SettingsPageId.SHARING) {
        SettingsSharingScreen(settings, {}, {}, {}, {})
    }

    @Test
    fun backup() = assertPageTagsItsSpecs(SettingsPageId.BACKUP) {
        SettingsBackupScreen(onBack = {}, onExportSettings = {}, onImportSettings = {}, pendingImport = null, onConfirmImport = {}, onCancelImport = {})
    }

    /** Pages with anchored specs are exactly the ones this test renders above. */
    @Test
    fun everyAnchoredPageIsCovered() {
        val covered = setOf(
            SettingsPageId.APPEARANCE, SettingsPageId.NOTEBOOKS, SettingsPageId.CAPTURE_TEMPLATES, SettingsPageId.SYNC,
            SettingsPageId.NOTES, SettingsPageId.AGENDA, SettingsPageId.WIDGET, SettingsPageId.REMINDERS,
            SettingsPageId.ROAM, SettingsPageId.ATTACHMENTS, SettingsPageId.SHARING, SettingsPageId.BACKUP,
        )
        val anchored = SettingSpecs.all.filter { it.kind != SettingKind.PAGE }.map { it.page }.toSet()
        assertTrue("add a coverage test for ${anchored - covered}", (anchored - covered).isEmpty())
    }
}
