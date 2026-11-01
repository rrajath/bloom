package com.rrajath.grove.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult

/**
 * Detects a double-tap on rendered org text and invokes [onDoubleTap].
 *
 * Unlike a plain `detectTapGestures(onDoubleTap = ...)`, this never consumes
 * the *first* tap of a pair: it only watches (at [PointerEventPass.Final], the
 * last pass, so it can't ever get in anyone else's way) until that gesture
 * ends, then remembers its time/position. Only once a *second* tap lands
 * within the platform double-tap window does it take over the pointer stream.
 * That's essential here because Compose's `SelectionContainer` has its own
 * built-in double-tap-to-select-word gesture wired directly to the underlying
 * `Text`; a naive higher-level tap detector using `requireUnconsumed = true`
 * loses that race for taps that land on actual text (SelectionContainer wins),
 * while still winning for taps on blank space, which is exactly the
 * inconsistent behavior this modifier replaces. A single-press, long-press,
 * or drag is *always* left completely untouched, so word-selection via
 * long-press keeps working normally.
 *
 * [layoutResult] and [links] exist only to leave link taps alone. On a container
 * that renders no links (e.g. a whole `:PROPERTIES:` key/value row), pass
 * `layoutResult = { null }` and no [links]: the null layout is then treated as
 * "nothing to avoid" rather than "can't map this tap".
 */
fun Modifier.doubleTapToEdit(
    layoutResult: () -> TextLayoutResult?,
    links: List<InlineLink> = emptyList(),
    enabled: Boolean = true,
    onDoubleTap: () -> Unit,
): Modifier {
    if (!enabled) return this
    return this.pointerInput(Unit) {
        var lastUpMillis = 0L
        var lastPosition: Offset? = null

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val prior = lastPosition
            val isSecondTap = prior != null &&
                (down.position - prior).getDistance() < viewConfiguration.touchSlop &&
                (down.uptimeMillis - lastUpMillis) < viewConfiguration.doubleTapTimeoutMillis

            if (!isSecondTap) {
                // Just watch this gesture to completion (never consume), so
                // whatever is underneath (selection long-press/drag, link tap)
                // handles it exactly as if we weren't here.
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                    }
                    if (!change.pressed) {
                        lastPosition = if (moved) null else change.position
                        lastUpMillis = change.uptimeMillis
                        break
                    }
                }
            } else {
                val layout = layoutResult()
                val charOffset = layout?.getOffsetForPosition(down.position)
                val onLink = layout != null && layout.linkAt(down.position, links) != null
                lastPosition = null

                if (onLink || (links.isNotEmpty() && (layout == null || charOffset == null))) {
                    // It's on a link (which owns its own tap/long-press handling),
                    // or this is a text target whose tap we couldn't map to a
                    // character; don't hijack the gesture. A link-free container
                    // (layout intentionally null) falls through and takes over.
                    return@awaitEachGesture
                }

                // Confirmed double-tap: take over so SelectionContainer's own
                // double-tap-to-select-word never also fires for this gesture.
                down.consume()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                }
                onDoubleTap()
            }
        }
    }
}
