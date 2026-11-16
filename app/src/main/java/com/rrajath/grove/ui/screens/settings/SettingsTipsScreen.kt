package com.rrajath.grove.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.StringSetSaver

/**
 * Settings § Help → Tips & tricks (design/Grove.dc.html lines 1820-1863). A
 * read-only list of accordion rows grouped into sections: each row is an icon
 * tile + title that expands on tap to reveal a short body and, for a couple of
 * tips, a numbered step list. The prototype's textual "Expand all"/"Collapse
 * all" pill is replaced here with the outline view's fold/unfold icon action
 * (`UnfoldMore`/`UnfoldLess`): tap to expand every tip, tap again to collapse.
 *
 * Tip bodies and steps may embed `{{check}}`, `{{save}}`, `{{clock}}`, `{{star}}`
 * or `{{link}}` markers; [TipText] renders each as a small themed keycap of the
 * exact glyph the app shows for that control (see [tipGlyphContent]). `{{readlink}}`
 * renders a non-interactive sample of a read-mode link; a bare checkbox literal
 * (`[ ]` / `[-]` / `[X]`) is set in `PlexMono`; and bare org link syntax in the
 * copy (`[[link]]`, `[[link][description]]`, `https://`) is set in `PlexMono`
 * `synLink`, matching the editor.
 */
