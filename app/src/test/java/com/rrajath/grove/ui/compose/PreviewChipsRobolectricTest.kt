package com.rrajath.grove.ui.compose

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.attach.AttachmentStatus
import com.rrajath.grove.attach.AttachmentStatusCache
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.attach.rawPreviewChips
import com.rrajath.grove.ui.attach.rememberPreviewChipText
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1i/1j preview chips: drawn inline, missing only when read mode already knows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class PreviewChipsRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val text = "Kept the [[attachment:receipt.pdf]] and [[attachment:lantern.jpg]]."

    private fun content() {
        composeRule.setContent {
            GroveTheme {
                val chips = rawPreviewChips(text)
                val t = rememberPreviewChipText(AnnotatedString(text), chips, "travel.org", listOf("receipt"))
                Text(t.text, inlineContent = t.inlineContent)
            }
        }
    }

    @Test
    fun attachmentLinksDrawAsChips() {
        content()
        composeRule.onAllNodesWithTag("preview_chip", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNodeWithTag("preview_chip_missing", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aChipReadModeFoundMissingShowsMissing() {
        val app = ApplicationProvider.getApplicationContext<TestGroveApplication>()
        app.attachmentStatusCache.putStatus(
            AttachmentStatusCache.key("travel.org", "attachment:lantern.jpg", 3),
            AttachmentStatus.Missing,
        )
        content()
        composeRule.onAllNodesWithTag("preview_chip", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("preview_chip_missing", useUnmergedTree = true).assertExists()
    }
}
