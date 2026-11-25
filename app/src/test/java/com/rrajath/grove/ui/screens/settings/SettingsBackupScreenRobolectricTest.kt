package com.rrajath.grove.ui.screens.settings

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h4000dp")
class SettingsBackupScreenRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBloom"
    private val now = 10 * 60_000L

    private var picks = 0
    private var setEnabled: Boolean? = null
    private var restores = 0

    /** Stands in for the system pickers: counts folder-picker launches and answers each with [pickResult]. */
    private var pickResult: android.net.Uri? = null
    private val registryOwner = object : androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) {
                if (contract is androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree) {
                    picks++
                    dispatchResult(requestCode, pickResult)
                }
            }
        }
    }
    private var picked: String? = null

    private fun show(state: AutoBackupUiState) = showState { state }

    private fun showState(state: @androidx.compose.runtime.Composable () -> AutoBackupUiState) {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                GroveTheme {
                    SettingsBackupScreen(
                        onBack = {}, onExportSettings = {}, onImportSettings = {},
                        autoBackup = state(),
                        onSetAutoBackupEnabled = { setEnabled = it },
                        onAutoBackupFolderPicked = { picked = it },
                        onRestoreAutoBackup = { restores++ },
                        nowMillis = { now },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun node(spec: SettingSpec) = composeRule.onNodeWithTag(spec.testTag, useUnmergedTree = true).performScrollTo()

    @Test
    fun `toggling on with no folder launches the picker instead of enabling`() {
        show(AutoBackupUiState())
        node(SettingSpecs.BackupAuto).performClick()
        composeRule.waitForIdle()
        assertEquals(1, picks)
        assertNull(setEnabled)
    }

    @Test
    fun `toggling on with a remembered folder enables without the picker`() {
        show(AutoBackupUiState(treeUri = folder))
        node(SettingSpecs.BackupAuto).performClick()
        composeRule.waitForIdle()
        assertEquals(true, setEnabled)
        assertEquals(0, picks)
    }

    @Test
    fun `toggling off disables`() {
        show(AutoBackupUiState(enabled = true, treeUri = folder))
        node(SettingSpecs.BackupAuto).performClick()
        composeRule.waitForIdle()
        assertEquals(false, setEnabled)
    }

    @Test
    fun `the folder row shows the folder name and re-picks on tap`() {
        show(AutoBackupUiState(enabled = true, treeUri = folder))
        composeRule.onNodeWithText("Documents/Bloom").performScrollTo()
        node(SettingSpecs.BackupFolder).performClick()
        assertEquals(1, picks)
    }

    @Test
    fun `while off the folder row is not clickable and restore is disabled`() {
        show(AutoBackupUiState(enabled = false, treeUri = folder, fileExists = true, lastSuccessAt = now))
        node(SettingSpecs.BackupFolder).performClick()
        assertEquals(0, picks)
        composeRule.onNodeWithTag(AUTO_BACKUP_RESTORE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `status says not backed up yet before the first write`() {
        show(AutoBackupUiState(enabled = true, treeUri = folder))
        composeRule.onNodeWithText("Not backed up yet").performScrollTo()
    }

    @Test
    fun `status shows the relative time of the last write`() {
        show(AutoBackupUiState(enabled = true, treeUri = folder, lastSuccessAt = now - 2 * 60_000L, fileExists = true))
        composeRule.onNodeWithText("Last backed up 2 minutes ago").performScrollTo()
    }

    @Test
    fun `status says just now for a write under a minute old`() {
        show(AutoBackupUiState(enabled = true, treeUri = folder, lastSuccessAt = now - 5_000L, fileExists = true))
        composeRule.onNodeWithText("Last backed up just now").performScrollTo()
    }

    @Test
    fun `the error status re-opens the picker on tap`() {
        show(AutoBackupUiState(enabled = true, treeUri = folder, lastSuccessAt = now, error = true))
        composeRule.onNodeWithText("can't write to this folder", substring = true).performScrollTo().performClick()
        assertEquals(1, picks)
    }

    @Test
    fun `while off the error status is not tappable`() {
        show(AutoBackupUiState(enabled = false, treeUri = folder, error = true))
        composeRule.onNodeWithTag(AUTO_BACKUP_STATUS_TAG).performScrollTo().assertHasNoClickAction()
    }

    @Test
    fun `restore is disabled without a backup file`() {
        show(AutoBackupUiState(enabled = true, treeUri = folder, fileExists = false))
        composeRule.onNodeWithTag(AUTO_BACKUP_RESTORE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `restore is enabled with a backup file and calls through`() {
        show(AutoBackupUiState(enabled = true, treeUri = folder, fileExists = true, lastSuccessAt = now))
        composeRule.onNodeWithTag(AUTO_BACKUP_RESTORE_TAG).performScrollTo().assertIsEnabled().assertHasClickAction().performClick()
        assertEquals(1, restores)
    }

    @Test
    fun `a cancelled pick leaves the switch off`() {
        pickResult = null
        show(AutoBackupUiState())
        node(SettingSpecs.BackupAuto).performClick()
        composeRule.waitForIdle()
        assertEquals(1, picks)
        assertNull(picked)
        composeRule.onNode(
            androidx.compose.ui.test.isToggleable()
                .and(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(SettingSpecs.BackupAuto.testTag))),
            useUnmergedTree = true,
        ).assertIsOff()
    }

    @Test
    fun `a picked folder is handed over as a string`() {
        pickResult = android.net.Uri.parse(folder)
        show(AutoBackupUiState())
        node(SettingSpecs.BackupAuto).performClick()
        composeRule.waitForIdle()
        assertEquals(folder, picked)
    }
}
