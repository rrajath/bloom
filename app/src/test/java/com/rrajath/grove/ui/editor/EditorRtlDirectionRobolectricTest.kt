package com.rrajath.grove.ui.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveDarkColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The raw org editor lays each line out in its own direction: plain lines by their
 * first strong character ([EditorTextDirection]), and a heading by its title, past the
 * Latin stars/keyword/priority prefix ([OrgSyntaxHighlight]). The paragraph styles that
 * do this must not add lines or shift offsets: editor cursor math relies on 1:1 offsets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class EditorRtlDirectionRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val lines = listOf(
        "* TODO [#A] خرید از بازار :خرید:", // 0: RTL title behind a Latin prefix
        "plain english line", // 1
        "این یک پاراگراف است.", // 2
        "- [ ] رزرو هتل", // 3: neutral bullet prefix
        "** DONE Buy milk :home:", // 4: LTR heading stays LTR
        "", // 5
        "*** عنوان بدون کلمه", // 6: RTL heading with no keyword
        "SCHEDULED: <2026-11-02 Mon>", // 7
    )
    private val text = lines.joinToString("\n")

    private fun layOut(): TextLayoutResult {
        var layout: TextLayoutResult? = null
        composeRule.setContent {
            BasicTextField(
                state = remember { TextFieldState(text) },
                outputTransformation = remember {
                    OrgSyntaxHighlight(GroveDarkColors, OrgKeywords(listOf("TODO"), listOf("DONE")))
                },
                textStyle = TextStyle(textDirection = EditorTextDirection),
                lineLimits = TextFieldLineLimits.MultiLine(),
                onTextLayout = { getResult -> layout = getResult() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        composeRule.waitForIdle()
        return requireNotNull(layout)
    }

    private fun lineStart(i: Int) = lines.take(i).sumOf { it.length + 1 }

    @Test
    fun `each line takes its own direction`() {
        val layout = layOut()
        val expected = mapOf(
            0 to ResolvedTextDirection.Rtl,
            1 to ResolvedTextDirection.Ltr,
            2 to ResolvedTextDirection.Rtl,
            3 to ResolvedTextDirection.Rtl,
            4 to ResolvedTextDirection.Ltr,
            6 to ResolvedTextDirection.Rtl,
            7 to ResolvedTextDirection.Ltr,
        )
        expected.forEach { (i, dir) ->
            assertEquals("line $i: ${lines[i]}", dir, layout.getParagraphDirection(lineStart(i)))
        }
    }

    @Test
    fun `direction styles add no lines and keep offsets 1 to 1`() {
        val layout = layOut()
        assertEquals(text.length, layout.layoutInput.text.length)
        lines.indices.forEach { i ->
            assertEquals("line $i", i, layout.getLineForOffset(lineStart(i)))
        }
        assertEquals(lines.size, layout.lineCount)
    }
}
