package com.rrajath.grove.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.rrajath.grove.org.LineEditing
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.TextEdit

/**
 * Org-aware typing behaviour shared by the note editor and quick capture:
 * continues lists on Enter, capitalizes the first letter typed into a heading
 * (including right after a TODO keyword, e.g. `"* TODO "`) when
 * [capitalizeHeadings] is on (Settings § Notes), and collapses a doubled URL
 * scheme after a link's scheme was pre-inserted and a full URL then pasted over
 * the cursor.
 *
 * An [InputTransformation] runs only for genuine user input (soft/hard
 * keyboard, paste, accessibility). Programmatic [TextFieldState.edit] writes
 * bypass it, so restoring a buffer (an initial load, or a metadata-sheet
 * rewrite) can never re-trigger list continuation on text that already has it.
 */
@OptIn(ExperimentalFoundationApi::class)
fun orgInputTransformation(
    keywords: OrgKeywords = OrgKeywords.DEFAULT,
    capitalizeHeadings: Boolean = true,
) = InputTransformation {
    val old = originalText.toString()
    val typed = toString()
    val cursor = selection.start

    val afterList = if (insertedSoloNewlineAt(cursor)) LineEditing.continueListOnEnter(typed, cursor) else null
    val base = afterList ?: TextEdit(typed, cursor)
    val capitalized = if (capitalizeHeadings) {
        LineEditing.capitalizeHeadingOnType(old, base.text, base.cursor, keywords) ?: base
    } else {
        base
    }
    val result = collapseDoubledScheme(capitalized.text, capitalized.cursor) ?: capitalized

    if (result.text != typed || result.cursor != cursor) {
        replace(0, length, result.text)
        selection = TextRange(result.cursor)
    }
}

/**
 * Whether this edit's change list carries a solitary `"\n"` insertion landing
 * right before [cursor] — a real Enter press, as opposed to a paste/drop/
 * accessibility edit that merely happens to end in a newline. Reading the
 * change list rather than diffing [TextFieldBuffer.originalText] against the
 * whole new text means an IME finalizing/autocorrecting a composing word
 * earlier on the same line, in the same batch as the Enter that follows it,
 * doesn't hide the newline insertion inside a larger "more than one character
 * changed" diff and get mistaken for something other than Enter.
 *
 * `internal` rather than `private`: the bug-report screen's list-only
 * transformation (`SettingsBugReportScreen.kt`) reuses this rather than
 * duplicating it.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun TextFieldBuffer.insertedSoloNewlineAt(cursor: Int): Boolean {
    if (cursor < 1) return false
    for (i in 0 until changes.changeCount) {
        val range = changes.getRange(i)
        if (range.start == cursor - 1 && range.end == cursor) {
            return changes.getOriginalRange(i).collapsed && charAt(range.start) == '\n'
        }
    }
    return false
}

/**
 * Applies one of the pure toolbar editing helpers to this [TextFieldState].
 *
 * The helpers stay expressed in [TextFieldValue] terms so they remain plain
 * JVM-testable functions with no [TextFieldState] dependency; this bridges
 * their result back into the field. A [transform] returning null means "no
 * applicable edit here" and leaves the field untouched.
 */
fun TextFieldState.applyEdit(transform: (TextFieldValue) -> TextFieldValue?) {
    val before = TextFieldValue(text.toString(), selection)
    val after = transform(before) ?: return
    edit {
        replace(0, length, after.text)
        selection = after.selection
    }
}
