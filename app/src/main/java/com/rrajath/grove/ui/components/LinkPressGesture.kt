package com.rrajath.grove.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Takes over tap/long-press for the link spans in [links] within a rendered
 * org [androidx.compose.material3.Text]: a quick tap opens the link via
 * [onTap], a long press reports it via [onLongPress] (e.g. a copy/share menu)
 * instead of falling through to the ambient SelectionContainer's
 * long-press-to-select. Presses outside any link span are left completely
 * untouched (never consumed), so normal text selection, scrolling and
 * double-tap-to-edit behave exactly as if this modifier weren't present.
 */
fun Modifier.linkPressHandler(
    links: List<InlineLink>,
    layoutResult: () -> TextLayoutResult?,
    onTap: (String) -> Unit,
    onLongPress: (String, androidx.compose.ui.geometry.Offset) -> Unit,
): Modifier {
    if (links.isEmpty()) return this
    return this.pointerInput(links) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val layout = layoutResult() ?: return@awaitEachGesture
            val link = layout.linkAt(down.position, links) ?: return@awaitEachGesture

            // We own this gesture now: consume the down and every subsequent
            // change for this pointer so the built-in link click and the
            // SelectionContainer's long-press-to-select never also see it.
            down.consume()
            var outcome: String? = null
            val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (outcome == null) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        outcome = "gone"
                        break
                    }
                    change.consume()
                    if (!change.pressed) {
                        outcome = "tap"
                        break
                    }
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (kotlin.math.sqrt(dx * dx + dy * dy) > viewConfiguration.touchSlop) {
                        outcome = "moved"
                        break
                    }
                }
            } == null

            when {
                timedOut -> onLongPress(link.target, down.position) // held past the threshold, still down & stationary
                outcome == "tap" -> onTap(link.target)
                else -> {} // moved beyond touch slop, or the pointer stream ended: do nothing
            }
        }
    }
}

/**
 * The link whose glyphs sit under [position], or null. A press in the empty space
 * past a line's text misses: `getOffsetForPosition` alone snaps it to the line's
 * nearest character, which in a full-width text (an RTL line pushed right, a heading
 * title) would open a link the press never touched.
 */
internal fun TextLayoutResult.linkAt(
    position: androidx.compose.ui.geometry.Offset,
    links: List<InlineLink>,
): InlineLink? {
    // Inline content (attachment chips) is one placeholder over the whole link
    // range; hit-test its box, since offsets inside it snap to either end.
    for (link in links) {
        if (link.placeholder < 0) continue
        val rect = placeholderRects.getOrNull(link.placeholder) ?: continue
        if (rect.contains(position)) return link
    }
    val line = getLineForVerticalPosition(position.y)
    if (position.x < getLineLeft(line) || position.x > getLineRight(line)) return null
    val charOffset = getOffsetForPosition(position)
    return links.firstOrNull { charOffset in it.range }
}
