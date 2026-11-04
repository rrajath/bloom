package com.rrajath.grove.ui.attach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachmentLink
import com.rrajath.grove.attach.LocalAttachment
import com.rrajath.grove.attach.linkDescription
import com.rrajath.grove.org.InlineTokenizer
import com.rrajath.grove.org.InlineType
import com.rrajath.grove.search.Snippets
import com.rrajath.grove.ui.editor.categoryColors
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An attachment link drawn as a preview-size chip (1i outline, 1j search):
 * [range] is the span of the preview text the chip replaces.
 */
@Immutable
data class PreviewChip(val range: IntRange, val rawTarget: String, val link: LocalAttachment)

/** A line as search shows it, plus where its attachment chips sit in that text. */
data class ChipLine(val text: String, val chips: List<PreviewChip>)

/** Every local-attachment link in raw org [text] as a chip over its link syntax. */
fun rawPreviewChips(text: String): List<PreviewChip> =
    chipLinksIn(text).map { PreviewChip(it.range, it.rawTarget, it.link) }

/**
 * [line] rendered the way search snippets read (links show their label, markup
 * dropped), trimmed, with each attachment link's span: the chip label stands in
 * for the link so a term can match inside it.
 */
fun renderedWithChips(line: String): ChipLine {
    val out = StringBuilder()
    val chips = mutableListOf<PreviewChip>()
    for (token in InlineTokenizer.tokenize(line)) {
        val target = token.target
        val link = if (token.type == InlineType.LINK && target != null) {
            AttachmentLink.classify(target, linkDescription(line, token.range, token.text))
        } else {
            null
        }
        if (target != null && link != null && link.label.isNotEmpty()) {
            val start = out.length
            out.append(link.label)
            chips += PreviewChip(start until out.length, target, link)
        } else {
            out.append(token.text)
        }
    }
    val raw = out.toString()
    val text = raw.trim()
    val lead = raw.length - raw.trimStart().length
    return ChipLine(
        text,
        chips.map { it.copy(range = (it.range.first - lead)..(it.range.last - lead)) }
            .filter { it.range.first >= 0 && it.range.last < text.length },
    )
}

/**
 * The first [max] chars of [text], cut before a link rather than through it,
 * so a preview never shows half a chip (or half a link's syntax).
 */
fun linkSafeTake(text: String, max: Int): String {
    if (text.length <= max) return text
    if (!text.contains("[[")) return text.take(max)
    var cut = max
    for (token in InlineTokenizer.tokenize(text)) {
        if (token.type == InlineType.LINK && token.range.first < cut && token.range.last >= cut) {
            cut = token.range.first
        }
    }
    return text.take(cut)
}

/** A preview string with its chips swapped in as inline content. */
class PreviewChipText(val text: AnnotatedString, val inlineContent: Map<String, InlineTextContent>)

/**
 * [text] (already styled, e.g. with search highlights) with each of [chips]
 * replaced by a 1i/1j preview chip. Chips are display-only. No existence check
 * runs here: a chip shows missing only when read mode already found it missing
 * in [orgFile]. Matches of [terms] inside a chip label are highlighted in the chip.
 */
@Composable
fun rememberPreviewChipText(
    text: AnnotatedString,
    chips: List<PreviewChip>,
    orgFile: String,
    terms: List<String> = emptyList(),
): PreviewChipText {
    if (chips.isEmpty()) return remember(text) { PreviewChipText(text, emptyMap()) }
    val c = MaterialTheme.grove
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val cache = (LocalContext.current.applicationContext as? GroveApplication)?.attachmentStatusCache
    val generation by remember(cache) { cache?.generation ?: MutableStateFlow(0) }.collectAsState()
    return remember(text, chips, orgFile, terms, c, density, generation) {
        val sorted = chips.sortedBy { it.range.first }
        val inline = mutableMapOf<String, InlineTextContent>()
        val annotated = buildAnnotatedString {
            var pos = 0
            for (chip in sorted) {
                if (chip.range.first < pos || chip.range.last >= text.length) continue
                append(text.subSequence(pos, chip.range.first))
                val id = "preview-chip-${chip.range.first}"
                val label = chipLabel(chip.link.label)
                val styled = highlightedChipLabel(label, terms, c)
                val missing = cache?.knownMissing(orgFile, chip.rawTarget) == true
                val placeholder = with(density) {
                    val labelSize = measurer.measure(styled, PreviewLabelStyle).size
                    val lead = if (missing) {
                        4.dp.toPx() + 10.dp.toPx()
                    } else {
                        2.dp.toPx() + measurer.measure(chip.link.badge, PreviewBadgeStyle).size.width + 6.dp.toPx()
                    }
                    val width = lead + 4.dp.toPx() + labelSize.width + 6.dp.toPx()
                    Placeholder(width.toSp(), labelSize.height.toSp(), PlaceholderVerticalAlign.TextCenter)
                }
                inline[id] = InlineTextContent(placeholder) { PreviewAttachmentChip(chip.link, styled, missing) }
                appendInlineContent(id, label)
                pos = chip.range.last + 1
            }
            append(text.subSequence(pos, text.length))
        }
        PreviewChipText(annotated, inline)
    }
}

/** [label] with every match of [terms] in the search highlight. */
private fun highlightedChipLabel(label: String, terms: List<String>, c: GroveColors): AnnotatedString =
    buildAnnotatedString {
        append(label)
        Snippets.highlightRanges(label, terms).forEach { range ->
            addStyle(
                SpanStyle(color = c.amber, background = c.amberSoft, fontWeight = FontWeight.SemiBold),
                range.first, (range.last + 1).coerceAtMost(label.length),
            )
        }
    }

private val PreviewLabelStyle = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 1.4.em)
private val PreviewBadgeStyle = TextStyle(fontFamily = PlexMono, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)

/** The 1i/1j preview chip: the 1d chip at preview size. */
@Composable
private fun PreviewAttachmentChip(link: LocalAttachment, label: AnnotatedString, missing: Boolean) {
    val c = MaterialTheme.grove
    val (fg, bg) = categoryColors(c, link.category)
    Row(
        Modifier
            .offset(y = (-1).dp)
            .clip(RoundedCornerShape(5.dp))
            .background(c.surface2)
            .padding(start = if (missing) 4.dp else 2.dp, end = 6.dp)
            .then(if (missing) Modifier.alpha(0.8f) else Modifier)
            .testTag(if (missing) "preview_chip_missing" else "preview_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (missing) {
            Icon(
                painterResource(R.drawable.ic_attach_chip_missing),
                contentDescription = null,
                tint = c.ink3,
                modifier = Modifier.size(10.dp),
            )
        } else {
            Text(
                link.badge,
                style = PreviewBadgeStyle,
                color = fg,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(bg)
                    .padding(horizontal = 3.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = PreviewLabelStyle,
            color = if (missing) c.ink3 else c.ink,
            textDecoration = if (missing) TextDecoration.LineThrough else null,
            maxLines = 1,
        )
    }
}
