package com.rrajath.grove.ui.editor

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.rrajath.grove.org.InlineTokenizer
import com.rrajath.grove.org.InlineType
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.starColor

/**
 * Highlight-only syntax colouring for the raw org editor (design spec §6).
 * Applied as an [OutputTransformation]: it only adds [SpanStyle] spans, plus
 * [trailingBlankLines] display-only newlines after the end of the text (never
 * saved), so every offset into the underlying text maps 1:1 onto the presented
 * text and cursor math stays trivial. The blank lines give the field's own scroll
 * range room to lift the last line off the bottom edge ([KeepCursorClearOfBottom]);
 * a tap on them places the cursor at the end of the real text.
 */
class OrgSyntaxHighlight(
    private val colors: GroveColors,
    private val keywords: OrgKeywords,
    private val trailingBlankLines: Int = 0,
) : OutputTransformation {

    /** Span styles relative to the start of one line: cacheable across edits. */
    private data class LineSpan(val style: SpanStyle, val start: Int, val end: Int)

    // Across keystrokes only one line changes, so tokenization results are
    // cached per line content: a keystroke re-tokenizes the edited line, not
    // the whole buffer.
    private var lineCache = HashMap<String, List<LineSpan>>()

    override fun TextFieldBuffer.transformOutput() {
        // Rebuilt each pass from the previous cache so entries for deleted
        // lines don't accumulate over a long editing session.
        val next = HashMap<String, List<LineSpan>>()
        var lineStart = 0
        // Split on '\n' only: lineSequence() also treats "\r\n" and a lone '\r'
        // as one terminator, which desyncs the `+ 1` below from the real offsets.
        for (line in toString().split('\n')) {
            val spans = next[line] ?: lineCache[line] ?: styleLine(line)
            next[line] = spans
            for (s in spans) addStyle(s.style, lineStart + s.start, lineStart + s.end)
            lineStart += line.length + 1
        }
        lineCache = next
        if (trailingBlankLines > 0) append("\n".repeat(trailingBlankLines))
    }

    private fun styleLine(line: String): List<LineSpan> {
        val spans = mutableListOf<LineSpan>()
        val headline = HEADLINE.matchEntire(line)
        when {
            headline != null -> spans.styleHeadline(line, headline)
            isPlanning(line) -> spans.stylePlanning(line)
            isDrawerLine(line) -> spans.add(LineSpan(SpanStyle(color = colors.synProp), 0, line.length))
            PROPERTY.matchEntire(line) != null -> spans.styleProperty(line)
            else -> spans.styleInline(line)
        }
        return spans
    }

    private fun MutableList<LineSpan>.styleHeadline(line: String, m: MatchResult) {
        val stars = m.groupValues[1]
        add(
            LineSpan(
                SpanStyle(color = colors.starColor(stars.length), fontWeight = FontWeight.SemiBold),
                0, stars.length,
            )
        )
        // Heading text: ink, semibold. Applied first so the keyword and tag spans
        // below layer on top (previously these were applied, fully overwritten by
        // this span, then re-applied: pure wasted work).
        add(
            LineSpan(
                SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold),
                stars.length + 1, line.length,
            )
        )
        val rest = m.groupValues[2]
        val firstWord = rest.substringBefore(' ')
        if (firstWord in keywords.all) {
            val color = if (keywords.isDone(firstWord)) colors.synDone else colors.synTodo
            val kwStart = stars.length + 1
            add(LineSpan(SpanStyle(color = color, fontWeight = FontWeight.Bold), kwStart, kwStart + firstWord.length))
        }
        TAGS_AT_END.find(line)?.let { tags ->
            add(
                LineSpan(
                    SpanStyle(color = colors.synTag, fontWeight = FontWeight.Normal),
                    tags.range.first, tags.range.last + 1,
                )
            )
        }
    }

    private fun MutableList<LineSpan>.stylePlanning(line: String) {
        add(LineSpan(SpanStyle(color = colors.synKw), 0, line.length))
        for (m in TIMESTAMP.findAll(line)) {
            add(LineSpan(SpanStyle(color = colors.synTs), m.range.first, m.range.last + 1))
        }
        for (m in PLANNING_KW.findAll(line)) {
            add(
                LineSpan(
                    SpanStyle(color = colors.synKw, fontWeight = FontWeight.SemiBold),
                    m.range.first, m.range.last + 1,
                )
            )
        }
    }

    private fun MutableList<LineSpan>.styleProperty(line: String) {
        val keyEnd = line.indexOf(':', line.indexOf(':') + 1) + 1
        add(LineSpan(SpanStyle(color = colors.synKw), 0, keyEnd))
        add(LineSpan(SpanStyle(color = colors.synProp), keyEnd, line.length))
    }

    private fun MutableList<LineSpan>.styleInline(line: String) {
        for (token in InlineTokenizer.tokenize(line)) {
            val s = token.range.first
            val e = token.range.last + 1
            when (token.type) {
                InlineType.TEXT -> {}
                InlineType.BOLD -> add(LineSpan(SpanStyle(color = colors.synTag, fontWeight = FontWeight.SemiBold), s, e))
                InlineType.ITALIC -> add(LineSpan(SpanStyle(color = colors.green, fontStyle = FontStyle.Italic), s, e))
                InlineType.UNDERLINE -> add(LineSpan(SpanStyle(textDecoration = TextDecoration.Underline), s, e))
                InlineType.STRIKETHROUGH -> add(LineSpan(SpanStyle(textDecoration = TextDecoration.LineThrough), s, e))
                InlineType.CODE -> add(LineSpan(SpanStyle(color = colors.accent, background = colors.surface2), s, e))
                InlineType.VERBATIM -> add(LineSpan(SpanStyle(color = colors.accent, background = colors.surface2), s, e))
                InlineType.LINK -> add(
                    LineSpan(SpanStyle(color = colors.synLink, textDecoration = TextDecoration.Underline), s, e)
                )
                InlineType.TIMESTAMP -> add(LineSpan(SpanStyle(color = colors.synTs), s, e))
            }
        }
    }

    private fun isPlanning(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("SCHEDULED:") || t.startsWith("DEADLINE:") || t.startsWith("CLOSED:")
    }

    private fun isDrawerLine(line: String): Boolean {
        val t = line.trim()
        return t.equals(":PROPERTIES:", true) || t.equals(":END:", true)
    }

    companion object {
        private val HEADLINE = Regex("""^(\*+)\s+(.*)$""")
        private val TAGS_AT_END = Regex("""\s+(:[A-Za-z0-9_@#%-]+(?::[A-Za-z0-9_@#%-]+)*:)\s*$""")
        private val TIMESTAMP = Regex("""[<\[][^>\]]*[>\]]""")
        private val PLANNING_KW = Regex("""(SCHEDULED|DEADLINE|CLOSED):""")
        private val PROPERTY = Regex("""^\s*:([^:\s]+):\s*(.*)$""")
    }
}
