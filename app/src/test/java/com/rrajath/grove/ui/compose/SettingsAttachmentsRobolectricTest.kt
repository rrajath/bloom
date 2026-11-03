package com.rrajath.grove.ui.compose

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.settings.AttachPhotoSize
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class SettingsAttachmentsRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var size: AttachPhotoSize? = null
    private var width: AttachImageWidth? = null
    private var px: Int? = null

    private fun content(settings: GroveSettings = GroveSettings()) {
        composeRule.setContent {
            GroveTheme {
                SettingsAttachmentsScreen(
                    settings = settings,
                    onBack = {},
                    onSetPhotoSize = { size = it },
                    onSetImageWidth = { width = it },
                    onSetImageWidthPx = { px = it },
                )
            }
        }
    }

    @Test
    fun defaultsShowOriginalHelperAndNoStepper() {
        content()
        composeRule.onNodeWithText("Photo size").assertExists()
        composeRule.onNodeWithText("Camera and Photos only. Files are copied as-is.").assertExists()
        composeRule.onNodeWithText("Full resolution. HEIC is still converted to JPEG.").assertExists()
        composeRule.onNodeWithText("Default image width").assertExists()
        composeRule.onNodeWithText("Both settings are included in Settings import and export.").assertDoesNotExist()
        composeRule.onNodeWithTag("attach_width_value").assertDoesNotExist()
    }

    @Test
    fun helperFollowsThePhotoSizeAndPickingReportsIt() {
        content(GroveSettings(attachPhotoSize = AttachPhotoSize.MEDIUM))
        composeRule.onNodeWithText("Long edge 1920 px").assertExists()
        composeRule.onNodeWithText("Small").performClick()
        composeRule.onNodeWithText("Fixed").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(AttachPhotoSize.SMALL, size)
        assertEquals(AttachImageWidth.FIXED, width)
    }

    @Test
    fun fixedShowsTheStepperWhichMovesIn40pxSteps() {
        content(GroveSettings(attachImageWidth = AttachImageWidth.FIXED, attachImageWidthPx = 320))
        composeRule.onNodeWithText("320 px").performScrollTo().assertExists()
        composeRule.onNodeWithTag("attach_width_plus").performClick()
        assertEquals(360, px)
        composeRule.onNodeWithTag("attach_width_minus").performClick()
        assertEquals(280, px)
    }

    @Test
    fun stepperStopsAtTheLimits() {
        content(GroveSettings(attachImageWidth = AttachImageWidth.FIXED, attachImageWidthPx = 120))
        composeRule.onNodeWithTag("attach_width_minus").performScrollTo().assertIsNotEnabled()
    }
}
