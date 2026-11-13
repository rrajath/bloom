package com.rrajath.grove.org.math

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Notes are typed by hand, so half-finished and nonsensical LaTeX is normal input. Whatever
 * the text, scanning, parsing and layout must neither throw nor produce non-finite geometry.
 */
class MathRobustnessTest {

    private object HalfEm : MathMetrics {
        override fun width(text: String, sizePx: Float, variant: MathVariant) = 0.5f * sizePx * text.length
    }

    private val pieces = listOf(
        "\\frac", "\\dfrac", "\\binom", "\\sqrt", "\\sqrt[3]", "{", "}", "{", "}", "^", "_", "&", "\\\\", "\\left", "\\right",
        "\\middle", "\\bigl", "\\Bigr", "(", ")", "[", "]", "|", ".", "\\{", "\\}", "\\langle", "\\rangle", "x", "y", "1", "23",
        "+", "-", "=", ",", "'", "\\sum", "\\int", "\\lim", "\\prod", "\\limits", "\\nolimits", "\\alpha", "\\Omega", "\\infty",
        "\\begin{pmatrix}", "\\end{pmatrix}", "\\begin{align}", "\\end{align}", "\\begin{cases}", "\\end{cases}",
        "\\begin{array}{lc}", "\\end{array}", "\\begin{matrix}", "\\end{matrix}", "\\begin", "\\end", "\\text{a b}", "\\text{",
        "\\mathbb", "\\mathrm{d}", "\\operatorname{f}", "\\hat", "\\overline", "\\underbrace", "\\overbrace", "\\vec", "\\substack",
        "\\over", "\\atop", "\\choose", "\\displaystyle", "\\scriptstyle", "\\quad", "\\,", "\\!", "~", "%", "\\", "$", "\\$",
        "\\newcommand", "\\unknown", "\\not", "\\pmod", "\\xrightarrow", "\\overset", "\\hspace{2em}", "\\color{red}", " ", "\n",
    )

    private fun randomSource(random: Random): String {
        val n = random.nextInt(1, 40)
        return buildString { repeat(n) { append(pieces[random.nextInt(pieces.size)]) } }
    }

    @Test
    fun `parsing and layout never throw and stay finite on random input`() {
        val random = Random(20260101)
        repeat(4000) {
            val src = randomSource(random)
            for (display in listOf(false, true)) {
                val layout = MathTypesetter.layout(src, display, 16f, HalfEm) ?: continue
                assertTrue("non-finite size for: $src", layout.width.isFinite() && layout.ascent.isFinite() && layout.descent.isFinite())
                assertTrue("negative width for: $src", layout.width >= 0f)
                for (op in layout.ops) when (op) {
                    is MathOp.Glyphs -> assertTrue("glyph for: $src", op.x.isFinite() && op.y.isFinite() && op.sizePx > 0f)
                    is MathOp.Rule -> assertTrue("rule for: $src", op.x.isFinite() && op.y.isFinite() && op.w.isFinite() && op.h.isFinite())
                    is MathOp.Poly -> assertTrue("poly for: $src", op.pts.size >= 4 && op.pts.size % 2 == 0 && op.pts.all { it.isFinite() })
                }
            }
        }
    }

    @Test
    fun `fragment scanner returns ordered in-bounds ranges on random input`() {
        val random = Random(7)
        repeat(4000) {
            val src = randomSource(random)
            val found = MathFragments.find(src)
            var end = -1
            for (f in found) {
                assertTrue("range out of bounds in: $src", f.range.first >= 0 && f.range.last < src.length && f.range.first <= f.range.last)
                assertTrue("overlap in: $src", f.range.first > end)
                end = f.range.last
            }
        }
    }

    @Test
    fun `huge flat input is handled`() {
        val src = "x+".repeat(5000) + "y"
        val layout = MathTypesetter.layout(src, false, 16f, HalfEm)!!
        assertTrue(layout.width > 0f)
        val fragments = MathFragments.find("$" + "a".repeat(100000))
        assertTrue(fragments.isEmpty())
    }
}
