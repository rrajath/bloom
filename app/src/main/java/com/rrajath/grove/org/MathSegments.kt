package com.rrajath.grove.org

import com.rrajath.grove.org.math.MathKind

/** A run of a paragraph: either text to render as inline org markup, or a display formula on its own line. */
sealed class MathSegment {
    data class Prose(val text: String) : MathSegment()

    /** [source] is the LaTeX to typeset (delimiters removed); [raw] the text as written, for fallbacks. */
    data class DisplayMath(val source: String, val raw: String) : MathSegment()
}

object MathSegments {

    /**
     * Splits a paragraph at its display math (`$$ … $$`, `\[ … \]`, `\begin{align} … \end{align}`),
     * which sits on its own centred line, so inline math stays inside [MathSegment.Prose].
     * Whitespace around a formula is dropped; prose that is only whitespace is omitted.
     * A paragraph with no display math comes back unchanged as one segment.
     */
    fun split(text: String): List<MathSegment> {
        if (text.indexOf('$') < 0 && text.indexOf('\\') < 0) return listOf(MathSegment.Prose(text))
        val display = InlineTokenizer.tokenize(text).filter { it.math?.kind == MathKind.DISPLAY }
        if (display.isEmpty()) return listOf(MathSegment.Prose(text))
        val out = ArrayList<MathSegment>(display.size * 2 + 1)
        var pos = 0
        for (token in display) {
            addProse(out, text.substring(pos, token.range.first))
            out.add(MathSegment.DisplayMath(token.math!!.body, token.text))
            pos = token.range.last + 1
        }
        addProse(out, text.substring(pos))
        return out
    }

    private fun addProse(out: MutableList<MathSegment>, s: String) {
        val trimmed = s.trim()
        if (trimmed.isNotEmpty()) out.add(MathSegment.Prose(trimmed))
    }
}
