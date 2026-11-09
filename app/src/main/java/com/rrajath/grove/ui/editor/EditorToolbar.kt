package com.rrajath.grove.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDotBadge
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.grove
import java.time.LocalDateTime

/**
 * Formatting toolbar shared by the regular note editor and quick capture, so
 * both editing surfaces offer the same bold/italic/underline/code/link/etc.
 * buttons (design spec §6).
 */

@Composable
fun EditorToolbar(
    onWrap: (Char) -> Unit,
    onInsert: (String) -> Unit,
    onLink: () -> Unit,
    onHeading: () -> Unit,
    onIndent: (Int) -> Unit,
    /**
     * Long-press on the `[[]]` button. Supplied by the note editor and Dailies,
     * where it opens the file/heading link picker; null elsewhere (the button is
     * then tap-only).
     */
    onLinkLongPress: (() -> Unit)? = null,
    /**
     * Long-press on the clock button. Supplied by the note editor and Dailies,
     * where it opens the Insert Timestamp picker instead of inserting directly;
     * null elsewhere (the button falls back to inserting a date+time stamp).
     */
    onTimestampLongPress: (() -> Unit)? = null,
    /**
     * Opens the attach sheet (experimental attachments). When null (scoped
     * region editors) there's no paperclip and the row keeps
     * its equal-width layout; when set, the row switches to fixed-width
     * buttons in a horizontal scroll (1c), with the paperclip after `[[]]`.
     */
    onAttach: (() -> Unit)? = null,
) {
    val c = MaterialTheme.grove
    val timestampLongPress = onTimestampLongPress ?: {
        val now = LocalDateTime.now()
        onInsert(
            OrgTimestamp(
                now.toLocalDate(),
                time = now.toLocalTime().withSecond(0).withNano(0),
                active = false,
            ).format()
        )
    }
    val insertDate = { onInsert(OrgTimestamp(LocalDateTime.now().toLocalDate(), active = false).format()) }

    if (onAttach != null) {
        // 1c: fixed 38x34dp buttons in a scrolling row. At typical widths the last
        // indent button is clipped at the edge, which is the cue that it scrolls.
        val fixed = Modifier.size(width = 38.dp, height = 34.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(c.surface)
                .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp)
                .testTag("editor_toolbar"),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(icon = R.drawable.ic_format_bold, color = c.ink, modifier = fixed) { onWrap('*') }
            ToolButton(icon = R.drawable.ic_format_italic, color = c.ink, modifier = fixed) { onWrap('/') }
            ToolButton(icon = R.drawable.ic_format_underlined, color = c.ink, modifier = fixed) { onWrap('_') }
            ToolButton(icon = R.drawable.ic_code, color = c.ink, modifier = fixed) { onWrap('~') }
            ToolButton(icon = R.drawable.ic_check_box, color = c.ink, modifier = fixed) { onInsert("\n- [ ] ") }
            // The thin, 45-degree paperclip reads small next to the square
            // glyphs at the shared 20dp, so 22dp matches the checkbox's visual
            // height. The Box centers it on both axes like every other button.
            NewDotBadge(NewAnchors.EDITOR_ATTACH, ringColor = c.surface) {
                ToolButton(
                    icon = R.drawable.ic_attach_file, color = c.ink, iconSize = 22.dp,
                    contentDescription = "Attach",
                    modifier = fixed.testTag("editor_attach"),
                    onClick = onAttach,
                )
            }
            ToolButton("[[]]", c.synLink, fixed, onLongClick = onLinkLongPress, onClick = onLink)
            ToolButton("◷", c.synTs, fixed, fontSize = 27.sp, onLongClick = timestampLongPress, onClick = insertDate)
            ToolButton("*", c.synStar, fixed, bold = true) { onHeading() }
            ToolButton("«", c.ink, fixed) { onIndent(-1) }
            ToolButton("»", c.ink, fixed) { onIndent(+1) }
        }
        return
    }

    // Each button gets equal width (RowScope.weight) so the row always fits
    // the screen exactly, with no scrolling and identical spacing between
    // every icon regardless of how wide its glyph happens to be.
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .border(1.dp, c.line)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(icon = R.drawable.ic_format_bold, color = c.ink, modifier = Modifier.weight(1f)) { onWrap('*') }
        ToolButton(icon = R.drawable.ic_format_italic, color = c.ink, modifier = Modifier.weight(1f)) { onWrap('/') }
        ToolButton(icon = R.drawable.ic_format_underlined, color = c.ink, modifier = Modifier.weight(1f)) { onWrap('_') }
        ToolButton(icon = R.drawable.ic_code, color = c.ink, modifier = Modifier.weight(1f)) { onWrap('~') }
        ToolButton(icon = R.drawable.ic_check_box, color = c.ink, modifier = Modifier.weight(1f)) { onInsert("\n- [ ] ") }
        LinkToolButton(onLink = onLink, onLongPress = onLinkLongPress)
        // The clock glyph is drawn smaller than the letters at a given size, so
        // bump its font so it reads at the same height as the other buttons.
        // Tap inserts an inactive date-only stamp; long-press adds the time
        // (HH:MM), both inactive, since this button is for logging a moment
        // rather than scheduling an active org agenda entry.
        ToolButton("◷", c.synTs, Modifier.weight(1f), fontSize = 27.sp, onLongClick = timestampLongPress, onClick = insertDate)
        ToolButton("*", c.synStar, Modifier.weight(1f), bold = true) { onHeading() }
        // List indent: « promotes a sub-list item, » demotes into a sub-list.
        ToolButton("«", c.ink, Modifier.weight(1f)) { onIndent(-1) }
        ToolButton("»", c.ink, Modifier.weight(1f)) { onIndent(+1) }
    }
}

