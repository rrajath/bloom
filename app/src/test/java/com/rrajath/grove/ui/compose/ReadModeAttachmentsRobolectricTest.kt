package com.rrajath.grove.ui.compose

import com.rrajath.grove.attach.AttachConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rrajath.grove.attach.AttachmentStatusCache
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.attach.AttachmentRenderContext
import com.rrajath.grove.ui.attach.LocalAttachmentRender
import com.rrajath.grove.ui.screens.BodyBlocks
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Read mode 1d: images split out of paragraphs, chips inline, missing states, ATTR_ORG hidden. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class ReadModeAttachmentsRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = mutableListOf<String>()
    private val toasts = mutableListOf<String>()

    // 1x1 transparent PNG.
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    private fun content(lines: List<String>) {
        File(tmp.root, "receipt.pdf").writeText("pdf")
        File(tmp.root, "route.png").writeBytes(png)
        val ctx = AttachmentRenderContext(
            orgFile = "kyoto.org",
            doc = null,
            store = JvmFileStore(tmp.root, skipAttachShards = true),
            cache = AttachmentStatusCache(),
            config = AttachConfig.Default,
            widthMode = AttachImageWidth.FIT,
            fixedWidthPx = AttachImageWidth.DEFAULT_PX,
            preferLines = null,
            openTarget = { opened += it },
            toast = { toasts += it },
            io = Dispatchers.IO,
        )
        composeRule.setContent {
            GroveTheme {
                CompositionLocalProvider(LocalAttachmentRender provides ctx) {
                    Column {
                        BodyBlocks(
                            bodyLines = lines,
                            lineOffset = 0,
                            onToggleCheckbox = { _, _ -> },
                            openTarget = { opened += it },
                            onLinkLongPress = { _, _, _ -> },
                            onEditAt = {},
                        )
                    }
                }
            }
        }
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private val body = listOf(
        "Kept the [[file:receipt.pdf]] and [[file:gone.pdf]].",
        "",
        "[[file:missing.jpg]]",
        "",
        "#+ATTR_ORG: :width 200",
        "[[file:route.png]]",
    )

    @Test
    fun chipsImagesAndMissingStatesRender() {
        content(body)
        waitForTag("attachment_chip")
        waitForTag("attachment_chip_missing")
        composeRule.onNodeWithText("receipt.pdf", useUnmergedTree = true).assertExists()
        waitForTag("attachment_image_missing")
        composeRule.onNodeWithText("Image not found: missing.jpg", useUnmergedTree = true).assertExists()
        // The ATTR_ORG run belongs to the image and isn't drawn as a keyword block.
        composeRule.onNodeWithText("ATTR_ORG", substring = true).assertDoesNotExist()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("attachment_image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("attachment_image_error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tappingAChipOpensItAndAMissingOneToasts() {
        content(body)
        waitForTag("attachment_chip_missing")
        composeRule.onNodeWithTag("attachment_chip", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("attachment_chip_missing", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("file:receipt.pdf"), opened)
        assertEquals(listOf("File not found: gone.pdf"), toasts)
    }

    @Test
    fun tappingAMissingImageToasts() {
        content(body)
        waitForTag("attachment_image_missing")
        composeRule.onNodeWithTag("attachment_image_missing", useUnmergedTree = true).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertEquals(listOf("Image not found: missing.jpg"), toasts)
    }

    @Test
    fun imageLinksInListItemsStayChips() {
        content(listOf("- see [[file:route.png]]"))
        waitForTag("attachment_chip")
        composeRule.onAllNodesWithTag("attachment_image", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }
}
