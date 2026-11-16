package com.rrajath.grove.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.rrajath.grove.org.InlineDirection
import com.rrajath.grove.org.InlineTokenizer
import com.rrajath.grove.org.InlineType
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.PlexMono

/**
 * Render org inline markup (bold/italic/code/links/…) into an AnnotatedString.
 * Links display their description, not the raw `[[target][desc]]` syntax. When
 * [onLink] is given they are tappable; when null they're only styled (for rows
 * that are themselves clickable, like the outline).
 *
 * LaTeX fragments show their source as plain text, unless their range is in
 * [inlineIds]: then the typeset formula replaces the source in place.
 */
fun annotateOrgInline(
    text: String,
    c: GroveColors,
    onLink: ((String) -> Unit)? = null,
    /**
     * Link and math tokens (by source range) drawn as inline content instead of text: the
     * value is the `inlineContent` id. The token's display text is kept as the
     * alternate text (a formula's is its LaTeX source), so every offset (links,
     * selection) stays the same.
     */
    inlineIds: Map<IntRange, String> = emptyMap(),
): AnnotatedString = buildAnnotatedString {
    for (token in InlineTokenizer.tokenize(text)) {
        when (token.type) {
            InlineType.TEXT -> append(token.text)
            InlineType.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(token.text) }
            InlineType.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.text) }
            InlineType.UNDERLINE -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(token.text) }
            InlineType.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.text) }
            InlineType.CODE, InlineType.VERBATIM -> withStyle(
                SpanStyle(fontFamily = PlexMono, fontSize = 13.5.sp, background = c.surface2)
            ) { append(token.text) }

            InlineType.TIMESTAMP -> withStyle(
                SpanStyle(fontFamily = PlexMono, fontSize = 13.5.sp, color = c.synTs)
            ) { append(token.text) }

            InlineType.MATH -> if (token.range in inlineIds) {
                appendInlineContent(inlineIds.getValue(token.range), token.text)
            } else {
                append(token.text)
            }

            InlineType.LINK -> if (token.range in inlineIds) {
                appendInlineContent(inlineIds.getValue(token.range), token.text)
            } else {
                val target = token.target ?: token.text
                val linkStyle = SpanStyle(color = c.synLink, textDecoration = TextDecoration.Underline)
                if (onLink != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = target,
                            styles = TextLinkStyles(linkStyle),
                        ) { onLink(target) }
                    ) { append(token.text) }
                } else {
                    withStyle(linkStyle) { append(token.text) }
                }
            }
        }
    }
}

/**
 * Render org inline markup as plain text: links show their description (not the raw
 * `[[target][desc]]` syntax), and other markup (bold/italic/code/…) shows its bare text.
 * Used where a heading's title is displayed outside of Compose text styling, e.g. a
 * notification title.
 */
fun orgInlinePlainText(text: String): String =
    InlineTokenizer.tokenize(text).joinToString("") { it.text }

/**
 * Whether [text] reads right-to-left, decided by its first strong-direction character
 * as rendered (link labels, bare markup): the Unicode "first strong" rule behind CSS
 * `unicode-bidi: plaintext`. Text with no strong character (digits, punctuation) is LTR.
 */
fun orgInlineStartsRtl(text: String): Boolean = textStartsRtl(orgInlinePlainText(text))

/**
 * [orgInlineStartsRtl] for text whose LaTeX fragments are drawn as typeset formulas
 * ([mathDrawn]): their source (strong left-to-right letters like `x` in `$x$`) is ignored, so
 * a Persian line that starts with a formula still reads right-to-left. With [mathDrawn] false
 * the source is shown as text and counts like any other text.
 */
fun orgInlineStartsRtl(text: String, mathDrawn: Boolean): Boolean =
    if (mathDrawn) InlineDirection.startsRtl(InlineTokenizer.tokenize(text)) else orgInlineStartsRtl(text)

/** [orgInlineStartsRtl] for text that is already rendered (no org markup left in it). */
fun textStartsRtl(text: CharSequence): Boolean {
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

/** A link's character range within the string [annotateOrgInline] renders, and its target. */
data class InlineLink(
    val range: IntRange,
    val target: String,
    /** Index into `TextLayoutResult.placeholderRects` when the link draws as inline content, else -1. */
    val placeholder: Int = -1,
)

/**
 * Every link's rendered character range in [text] once tokenized (labels shown, not raw syntax).
 *
 * [inlineIds] are the tokens (by source range, as for [annotateOrgInline]) drawn as inline
 * content. Compose numbers those placeholders in text order, links and formulas alike, so a
 * link drawn as a chip gets that running index in [InlineLink.placeholder]; a formula before
 * it moves its index along.
 */
fun orgInlineLinks(text: String, inlineIds: Map<IntRange, String> = emptyMap()): List<InlineLink> {
    var offset = 0
    var placeholders = 0
    val links = mutableListOf<InlineLink>()
    for (token in InlineTokenizer.tokenize(text)) {
        val start = offset
        offset += token.text.length
        val drawnInline = token.range in inlineIds
        if (token.type == InlineType.LINK) {
            links += InlineLink(
                start until offset, token.target ?: token.text,
                placeholder = if (drawnInline) placeholders else -1,
            )
        }
        if (drawnInline) placeholders++
    }
    return links
}
