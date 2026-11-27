package com.rrajath.grove.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tapping a gutter number selects its whole line; dragging selects every line crossed. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class LineNumberGutterRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val text = (1..10).joinToString("\n") { "line $it" }
    private val state = TextFieldState(text, TextRange(0))
    private lateinit var scrollState: ScrollState
    private var layout: TextLayoutResult? = null
    private var gutterWidthPx = 0f
    private var compositions = 0

    private fun setUp(direction: LayoutDirection = LayoutDirection.Ltr) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                GroveTheme {
                    scrollState = rememberScrollState()
                    val focusRequester = remember { FocusRequester() }
                    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val gutter = rememberLineNumberGutter(true, state, scrollState, { textLayout }, 41, focusRequester)!!
                    with(androidx.compose.ui.platform.LocalDensity.current) { gutterWidthPx = gutter.startPadding.toPx() }
                    SideEffect { compositions++ }
                    Box(Modifier.height(400.dp)) {
                        BasicTextField(
                            state = state,
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            scrollState = scrollState,
                            onTextLayout = { getResult ->
                                textLayout = getResult()
                                layout = textLayout
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("field")
                                .then(gutter.inputModifier)
                                .padding(start = gutter.startPadding, end = 18.dp)
                                .then(gutter.drawModifier)
                                .focusRequester(focusRequester),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // The field's semantics bounds are its text area (inside the padding), so touch
    // offsets are relative to that: the gutter sits at negative x in LTR and past
    // `width` in RTL.

    /** Y (text-area coordinates) of the middle of logical line [index]. */
    private fun rowCenter(index: Int): Float {
        val l = layout!!
        val row = l.getLineForOffset(lineStartOffsets(text)[index])
        return (l.getLineTop(row) + l.getLineBottom(row)) / 2
    }

    @Test
    fun `tapping a number selects that whole line including its newline`() {
        setUp()
        composeRule.onNodeWithTag("field").performTouchInput { click(Offset(-gutterWidthPx / 2, rowCenter(2))) }
        composeRule.waitForIdle()
        val starts = lineStartOffsets(text)
        assertEquals(TextRange(starts[2], starts[3]), state.selection)
    }

    @Test
    fun `dragging across numbers selects every line in between`() {
        setUp()
        composeRule.onNodeWithTag("field").performTouchInput {
            down(Offset(-gutterWidthPx / 2, rowCenter(5)))
            moveTo(Offset(-gutterWidthPx / 2, rowCenter(4)))
            moveTo(Offset(-gutterWidthPx / 2, rowCenter(1)))
            up()
        }
        composeRule.waitForIdle()
        val starts = lineStartOffsets(text)
        assertEquals(TextRange(starts[1], starts[6]), state.selection)
    }

    @Test
    fun `tapping the text area leaves selection to the field`() {
        setUp()
        composeRule.onNodeWithTag("field").performTouchInput { click(Offset(40f, rowCenter(2))) }
        composeRule.waitForIdle()
        assertTrue(state.selection.collapsed)
    }

    @Test
    fun `typing recomposes the host only when the gutter gains a digit`() {
        setUp()
        // Lines 41..50: two digits.
        val twoDigitWidth = gutterWidthPx
        val before = compositions
        composeRule.runOnIdle { state.edit { append("x") } }
        composeRule.waitForIdle()
        assertEquals("a keystroke must not recompose the host", before, compositions)

        // Lines 41..100: the last number needs a third digit, so the gutter widens.
        composeRule.runOnIdle { state.edit { append("\nmore".repeat(50)) } }
        composeRule.waitForIdle()
        assertNotEquals(twoDigitWidth, gutterWidthPx)
        assertTrue(gutterWidthPx > twoDigitWidth)
    }

    @Test
    fun `in RTL the gutter is on the right`() {
        setUp(LayoutDirection.Rtl)
        composeRule.onNodeWithTag("field").performTouchInput { click(Offset(width + gutterWidthPx / 2, rowCenter(0))) }
        composeRule.waitForIdle()
        val starts = lineStartOffsets(text)
        assertEquals(TextRange(starts[0], starts[1]), state.selection)
    }
}