@Composable
fun SettingsTipsScreen(onBack: () -> Unit) {
    val c = MaterialTheme.grove
    val appName = stringResource(R.string.app_name)
    val context = LocalContext.current
    val groups = remember(appName) { tipGroups(appName, context::getString) }
    val allIds = remember(groups) { groups.flatMap { g -> g.tips.map { it.id } }.toSet() }
    var openIds by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet()) }
    val allExpanded = allIds.isNotEmpty() && openIds.containsAll(allIds)

    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = { IconGlyph("←", onClick = onBack) },
                title = {
                    Text(
                        "Tips & Tricks",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.ink,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
                actions = {
                    IconGlyph(
                        icon = if (allExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                        contentDescription = if (allExpanded) "Collapse all tips" else "Expand all tips",
                        onClick = { openIds = if (allExpanded) emptySet() else allIds },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 40.dp),
        ) {
            Text(
                "A handful of gestures and shortcuts the interface doesn't spell out. Tap a tip to read it.",
                fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 1.55.em, color = c.ink2,
                modifier = Modifier.padding(start = 4.dp, bottom = 20.dp),
            )

            groups.forEach { group ->
                // Leaving this screen retires any NEW dot whose destination is
                // one of these groups (so the header dot shows for the visit).
                MarkNewFeatureSeen(NewAnchors.tipsGroup(group.id))
                SectionLabel(group.label.uppercase()) {
                    NewDot(NewAnchors.tipsGroup(group.id))
                }
                SettingsGroup {
                    group.tips.forEachIndexed { i, tip ->
                        if (i > 0) RowDivider()
                        // Same "clears on exit" treatment as the group dot, but scoped
                        // to this one row so it doesn't retire until this tip is seen.
                        tip.newAnchor?.let { MarkNewFeatureSeen(it) }
                        TipRow(
                            tip = tip,
                            open = tip.id in openIds,
                            onToggle = {
                                openIds = if (tip.id in openIds) openIds - tip.id else openIds + tip.id
                            },
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun TipRow(tip: Tip, open: Boolean, onToggle: () -> Unit) {
    val c = MaterialTheme.grove
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "tipChevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tip.icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(13.dp))
            Text(
                tip.title,
                fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp, lineHeight = 1.35.em, color = c.ink,
                modifier = Modifier.weight(1f),
            )
            tip.newAnchor?.let {
                Spacer(Modifier.width(8.dp))
                NewDot(it)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "▾",
                fontFamily = PlexMono, fontSize = 13.sp, color = c.ink3,
                modifier = Modifier.rotate(rotation),
            )
        }
        AnimatedVisibility(visible = open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 62.dp, end = 16.dp, bottom = 15.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                TipText(tip.body, color = c.ink2, lineHeight = 1.62.em)
                if (tip.steps.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tip.steps.forEachIndexed { idx, step ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(19.dp)
                                        .clip(CircleShape)
                                        .background(c.surface2),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${idx + 1}",
                                        fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp, color = c.ink2,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                TipText(step, color = c.ink, lineHeight = 1.5.em)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Android's ICU regex engine rejects a bare `}` or `]` (PatternSyntaxException at
// class-init) even though the JVM accepts them, so every brace and bracket is
// escaped. Alternatives, in order: the `{{…}}` keycap markers; `{{readlink}}`,
// which renders the word "link" the way read mode paints a link; the two drawer
// names; a bare checkbox literal (`[ ]` / `[-]` / `[X]`); and bare org link
// syntax — a `https://` scheme or a `[[target]]` / `[[target][description]]`
// bracket pair; a backtick code span; and a `{{file:name}}` file name.
private val TIP_MARKUP_RE = Regex(
    "\\{\\{(check|save|clock|star|link|menu)\\}\\}" +
        "|\\{\\{readlink\\}\\}" +
        "|:PROPERTIES:|:LOGBOOK:" +
        "|\\[ \\]|\\[-\\]|\\[X\\]" +
        "|https://" +
        "|\\[\\[[^\\[\\]]*\\](?:\\[[^\\[\\]]*\\])?\\]" +
        "|`[^`]+`" +
        "|\\{\\{file:[^\\}]+\\}\\}",
)

/**
 * A 13sp `PlexSans` paragraph with its markup expanded: `{{check}}` / `{{save}}`
 * / `{{clock}}` / `{{star}}` / `{{link}}` become inline keycaps (see
 * [tipGlyphContent]); `{{readlink}}` becomes the word "link" in `synLink` +
 * underline — a non-interactive sample of read mode's own link styling; a
 * literal `:PROPERTIES:` / `:LOGBOOK:` is set in `PlexMono` `synProp` (the
 * drawer-name treatment the editor and drawers use); a bare checkbox literal
 * (`[ ]` / `[-]` / `[X]`) is set in `PlexMono` at the paragraph color so the
 * brackets align and read cleanly; and bare link syntax (`https://`, `[[link]]`,
 * `[[link][description]]`) is set in `PlexMono` `synLink`, matching how the
 * editor colors it. A backtick code span (`` `#+ARCHIVE:` ``) drops its
 * backticks and is set in `PlexMono` at the paragraph color; `{{file:name}}`
 * renders `name` in `PlexMono` `synTag`, so file names stand apart from syntax.
 */
@Composable
private fun TipText(text: String, color: Color, lineHeight: TextUnit) {
    val c = MaterialTheme.grove
    val annotated = remember(text, c.synProp, c.synLink, c.synTag) {
        buildAnnotatedString {
            var last = 0
            TIP_MARKUP_RE.findAll(text).forEach { m ->
                append(text.substring(last, m.range.first))
                val glyph = m.groupValues[1]
                when {
                    glyph.isNotEmpty() -> appendInlineContent(glyph, m.value)
                    // A display-only echo of read mode's link paint. No
                    // LinkAnnotation, so a tap just toggles the row like any
                    // other tip text; it never navigates.
                    m.value == "{{readlink}}" ->
                        withStyle(SpanStyle(color = c.synLink, textDecoration = TextDecoration.Underline)) {
                            append("link")
                        }
                    m.value.startsWith(":") ->
                        withStyle(SpanStyle(fontFamily = PlexMono, color = c.synProp)) { append(m.value) }
                    m.value.startsWith("`") ->
                        withStyle(SpanStyle(fontFamily = PlexMono)) { append(m.value.trim('`')) }
                    m.value.startsWith("{{file:") ->
                        withStyle(SpanStyle(fontFamily = PlexMono, color = c.synTag)) {
                            append(m.value.removePrefix("{{file:").removeSuffix("}}"))
                        }
                    m.value == "[ ]" || m.value == "[-]" || m.value == "[X]" ->
                        withStyle(SpanStyle(fontFamily = PlexMono)) { append(m.value) }
                    else ->
                        withStyle(SpanStyle(fontFamily = PlexMono, color = c.synLink)) { append(m.value) }
                }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(
        annotated,
        fontFamily = PlexSans, fontSize = 13.sp, lineHeight = lineHeight, color = color,
        inlineContent = tipGlyphContent(),
    )
}

/**
 * The inline keycaps used in tip copy, one per control the tips point at, each
 * drawn as the exact glyph that control uses in the app so it's recognisable:
 * the green [Check] sync tick from the Notebooks bar, the green [Save] icon from
 * the editor top bar, and the `PlexMono` clock / asterisk / `[[]]` buttons from
 * the formatting toolbar (`EditorToolbar`, tinted `synTs` / `synStar` / `accent`).
 * Every keycap sits on a `surface2` chip and reads at roughly the surrounding
 * 13sp text size; the `[[]]` cap is wider to hold its four characters.
 */
@Composable
private fun tipGlyphContent(): Map<String, InlineTextContent> {
    val c = MaterialTheme.grove
    fun chip(width: TextUnit = 2.0.em, inner: @Composable BoxScope.() -> Unit) = InlineTextContent(
        Placeholder(width = width, height = 1.7.em, PlaceholderVerticalAlign.TextCenter),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 1.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(c.surface2)
                .padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
            content = inner,
        )
    }
    return mapOf(
        "check" to chip { Icon(Icons.Default.Check, contentDescription = null, tint = c.green, modifier = Modifier.size(14.dp)) },
        "save" to chip { Icon(Icons.Outlined.Save, contentDescription = null, tint = c.green, modifier = Modifier.size(14.dp)) },
        // The clock and asterisk are text glyphs, and a bare Text puts them low in
        // its line box; trimmed line metrics + no font padding center the glyph in
        // the chip. The clock also renders small for its size, so it runs larger.
        "clock" to chip { GlyphText("◷", 17.sp, c.synTs, bold = false) },
        "star" to chip { GlyphText("*", 15.sp, c.synStar, bold = true) },
        // The toolbar's link button, `[[]]` — four characters, so a wider cap,
        // in the app's accent so it reads as a button next to the mono link text.
        "link" to chip(width = 3.5.em) { GlyphText("[[]]", 12.5.sp, c.accent, bold = false) },
        // The top bar's overflow menu button.
        "menu" to chip { GlyphText("⋮", 15.sp, c.ink2, bold = true) },
    )
}

/** A single centered glyph for [tipGlyphContent]'s keycaps: font padding stripped
 *  and the line height trimmed to the glyph so `Box`'s centering actually lands. */
@Composable
private fun GlyphText(glyph: String, size: TextUnit, color: Color, bold: Boolean) {
    Text(
        glyph,
        style = TextStyle(
            fontFamily = PlexMono,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = size,
            color = color,
            lineHeight = size,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

private data class Tip(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val steps: List<String> = emptyList(),
    val newAnchor: String? = null,
)

private data class TipGroup(val id: String, val label: String, val tips: List<Tip>)

/**
 * Tip content, reworded from the author's notes in design/Grove.dc.html
 * `tipVals()` (lines 3835-3868); the "Links" group is new to the app and has no
 * prototype entry. `{{…}}` markers become inline keycaps. [appName] is
 * `R.string.app_name`, so the copy never hardcodes the app's name. [str] resolves string
 * resources: newer tips (the Outline group) live in `strings.xml` so they can be translated.
 */
private fun tipGroups(appName: String, str: (Int) -> String): List<TipGroup> = listOf(
    TipGroup(
        "sync-saving",
        "Sync & saving",
        listOf(
            Tip(
                "sync", Icons.Default.CloudDone, "View the last synced time",
                "On the Notebooks screen, tap the {{check}} checkmark in the top bar to see when your vault last synced.",
            ),
            Tip(
                "auto", Icons.Default.Autorenew, "Autosave, and two ways to save now",
                "The editor saves on its own about five seconds after you stop typing. Two things also save it right away:",
                listOf("Tap the {{save}} save icon in the top bar", "Switch to Read mode"),
            ),
            Tip(
                "saved", Icons.Default.Schedule, "Check when a note was last saved",
                "While a note has unsaved changes the {{save}} save icon is highlighted; tap it to save now. Once it turns " +
                    "grey the note matches what's on disk, and tapping it then shows the time of the last save.",
            ),
        ),
    ),
    TipGroup(
        "writing",
        "Writing",
        listOf(
            Tip(
                "date", Icons.Default.EditCalendar, "Insert today's date or time",
                "The {{clock}} button on the formatting toolbar drops an inactive timestamp for today at the cursor. " +
                    "Long-press it to open the date and time picker, prefilled with right now.",
                newAnchor = NewAnchors.tipsItem("writing", "date"),
            ),
            Tip(
                "stars", Icons.Default.FormatSize, "Cycle heading levels with the asterisk",
                "The {{star}} button on the formatting toolbar starts a new top-level heading below the cursor. " +
                    "Each further tap demotes that heading one more level.",
            ),
            Tip(
                "dbl", Icons.Default.TouchApp, "Double-tap to edit while reading",
                "Double-tap anywhere in a note you're reading to drop straight into edit mode. It only works that " +
                    "direction, so a stray tap in the editor won't send you back.",
            ),
            Tip(
                "drawers", Icons.Default.EditNote, "Edit blocks and drawers in place",
                "The preface keyword block and any :PROPERTIES: or :LOGBOOK: drawer each open in their own small " +
                    "editor. Double-tap a key/value row to edit just that block.",
            ),
            Tip(
                "block-shorthand", Icons.Default.FormatQuote, "Insert a quote, example or src block",
                "At the start of a line or after a space, type a shorthand (any case). A chip above the keyboard " +
                    "offers to swap it for the block, with the cursor inside:",
                listOf(
                    "<q for #+BEGIN_QUOTE",
                    "<e for #+BEGIN_EXAMPLE",
                    "<s for #+BEGIN_SRC, with the cursor ready for the language",
                ),
                newAnchor = NewAnchors.tipsItem("writing", "block-shorthand"),
            ),
            Tip(
                "drawer-shorthand", Icons.Default.DataObject, "Insert a properties or logbook drawer",
                "Type :PRO or :LOG (any case) and tap the chip above the keyboard to insert a :PROPERTIES: or " +
                    ":LOGBOOK: drawer, closed by :END:, with the cursor on its empty line. The chip stays up as " +
                    "you type more of the word.",
                newAnchor = NewAnchors.tipsItem("writing", "drawer-shorthand"),
            ),
        ),
    ),
    TipGroup(
        "outline",
        str(R.string.tips_group_outline),
        listOf(
            Tip(
                "outline-view-file", Icons.Default.Description, str(R.string.tip_outline_view_file_title),
                str(R.string.tip_outline_view_file_body),
                newAnchor = NewAnchors.tipsItem("outline", "outline-view-file"),
            ),
        ),
    ),
    TipGroup(
        "checklists",
        "Checklists",
        listOf(
            Tip(
                "checkbox-tap", Icons.Default.CheckBox, "Tap and hold a checkbox in Read mode",
                "In Read mode, a checklist box responds to two gestures:",
                listOf(
                    "Tap to toggle it done. An empty or in-progress box becomes [X]; a done box goes back to [ ]",
                    "Tap and hold (with a short buzz) to mark it in-progress [-]. Hold again to clear it back to [ ]",
                ),
            ),
        ),
    ),
    TipGroup(
        "links",
        "Links",
        listOf(
            Tip(
                "link-follow", Icons.Default.Link, "Tap a link to follow it",
                "In Read mode, tap any {{readlink}} to jump to its target. A link to a heading opens that " +
                        "heading; a link to a whole file opens that file's outline. Web, email and phone links " +
                        "open in the matching app. If nothing matches the link, a short message tells you and " +
                        "you stay where you are.",
            ),
            Tip(
                "link-build", Icons.Default.AddLink, "Let the toolbar write links for you",
                "The {{link}} button on the formatting toolbar builds the link syntax so you never type the brackets.",
                listOf(
                    "Copy a link first, then select some text and tap {{link}}: the copied link becomes the target and your text becomes the label, nothing to type",
                    "No link copied? Select text and tap {{link}} for a https:// scaffold, then just paste your URL over it, https:// and all",
                    "Tap with nothing selected to drop in an empty [[link][description]] template",
                    "Long-press {{link}} to search the whole vault live and link straight to a file or a heading",
                ),
            ),
            Tip(
                "link-picker", Icons.Default.Search, "Search the vault to build a link",
                "Long-pressing {{link}} opens a full-height picker with the search box already focused, starting at your notebook list.",
                listOf(
                    "Type to filter files and headings live across the whole vault, or browse without typing by tapping a notebook to drill into its headings.",
                    "Tapping a heading with no sub-headings links it right away; only a heading with sub-headings drills in further."
                ),
                newAnchor = NewAnchors.tipsItem("links", "link-picker"),
            ),
            Tip(
                "link-id", Icons.Default.Tag, "Link by ID so it survives moves",
                "When a file or heading you pick has an ID (or CUSTOM_ID), $appName offers to link by that instead of by name. " +
                        "Choose it: an ID link keeps working after the target is renamed, moved, or refiled into " +
                        "another file. To put an ID on every new heading automatically, turn on Settings › Notes › " +
                        "Add ID to new notes.",
            ),
        ),
    ),
    TipGroup(
        "search",
        "Search",
        listOf(
            Tip(
                "swipe", Icons.Default.SwapHoriz, "Swipe search results",
                "A search result that's a TODO heading can be swiped. Swipe right to change its TODO state or add a " +
                    "note; swipe left to schedule it.",
            ),
            Tip(
                "queries", Icons.Default.StarBorder, "Change your quick-start and saved queries",
                "The quick-start cards and saved searches aren't fixed. Tap the star beside the search box to point a " +
                    "slot at a different saved query, or to save a new one.",
            ),
        ),
    ),
    TipGroup(
        "capture-automation",
        "Capture & automation",
        listOf(
            Tip(
                "launcher", Icons.Default.Apps, "Capture from the home-screen icon",
                "Long-press $appName's home-screen icon to open a capture template without going through the app first.",
            ),
            Tip(
                "archive", Icons.Default.Archive, "Auto-archive tasks when they're done",
                "Turn on auto-archive in Settings › Notes and a task refiles itself to your archive location the moment " +
                    "it's marked done, unless the file or heading already points somewhere else.",
            ),
            Tip(
                "archive-location", Icons.Default.Inventory2, "Choose where a file archives to",
                "Set an `ARCHIVE` property on a heading or in the file's top properties drawer, or a `#+ARCHIVE:` line. " +
                    "`%s` stands for the current file, so `%s_archive::` archives {{file:agenda.org}} into " +
                    "{{file:agenda.org_archive}}. " +
                    "The nearest one wins over the Settings location.",
                newAnchor = NewAnchors.tipsItem("capture-automation", "archive-location"),
            ),
        ),
    ),
)
