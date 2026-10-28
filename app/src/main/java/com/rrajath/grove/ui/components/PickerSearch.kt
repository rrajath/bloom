package com.rrajath.grove.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.editor.LinkFileHit
import com.rrajath.grove.ui.editor.LinkHeadingHit
import com.rrajath.grove.ui.editor.LinkSearchItem
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.pluralCount

/**
 * The live "search files and headings" field shared by the insert-link picker
 * and the refile picker: a 40dp `surface2` pill with a leading search glyph, a
 * PlexMono query, a PlexSans `ink3` placeholder and a trailing clear `×` once
 * something is typed. The border is `accent` while focused, `line` otherwise.
 * [placeholder] overrides the default "Search files and headings" (a picker
 * scoped to one file says "Search headings").
 */
@Composable
fun PickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    placeholder: String? = null,
) {
    val c = MaterialTheme.grove
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.surface2)
            .border(1.dp, if (focused) c.accent else c.line, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(searchIcon(), contentDescription = null, tint = c.ink3, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = PlexMono, fontSize = 13.5.sp, color = c.ink),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused },
            )
            if (query.isEmpty()) {
                Text(
                    placeholder ?: stringResource(R.string.picker_search_placeholder),
                    fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink3,
                )
            }
        }
        if (query.isNotEmpty()) {
            Text(
                "×", fontFamily = PlexMono, fontSize = 15.sp, color = c.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onQueryChange("") }
                    .padding(4.dp),
            )
        }
    }
}

/** Small-caps "FILES & HEADINGS" label (or [text]) shown above live search results. */
@Composable
fun PickerSearchHeader(text: String? = null) {
    val c = MaterialTheme.grove
    Text(
        text ?: stringResource(R.string.picker_search_results_header),
        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp, letterSpacing = 0.09.em, color = c.ink3,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/**
 * Live search results as [PickerRow]s: a notebook icon + "file" crumb for a
 * whole file, the brand asterisk + breadcrumb for a heading, and a single
 * "No matches" line when [results] is empty. [headingTag] puts a small
 * muted tag (e.g. "ID") after a heading hit's title.
 */
fun LazyListScope.pickerSearchResults(
    results: List<LinkSearchItem>,
    onClick: (LinkSearchItem) -> Unit,
    headingTag: (LinkHeadingHit) -> String? = { null },
) {
    if (results.isEmpty()) {
        item(key = "picker-search-empty") {
            val c = MaterialTheme.grove
            Text(
                stringResource(R.string.picker_search_no_matches),
                fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
    items(
        results,
        key = { hit ->
            when (hit) {
                is LinkFileHit -> "file:${hit.fileName}"
                is LinkHeadingHit -> "heading:${hit.fileName}:${hit.lineIndex}"
            }
        },
    ) { hit ->
        when (hit) {
            is LinkFileHit -> PickerRow(
                icon = notebookIcon(),
                title = hit.title,
                crumb = stringResource(R.string.picker_search_file_crumb),
                meta = hit.noteCount.takeIf { it > 0 }?.let { pluralCount(it, "heading") },
                onClick = { onClick(hit) },
            )
            is LinkHeadingHit -> PickerRow(
                asterisk = true,
                title = hit.title,
                titleTag = headingTag(hit),
                crumb = hit.crumb,
                meta = stringResource(R.string.picker_search_heading_meta),
                onClick = { onClick(hit) },
            )
        }
    }
}

/**
 * One picker list row: 12dp radius, 1dp `line` border, a leading [icon] or
 * brand [asterisk] in `accent`, a PlexMono title over an optional PlexSans
 * [crumb], and an optional trailing [meta] label. [titleTag] follows the
 * title as a small muted tag (see [HeadingTag]).
 */
@Composable
fun PickerRow(
    asterisk: Boolean = false,
    icon: ImageVector? = null,
    title: String,
    titleTag: String? = null,
    crumb: String? = null,
    meta: String?,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
        } else if (asterisk) {
            BrandMarkGlyph(size = 10.dp, color = c.accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, fontFamily = PlexMono, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                titleTag?.let { HeadingTag(it, Modifier.padding(start = 7.dp).alignByBaseline()) }
            }
            crumb?.let {
                Text(it, fontFamily = PlexSans, fontSize = 11.sp, color = c.ink3)
            }
        }
        meta?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink3)
        }
    }
}

/**
 * A small muted tag after a heading's title, e.g. "ID" on a heading that has an
 * `:ID:` / `:CUSTOM_ID:` (capture template heading picker): PlexMono 10.5sp
 * SemiBold `ink3`. Callers baseline-align it with the title (`alignByBaseline`
 * on both): centered, its caps sit visibly above the title's lowercase.
 */
@Composable
fun HeadingTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp, color = MaterialTheme.grove.ink3,
        maxLines = 1,
        modifier = modifier,
    )
}
