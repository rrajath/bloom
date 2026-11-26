package com.rrajath.grove.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.rrajath.grove.ui.theme.grove

/**
 * Settings § Notes "Highlight current line": tints the logical line holding the
 * caret, every visual row it soft-wraps onto, so where a line really ends is
 * visible without a line-number gutter. Shown only while the field is focused.
 *
 * Chain it on a `BasicTextField`'s modifier *after* its padding, so this node's
 * bounds are the text area: the band then bleeds [bleed] into the end padding and
 * [startBleed] into the start padding (wider when a [LineNumberGutter] sits there;
 * start is the right side in RTL), and is clipped vertically to the visible text area. [layout] is the field's latest `onTextLayout` result,
 * scrolled by [scrollState] (the field scrolls itself).
 */
@Composable
fun Modifier.currentLineHighlight(
    enabled: Boolean,
    textState: TextFieldState,
    scrollState: ScrollState,
    layout: () -> TextLayoutResult?,
    bleed: Dp,
    startBleed: Dp = bleed,
): Modifier {
    if (!enabled) return this
    val color = MaterialTheme.grove.surface2
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawBehind {
            if (!focused) return@drawBehind
            val result = layout() ?: return@drawBehind
            val (top, bottom) = currentLineBand(textState.text, textState.selection, result)
                ?: return@drawBehind
            val ltr = layoutDirection == LayoutDirection.Ltr
            val leftPx = (if (ltr) startBleed else bleed).toPx()
            val rightPx = (if (ltr) bleed else startBleed).toPx()
            clipRect(left = -leftPx, top = 0f, right = size.width + rightPx, bottom = size.height) {
                drawRect(
                    color = color,
                    topLeft = Offset(-leftPx, top - scrollState.value),
                    size = Size(size.width + leftPx + rightPx, bottom - top),
                )
            }
        }
}

/**
 * Vertical extent (top to bottom, in text-layout px) of the logical line holding a
 * collapsed caret, spanning every visual row it soft-wraps onto. Null for a range
 * selection, which already has its own highlight. Offsets map 1:1 onto [layout]:
 * [OrgSyntaxHighlight] keeps lengths, only appending blank lines past the end.
 */
internal fun currentLineBand(text: CharSequence, selection: TextRange, layout: TextLayoutResult): Pair<Float, Float>? {
    if (!selection.collapsed) return null
    val caret = selection.start.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', caret - 1) + 1
    val end = text.indexOf('\n', caret).let { if (it == -1) text.length else it }
    if (end > layout.layoutInput.text.length) return null
    val first = layout.getLineForOffset(start)
    val last = layout.getLineForOffset(end)
    return layout.getLineTop(first) to layout.getLineBottom(last)
}
