package com.rrajath.grove.ui.capture

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.TargetLocation
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.ui.support.ScreenTestEnv
import com.rrajath.grove.ui.support.setGroveContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 UI coverage for the capture flow — [CapturePickerSheet] +
 * [CaptureEditorScreen] (see internal/test-suite-02-ui-compose.md § Capture).
 * The template list comes from the real (DataStore-backed) [TemplatesRepository],
 * which on a clean emulator serves the three built-in templates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class CaptureScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var env: ScreenTestEnv
    private val vm by lazy { env.captureViewModel() }

    @Before
    fun setUp() {
        env = ScreenTestEnv()
        // The capture write path refuses to run without a configured sync folder.
        env.fakeSettings.update { it.copy(vaultTreeUri = "content://grove-test-vault") }
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun picker(
        onPickTemplate: (CaptureTemplate) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setGroveContent {
            CapturePickerSheet(
                onDismiss = onDismiss,
                onPickTemplate = onPickTemplate,
                onManage = {},
                viewModel = vm,
            )
        }
    }

    private fun editor(
        templateId: String,
        onClose: () -> Unit = {},
        onSaved: (String?) -> Unit = {},
    ) {
        composeRule.setGroveContent {
            CaptureEditorScreen(
                templateId = templateId,
                onClose = onClose,
                onSaved = onSaved,
                viewModel = vm,
            )
        }
    }

    @Test
    fun pickerListsOneRowPerTemplate() {
        picker()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("capture_template_row").fetchSemanticsNodes().size == 3
        }
        composeRule.onNodeWithText("Quick Note").assertIsDisplayed()
        composeRule.onNodeWithText("Journal Entry").assertIsDisplayed()
    }

    @Test
    fun tappingATemplateSelectsIt() {
        var picked: CaptureTemplate? = null
        picker(onPickTemplate = { picked = it })

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("capture_template_row").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Quick Note").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { picked != null }
        assertEquals("Quick Note", picked?.name)
    }

    @Test
    fun editorOpensWithTheExpandedTemplateBody() {
        editor("builtin-quick-note")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("capture_body_field").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capture_body_field").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_save").assertIsDisplayed()
        composeRule.onNodeWithText("Quick Note").assertIsDisplayed()
    }

    @Test
    fun typingAHeadingAndSavingWritesToTheTargetFile() {
        var saved = false
        editor("builtin-quick-note", onSaved = { saved = true })

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("capture_body_field").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capture_body_field").performTextInput("Buy milk")
        composeRule.onNodeWithTag("capture_save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { saved }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { env.store.read("inbox.org") }.contains("Buy milk")
        }
    }

    @Test
    fun systemBackWithUnsavedTextAsksBeforeDiscarding() {
        var closed = false
        editor("builtin-quick-note", onClose = { closed = true })
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("capture_body_field").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capture_body_field").performTextInput("Buy milk")

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithText("Discard note?").assertIsDisplayed()
        assertFalse(closed)
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { closed }
    }

    @Test
    fun systemBackWithNoChangesClosesStraightAway() {
        var closed = false
        editor("builtin-quick-note", onClose = { closed = true })
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("capture_body_field").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(timeoutMillis = 3_000) { closed }
    }

    @Test
    fun savingABlankHeadingIsBlocked() {
        var saved = false
        editor("builtin-quick-note", onSaved = { saved = true })

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("capture_save").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capture_save").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("capture_body_field").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Add a heading").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(saved)
    }

    @Test
    fun roamCaptureReadModeRendersHeadlinelessDraftAndHidesHamburger() {
        val roamTemplateId = "test-roam-node"
        runBlocking {
            env.templatesRepository.upsert(
                CaptureTemplate(
                    id = roamTemplateId,
                    name = "Roam Node",
                    targetFile = "",
                    location = TargetLocation.BottomOfFile,
                    template = "",
                    kind = TemplateKind.ROAM_NODE,
                ),
            )
        }
        editor(roamTemplateId)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("capture_body_field").fetchSemanticsNodes().isNotEmpty()
        }
        // A roam draft's newFileTemplate has no leading "* " headline (just
        // #+title:), so the metadata sheet's hamburger
        // trigger -- which edits a headline -- has nothing to act on here.
        composeRule.onAllNodesWithText("☰").assertCountEquals(0)

        composeRule.onNodeWithTag("capture_body_field").performTextInput("My roam title")
        composeRule.onNodeWithTag("read_edit_toggle").performClick()

        // Read mode must render the draft's title, not blank out (the
        // headline-less draft has no OrgHeadline for the old preview to key off).
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("My roam title", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