/**
 * The `[[]]` button. Tap inserts a link at the cursor (an `[[https://]]`
 * scaffold when text is selected, the neutral `[[link][description]]` template
 * otherwise); [onLongPress], when supplied, opens the file/heading picker.
 */
@Composable
private fun RowScope.LinkToolButton(
    onLink: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val c = MaterialTheme.grove
    ToolButton(
        "[[]]", c.synLink, Modifier.weight(1f),
        onLongClick = onLongPress,
        onClick = onLink,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        // autoSize shrinks the glyph to whatever fits the button's equal-width
        // slot, and maxLines/softWrap keep multi-char labels ("</>", "[[]]")
        // from wrapping onto a second line on narrow screens.
        BasicText(
            label,
            style = TextStyle(
                fontFamily = PlexMono,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (underline) TextDecoration.Underline else null,
                color = color,
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = fontSize),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    icon: Int,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Wrap the selection in emphasis markers, or insert a pair at the cursor. */
internal fun wrapSelection(value: TextFieldValue, marker: Char): TextFieldValue {
    val sel = value.selection
    val text = value.text
    return if (sel.collapsed) {
        val insert = "$marker$marker"
        TextFieldValue(
            text.substring(0, sel.start) + insert + text.substring(sel.start),
            TextRange(sel.start + 1),
        )
    } else {
        val selected = text.substring(sel.min, sel.max)
        TextFieldValue(
            text.substring(0, sel.min) + marker + selected + marker + text.substring(sel.max),
            TextRange(sel.max + 2),
        )
    }
}

internal fun insertAtCursor(value: TextFieldValue, snippet: String): TextFieldValue {
    val at = value.selection.start
    return TextFieldValue(
        value.text.substring(0, at) + snippet + value.text.substring(at),
        TextRange(at + snippet.length),
    )
}

/**
 * Insert an org link template with named placeholders and pre-select "link" so
 * the user can type the URL over it, then move to "description". A non-empty
 * selection is consumed as the description instead of the "description"
 * placeholder, so e.g. selecting "my note" and tapping link yields
 * [[link][my note]] with "link" highlighted to type the URL over.
 */
internal fun insertLinkTemplate(value: TextFieldValue): TextFieldValue {
    val sel = value.selection
    val text = value.text
    val description = if (sel.collapsed) "description" else text.substring(sel.min, sel.max)
    val template = "[[link][$description]]"
    val linkStart = sel.min + 2 // just inside the opening "[["
    return TextFieldValue(
        text.substring(0, sel.min) + template + text.substring(sel.max),
        TextRange(linkStart, linkStart + "link".length),
    )
}
