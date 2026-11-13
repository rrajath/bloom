package com.rrajath.grove.org.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathLayoutTest {

    /** Every character is half an em wide, whatever it is. Keeps expected widths easy to compute. */
    private object HalfEm : MathMetrics {
        override fun width(text: String, sizePx: Float, variant: MathVariant) = 0.5f * sizePx * text.length
    }

    private val size = 18f
    private val eps = 0.01f

    private fun layout(src: String, display: Boolean = false): MathLayout =
        MathTypesetter.layout(src, display, size, HalfEm)!!

    private fun glyphs(l: MathLayout) = l.ops.filterIsInstance<MathOp.Glyphs>()
    private fun rules(l: MathLayout) = l.ops.filterIsInstance<MathOp.Rule>()
    private fun polys(l: MathLayout) = l.ops.filterIsInstance<MathOp.Poly>()

    @Test
    fun `blank or unusable input has no layout`() {
        assertNull(MathTypesetter.layout("", false, size, HalfEm))
        assertNull(MathTypesetter.layout("   ", false, size, HalfEm))
        assertNull(MathTypesetter.layout("{}", false, size, HalfEm))
        assertNull(MathTypesetter.layout("x", false, 0f, HalfEm))
        assertNull(MathTypesetter.layout("{".repeat(500) + "}".repeat(500), false, size, HalfEm))
    }

    @Test
    fun `a single letter is one glyph run at the base size on the baseline`() {
        val l = layout("x")
        val g = glyphs(l).single()
        assertEquals("x", g.text)
        assertEquals(size, g.sizePx, eps)
        assertEquals(0f, g.y, eps)
        assertEquals(0.5f * size, l.width, eps)
        assertEquals(MathVariant.ITALIC, g.variant)
    }

    @Test
    fun `ordinary symbols sit next to each other with no space`() {
        assertEquals(3 * 0.5f * size, layout("xyz").width, eps)
    }

    @Test
    fun `binary operator gets a medium space on each side`() {
        val expected = 3 * 0.5f * size + 2 * (4f / 18f) * size
        assertEquals(expected, layout("a+b").width, eps)
    }

    @Test
    fun `relation gets a thick space on each side`() {
        val expected = 3 * 0.5f * size + 2 * (5f / 18f) * size
        assertEquals(expected, layout("a=b").width, eps)
    }

    @Test
    fun `unary minus gets no space`() {
        assertEquals(2 * 0.5f * size, layout("-a").width, eps)
        // After a relation the minus is unary too: only the relation's own spaces appear.
        assertEquals(4 * 0.5f * size + 2 * (5f / 18f) * size, layout("a=-b").width, eps)
    }

    @Test
    fun `punctuation gets a thin space after it`() {
        assertEquals(3 * 0.5f * size + (3f / 18f) * size, layout("a,b").width, eps)
    }

    @Test
    fun `no medium or thick spaces in script style`() {
        val l = layout("x^{a+b}")
        val sup = glyphs(l).filter { it.sizePx < size }
        assertEquals(3, sup.size)
        val right = sup.maxOf { it.x } - sup.minOf { it.x }
        // a, +, b adjacent: each 0.5 * 0.7 * size wide, nothing in between.
        assertEquals(2 * 0.5f * 0.7f * size, right, eps)
    }

    @Test
    fun `superscript is smaller and raised, subscript is smaller and lowered`() {
        val l = layout("x^2_i")
        val sup = glyphs(l).single { it.text == "2" }
        val sub = glyphs(l).single { it.text == "i" }
        assertEquals(0.7f * size, sup.sizePx, eps)
        assertEquals(0.7f * size, sub.sizePx, eps)
        assertTrue(sup.y < 0f)
        assertTrue(sub.y > 0f)
        assertEquals(0.5f * size, sup.x, eps)
        assertEquals(0.5f * size, sub.x, eps)
    }

    @Test
    fun `script of script is smaller still`() {
        val l = layout("x^{y^z}")
        assertEquals(0.5f * size, glyphs(l).single { it.text == "z" }.sizePx, eps)
    }

    @Test
    fun `fraction stacks numerator over denominator with a bar between`() {
        val l = layout("\\frac{a}{b}")
        val num = glyphs(l).single { it.text == "a" }
        val den = glyphs(l).single { it.text == "b" }
        val bar = rules(l).single()
        assertTrue(num.y < bar.y)
        assertTrue(den.y > bar.y + bar.h)
        assertTrue(bar.w >= 0.5f * 0.7f * size - eps)
        // Inline fractions use script size for both parts.
        assertEquals(0.7f * size, num.sizePx, eps)
        assertTrue(l.ascent > 0f && l.descent > 0f)
    }

    @Test
    fun `display fraction keeps full size parts and a taller box`() {
        val inline = layout("\\frac{a}{b}")
        val display = layout("\\frac{a}{b}", display = true)
        assertEquals(size, glyphs(display).first().sizePx, eps)
        assertTrue(display.height > inline.height)
    }

    @Test
    fun `fraction parts are centred on the bar`() {
        val l = layout("\\frac{a}{\\mathrm{bbbb}}")
        val num = glyphs(l).single { it.text == "a" }
        val den = glyphs(l).single { it.text == "bbbb" }
        val numMid = num.x + 0.5f * 0.7f * size / 2f
        val denMid = den.x + den.text.length * 0.5f * 0.7f * size / 2f
        assertEquals(denMid, numMid, eps)
    }

    @Test
    fun `fraction bar sits on the math axis`() {
        val bar = rules(layout("\\frac{a}{b}")).single()
        assertEquals(-0.25f * size, bar.y + bar.h / 2f, eps)
    }

    @Test
    fun `display style stacks limits above and below a big operator`() {
        val l = layout("\\sum_{i}^{n}", display = true)
        val sum = glyphs(l).single { it.text == "∑" }
        val sup = glyphs(l).single { it.text == "n" }
        val sub = glyphs(l).single { it.text == "i" }
        assertTrue(sup.y < sum.y)
        assertTrue(sub.y > sum.y)
        assertTrue(sum.sizePx > size) // grows in display style
    }

    @Test
    fun `text style puts the limits beside the operator`() {
        val l = layout("\\sum_{i}^{n}", display = false)
        val sum = glyphs(l).single { it.text == "∑" }
        val sup = glyphs(l).single { it.text == "n" }
        assertEquals(size, sum.sizePx, eps)
        assertTrue(sup.x >= sum.x + 0.5f * size - eps)
    }

    @Test
    fun `integral keeps its limits at the side even in display style`() {
        val l = layout("\\int_0^1", display = true)
        val int = glyphs(l).single { it.text == "∫" }
        val sup = glyphs(l).single { it.text == "1" }
        assertTrue(sup.x > int.x)
    }

    @Test
    fun `square root draws a radical and moves the body right of it`() {
        val l = layout("\\sqrt{x}")
        val radical = polys(l).single()
        val body = glyphs(l).single()
        assertEquals(10, radical.pts.size)
        assertTrue(body.x > 0f)
        assertTrue(l.width > body.x + 0.5f * size - eps)
        // The top of the radical is above the body.
        assertTrue(radical.pts[radical.pts.size - 1] < body.y - 0.4f * size)
    }

    @Test
    fun `root index is drawn small`() {
        val l = layout("\\sqrt[3]{x}")
        val idx = glyphs(l).single { it.text == "3" }
        assertEquals(0.5f * size, idx.sizePx, eps)
    }

    @Test
    fun `left right delimiters grow around a tall body and stay glyphs around a short one`() {
        val short = layout("\\left( x \\right)")
        assertTrue(polys(short).isEmpty())
        assertEquals(2, glyphs(short).count { it.text == "(" || it.text == ")" })
        val tall = layout("\\left( \\frac{a}{b} \\right)", display = true)
        assertEquals(2, polys(tall).size)
        val box = tall.height
        assertTrue(box > 1.5f * size)
    }

    @Test
    fun `fixed size delimiters are drawn at the requested height`() {
        val l = layout("\\Bigg(")
        val p = polys(l).single()
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 1
        while (i < p.pts.size) {
            minY = minOf(minY, p.pts[i])
            maxY = maxOf(maxY, p.pts[i])
            i += 2
        }
        assertEquals(3.0f * size, maxY - minY, 0.1f)
        // Centred on the math axis.
        assertEquals(-0.25f * size, (minY + maxY) / 2f, 0.1f)
    }

    @Test
    fun `matrix lays cells on a grid`() {
        val l = layout("\\begin{matrix} a & b \\\\ c & d \\end{matrix}")
        val a = glyphs(l).single { it.text == "a" }
        val b = glyphs(l).single { it.text == "b" }
        val c = glyphs(l).single { it.text == "c" }
        val d = glyphs(l).single { it.text == "d" }
        assertEquals(a.y, b.y, eps)
        assertEquals(c.y, d.y, eps)
        assertTrue(c.y > a.y)
        assertEquals(a.x, c.x, eps)
        assertEquals(b.x, d.x, eps)
        assertTrue(b.x > a.x + 0.5f * size)
    }

    @Test
    fun `matrix is centred on the math axis`() {
        val l = layout("\\begin{matrix} a \\\\ b \\end{matrix}")
        val top = glyphs(l).single { it.text == "a" }.y
        val bottom = glyphs(l).single { it.text == "b" }.y
        // Each row is a strut 0.7em above and 0.3em below its baseline; the two struts together
        // are centred on the axis.
        assertEquals(-0.25f * size, ((top - 0.7f * size) + (bottom + 0.3f * size)) / 2f, eps)
    }

    @Test
    fun `pmatrix draws stretched parentheses`() {
        val l = layout("\\begin{pmatrix} a \\\\ b \\end{pmatrix}")
        assertEquals(2, polys(l).size)
    }

    @Test
    fun `align columns line up on the relation`() {
        val l = layout("\\begin{align} a &= b \\\\ \\mathrm{cc} &= d \\end{align}", display = true)
        val eqs = glyphs(l).filter { it.text == "=" }
        assertEquals(2, eqs.size)
        assertEquals(eqs[0].x, eqs[1].x, eps)
        // The left column is right-aligned: `a` ends where `cc` ends.
        val a = glyphs(l).single { it.text == "a" }
        val cc = glyphs(l).single { it.text == "cc" }
        assertEquals(a.x + 0.5f * size, cc.x + 2 * 0.5f * size, eps)
    }

    @Test
    fun `relation starting an align cell gets relation spacing like amsmath`() {
        val l = layout("\\begin{align} a &= b \\\\ c &= d \\end{align}", display = true)
        val a = glyphs(l).single { it.text == "a" }
        val eq = glyphs(l).first { it.text == "=" }
        // `a` is right-aligned in a column exactly as wide as itself; `=` follows after a thick space.
        assertEquals(a.x + 0.5f * size + (5f / 18f) * size, eq.x, eps)
    }

    @Test
    fun `cases are left aligned with a brace on the left only`() {
        val l = layout("\\begin{cases} a & b \\\\ \\mathrm{ccc} & d \\end{cases}")
        assertEquals(1, polys(l).size)
        val a = glyphs(l).single { it.text == "a" }
        val ccc = glyphs(l).single { it.text == "ccc" }
        assertEquals(a.x, ccc.x, eps)
    }

    @Test
    fun `accents add marks above the body`() {
        val hat = layout("\\hat{x}")
        assertEquals(1, polys(hat).size)
        assertTrue(polys(hat)[0].pts.all { !it.isNaN() })
        assertTrue(hat.ascent > layout("x").ascent)
        val bar = layout("\\overline{xyz}")
        assertEquals(1, rules(bar).size)
        assertEquals(bar.width, rules(bar)[0].w, eps)
        assertTrue(rules(bar)[0].y < 0f)
        val under = layout("\\underline{xyz}")
        assertTrue(rules(under)[0].y > 0f)
        assertTrue(under.descent > 0f)
    }

    @Test
    fun `overbrace puts its label above`() {
        val l = layout("\\overbrace{abc}^{n}", display = true)
        val label = glyphs(l).single { it.text == "n" }
        val body = glyphs(l).single { it.text == "a" }
        assertTrue(label.y < body.y)
        assertEquals(1, polys(l).size)
    }

    @Test
    fun `text runs keep their spaces`() {
        val l = layout("x \\text{ for all } y")
        assertTrue(glyphs(l).any { it.text == " for all " && it.variant == MathVariant.TEXT })
    }

    @Test
    fun `explicit spacing widens the layout`() {
        val tight = layout("ab").width
        assertEquals(tight + size, layout("a\\quad b").width, eps)
        assertEquals(tight + (3f / 18f) * size, layout("a\\,b").width, eps)
    }

    @Test
    fun `big operator box is centred on the axis`() {
        val l = layout("\\sum", display = true)
        assertEquals(l.ascent - 0.25f * size, l.descent + 0.25f * size, 0.01f * size)
    }

    @Test
    fun `display and inline layouts differ only in style`() {
        val a = layout("x^2 + y")
        val b = layout("x^2 + y", display = true)
        assertEquals(a.width, b.width, eps)
    }

    @Test
    fun `layout is finite for a broad sample`() {
        val samples = listOf(
            "\\frac{\\partial^2 f}{\\partial x^2}", "\\sum_{k=0}^{n} \\binom{n}{k} x^k",
            "\\int_{-\\infty}^{\\infty} e^{-t^2/2}\\,dt", "\\left\\{ \\begin{array}{ll} 1 & x>0 \\\\ 0 & x \\le 0 \\end{array} \\right.",
            "\\prod_{i=1}^{n} (1 + x_i)", "\\vec{a} \\times \\vec{b}", "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1",
            "\\sqrt{\\frac{1}{n}\\sum_{i=1}^n (x_i - \\bar{x})^2}", "\\mathbb{P}(A \\mid B)",
            "\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}^{-1}", "x \\pmod{n}", "a \\bmod b",
            "\\xrightarrow{f} \\overset{!}{=} \\underset{n}{\\max}", "\\substack{i<j \\\\ j<n}",
            "\\frac{a}{\\frac{b}{\\frac{c}{d}}}", "\\hat\\theta_{\\mathrm{MLE}}", "{}^{14}\\mathrm{C}",
        )
        for (src in samples) for (display in listOf(false, true)) {
            val l = MathTypesetter.layout(src, display, size, HalfEm)
            assertNotNull(src, l)
            l!!
            assertTrue(src, l.width.isFinite() && l.ascent.isFinite() && l.descent.isFinite())
            assertTrue(src, l.width > 0f)
            for (op in l.ops) when (op) {
                is MathOp.Glyphs -> assertTrue(src, op.x.isFinite() && op.y.isFinite())
                is MathOp.Rule -> assertTrue(src, op.x.isFinite() && op.y.isFinite() && op.w >= 0f && op.h >= 0f)
                is MathOp.Poly -> assertTrue(src, op.pts.all { it.isFinite() })
            }
        }
    }
}
