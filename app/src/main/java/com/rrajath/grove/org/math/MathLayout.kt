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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Supplies glyph advance widths, the only font fact the layout needs. Heights come from [MathEngine]'s tables. */
interface MathMetrics {
    /** Advance width in px of [text] set at [sizePx] in [variant]. */
    fun width(text: String, sizePx: Float, variant: MathVariant): Float
}

/**
 * One drawing instruction. Coordinates are px relative to the layout's left edge and its
 * baseline, with y growing downward (so things above the baseline have negative y).
 */
sealed class MathOp {
    abstract fun translate(dx: Float, dy: Float): MathOp

    /** [text] drawn with its baseline at [y] and its left edge at [x]. */
    class Glyphs(val x: Float, val y: Float, val text: String, val sizePx: Float, val variant: MathVariant) : MathOp() {
        override fun translate(dx: Float, dy: Float) = Glyphs(x + dx, y + dy, text, sizePx, variant)
    }

    /** A filled rectangle: fraction bars, underlines, dots. [y] is the top edge. */
    class Rule(val x: Float, val y: Float, val w: Float, val h: Float) : MathOp() {
        override fun translate(dx: Float, dy: Float) = Rule(x + dx, y + dy, w, h)
    }

    /** An open polyline stroked [strokePx] wide: radicals, stretched delimiters, accents. [pts] is x0,y0,x1,y1,… */
    class Poly(val pts: FloatArray, val strokePx: Float) : MathOp() {
        override fun translate(dx: Float, dy: Float): Poly {
            val out = FloatArray(pts.size)
            var i = 0
            while (i < pts.size) {
                out[i] = pts[i] + dx
                out[i + 1] = pts[i + 1] + dy
                i += 2
            }
            return Poly(out, strokePx)
        }
    }
}

/** A typeset formula: [width] × ([ascent] + [descent]) px, with [ops] positioned against its baseline. */
class MathLayout(val width: Float, val ascent: Float, val descent: Float, val ops: List<MathOp>) {
    val height: Float get() = ascent + descent
}

/** Parses LaTeX and lays it out. The only entry point the UI needs. */
object MathTypesetter {

