package com.rrajath.grove.org.math

import com.rrajath.grove.org.math.MathNode.Accent
import com.rrajath.grove.org.math.MathNode.Classed
import com.rrajath.grove.org.math.MathNode.Delim
import com.rrajath.grove.org.math.MathNode.Delimited
import com.rrajath.grove.org.math.MathNode.Frac
import com.rrajath.grove.org.math.MathNode.Glyphs
import com.rrajath.grove.org.math.MathNode.Row
import com.rrajath.grove.org.math.MathNode.Scripts
import com.rrajath.grove.org.math.MathNode.Space
import com.rrajath.grove.org.math.MathNode.Sqrt
import com.rrajath.grove.org.math.MathNode.StyleSwitch
import com.rrajath.grove.org.math.MathNode.Table

/**
 * Parser for the subset of LaTeX math that notes actually use: letters, numbers and
 * the usual symbol commands, `^`/`_` scripts, `\frac`/`\binom`, `\sqrt`, accents,
 * `\left…\right` and `\big…`, font commands (`\mathbb`, `\mathrm`, `\text`, …),
 * spacing, and the matrix / `cases` / `align` / `gather` family of environments.
 *
 * It never throws on bad input: an unknown command becomes a literal `\name` glyph run
 * and unbalanced braces are tolerated. The one failure is nesting deeper than
 * [MAX_DEPTH], which returns null so the caller can fall back to the raw source.
 */
internal object LatexParser {

    const val MAX_DEPTH = 48

    fun parse(src: String): MathNode? =
        try {
            Parser(src, 0).parseTop()
        } catch (_: TooDeep) {
            null
        }

    private class TooDeep : RuntimeException()

    private enum class K { CS, CH, LBRACE, RBRACE, CARET, UNDER, AMP, TILDE, EOF }

    private class Tok(val k: K, val s: String = "") {
        fun isCs(name: String) = k == K.CS && s == name
    }

    private val EOF = Tok(K.EOF)

