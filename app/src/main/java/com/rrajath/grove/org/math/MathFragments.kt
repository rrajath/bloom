package com.rrajath.grove.org.math

/** Whether a math fragment flows inside a line of text or stands on its own line. */
enum class MathKind { INLINE, DISPLAY }

/**
 * One LaTeX fragment found in org text.
 *
 * @param range char range in the scanned string, delimiters included
 * @param kind inline (`$x$`, `\(x\)`) or display (`$$x$$`, `\[x\]`, `\begin{align}…`)
 * @param body the LaTeX between the delimiters. For an environment it is the whole
 *  `\begin{…}…\end{…}` text, which [LatexParser] understands directly.
 */
data class MathFragment(val range: IntRange, val kind: MathKind, val body: String)

/**
 * Finds LaTeX fragments in a run of org text, following Emacs Org's rules
 * (`org-element-latex-fragment`):
 *
 * - `\( … \)` is inline math and `\[ … \]` is display math.
 * - `$$ … $$` is display math.
 * - `$ … $` is inline math, but only when the opening `$` is followed by a
 *   non-space character other than `,;.$`, the closing `$` follows a non-space
 *   character other than `,.$`, and the content has no `$` and spans at most three
 *   lines. This is what keeps "costs $5 and $10" from turning into a formula.
 * - `\begin{env} … \end{env}` for the math environments in [ENVIRONMENTS].
 *
 * A backslash escapes the next character, so `\$` is a literal dollar and `\\(` is a
 * line break followed by a paren, not an opening `\(`.
 *
 * Pure Kotlin; the scan is linear and returns early for text with no `$` or `\`.
 */
object MathFragments {

    /** Environments rendered as math. Value: whether the environment is inline-level. */
    private val ENVIRONMENTS: Map<String, Boolean> = mapOf(
        "math" to true,
        "displaymath" to false,
        "equation" to false, "equation*" to false,
        "align" to false, "align*" to false,
        "alignat" to false, "alignat*" to false,
        "aligned" to false, "alignedat" to false,
        "flalign" to false, "flalign*" to false,
        "gather" to false, "gather*" to false, "gathered" to false,
        "multline" to false, "multline*" to false,
        "split" to false,
        "eqnarray" to false, "eqnarray*" to false,
        "cases" to false,
        "matrix" to false, "pmatrix" to false, "bmatrix" to false,
        "Bmatrix" to false, "vmatrix" to false, "Vmatrix" to false, "smallmatrix" to false,
        "array" to false,
    )

    private const val BEGIN = "\\begin{"

    fun find(text: String): List<MathFragment> {
        if (text.indexOf('$') < 0 && text.indexOf('\\') < 0) return emptyList()
        val out = ArrayList<MathFragment>(2)
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '\\') {
                if (i + 1 >= n) break
                val d = text[i + 1]
                val found: MathFragment? = when {
                    d == '(' -> delimited(text, i, "\\)", MathKind.INLINE)
                    d == '[' -> delimited(text, i, "\\]", MathKind.DISPLAY)
                    text.startsWith(BEGIN, i) -> environment(text, i)
                    else -> null
                }
                if (found != null) {
                    out.add(found)
                    i = found.range.last + 1
                } else {
                    // Skip the escaped character: `\$`, `\\`, `\%`, …
                    i += 2
                }
                continue
            }
            if (c == '$') {
                val found = if (i + 1 < n && text[i + 1] == '$') doubleDollar(text, i) else singleDollar(text, i)
                if (found != null) {
                    out.add(found)
                    i = found.range.last + 1
                } else {
                    i += if (i + 1 < n && text[i + 1] == '$') 2 else 1
                }
                continue
            }
            i++
        }
        return out
    }

    /** `\( … \)` / `\[ … \]`: [open] is at [start], [close] ends the fragment. */
    private fun delimited(text: String, start: Int, close: String, kind: MathKind): MathFragment? {
        val bodyStart = start + 2
        val end = text.indexOf(close, bodyStart)
        if (end < 0) return null
        val body = text.substring(bodyStart, end)
        if (body.isBlank()) return null
        return MathFragment(start until end + close.length, kind, body)
    }

    private fun doubleDollar(text: String, start: Int): MathFragment? {
        val bodyStart = start + 2
        var k = bodyStart
        val n = text.length
        while (k < n) {
            val ch = text[k]
            if (ch == '\\') {
                k += 2
                continue
            }
            if (ch == '$' && k + 1 < n && text[k + 1] == '$') {
                val body = text.substring(bodyStart, k)
                if (body.isBlank()) return null
                return MathFragment(start until k + 2, MathKind.DISPLAY, body)
            }
            k++
        }
        return null
    }

    private fun singleDollar(text: String, start: Int): MathFragment? {
        val n = text.length
        if (start > 0 && text[start - 1] == '$') return null
        if (start + 1 >= n) return null
        val first = text[start + 1]
        if (first.isWhitespace() || first in ",;.$") return null
        var j = start + 2
        var newlines = 0
        while (j < n) {
            val ch = text[j]
            if (ch == '\\') {
                j += 2
                continue
            }
            if (ch == '\n') {
                if (++newlines > 2) return null
                j++
                continue
            }
            if (ch == '$') {
                // The first unescaped `$` decides: Org's content class excludes `$`,
                // so a closer that fails the border rules means "not math" here.
                val prev = text[j - 1]
                val prevOk = !prev.isWhitespace() && prev !in ",.$"
                val next = if (j + 1 < n) text[j + 1] else null
                val nextOk = next == null || !next.isLetterOrDigit()
                if (!prevOk || !nextOk) return null
                return MathFragment(start..j, MathKind.INLINE, text.substring(start + 1, j))
            }
            j++
        }
        return null
    }

    private fun environment(text: String, start: Int): MathFragment? {
        val nameStart = start + BEGIN.length
        val nameEnd = text.indexOf('}', nameStart)
        if (nameEnd < 0 || nameEnd - nameStart > 16) return null
        val name = text.substring(nameStart, nameEnd)
        val inline = ENVIRONMENTS[name] ?: return null
        val open = "\\begin{$name}"
        val close = "\\end{$name}"
        // Count nesting of the same environment so `matrix` inside `matrix` closes properly.
        var depth = 1
        var k = nameEnd + 1
        while (k < text.length) {
            val nextClose = text.indexOf(close, k)
            if (nextClose < 0) return null
            val nextOpen = text.indexOf(open, k)
            if (nextOpen in 0 until nextClose) {
                depth++
                k = nextOpen + open.length
            } else {
                depth--
                k = nextClose + close.length
                if (depth == 0) {
                    val body = text.substring(start, k)
                    return MathFragment(start until k, if (inline) MathKind.INLINE else MathKind.DISPLAY, body)
                }
            }
        }
        return null
    }
}
