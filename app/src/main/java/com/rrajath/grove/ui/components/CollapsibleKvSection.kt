package com.rrajath.grove.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.grove

/**
 * Collapsible, faded (66% alpha), monospace key-value section for file-level
 * `#+` keyword lines or a `:PROPERTIES:` drawer (design/Grove.dc.html lines
 * 499-552, style block at 1682+). The header row expands/collapses; the body is
 * hidden unless [expanded]. Display-only by default: never mutates the source
 * file, unless the caller opts in via [onDoubleTap] (e.g. Outline's PREFACE and
 * file-level `:PROPERTIES:` sections, which double-tap-open a scoped editor;
 * see [doubleTapToEdit]).
 */
@Composable
fun CollapsibleKvSection(
    label: String,
    entries: List<Pair<String, String>>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Double-tapping anywhere on a key/value row (not the header) opens an editor scoped to
     *  this section. Null (default) leaves the section purely display-only. */
    onDoubleTap: (() -> Unit)? = null,
) {
    val c = MaterialTheme.grove
    CollapsibleDrawer(label, entries.size, expanded, onToggle, modifier) {
        entries.forEach { (key, value) ->
            // The whole row is the double-tap target (not just the glyphs): these
            // rows carry no inline links, so the gesture takes a null layout.
            // The row stays LTR (the Latin key comes first, as in Emacs); only the
            // value takes its own direction, so an RTL value's punctuation and digits
            // land on the right side.
            Row(Modifier.doubleTapEditRow(onDoubleTap)) {
                KvText("$key ", color = c.synKw)
                KvText(value, color = c.ink2, direction = TextDirection.Content)
            }
        }
    }
}

/** A single key or value run within [CollapsibleKvSection]; the enclosing row owns the double-tap. */
@Composable
private fun KvText(text: String, color: Color, direction: TextDirection = TextDirection.Unspecified) {
    Text(
        text, style = TextStyle(textDirection = direction),
        fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 1.5.em, color = color,
    )
}

/** Whole-row double-tap-to-edit for a link-free drawer line; no-op when [onDoubleTap] is null. */
private fun Modifier.doubleTapEditRow(onDoubleTap: (() -> Unit)?): Modifier =
    this
        .fillMaxWidth()
        .doubleTapToEdit(
            layoutResult = { null },
            enabled = onDoubleTap != null,
            onDoubleTap = { onDoubleTap?.invoke() },
        )

/**
 * Collapsible, faded, monospace section for a `:LOGBOOK:` drawer, same
 * header/body chrome as [CollapsibleKvSection], but each line is raw log text
 * (state changes, CLOCK entries) rather than a `:key: value` pair.
 */
@Composable
fun CollapsibleLogSection(
    label: String,
    lines: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Double-tapping anywhere on a log line (not the header) opens an editor scoped to this
     *  drawer. Null (default) leaves the section purely display-only. */
    onDoubleTap: (() -> Unit)? = null,
) {
    val c = MaterialTheme.grove
    CollapsibleDrawer(label, lines.size, expanded, onToggle, modifier) {
        // Each line takes its own direction: CLOCK/state lines start Latin and stay
        // LTR, a Persian note body reads RTL and aligns right (the row is full width).
        lines.forEach { line ->
            Text(
                line.trim(),
                style = TextStyle(textDirection = TextDirection.Content),
                fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 1.5.em, color = c.ink2,
                modifier = Modifier.doubleTapEditRow(onDoubleTap),
            )
        }
    }
}

/**
 * Collapsible, faded, monospace section for a `#+BEGIN_x … #+END_x` block
 * (`QUOTE`, `SRC`, `EXAMPLE`, `VERSE`, `LATEX`, a custom name, …). Same
 * header/body chrome as [CollapsibleKvSection]; [label] is the upper-cased block
 * kind and [trailingLabel] (e.g. a `SRC` language) sits next to it. [affiliated]
 * are the folded-in `#+ATTR_*` / `#+CAPTION:` / `#+NAME:` lines, shown dimmer
 * above the content. Unlike the drawer sections this opens **expanded**: block
 * content is note content, not metadata.
 */
@Composable
fun CollapsibleBlockSection(
    label: String,
    trailingLabel: String?,
    affiliated: List<String>,
    contentLines: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Double-tapping anywhere in the block body opens an editor scoped to this block. */
    onDoubleTap: (() -> Unit)? = null,
) {
    val c = MaterialTheme.grove
    CollapsibleDrawer(
        label = label,
        count = contentLines.size,
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
        trailingLabel = trailingLabel,
    ) {
        affiliated.forEach { line ->
            Text(
                line.trim(),
                fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 1.5.em, color = c.ink3,
                modifier = Modifier.doubleTapEditRow(onDoubleTap),
            )
        }
        // Prose blocks (QUOTE, CENTER, ...) take each line's direction from its first
        // strong character, full width so an RTL line aligns right. Code stays LTR.
        val contentStyle = if (label == "SRC" || label == "EXAMPLE") TextStyle.Default
        else TextStyle(textDirection = TextDirection.Content)
        contentLines.forEach { line ->
            Text(
                line,
                style = contentStyle,
                fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 1.5.em, color = c.ink2,
                modifier = Modifier.fillMaxWidth().doubleTapEditRow(onDoubleTap),
            )
        }
    }
}

@Composable
private fun CollapsibleDrawer(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = MaterialTheme.grove
    val caretRotation by animateFloatAsState(if (expanded) 90f else 0f, label = "collapsibleCaret")
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .alpha(0.66f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "▸",
                fontFamily = PlexMono, fontSize = 10.sp, color = c.ink3,
                modifier = Modifier.width(10.dp).rotate(caretRotation),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3)
            if (trailingLabel != null) {
                Spacer(Modifier.width(8.dp))
                Text(trailingLabel, fontFamily = PlexMono, fontSize = 11.sp, color = c.synKw)
            }
            Spacer(Modifier.weight(1f))
            Text(count.toString(), fontFamily = PlexMono, fontSize = 11.sp, color = c.ink3)
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 30.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                body()
            }
        }
    }
}