    private class Lexer(val src: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < src.length) {
                val c = src[pos]
                if (c.isWhitespace()) {
                    pos++
                } else if (c == '%') {
                    while (pos < src.length && src[pos] != '\n') pos++
                } else {
                    break
                }
            }
        }

        fun peek(): Tok {
            val save = pos
            val t = next()
            pos = save
            return t
        }

        fun next(): Tok {
            skipSpace()
            if (pos >= src.length) return EOF
            val c = src[pos]
            return when (c) {
                '{' -> { pos++; Tok(K.LBRACE) }
                '}' -> { pos++; Tok(K.RBRACE) }
                '^' -> { pos++; Tok(K.CARET) }
                '_' -> { pos++; Tok(K.UNDER) }
                '&' -> { pos++; Tok(K.AMP) }
                '~' -> { pos++; Tok(K.TILDE) }
                '\\' -> {
                    pos++
                    if (pos >= src.length) return Tok(K.CH, "\\")
                    val d = src[pos]
                    if (d in 'a'..'z' || d in 'A'..'Z') {
                        val start = pos
                        while (pos < src.length && (src[pos] in 'a'..'z' || src[pos] in 'A'..'Z')) pos++
                        Tok(K.CS, src.substring(start, pos))
                    } else {
                        val cp = src.codePointAt(pos)
                        val s = String(Character.toChars(cp))
                        pos += s.length
                        Tok(K.CS, s)
                    }
                }
                else -> {
                    val cp = src.codePointAt(pos)
                    val s = String(Character.toChars(cp))
                    pos += s.length
                    Tok(K.CH, s)
                }
            }
        }

        /** The balanced `{…}` group at the cursor as raw text, or a single token if there is no brace. */
        fun readRawGroup(): String {
            skipSpace()
            if (pos >= src.length) return ""
            if (src[pos] != '{') {
                val t = next()
                return when (t.k) {
                    K.CS -> "\\" + t.s
                    K.CH -> t.s
                    else -> ""
                }
            }
            pos++
            val start = pos
            var depth = 1
            while (pos < src.length) {
                val c = src[pos]
                if (c == '\\') {
                    pos += 2
                    continue
                }
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) break
                }
                pos++
            }
            val end = minOf(pos, src.length)
            val text = src.substring(start, end)
            if (pos < src.length) pos++
            return text
        }

        fun readOptionalBracket(): String? {
            skipSpace()
            if (pos >= src.length || src[pos] != '[') return null
            val close = src.indexOf(']', pos + 1)
            if (close < 0) return null
            val s = src.substring(pos + 1, close)
            pos = close + 1
            return s
        }
    }

    private val EMPTY = Row(emptyList())

    private val ROW_END = setOf("\\", "right", "end")
    private val INFIX = setOf("over", "atop", "choose")
    private val STYLE_LEVELS = mapOf("displaystyle" to 0, "textstyle" to 1, "scriptstyle" to 2, "scriptscriptstyle" to 3)

    private val IGNORED_WITH_ARG = setOf("label", "tag", "color", "ref", "eqref", "hypertarget", "vspace")
    private val IGNORED = setOf(
        "nonumber", "notag", "qedhere", "hline", "hdashline", "toprule", "midrule", "bottomrule",
        "allowbreak", "relax", "protect", "limits", "nolimits", "mathstrut", "strut", "displaybreak",
        "centering", "raggedright", "newline", "nobreak", "ignorespaces",
    )

    private val ACCENTS = mapOf(
        "hat" to AccentKind.HAT, "widehat" to AccentKind.WIDEHAT, "check" to AccentKind.CHECK,
        "widecheck" to AccentKind.CHECK, "tilde" to AccentKind.TILDE, "widetilde" to AccentKind.WIDETILDE,
        "bar" to AccentKind.BAR, "overline" to AccentKind.OVERLINE, "underline" to AccentKind.UNDERLINE,
        "vec" to AccentKind.VEC, "overrightarrow" to AccentKind.OVERRIGHTARROW,
        "overleftarrow" to AccentKind.OVERLEFTARROW, "dot" to AccentKind.DOT, "ddot" to AccentKind.DDOT,
        "acute" to AccentKind.ACUTE, "grave" to AccentKind.GRAVE, "breve" to AccentKind.BREVE,
        "overbrace" to AccentKind.OVERBRACE, "underbrace" to AccentKind.UNDERBRACE, "mathring" to AccentKind.DOT,
    )

    private val CLASSES = mapOf(
        "mathop" to Atom.OP, "mathrel" to Atom.REL, "mathbin" to Atom.BIN, "mathord" to Atom.ORD,
        "mathopen" to Atom.OPEN, "mathclose" to Atom.CLOSE, "mathpunct" to Atom.PUNCT, "mathinner" to Atom.INNER,
    )

    private val NEGATIONS = mapOf(
        "=" to "≠", "<" to "≮", ">" to "≯", "∈" to "∉", "⊂" to "⊄", "⊆" to "⊈", "⊃" to "⊅", "⊇" to "⊉",
        "≡" to "≢", "∼" to "≁", "≈" to "≉", "∣" to "∤", "∥" to "∦", "≤" to "≰", "≥" to "≱", "→" to "↛",
        "⇒" to "⇏", "↔" to "↮", "⇔" to "⇎", "∋" to "∌", "⊢" to "⊬", "⊨" to "⊭", "≅" to "≇", "≃" to "≄",
    )

    private val BIG_HEIGHTS = mapOf("big" to 1.2f, "Big" to 1.8f, "bigg" to 2.4f, "Bigg" to 3.0f)

    private class Parser(src: String, private var depth: Int) {
        private val lx = Lexer(src)

        fun parseTop(): MathNode {
            val rows = parseTableRows(inGroup = false)
            return tableOrCell(rows, aligns = null)
        }

        /** Single cell stays a plain row; several rows/cells become a table. */
        private fun tableOrCell(rows: List<List<MathNode>>, aligns: String?): MathNode {
            if (rows.size == 1 && rows[0].size == 1) return rows[0][0]
            val multiColumn = rows.any { it.size > 1 }
            return Table(
                rows = rows,
                aligns = aligns ?: if (multiColumn) "rl" else "c",
                left = "",
                right = "",
                kind = if (multiColumn) TableKind.ALIGN else TableKind.GATHER,
            )
        }

        private fun enter() {
            if (++depth > MAX_DEPTH) throw TooDeep()
        }

        private fun leave() {
            depth--
        }

        // ---- rows ---------------------------------------------------------------------

        private fun parseRow(): Row {
            enter()
            try {
                val nodes = ArrayList<MathNode>()
                while (true) {
                    val t = lx.peek()
                    if (t.k == K.EOF || t.k == K.RBRACE || t.k == K.AMP) break
                    if (t.k == K.CS && t.s in ROW_END) break
                    if (t.k == K.CS && t.s in INFIX) {
                        lx.next()
                        val den = parseRow()
                        val num = Row(mergeDigits(nodes))
                        val frac = Frac(num, den, bar = t.s != "atop" && t.s != "choose", style = null)
                        val node = if (t.s == "choose") Delimited("(", ")", frac) else frac
                        return Row(listOf(node))
                    }
                    if (t.k == K.CS && t.s in STYLE_LEVELS) {
                        lx.next()
                        nodes.add(StyleSwitch(STYLE_LEVELS.getValue(t.s), parseRow()))
                        break
                    }
                    val n = parseScripted() ?: continue
                    nodes.add(n)
                }
                return Row(mergeDigits(nodes))
            } finally {
                leave()
            }
        }

        private fun mergeDigits(nodes: List<MathNode>): List<MathNode> {
            if (nodes.size < 2) return nodes
            val out = ArrayList<MathNode>(nodes.size)
            for (n in nodes) {
                val prev = out.lastOrNull()
                if (prev is Glyphs && n is Glyphs && isNumeric(prev) && isNumeric(n)) {
                    out[out.size - 1] = prev.copy(text = prev.text + n.text)
                } else {
                    out.add(n)
                }
            }
            return out
        }

        private fun isNumeric(g: Glyphs): Boolean =
            g.atom == Atom.ORD && g.variant == MathVariant.UPRIGHT && g.text.isNotEmpty() &&
                g.text.all { it in '0'..'9' || it == '.' }

        // ---- scripts ------------------------------------------------------------------

        private fun parseScripted(): MathNode? {
            val first = lx.peek()
            val base: MathNode =
                if (first.k == K.CARET || first.k == K.UNDER) EMPTY else (parseAtom() ?: return null)
            var sub: MathNode? = null
            var sup: MathNode? = null
            var limits: Boolean? = null
            while (true) {
                val t = lx.peek()
                when {
                    t.k == K.CARET -> {
                        lx.next()
                        val a = scriptArg()
                        sup = if (sup == null) a else Row(listOf(sup, a))
                    }
                    t.k == K.UNDER -> {
                        lx.next()
                        sub = scriptArg()
                    }
                    t.k == K.CH && t.s == "'" -> {
                        var primes = 0
                        while (lx.peek().let { it.k == K.CH && it.s == "'" }) {
                            lx.next()
                            primes++
                        }
                        val p = Glyphs(primeGlyph(primes), Atom.ORD, MathVariant.UPRIGHT)
                        sup = if (sup == null) p else Row(listOf(sup, p))
                    }
                    t.isCs("limits") -> {
                        lx.next()
                        limits = true
                    }
                    t.isCs("nolimits") -> {
                        lx.next()
                        limits = false
                    }
                    else -> break
                }
            }
            return if (sub != null || sup != null) Scripts(base, sub, sup, limits) else base
        }

        private fun primeGlyph(n: Int): String = when (n) {
            1 -> "′"
            2 -> "″"
            3 -> "‴"
            else -> "′".repeat(n)
        }

        private fun scriptArg(): MathNode = arg()

        // ---- atoms --------------------------------------------------------------------

        private fun parseAtom(): MathNode? {
            val t = lx.next()
            return when (t.k) {
                K.LBRACE -> {
                    val r = parseRow()
                    if (lx.peek().k == K.RBRACE) lx.next()
                    r
                }
                K.CH -> charAtom(t.s)
                K.TILDE -> Space(6f)
                K.CS -> command(t.s)
                else -> null
            }
        }

        /** The next atom as a command/environment argument; empty when the group is missing. */
        private fun arg(): MathNode {
            val t = lx.peek()
            if (t.k == K.EOF || t.k == K.RBRACE || t.k == K.AMP) return EMPTY
            if (t.k == K.CS && t.s in ROW_END) return EMPTY
            return parseAtom() ?: EMPTY
        }

        private fun charAtom(s: String): MathNode {
            val c = s[0]
            return when {
                s == "+" -> Glyphs("+", Atom.BIN, MathVariant.UPRIGHT)
                s == "-" -> Glyphs("−", Atom.BIN, MathVariant.UPRIGHT)
                s == "*" -> Glyphs("∗", Atom.BIN, MathVariant.UPRIGHT)
                s == "=" || s == "<" || s == ">" || s == ":" -> Glyphs(s, Atom.REL, MathVariant.UPRIGHT)
                s == "," || s == ";" -> Glyphs(s, Atom.PUNCT, MathVariant.UPRIGHT)
                s == "(" || s == "[" -> Glyphs(s, Atom.OPEN, MathVariant.UPRIGHT)
                s == ")" || s == "]" -> Glyphs(s, Atom.CLOSE, MathVariant.UPRIGHT)
                s == "'" -> Glyphs("′", Atom.ORD, MathVariant.UPRIGHT)
                c in 'a'..'z' || c in 'A'..'Z' -> Glyphs(s, Atom.ORD, MathVariant.ITALIC)
                c in 'α'..'ω' -> Glyphs(s, Atom.ORD, MathVariant.ITALIC)
                else -> Glyphs(s, MathSymbols.unicodeAtom(c), MathVariant.UPRIGHT)
            }
        }

        // ---- commands -----------------------------------------------------------------

        private fun command(name: String): MathNode? {
            MathSymbols.lookup(name)?.let { return Glyphs(it.text, it.atom, it.variant, it.limits, it.big) }
            ACCENTS[name]?.let { return Accent(it, arg()) }
            CLASSES[name]?.let { return Classed(it, arg()) }
            bigSize(name)?.let { return sizedDelimiter(name, it) }
            if (name in IGNORED) return Space(0f)
            if (name in IGNORED_WITH_ARG) {
                lx.readRawGroup()
                return Space(0f)
            }
            return when (name) {
                "frac", "cfrac" -> Frac(arg(), arg(), bar = true, style = if (name == "cfrac") 0 else null)
                "dfrac" -> Frac(arg(), arg(), bar = true, style = 0)
                "tfrac" -> Frac(arg(), arg(), bar = true, style = 1)
                "binom" -> Delimited("(", ")", Frac(arg(), arg(), bar = false, style = null))
                "dbinom" -> Delimited("(", ")", Frac(arg(), arg(), bar = false, style = 0))
                "tbinom" -> Delimited("(", ")", Frac(arg(), arg(), bar = false, style = 1))
                "sqrt" -> {
                    val idx = lx.readOptionalBracket()?.let { sub(it) }
                    Sqrt(arg(), idx)
                }
                "left" -> parseLeftRight()
                "middle" -> Glyphs(readDelimiter().ifEmpty { "|" }, Atom.ORD, MathVariant.UPRIGHT)
                "text", "textrm", "mbox", "hbox", "textnormal" -> textRun(MathVariant.TEXT)
                "textbf" -> textRun(MathVariant.BOLD)
                "textit", "emph" -> textRun(MathVariant.ITALIC)
                "textsf" -> textRun(MathVariant.SANS)
                "texttt" -> textRun(MathVariant.MONO)
                "mathrm", "operatorfont" -> fontCommand { it.copy(variant = MathVariant.UPRIGHT) }
                "mathbf" -> fontCommand { it.copy(variant = MathVariant.BOLD) }
                "boldsymbol", "bm", "pmb" -> fontCommand {
                    it.copy(variant = if (it.variant == MathVariant.ITALIC) MathVariant.BOLD_ITALIC else MathVariant.BOLD)
                }
                "mathit", "mathnormal" -> fontCommand { it.copy(variant = MathVariant.ITALIC) }
                "mathsf" -> fontCommand { it.copy(variant = MathVariant.SANS) }
                "mathtt" -> fontCommand { it.copy(variant = MathVariant.MONO) }
                "mathbb" -> fontCommand { mapLetters(it, MathSymbols::blackboard) }
                "mathcal", "mathscr" -> fontCommand { mapLetters(it, MathSymbols::script) }
                "mathfrak" -> fontCommand { mapLetters(it, MathSymbols::fraktur) }
                "operatorname" -> {
                    val star = lx.peek().let { it.k == K.CH && it.s == "*" }
                    if (star) lx.next()
                    Glyphs(textContent(lx.readRawGroup()), Atom.OP, MathVariant.UPRIGHT, limits = star)
                }
                "bmod" -> Glyphs("mod", Atom.BIN, MathVariant.UPRIGHT)
                "pmod" -> Row(
                    listOf(
                        Space(18f), Glyphs("(", Atom.OPEN, MathVariant.UPRIGHT),
                        Glyphs("mod", Atom.ORD, MathVariant.UPRIGHT), Space(6f), arg(),
                        Glyphs(")", Atom.CLOSE, MathVariant.UPRIGHT),
                    ),
                )
                "mod" -> Row(listOf(Space(18f), Glyphs("mod", Atom.ORD, MathVariant.UPRIGHT), Space(6f), arg()))
                "overset", "stackrel" -> {
                    val top = arg()
                    Scripts(arg(), null, top, limits = true)
                }
                "underset" -> {
                    val bottom = arg()
                    Scripts(arg(), bottom, null, limits = true)
                }
                "xrightarrow", "xleftarrow" -> {
                    val below = lx.readOptionalBracket()?.let { sub(it) }
                    val above = arg()
                    val arrow = Glyphs(if (name == "xrightarrow") "⟶" else "⟵", Atom.REL, MathVariant.UPRIGHT)
                    Scripts(arrow, below, above, limits = true)
                }
                "substack" -> {
                    if (lx.peek().k == K.LBRACE) lx.next()
                    val rows = parseTableRows(inGroup = true)
                    if (lx.peek().k == K.RBRACE) lx.next()
                    Table(rows, "c", "", "", TableKind.SUBSTACK)
                }
                "textcolor", "colorbox" -> {
                    lx.readRawGroup()
                    arg()
                }
                "boxed", "fbox", "cancel", "bcancel", "xcancel", "smash", "mathllap", "mathrlap" -> arg()
                "phantom", "hphantom", "vphantom" -> {
                    arg()
                    Space(0f)
                }
                "not" -> negation()
                "begin" -> parseEnvironment()
                "hspace", "hspace*" -> Space(hspaceMu(lx.readRawGroup()))
                "quad" -> Space(18f)
                "qquad" -> Space(36f)
                "enspace" -> Space(9f)
                ",", "thinspace" -> Space(3f)
                ":", ">", "medspace" -> Space(4f)
                ";", "thickspace" -> Space(5f)
                "!", "negthinspace" -> Space(-3f)
                " " -> Space(6f)
                "\\", "right", "end" -> null
                else -> Glyphs("\\$name", Atom.ORD, MathVariant.MONO)
            }
        }

        /** `\big`, `\Bigl`, `\biggr`, … -> height in em, or null when [name] isn't one of them. */
        private fun bigSize(name: String): Float? {
            val stem = if (name.length > 3 && name.last() in "lrm") name.dropLast(1) else name
            return BIG_HEIGHTS[stem]
        }

        private fun sizedDelimiter(name: String, height: Float): MathNode {
            val atom = if (name.length > 3) {
                when (name.last()) {
                    'l' -> Atom.OPEN
                    'r' -> Atom.CLOSE
                    'm' -> Atom.REL
                    else -> Atom.ORD
                }
            } else {
                Atom.ORD
            }
            val d = readDelimiter()
            return if (d.isEmpty()) Space(0f) else Delim(d, height, atom)
        }

        private fun parseLeftRight(): MathNode {
            val left = readDelimiter()
            val body = parseRow()
            var right = ""
            if (lx.peek().isCs("right")) {
                lx.next()
                right = readDelimiter()
            }
            return Delimited(left, right, body)
        }

        /** Reads a delimiter after `\left`/`\right`/`\big`: returns "" for `.` or anything unrecognised. */
        private fun readDelimiter(): String {
            val t = lx.next()
            return when (t.k) {
                K.CH -> when (t.s) {
                    "(", ")", "[", "]", "|", "/" -> t.s
                    "<" -> "⟨"
                    ">" -> "⟩"
                    else -> ""
                }
                K.CS -> MathSymbols.delimiterFromControl(t.s)
                    ?: MathSymbols.lookup(t.s)?.text?.takeIf { it.length == 1 && it[0] in "⟨⟩⌊⌋⌈⌉↑↓" }
                    ?: ""
                else -> ""
            }
        }

        private fun negation(): MathNode {
            val n = arg()
            if (n is Glyphs && n.atom == Atom.REL) {
                val neg = NEGATIONS[n.text]
                if (neg != null) return n.copy(text = neg)
            }
            return n
        }

        private fun textRun(variant: MathVariant): MathNode {
            val text = textContent(lx.readRawGroup())
            return if (text.isEmpty()) EMPTY else Glyphs(text, Atom.ORD, variant)
        }

        /** Resolves the few escapes that matter inside `\text{…}`. */
        private fun textContent(raw: String): String {
            if (raw.indexOf('\\') < 0 && raw.indexOf('~') < 0) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '~' -> sb.append(' ')
                    c == '\\' && i + 1 < raw.length -> {
                        val d = raw[i + 1]
                        if (d.isLetter()) {
                            var j = i + 1
                            while (j < raw.length && raw[j].isLetter()) j++
                            when (raw.substring(i + 1, j)) {
                                "quad" -> sb.append('\u2003')
                                "qquad" -> sb.append("\u2003\u2003")
                            }
                            i = j
                            continue
                        }
                        when (d) {
                            '\\', ' ', ',', ';', ':' -> sb.append(' ')
                            else -> sb.append(d)
                        }
                        i += 2
                        continue
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }

        private fun hspaceMu(raw: String): Float {
            val m = Regex("""^\s*(-?\d*\.?\d+)\s*(em|ex|pt|mu|cm|mm|in)?\s*$""").find(raw) ?: return 0f
            val v = m.groupValues[1].toFloatOrNull() ?: return 0f
            return when (m.groupValues[2]) {
                "em", "" -> v * 18f
                "ex" -> v * 8f
                "pt" -> v * 1.8f
                "mu" -> v
                "cm" -> v * 51f
                "mm" -> v * 5.1f
                "in" -> v * 130f
                else -> 0f
            }
        }

        private fun sub(raw: String): MathNode = Parser(raw, depth).parseRowOnly()

        fun parseRowOnly(): MathNode = parseRow()

        // ---- font restyling -----------------------------------------------------------

        /** Applies [f] to every glyph run of the next argument, then collapses the result where it can. */
        private fun fontCommand(f: (Glyphs) -> Glyphs): MathNode = simplify(restyle(arg(), f))

        /**
         * `\mathbb{R}` should be one glyph, and `\mathrm{Var}` one upright run (so the font can
         * kern it and the renderer draws it once). Italic letters stay separate: merged, a
         * font would turn the variables `f` `i` into an "fi" ligature.
         */
        private fun simplify(n: MathNode): MathNode {
            if (n !is Row) return n
            if (n.nodes.size == 1) return n.nodes[0]
            val runs = n.nodes.filterIsInstance<Glyphs>()
            if (runs.isEmpty() || runs.size != n.nodes.size) return n
            val first = runs[0]
            val mergeable = first.variant != MathVariant.ITALIC && first.variant != MathVariant.BOLD_ITALIC &&
                runs.all { it.atom == Atom.ORD && it.variant == first.variant && !it.limits && !it.big }
            return if (mergeable) first.copy(text = runs.joinToString("") { it.text }) else n
        }

        private fun restyle(node: MathNode, f: (Glyphs) -> Glyphs): MathNode = when (node) {
            is Glyphs -> f(node)
            is Row -> Row(node.nodes.map { restyle(it, f) })
            is Scripts -> Scripts(
                restyle(node.base, f), node.sub?.let { restyle(it, f) }, node.sup?.let { restyle(it, f) }, node.limits,
            )
            is Frac -> node.copy(num = restyle(node.num, f), den = restyle(node.den, f))
            is Sqrt -> node.copy(body = restyle(node.body, f))
            is Delimited -> node.copy(body = restyle(node.body, f))
            is Accent -> node.copy(body = restyle(node.body, f))
            is Classed -> node.copy(body = restyle(node.body, f))
            is StyleSwitch -> node.copy(body = restyle(node.body, f))
            else -> node
        }

        private fun mapLetters(g: Glyphs, map: (Char) -> String?): Glyphs {
            if (g.text.length != 1) return g.copy(variant = MathVariant.UPRIGHT)
            val mapped = map(g.text[0]) ?: return g.copy(variant = MathVariant.UPRIGHT)
            return g.copy(text = mapped, variant = MathVariant.UPRIGHT)
        }

        // ---- environments -------------------------------------------------------------

        private fun parseEnvironment(): MathNode {
            val name = lx.readRawGroup().trim()
            val base = name.removeSuffix("*")
            var spec: String? = null
            when (base) {
                "array" -> {
                    lx.readOptionalBracket()
                    spec = lx.readRawGroup()
                }
                "alignat", "alignedat" -> lx.readRawGroup()
            }
            val rows = parseTableRows(inGroup = false)
            if (lx.peek().isCs("end")) {
                lx.next()
                lx.readRawGroup()
            }
            return when (base) {
                "matrix", "smallmatrix" -> Table(rows, "c", "", "", TableKind.MATRIX)
                "pmatrix" -> Table(rows, "c", "(", ")", TableKind.MATRIX)
                "bmatrix" -> Table(rows, "c", "[", "]", TableKind.MATRIX)
                "Bmatrix" -> Table(rows, "c", "{", "}", TableKind.MATRIX)
                "vmatrix" -> Table(rows, "c", "|", "|", TableKind.MATRIX)
                "Vmatrix" -> Table(rows, "c", "‖", "‖", TableKind.MATRIX)
                "cases" -> Table(rows, "l", "{", "", TableKind.CASES)
                "array" -> Table(rows, columnSpec(spec.orEmpty()), "", "", TableKind.ARRAY)
                "align", "aligned", "alignat", "alignedat", "flalign", "split" ->
                    if (rows.size == 1 && rows[0].size == 1) rows[0][0] else Table(rows, "rl", "", "", TableKind.ALIGN)
                "eqnarray" -> Table(rows, "rcl", "", "", TableKind.ALIGN)
                else -> if (rows.size == 1 && rows[0].size == 1) rows[0][0] else Table(rows, "c", "", "", TableKind.GATHER)
            }
        }

        private fun columnSpec(spec: String): String {
            val cols = spec.filter { it == 'l' || it == 'c' || it == 'r' }
            return cols.ifEmpty { "c" }
        }

        /**
         * Rows of `&`-separated cells, up to the end of the input, an `\end`, or (with
         * [inGroup]) the closing brace. The terminator is left for the caller.
         */
        private fun parseTableRows(inGroup: Boolean): List<List<MathNode>> {
            val rows = ArrayList<List<MathNode>>()
            var cur = ArrayList<MathNode>()
            while (true) {
                cur.add(parseRow())
                val t = lx.peek()
                when {
                    t.k == K.AMP -> lx.next()
                    t.isCs("\\") -> {
                        lx.next()
                        lx.readOptionalBracket()
                        rows.add(cur)
                        cur = ArrayList()
                    }
                    t.k == K.EOF || t.isCs("end") -> break
                    t.k == K.RBRACE && inGroup -> break
                    else -> lx.next() // stray `}` or `\right`
                }
            }
            rows.add(cur)
            if (rows.size > 1 && rows.last().all { it is Row && it.nodes.isEmpty() }) rows.removeAt(rows.size - 1)
            return rows
        }
    }
}
