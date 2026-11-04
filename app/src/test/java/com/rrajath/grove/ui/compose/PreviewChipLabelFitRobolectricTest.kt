package com.rrajath.grove.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.attach.AttachmentStatus
import com.rrajath.grove.attach.AttachmentStatusCache
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.attach.AttachmentRenderContext
import com.rrajath.grove.ui.attach.chipLinksIn
import com.rrajath.grove.ui.attach.rawPreviewChips
import com.rrajath.grove.ui.attach.rememberChipRun
import com.rrajath.grove.ui.attach.rememberPreviewChipText
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A preview chip's label must lay out whole on one line: the inline placeholder
 * is the chip's only width constraint, so a placeholder even a pixel narrower
 * than the drawn Row wraps the last character onto a hidden second line. Only
 * fractional densities (1.5, 2.625, 3.5) used to break, where each dp padding
 * rounds to whole pixels but the placeholder summed the unrounded values.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class PreviewChipLabelFitRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val labels = listOf("15464.jpg", "receipt.pdf", "lantern.jpg", "a.png", "notes-2026-10-19.txt")
    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f)
    private val fontScales = listOf(0.85f, 1f, 1.15f, 1.3f)

    private fun render(missing: Boolean) {
        val text = labels.joinToString("\n") { "[[attachment:$it]]" }
        if (missing) {
            val app = ApplicationProvider.getApplicationContext<TestGroveApplication>()
            labels.forEach {
                app.attachmentStatusCache.putStatus(
                    AttachmentStatusCache.key("travel.org", "attachment:$it", 0),
                    AttachmentStatus.Missing,
                )
            }
        }
        composeRule.setContent {
            GroveTheme {
                Column {
                    for (d in densities) for (s in fontScales) {
                        CompositionLocalProvider(LocalDensity provides Density(d, s)) {
                            val chips = rawPreviewChips(text)
                            val t = rememberPreviewChipText(AnnotatedString(text), chips, "travel.org", -1, 12.5.sp)
                            Text(t.text, inlineContent = t.inlineContent)
                        }
                    }
                }
            }
        }
    }

    private fun assertLabelsFit() {
        val expected = densities.size * fontScales.size
        val failures = mutableListOf<String>()
        for (label in labels) {
            val nodes = composeRule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("chips labelled $label", expected, nodes.size)
            nodes.forEachIndexed { i, node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                val layout = results.single()
                // One line, every character on it, and the node as wide as the
                // text's natural width: catches both a wrapped and a clipped tail.
                val natural = kotlin.math.ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
                val fits = layout.lineCount == 1 &&
                    layout.getLineEnd(0, visibleEnd = true) == label.length &&
                    node.size.width >= natural
                if (!fits) {
                    val d = densities[i / fontScales.size]
                    val s = fontScales[i % fontScales.size]
                    val shown = label.take(layout.getLineEnd(0, visibleEnd = true))
                    failures += "$label at density $d fontScale $s shows \"$shown\" in ${node.size.width}px of ${natural}px"
                }
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "${failures.size} clipped labels:\n"), failures.isEmpty())
    }

    /** Read mode's 1d chips, sized the same way at 12.5sp. */
    private fun renderReadMode(missing: Boolean) {
        if (!missing) labels.forEach { File(tmp.root, it).writeText("x") }
        val ctx = AttachmentRenderContext(
            orgFile = "kyoto.org",
            doc = null,
            store = JvmFileStore(tmp.root, skipAttachShards = true),
            cache = AttachmentStatusCache(),
            widthMode = AttachImageWidth.FIT,
            fixedWidthPx = AttachImageWidth.DEFAULT_PX,
            preferLines = null,
            openTarget = {},
            toast = {},
            io = Dispatchers.IO,
        )
        val text = labels.joinToString(" ") { "[[file:$it]]" }
        composeRule.setContent {
            GroveTheme {
                Column {
                    for (d in densities) for (s in fontScales) {
                        CompositionLocalProvider(LocalDensity provides Density(d, s)) {
                            val chips = chipLinksIn(text)
                            val run = rememberChipRun(chips, ctx)
                            val annotated = buildAnnotatedString {
                                chips.forEach { appendInlineContent(it.id, it.link.label) }
                            }
                            Text(annotated, inlineContent = run.inlineContent)
                        }
                    }
                }
            }
        }
        val tag = if (missing) "attachment_chip_missing" else "attachment_chip"
        val expected = labels.size * densities.size * fontScales.size
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size == expected
        }
    }

    @Test
    fun missingChipLabelFitsOnOneLine() {
        render(missing = true)
        assertLabelsFit()
    }

    @Test
    fun chipLabelFitsOnOneLine() {
        render(missing = false)
        assertLabelsFit()
    }

    @Test
    fun missingReadModeChipLabelFitsOnOneLine() {
        renderReadMode(missing = true)
        assertLabelsFit()
    }

    @Test
    fun readModeChipLabelFitsOnOneLine() {
        renderReadMode(missing = false)
        assertLabelsFit()
    }
}