    /**
     * @param display true for display style (`\[ \]`, `$$ $$`, environments): bigger operators,
     *  stacked limits, roomier fractions.
     * @return null when [source] is empty or can't be handled; the caller shows the raw text instead.
     */
    fun layout(source: String, display: Boolean, sizePx: Float, metrics: MathMetrics): MathLayout? {
        if (source.isBlank() || sizePx <= 0f) return null
        return try {
            val node = LatexParser.parse(source) ?: return null
            if (node is Row && node.nodes.isEmpty()) return null
            val box = MathEngine(metrics, sizePx).layout(node, if (display) 0 else 1)
            if (box.ops.isEmpty()) return null
            // Negative glue (`\!` first in a formula) can net out below zero; a layout can't be.
            MathLayout(max(box.w, 0f), box.a, box.d, box.ops)
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** Width, ascent and descent of a laid-out piece, plus its drawing ops relative to its own origin. */
internal class Box(val w: Float, val a: Float, val d: Float, val ops: List<MathOp>) {
    fun shifted(dx: Float, dy: Float): List<MathOp> =
        if (dx == 0f && dy == 0f) ops else ops.map { it.translate(dx, dy) }
}

/**
 * A small TeX-like typesetter (TeXbook appendix G, simplified): atoms get class-based
 * spacing, scripts and fractions follow TeX's shift rules, and styles shrink script
 * and fraction parts. Glyph heights are estimated per character class rather than read
 * from the font, so the layout needs nothing from the platform but [MathMetrics.width].
 *
 * Style levels: 0 display, 1 text, 2 script, 3 scriptscript.
 */
internal class MathEngine(private val m: MathMetrics, private val base: Float) {

    private companion object {
        val LEVEL_SCALE = floatArrayOf(1f, 1f, 0.7f, 0.5f)

        /** Height of the math axis (the middle of `=` and the fraction bar) in em. */
        const val AXIS = 0.25f

        /** Rule thickness in em. */
        const val RULE = 0.045f

        const val CAP = 0.70f
        const val ASCENDER = 0.73f
        const val X_HEIGHT = 0.46f
        const val DESCENDER = 0.22f

        val EMPTY_BOX = Box(0f, 0f, 0f, emptyList())
        val EMPTY_ROW = Row(emptyList())

        /**
         * Inter-atom space, rows = left atom, cols = right atom (ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT, INNER).
         * 1 thin (3mu), 2 medium (4mu), 3 thick (5mu); negative = only in display/text style.
         */
        val SPACE_TABLE = arrayOf(
            intArrayOf(0, 1, -2, -3, 0, 0, 0, -1), // ORD
            intArrayOf(1, 1, 0, -3, 0, 0, 0, -1), // OP
            intArrayOf(-2, -2, 0, 0, -2, 0, 0, -2), // BIN
            intArrayOf(-3, -3, 0, 0, -3, 0, 0, -3), // REL
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0), // OPEN
            intArrayOf(0, 1, -2, -3, 0, 0, 0, -1), // CLOSE
            intArrayOf(-1, -1, 0, -1, -1, -1, -1, -1), // PUNCT
            intArrayOf(-1, 1, -2, -3, -1, 0, -1, -1), // INNER
        )
        val SPACE_MU = intArrayOf(0, 3, 4, 5)

        const val OPERATOR_CHARS = "+−=<>±∓×÷⋅∗⋆∘∙∪∩∖∧∨⊕⊖⊗⊘⊙⊎⊔⊓≤≥≠≈≡∼≃≅∝≪≫≺≻⊂⊃⊆⊇∈∉∋∣∥⊥⊢⊣⊨→←⇒⇐↔⇔↦⟶⟵⟹⟸⟺"
        const val BRACKET_CHARS = "()[]{}|‖⟨⟩⌊⌋⌈⌉"
        const val INTEGRALS = "∫∬∭∮∯"
    }

    private fun size(level: Int) = base * LEVEL_SCALE[level]
    private fun scriptLevel(level: Int) = if (level <= 1) 2 else 3
    private fun fracLevel(level: Int) = if (level == 0) 1 else min(3, level + 1)

    fun layout(node: MathNode, lvl: Int): Box = when (node) {
        is Glyphs -> glyphs(node, lvl)
        is Row -> row(node.nodes, lvl)
        is Scripts -> scripts(node, lvl)
        is Frac -> frac(node, lvl)
        is Sqrt -> sqrt(node, lvl)
        is Delimited -> delimited(node, lvl)
        is Delim -> stretched(node.text, node.heightEm * size(lvl), size(lvl))
        is Accent -> accent(node, lvl)
        is Space -> Box(node.mu * size(lvl) / 18f, 0f, 0f, emptyList())
        is Table -> table(node, lvl)
        is StyleSwitch -> layout(node.body, node.level)
        is Classed -> layout(node.body, lvl)
    }

    // ---- glyph runs -------------------------------------------------------------------

    /** Ascent and descent of [text] in em, estimated from which characters it contains. */
    private fun dims(text: String, variant: MathVariant): Pair<Float, Float> {
        var a = 0f
        var d = 0f
        val slanted = variant == MathVariant.ITALIC || variant == MathVariant.BOLD_ITALIC
        for (ch in text) {
            var ca = CAP
            var cd = 0f
            when {
                ch in "acemnorsuvwxz" -> ca = X_HEIGHT
                ch in "gpqy" -> { ca = X_HEIGHT; cd = DESCENDER }
                ch == 'j' -> { ca = 0.68f; cd = DESCENDER }
                ch == 'i' -> ca = 0.68f
                ch in "bdhklt" -> ca = ASCENDER
                ch == 'f' -> { ca = ASCENDER; if (slanted) cd = DESCENDER }
                ch in "αεικνοπστυωϵϖϰ" -> ca = X_HEIGHT
                ch in "δθλϑ" -> ca = ASCENDER
                ch in "βζξφψϕ" -> { ca = ASCENDER; cd = DESCENDER }
                ch in "γημρςχϱ" -> { ca = X_HEIGHT; cd = DESCENDER }
                ch in BRACKET_CHARS -> { ca = 0.75f; cd = 0.25f }
                ch in OPERATOR_CHARS -> { ca = 0.55f; cd = 0.05f }
                ch == ',' || ch == ';' -> { ca = 0.1f; cd = 0.15f }
                ch == '.' -> ca = 0.1f
                ch == ':' -> ca = X_HEIGHT
                ch == ' ' || ch == '\u2003' -> ca = 0f
                ch == '∞' -> ca = 0.55f
                ch == '…' -> ca = 0.1f
                ch == '⋯' -> { ca = 0.3f }
                ch in "′″‴" -> ca = 0.7f
                ch in "∑∏∐" -> { ca = 0.75f; cd = 0.25f }
                ch in INTEGRALS -> { ca = 0.8f; cd = 0.3f }
            }
            if (ca > a) a = ca
            if (cd > d) d = cd
        }
        return a to d
    }

    private fun glyphs(g: Glyphs, lvl: Int): Box {
        val s = size(lvl)
        val bigScale = if (g.big && lvl == 0) (if (g.text[0] in INTEGRALS) 1.7f else 1.4f) else 1f
        val sz = s * bigScale
        val w = m.width(g.text, sz, g.variant)
        val (asc, desc) = dims(g.text, g.variant)
        var dy = 0f
        if (g.big) {
            // Centre large operators on the math axis.
            dy = (asc - desc) / 2f * sz - AXIS * s
        }
        val a = asc * sz - dy
        val d = desc * sz + dy
        return Box(w, a, d, listOf(MathOp.Glyphs(0f, dy, g.text, sz, g.variant)))
    }

    // ---- rows and spacing -------------------------------------------------------------

    private fun atomOf(n: MathNode): Atom? = when (n) {
        is Glyphs -> n.atom
        is Row -> Atom.ORD
        is Scripts -> atomOf(n.base) ?: Atom.ORD
        is Frac -> Atom.INNER
        is Sqrt -> Atom.ORD
        is Delimited -> Atom.INNER
        is Delim -> n.atom
        is Accent -> Atom.ORD
        is Space -> null
        is Table -> Atom.ORD
        is StyleSwitch -> Atom.ORD
        is Classed -> n.atom
    }

    /** TeX rule 5/6: a binary operator with no left operand (or no right one) is really an ordinary symbol. */
    private fun fixBinaries(c: MutableList<Atom?>) {
        var prev = -1
        for (i in c.indices) {
            val cur = c[i] ?: continue
            if (cur == Atom.BIN) {
                val p = if (prev >= 0) c[prev] else null
                if (p == null || p == Atom.BIN || p == Atom.OP || p == Atom.REL || p == Atom.OPEN || p == Atom.PUNCT) {
                    c[i] = Atom.ORD
                }
            } else if ((cur == Atom.REL || cur == Atom.CLOSE || cur == Atom.PUNCT) && prev >= 0 && c[prev] == Atom.BIN) {
                c[prev] = Atom.ORD
            }
            prev = i
        }
        if (prev >= 0 && c[prev] == Atom.BIN) c[prev] = Atom.ORD
    }

    private fun spacing(left: Atom, right: Atom, lvl: Int): Float {
        val v = SPACE_TABLE[left.ordinal][right.ordinal]
        if (v == 0) return 0f
        if (v < 0 && lvl >= 2) return 0f
        return SPACE_MU[abs(v)] * size(lvl) / 18f
    }

    private fun row(nodes: List<MathNode>, lvl: Int): Box {
        if (nodes.isEmpty()) return EMPTY_BOX
        if (nodes.size == 1) return layout(nodes[0], lvl)
        val classes = nodes.mapTo(ArrayList<Atom?>(nodes.size)) { atomOf(it) }
        fixBinaries(classes)
        val ops = ArrayList<MathOp>()
        var x = 0f
        var a = 0f
        var d = 0f
        var prev: Atom? = null
        for ((i, n) in nodes.withIndex()) {
            val cls = classes[i]
            if (cls != null) {
                if (prev != null) x += spacing(prev, cls, lvl)
                prev = cls
            }
            val b = layout(n, lvl)
            ops.addAll(b.shifted(x, 0f))
            x += b.w
            a = max(a, b.a)
            d = max(d, b.d)
        }
        return Box(x, a, d, ops)
    }

    // ---- scripts ----------------------------------------------------------------------

    private fun wantsLimits(n: Scripts, lvl: Int): Boolean {
        n.limits?.let { return it }
        val b = n.base
        return when {
            b is Accent && (b.kind == AccentKind.OVERBRACE || b.kind == AccentKind.UNDERBRACE) -> true
            b is Glyphs && b.atom == Atom.OP && b.limits -> lvl == 0
            b is Classed && b.atom == Atom.OP -> lvl == 0
            else -> false
        }
    }

    private fun scripts(n: Scripts, lvl: Int): Box {
        val s = size(lvl)
        val baseBox = layout(n.base, lvl)
        val sLvl = scriptLevel(lvl)
        val ss = size(sLvl)
        val sup = n.sup?.let { layout(it, sLvl) }
        val sub = n.sub?.let { layout(it, sLvl) }
        if (sup == null && sub == null) return baseBox

        if (wantsLimits(n, lvl)) {
            val gap = 0.15f * s
            val w = max(baseBox.w, max(sup?.w ?: 0f, sub?.w ?: 0f))
            val ops = ArrayList<MathOp>(baseBox.ops.size)
            ops.addAll(baseBox.shifted((w - baseBox.w) / 2f, 0f))
            var a = baseBox.a
            var d = baseBox.d
            if (sup != null) {
                ops.addAll(sup.shifted((w - sup.w) / 2f, -(baseBox.a + gap + sup.d)))
                a = baseBox.a + gap + sup.d + sup.a
            }
            if (sub != null) {
                ops.addAll(sub.shifted((w - sub.w) / 2f, baseBox.d + gap + sub.a))
                d = baseBox.d + gap + sub.a + sub.d
            }
            return Box(w, a, d, ops)
        }

        val b = n.base
        val charBase = b is Glyphs && !b.big
        var u = if (charBase) 0f else baseBox.a - 0.386f * ss
        var v = if (charBase) 0f else baseBox.d + 0.05f * ss
        val xh = 0.43f * s
        val supMin = (if (lvl == 0) 0.413f else 0.363f) * s
        if (sup != null && sub == null) {
            u = max(u, max(supMin, sup.d + 0.25f * xh))
        } else if (sub != null && sup == null) {
            v = max(v, max(0.15f * s, sub.a - 0.8f * xh))
        } else if (sup != null && sub != null) {
            v = max(v, 0.247f * s)
            u = max(u, max(supMin, sup.d + 0.25f * xh))
            val gapMin = 4f * RULE * s
            if ((u - sup.d) - (sub.a - v) < gapMin) {
                v = gapMin - (u - sup.d) + sub.a
                val psi = 0.8f * xh - (u - sup.d)
                if (psi > 0f) {
                    u += psi
                    v -= psi
                }
            }
        }
        val ops = ArrayList<MathOp>(baseBox.ops.size)
        ops.addAll(baseBox.ops)
        var a = baseBox.a
        var d = baseBox.d
        var sw = 0f
        if (sup != null) {
            ops.addAll(sup.shifted(baseBox.w, -u))
            a = max(a, u + sup.a)
            sw = max(sw, sup.w)
        }
        if (sub != null) {
            ops.addAll(sub.shifted(baseBox.w, v))
            d = max(d, v + sub.d)
            sw = max(sw, sub.w)
        }
        return Box(baseBox.w + sw + 0.03f * s, a, d, ops)
    }

    // ---- fractions --------------------------------------------------------------------

    private fun frac(n: Frac, lvl: Int): Box {
        val outer = n.style ?: lvl
        val s = size(outer)
        val inner = fracLevel(outer)
        val num = layout(n.num, inner)
        val den = layout(n.den, inner)
        val rt = RULE * s
        val th = if (n.bar) rt else 0f
        val axis = AXIS * s
        val display = outer == 0
        var u: Float
        var v: Float
        val clear: Float
        if (display) {
            u = 0.677f * s
            v = 0.686f * s
            clear = if (n.bar) 3f * rt else 7f * rt
        } else {
            u = (if (n.bar) 0.394f else 0.444f) * s
            v = 0.345f * s
            clear = if (n.bar) rt else 3f * rt
        }
        if (n.bar) {
            val top = clear - ((u - num.d) - (axis + th / 2f))
            if (top > 0f) u += top
            val bottom = clear - ((axis - th / 2f) - (den.a - v))
            if (bottom > 0f) v += bottom
        } else {
            val phi = clear - ((u - num.d) - (den.a - v))
            if (phi > 0f) {
                u += phi / 2f
                v += phi / 2f
            }
        }
        val nul = 0.12f * s
        val inside = max(num.w, den.w)
        val w = inside + 2f * nul
        val ops = ArrayList<MathOp>(num.ops.size + den.ops.size + 1)
        ops.addAll(num.shifted((w - num.w) / 2f, -u))
        ops.addAll(den.shifted((w - den.w) / 2f, v))
        if (n.bar) ops.add(MathOp.Rule(nul, -(axis + th / 2f), inside, th))
        return Box(w, u + num.a, v + den.d, ops)
    }

    // ---- radicals ---------------------------------------------------------------------

    private fun sqrt(n: Sqrt, lvl: Int): Box {
        val s = size(lvl)
        val body = layout(n.body, lvl)
        val th = RULE * s
        val gap = (if (lvl == 0) 0.12f else 0.08f) * s
        val bodyA = max(body.a, 0.45f * s)
        val topY = -(bodyA + gap + th / 2f)
        val bottomY = body.d + 0.06f * s
        val h = bottomY - topY
        val sw = 0.62f * s
        val index = n.index?.let { layout(it, 3) }
        val x0 = if (index != null) max(0f, index.w - 0.3f * s) else 0f
        val bodyX = x0 + sw + 0.03f * s
        val endX = bodyX + body.w + 0.04f * s
        val pts = floatArrayOf(
            x0, topY + 0.58f * h,
            x0 + 0.2f * sw, topY + 0.52f * h,
            x0 + 0.5f * sw, bottomY,
            x0 + sw, topY,
            endX, topY,
        )
        val ops = ArrayList<MathOp>(body.ops.size + 2)
        ops.add(MathOp.Poly(pts, th * 1.15f))
        ops.addAll(body.shifted(bodyX, 0f))
        var a = -topY + th / 2f
        if (index != null) {
            val iy = topY + 0.5f * h - index.d
            ops.addAll(index.shifted(0f, iy))
            a = max(a, index.a - iy)
        }
        return Box(endX, a, max(body.d, bottomY), ops)
    }

    // ---- delimiters -------------------------------------------------------------------

    private fun delimited(n: Delimited, lvl: Int): Box {
        val s = size(lvl)
        val body = layout(n.body, lvl)
        val half = max(body.a - AXIS * s, body.d + AXIS * s)
        val need = max(2f * half * 0.91f, 2f * half - 0.5f * s)
        val left = delimiterBox(n.left, need, lvl, Atom.OPEN)
        val right = delimiterBox(n.right, need, lvl, Atom.CLOSE)
        val ops = ArrayList<MathOp>(body.ops.size + 2)
        ops.addAll(left.ops)
        ops.addAll(body.shifted(left.w, 0f))
        ops.addAll(right.shifted(left.w + body.w, 0f))
        return Box(
            left.w + body.w + right.w,
            max(body.a, max(left.a, right.a)),
            max(body.d, max(left.d, right.d)),
            ops,
        )
    }

    /** A delimiter [need] px tall: a plain font glyph when that is enough, else a drawn stretched one. */
    private fun delimiterBox(text: String, need: Float, lvl: Int, atom: Atom): Box {
        val s = size(lvl)
        if (text.isEmpty()) return Box(0.08f * s, 0f, 0f, emptyList())
        if (need <= 1.15f * s) return glyphs(Glyphs(text, atom, MathVariant.UPRIGHT), lvl)
        return stretched(text, need, s)
    }

    private fun stretched(text: String, h: Float, s: Float): Box {
        val axis = AXIS * s
        val top = -(axis + h / 2f)
        val bot = top + h
        val mid = (top + bot) / 2f
        val stroke = 0.055f * s
        val w = when (text) {
            "(", ")", "⟨", "⟩" -> 0.36f * s
            "[", "]", "⌊", "⌋", "⌈", "⌉" -> 0.34f * s
            "{", "}" -> 0.42f * s
            "|" -> 0.18f * s
            "‖" -> 0.28f * s
            "/", "\\" -> 0.4f * s
            else -> return glyphs(Glyphs(text, Atom.ORD, MathVariant.UPRIGHT), 1)
        }
        val ops = ArrayList<MathOp>(2)
        fun poly(vararg p: Float) {
            ops.add(MathOp.Poly(p, stroke))
        }

        val cap = min(0.5f * s, 0.35f * h)
        val bcap = min(0.4f * s, 0.2f * h)
        // Left-hand shapes; right-hand ones are mirrored across the box.
        fun mirror(x: Float, right: Boolean) = if (right) w - x else x
        when (text) {
            "(", ")" -> {
                val r = text == ")"
                val pts = ArrayList<Float>()
                quad(pts, mirror(0.8f * w, r), top, mirror(0.25f * w, r), top + cap * 0.55f, mirror(0.2f * w, r), top + cap)
                pts.add(mirror(0.2f * w, r)); pts.add(bot - cap)
                quad(pts, mirror(0.2f * w, r), bot - cap, mirror(0.25f * w, r), bot - cap * 0.55f, mirror(0.8f * w, r), bot)
                ops.add(MathOp.Poly(pts.toFloatArray(), stroke))
            }
            "[", "]" -> {
                val r = text == "]"
                poly(mirror(0.8f * w, r), top, mirror(0.2f * w, r), top, mirror(0.2f * w, r), bot, mirror(0.8f * w, r), bot)
            }
            "{", "}" -> {
                val r = text == "}"
                val pts = ArrayList<Float>()
                quad(pts, mirror(0.85f * w, r), top, mirror(0.5f * w, r), top, mirror(0.5f * w, r), top + bcap)
                pts.add(mirror(0.5f * w, r)); pts.add(mid - bcap)
                quad(pts, mirror(0.5f * w, r), mid - bcap, mirror(0.5f * w, r), mid, mirror(0.1f * w, r), mid)
                quad(pts, mirror(0.1f * w, r), mid, mirror(0.5f * w, r), mid, mirror(0.5f * w, r), mid + bcap)
                pts.add(mirror(0.5f * w, r)); pts.add(bot - bcap)
                quad(pts, mirror(0.5f * w, r), bot - bcap, mirror(0.5f * w, r), bot, mirror(0.85f * w, r), bot)
                ops.add(MathOp.Poly(pts.toFloatArray(), stroke))
            }
            "|" -> poly(0.5f * w, top, 0.5f * w, bot)
            "‖" -> {
                poly(0.3f * w, top, 0.3f * w, bot)
                poly(0.7f * w, top, 0.7f * w, bot)
            }
            "⟨" -> poly(0.8f * w, top, 0.2f * w, mid, 0.8f * w, bot)
            "⟩" -> poly(0.2f * w, top, 0.8f * w, mid, 0.2f * w, bot)
            "⌊" -> poly(0.25f * w, top, 0.25f * w, bot, 0.8f * w, bot)
            "⌋" -> poly(0.75f * w, top, 0.75f * w, bot, 0.2f * w, bot)
            "⌈" -> poly(0.8f * w, top, 0.25f * w, top, 0.25f * w, bot)
            "⌉" -> poly(0.2f * w, top, 0.75f * w, top, 0.75f * w, bot)
            "/" -> poly(0.8f * w, top, 0.2f * w, bot)
            "\\" -> poly(0.2f * w, top, 0.8f * w, bot)
        }
        return Box(w, -top, bot, ops)
    }

    /** Appends points along the quadratic Bézier p0 -> p1 via control c; p0 is the current end, so only c and p1 get sampled. */
    private fun quad(out: ArrayList<Float>, x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, steps: Int = 8) {
        if (out.isEmpty()) {
            out.add(x0)
            out.add(y0)
        }
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val mt = 1f - t
            out.add(mt * mt * x0 + 2f * mt * t * cx + t * t * x1)
            out.add(mt * mt * y0 + 2f * mt * t * cy + t * t * y1)
        }
    }

    // ---- accents ----------------------------------------------------------------------

    private fun accent(n: Accent, lvl: Int): Box {
        val s = size(lvl)
        val body = layout(n.body, lvl)
        val th = RULE * s
        val stroke = th * 1.1f
        val ops = ArrayList<MathOp>(body.ops.size + 3)
        ops.addAll(body.ops)
        var a = body.a
        var d = body.d
        val w = body.w
        val cx = w / 2f

        fun above(gap: Float, height: Float, draw: (bottom: Float, top: Float) -> Unit) {
            val bottom = -(body.a + gap)
            val top = bottom - height
            draw(bottom, top)
            a = max(a, -top + stroke / 2f)
        }

        val shortHw = min(w / 2f, 0.25f * s)
        when (n.kind) {
            AccentKind.OVERLINE -> {
                val y = -(body.a + 0.1f * s) - th
                ops.add(MathOp.Rule(0f, y, w, th))
                a = -y
            }
            AccentKind.UNDERLINE -> {
                val y = body.d + 0.1f * s
                ops.add(MathOp.Rule(0f, y, w, th))
                d = y + th
            }
            AccentKind.BAR -> above(0.08f * s, th) { _, top ->
                val bw = max(0.3f * s, w * 0.8f)
                ops.add(MathOp.Rule(cx - bw / 2f, top, bw, th))
            }
            AccentKind.HAT, AccentKind.WIDEHAT -> above(0.07f * s, 0.17f * s) { bottom, top ->
                val hw = if (n.kind == AccentKind.WIDEHAT) w / 2f else shortHw
                ops.add(MathOp.Poly(floatArrayOf(cx - hw, bottom, cx, top, cx + hw, bottom), stroke))
            }
            AccentKind.CHECK -> above(0.07f * s, 0.17f * s) { bottom, top ->
                ops.add(MathOp.Poly(floatArrayOf(cx - shortHw, top, cx, bottom, cx + shortHw, top), stroke))
            }
            AccentKind.TILDE, AccentKind.WIDETILDE -> above(0.07f * s, 0.14f * s) { bottom, top ->
                val hw = if (n.kind == AccentKind.WIDETILDE) w / 2f else shortHw
                val mid = (top + bottom) / 2f
                val amp = (bottom - top) * 0.4f
                val pts = FloatArray(2 * 13)
                for (i in 0..12) {
                    val t = i / 12f
                    pts[2 * i] = cx - hw + 2f * hw * t
                    pts[2 * i + 1] = mid - amp * sin(2.0 * PI * t).toFloat()
                }
                ops.add(MathOp.Poly(pts, stroke))
            }
            AccentKind.VEC, AccentKind.OVERRIGHTARROW, AccentKind.OVERLEFTARROW -> above(0.07f * s, 0.17f * s) { bottom, top ->
                val hw = if (n.kind == AccentKind.VEC) shortHw else w / 2f
                val y = (top + bottom) / 2f
                val tip = 0.07f * s
                ops.add(MathOp.Poly(floatArrayOf(cx - hw, y, cx + hw, y), stroke))
                if (n.kind == AccentKind.OVERLEFTARROW) {
                    ops.add(MathOp.Poly(floatArrayOf(cx - hw + tip, y - tip, cx - hw, y, cx - hw + tip, y + tip), stroke))
                } else {
                    ops.add(MathOp.Poly(floatArrayOf(cx + hw - tip, y - tip, cx + hw, y, cx + hw - tip, y + tip), stroke))
                }
            }
            AccentKind.DOT, AccentKind.DDOT -> above(0.07f * s, 0.07f * s) { _, top ->
                val dot = 0.075f * s
                if (n.kind == AccentKind.DOT) {
                    ops.add(MathOp.Rule(cx - dot / 2f, top, dot, dot))
                } else {
                    ops.add(MathOp.Rule(cx - 0.09f * s - dot / 2f, top, dot, dot))
                    ops.add(MathOp.Rule(cx + 0.09f * s - dot / 2f, top, dot, dot))
                }
            }
            AccentKind.ACUTE -> above(0.07f * s, 0.15f * s) { bottom, top ->
                ops.add(MathOp.Poly(floatArrayOf(cx - 0.05f * s, bottom, cx + 0.08f * s, top), stroke))
            }
            AccentKind.GRAVE -> above(0.07f * s, 0.15f * s) { bottom, top ->
                ops.add(MathOp.Poly(floatArrayOf(cx + 0.05f * s, bottom, cx - 0.08f * s, top), stroke))
            }
            AccentKind.BREVE -> above(0.07f * s, 0.12f * s) { bottom, top ->
                val pts = ArrayList<Float>()
                quad(pts, cx - shortHw, top, cx, 2f * bottom - top, cx + shortHw, top)
                ops.add(MathOp.Poly(pts.toFloatArray(), stroke))
            }
            AccentKind.OVERBRACE -> {
                val yb = -(body.a + 0.06f * s)
                val ym = yb - 0.1f * s
                val yt = yb - 0.22f * s
                val r = min(0.15f * s, w / 6f)
                ops.add(MathOp.Poly(bracePoints(w, yb, ym, yt, r), stroke))
                a = max(a, -yt + stroke / 2f)
            }
            AccentKind.UNDERBRACE -> {
                val yb = body.d + 0.06f * s
                val ym = yb + 0.1f * s
                val yt = yb + 0.22f * s
                val r = min(0.15f * s, w / 6f)
                ops.add(MathOp.Poly(bracePoints(w, yb, ym, yt, r), stroke))
                d = max(d, yt + stroke / 2f)
            }
        }
        return Box(w, a, d, ops)
    }

    /** A horizontal brace: ends at [yb], flat run at [ym], cusp at [yt] in the middle. */
    private fun bracePoints(w: Float, yb: Float, ym: Float, yt: Float, r: Float): FloatArray {
        val pts = ArrayList<Float>()
        val cx = w / 2f
        quad(pts, 0f, yb, 0f, ym, r, ym, 6)
        pts.add(cx - r); pts.add(ym)
        quad(pts, cx - r, ym, cx, ym, cx, yt, 6)
        quad(pts, cx, yt, cx, ym, cx + r, ym, 6)
        pts.add(w - r); pts.add(ym)
        quad(pts, w - r, ym, w, ym, w, yb, 6)
        return pts.toFloatArray()
    }

    // ---- tables -----------------------------------------------------------------------

    private fun table(n: Table, lvl: Int): Box {
        val s = size(lvl)
        val cellLvl = if (lvl == 0 && (n.kind == TableKind.MATRIX || n.kind == TableKind.CASES || n.kind == TableKind.ARRAY)) 1 else lvl
        val cs = size(cellLvl)
        val nCols = max(1, n.rows.maxOfOrNull { it.size } ?: 1)
        val alignOdd = n.kind == TableKind.ALIGN
        val cells: List<List<Box>> = n.rows.map { r ->
            List(nCols) { c ->
                val node = r.getOrNull(c)
                when {
                    node == null -> EMPTY_BOX
                    // `a &= b`: amsmath puts an empty group before the odd column so a leading `=` gets relation spacing.
                    alignOdd && c % 2 == 1 -> {
                        val inner = if (node is Row) node.nodes else listOf(node)
                        row(listOf<MathNode>(EMPTY_ROW) + inner, cellLvl)
                    }
                    else -> layout(node, cellLvl)
                }
            }
        }
        val colW = FloatArray(nCols) { c -> cells.maxOfOrNull { it[c].w } ?: 0f }
        val strutA = 0.7f * cs
        val strutD = 0.3f * cs
        val rowA = FloatArray(cells.size) { r -> max(strutA, cells[r].maxOf { it.a }) }
        val rowD = FloatArray(cells.size) { r -> max(strutD, cells[r].maxOf { it.d }) }
        val rowGap = when (n.kind) {
            TableKind.ALIGN -> 0.3f
            TableKind.CASES -> 0.25f
            TableKind.SUBSTACK -> 0.1f
            else -> 0.2f
        } * cs

        fun gapBefore(c: Int): Float = when {
            c == 0 -> 0f
            n.kind == TableKind.SUBSTACK -> 0f
            n.kind == TableKind.ALIGN && n.aligns == "rcl" -> 0.3f * cs
            n.kind == TableKind.ALIGN -> if (c % 2 == 1) 0f else 2f * cs
            else -> 1f * cs
        }

        fun alignOf(c: Int): Char = when {
            n.kind == TableKind.GATHER || n.kind == TableKind.SUBSTACK -> 'c'
            n.aligns.isEmpty() -> 'c'
            n.kind == TableKind.ARRAY -> n.aligns[min(c, n.aligns.length - 1)]
            else -> n.aligns[c % n.aligns.length]
        }

        val colX = FloatArray(nCols)
        var x = 0f
        for (c in 0 until nCols) {
            x += gapBefore(c)
            colX[c] = x
            x += colW[c]
        }
        val width = x
        val totalH = rowA.indices.sumOf { (rowA[it] + rowD[it]).toDouble() }.toFloat() + rowGap * (cells.size - 1)
        var y = -AXIS * s - totalH / 2f
        val ops = ArrayList<MathOp>()
        for (r in cells.indices) {
            val baseline = y + rowA[r]
            for (c in 0 until nCols) {
                val cell = cells[r][c]
                val cx = when (alignOf(c)) {
                    'r' -> colX[c] + colW[c] - cell.w
                    'l' -> colX[c]
                    else -> colX[c] + (colW[c] - cell.w) / 2f
                }
                ops.addAll(cell.shifted(cx, baseline))
            }
            y += rowA[r] + rowD[r] + rowGap
        }
        val tableA = AXIS * s + totalH / 2f
        val tableD = totalH / 2f - AXIS * s
        val inner = Box(width, tableA, max(0f, tableD), ops)
        if (n.left.isEmpty() && n.right.isEmpty()) return inner

        val half = max(inner.a - AXIS * s, inner.d + AXIS * s)
        val need = max(2f * half * 0.91f, 2f * half - 0.5f * s)
        val left = delimiterBox(n.left, need, lvl, Atom.OPEN)
        val right = delimiterBox(n.right, need, lvl, Atom.CLOSE)
        val pad = 0.08f * s
        val all = ArrayList<MathOp>(inner.ops.size + 2)
        all.addAll(left.ops)
        all.addAll(inner.shifted(left.w + pad, 0f))
        all.addAll(right.shifted(left.w + pad + inner.w + pad, 0f))
        return Box(
            left.w + pad + inner.w + pad + right.w,
            max(inner.a, max(left.a, right.a)),
            max(inner.d, max(left.d, right.d)),
            all,
        )
    }
}
