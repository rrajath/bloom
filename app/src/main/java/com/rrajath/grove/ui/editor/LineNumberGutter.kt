package com.rrajath.grove.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Space between the gutter's leading screen edge and the widest number. */
private val GutterEdgeMargin = 10.dp

/** Space between a number's trailing edge and the text it labels. */
private val GutterTextGap = 10.dp

/** Black overlay alpha that shades the gutter strip darker than the editor `bg`. */
private const val GutterShadeLight = 0.05f
private const val GutterShadeDark = 0.3f

/** Fewest digit columns reserved, so the text doesn't shift as a short buffer grows past 9 lines. */
private const val MinGutterDigits = 2

/**
 * Settings § Notes "Show line numbers": a start-side gutter of the buffer's real file
 * line numbers, so line 1 of a subtree buffer reads as its heading's line in the file.
 * Only a logical line's first visual row is numbered; its soft-wrapped rows are blank.
 * Numbers are `ink3` on a strip shaded darker than `bg`; the lines the caret or selection covers are
 * `ink2` while focused.
 *
 * Tapping a number selects its whole line, trailing newline included (so Cut removes
 * the line); dragging across numbers selects every line between, auto-scrolling past
 * the field's top or bottom edge. Either focuses the field, raising the keyboard.
 *
 * Wire it into a self-scrolling `BasicTextField`'s modifier chain in three places:
 * [inputModifier] before the field's horizontal padding (so it receives touches in the
 * padding, outside the field's own gesture handling), [startPadding] as that padding's
 * start, and [drawModifier] after it (so its bounds are the text area). Padding and
 * drawing follow the layout direction, so RTL puts the gutter on the right.
 */
@Stable
class LineNumberGutter internal constructor(
    /** The field's start padding with the gutter in it (replaces the plain screen gutter). */
    val startPadding: Dp,
    val inputModifier: Modifier,
    val drawModifier: Modifier,
)

/**
 * Null when [enabled] is false, so callers fall back to their plain padding. [layout] is
 * the field's latest `onTextLayout` result, scrolled by [scrollState]; [firstLineNumber]
 * is the 1-based file line of the buffer's first line.
 */
