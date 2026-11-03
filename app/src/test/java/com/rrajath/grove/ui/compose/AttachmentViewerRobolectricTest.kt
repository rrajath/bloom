package com.rrajath.grove.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.attach.AttachmentViewer
import com.rrajath.grove.ui.attach.viewerZoomLabel
import com.rrajath.grove.ui.theme.GroveBloomDarkColors
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.ui.theme.LocalGroveColors
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
class AttachmentViewerRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var closed = 0
    private var shared = 0
    private var opened = 0

    private fun content(
        missing: Boolean = false,
        shareResult: String? = null,
        openResult: String? = null,
    ) {
        composeRule.setContent {
            GroveTheme {
                CompositionLocalProvider(LocalGroveColors provides GroveBloomDarkColors) {
                    AttachmentViewer(
                        model = null,
                        title = "torii-path.jpg",
                        missing = missing,
                        onShare = { shared++; shareResult },
                        onOpen = { opened++; openResult },
                        onClose = { closed++ },
                    )
                }
            }
        }
    }

    @Test
    fun opensChromeFreeWithTheHint() {
        content()
        composeRule.onNodeWithText("Tap for controls").assertExists()
        composeRule.onNodeWithText("Share").assertDoesNotExist()
        composeRule.onNodeWithText("torii-path.jpg").assertDoesNotExist()
    }

    @Test
    fun tapTogglesChromeAndDropsTheHint() {
        content()
        composeRule.onNodeWithTag("attachment_viewer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tap for controls").assertDoesNotExist()
        composeRule.onNodeWithText("torii-path.jpg").assertExists()
        composeRule.onNodeWithText("Share").assertExists()
        composeRule.onNodeWithText("Open in…").assertExists()
        composeRule.onNodeWithTag("attachment_viewer_zoom").assertExists()
        composeRule.onNodeWithText("Fit").assertExists()

        composeRule.onNodeWithTag("attachment_viewer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Share").assertDoesNotExist()
        composeRule.onNodeWithText("Tap for controls").assertDoesNotExist()
    }

    @Test
    fun closeShareAndOpenReportBack() {
        content(openResult = "No app can open this file")
        composeRule.onNodeWithTag("attachment_viewer").performClick()
        composeRule.onNodeWithText("Share").performClick()
        composeRule.onNodeWithText("Open in…").performClick()
        composeRule.waitForIdle()
        assertEquals(1, shared)
        assertEquals(1, opened)
        composeRule.onNodeWithText("No app can open this file").assertExists()
        composeRule.onNodeWithTag("attachment_viewer_close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun missingFileSaysSo() {
        content(missing = true)
        composeRule.onNodeWithText("Image not found: torii-path.jpg").assertExists()
    }

    @Test
    fun zoomLabelIsFitOrPercentOfFit() {
        assertEquals("Fit", viewerZoomLabel(null))
        assertEquals("Fit", viewerZoomLabel(1f))
        assertEquals("Fit", viewerZoomLabel(1.004f))
        assertEquals("240%", viewerZoomLabel(2.4f))
        assertEquals("800%", viewerZoomLabel(8f))
    }
}