@Composable
fun rememberLineNumberGutter(
    enabled: Boolean,
    textState: TextFieldState,
    scrollState: ScrollState,
    layout: () -> TextLayoutResult?,
    firstLineNumber: Int,
    focusRequester: FocusRequester,
): LineNumberGutter? {
    if (!enabled) return null
    val c = MaterialTheme.grove
    val density = LocalDensity.current
    val ltr by rememberUpdatedState(LocalLayoutDirection.current == LayoutDirection.Ltr)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val style = TextStyle(fontFamily = PlexMono, fontSize = 11.5.sp)
    // PlexMono has one advance for every digit, so each number is drawn digit by digit
    // from ten pre-measured glyphs instead of measuring a string per row per frame.
    val digits = remember(measurer, density) { (0..9).map { measurer.measure(it.toString(), style) } }
    val digitWidth = digits[0].size.width
    val lineStarts by remember(textState) { derivedStateOf { lineStartOffsets(textState.text) } }
    val firstLine by rememberUpdatedState(firstLineNumber)
    val lastNumber = firstLineNumber + lineStarts.size - 1
    val columns = maxOf(MinGutterDigits, lastNumber.toString().length)
    val numbersWidth = with(density) { (digitWidth * columns).toDp() }
    val startPadding = GutterEdgeMargin + numbersWidth + GutterTextGap

    // Plain holder, not state: only the gesture reads these, never composition or drawing.
    val coords = remember { GutterCoords() }
    var focused by remember { mutableStateOf(false) }

    // The gutter's strip is the editor `bg` shaded with black (no palette token is darker
    // than `bg` in every theme; dark `bg`s need more black to show). It stops half the
    // text gap short of the text, and is drawn on the outer node (before the padding) so
    // it sits under the current-line band.
    val stripColor = Color.Black.copy(alpha = if (c.isDark) GutterShadeDark else GutterShadeLight)
    val stripWidth = startPadding - GutterTextGap / 2
    val inputModifier = Modifier
        .onPlaced { coords.outer = it }
        .drawBehind {
            val w = stripWidth.toPx()
            drawRect(
                color = stripColor,
                topLeft = Offset(if (ltr) 0f else size.width - w, 0f),
                size = Size(w, size.height),
            )
        }
        .pointerInput(textState, scrollState) {
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val outer = coords.outer ?: return@awaitEachGesture
                    val inner = coords.text ?: return@awaitEachGesture
                    val inText = inner.localPositionOf(outer, down.position)
                    val inGutter = if (ltr) inText.x < 0f else inText.x > inner.size.width
                    if (!inGutter || inText.y !in 0f..inner.size.height.toFloat()) return@awaitEachGesture
                    val anchor = lineAtY(inText.y, scrollState, layout(), lineStarts) ?: return@awaitEachGesture
                    down.consume()
                    textState.edit { selection = lineRangeSelection(anchor, anchor, lineStarts, length) }
                    focusRequester.requestFocus()
                    var pointerY = inText.y
                    var dragging = true
                    // Past the top/bottom edge, scroll each frame by how far past it the finger is.
                    val autoScroll = launch {
                        while (dragging) {
                            withFrameNanos { }
                            val height = inner.size.height.toFloat()
                            val over = when {
                                pointerY < 0f -> pointerY
                                pointerY > height -> pointerY - height
                                else -> 0f
                            }
                            if (over == 0f) continue
                            scrollState.scrollBy(over.coerceIn(-height / 8, height / 8))
                            selectDrag(anchor, pointerY.coerceIn(0f, height), textState, scrollState, layout(), lineStarts)
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) {
                                change.consume()
                                pointerY = inner.localPositionOf(outer, change.position).y
                                selectDrag(
                                    anchor, pointerY.coerceIn(0f, inner.size.height.toFloat()),
                                    textState, scrollState, layout(), lineStarts,
                                )
                            }
                        }
                    } finally {
                        dragging = false
                        autoScroll.cancel()
                    }
                }
            }
        }

    val drawModifier = Modifier
        .onPlaced { coords.text = it }
        .onFocusChanged { focused = it.isFocused }
        .drawBehind {
            val result = layout() ?: return@drawBehind
            val text = textState.text
            val selection = textState.selection
            val starts = lineStarts
            val scroll = scrollState.value.toFloat()
            val edgePx = startPadding.toPx()
            val gapPx = GutterTextGap.toPx()
            val (activeFirst, activeLast) = if (focused) {
                val lastOffset = if (selection.collapsed) selection.max else maxOf(selection.min, selection.max - 1)
                lineIndexForOffset(starts, selection.min) to lineIndexForOffset(starts, lastOffset)
            } else {
                -1 to -1
            }
            val firstRow = result.getLineForVerticalPosition(scroll)
            val lastRow = result.getLineForVerticalPosition(scroll + size.height)
            clipRect(left = -edgePx, top = 0f, right = size.width + edgePx, bottom = size.height) {
                for (row in firstRow..lastRow) {
                    val rowStart = result.getLineStart(row)
                    if (rowStart > text.length) break
                    val index = starts.binarySearch(rowStart)
                    if (index < 0) continue // a soft-wrapped continuation row
                    val label = (firstLine + index).toString()
                    val color = if (index in activeFirst..activeLast) c.ink2 else c.ink3
                    val width = digitWidth * label.length
                    var x = if (ltr) -gapPx - width else size.width + gapPx
                    val top = result.getLineBaseline(row) - scroll - digits[0].firstBaseline
                    for (ch in label) {
                        drawText(digits[ch - '0'], color = color, topLeft = Offset(x, top))
                        x += digitWidth
                    }
                }
            }
        }

    return LineNumberGutter(startPadding, inputModifier, drawModifier)
}

/** The field's outer node (before its padding) and its text area (after it). */
private class GutterCoords {
    var outer: LayoutCoordinates? = null
    var text: LayoutCoordinates? = null
}

private fun selectDrag(
    anchor: Int,
    y: Float,
    textState: TextFieldState,
    scrollState: ScrollState,
    layout: TextLayoutResult?,
    lineStarts: IntArray,
) {
    val line = lineAtY(y, scrollState, layout, lineStarts) ?: return
    val target = lineRangeSelection(minOf(anchor, line), maxOf(anchor, line), lineStarts, textState.text.length)
    if (target != textState.selection) textState.edit { selection = target }
}

/** Index of the logical line at text-area y [y], or null before the first layout. */
private fun lineAtY(y: Float, scrollState: ScrollState, layout: TextLayoutResult?, lineStarts: IntArray): Int? {
    layout ?: return null
    val row = layout.getLineForVerticalPosition(y + scrollState.value)
    return lineIndexForOffset(lineStarts, layout.getLineStart(row))
}

/** Char offset where each logical line of [text] starts; always holds at least 0. */
internal fun lineStartOffsets(text: CharSequence): IntArray {
    var count = 1
    for (ch in text) if (ch == '\n') count++
    val starts = IntArray(count)
    var i = 1
    text.forEachIndexed { offset, ch -> if (ch == '\n') starts[i++] = offset + 1 }
    return starts
}

/** Index of the logical line holding [offset]: the last start at or before it. */
internal fun lineIndexForOffset(lineStarts: IntArray, offset: Int): Int {
    val found = lineStarts.binarySearch(offset)
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

/**
 * Selection covering logical lines [first]..[last] whole: from the first's start up to
 * the start of the line after [last] (its newline included), or the text end for the
 * buffer's last line.
 */
internal fun lineRangeSelection(first: Int, last: Int, lineStarts: IntArray, length: Int): TextRange {
    val start = lineStarts[first.coerceIn(lineStarts.indices)]
    val end = lineStarts.getOrNull(last + 1) ?: length
    return TextRange(start, end)
}
