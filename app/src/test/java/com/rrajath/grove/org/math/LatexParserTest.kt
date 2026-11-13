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
import com.rrajath.grove.org.math.MathNode.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexParserTest {

    private fun parse(src: String): MathNode = LatexParser.parse(src)!!

    /** The top-level nodes of [src], unwrapping the single outer row. */
    private fun nodes(src: String): List<MathNode> = (parse(src) as Row).nodes

    private fun glyph(text: String, atom: Atom, variant: MathVariant = MathVariant.UPRIGHT) = Glyphs(text, atom, variant)

    @Test
    fun `letters are italic ordinary symbols and digits upright`() {
        assertEquals(listOf(glyph("x", Atom.ORD, MathVariant.ITALIC), glyph("2", Atom.ORD)), nodes("x2"))
    }

    @Test
    fun `adjacent digits merge into one run`() {
        assertEquals(listOf(glyph("123", Atom.ORD)), nodes("123"))
        assertEquals(listOf(glyph("3.14", Atom.ORD)), nodes("3.14"))
    }

    @Test
    fun `operators get their atom classes`() {
        val atoms = nodes("a+b=c,d(e)").map { (it as Glyphs).atom }
        assertEquals(
            listOf(Atom.ORD, Atom.BIN, Atom.ORD, Atom.REL, Atom.ORD, Atom.PUNCT, Atom.ORD, Atom.OPEN, Atom.ORD, Atom.CLOSE),
            atoms,
        )
        assertEquals("−", (nodes("-")[0] as Glyphs).text)
    }

    @Test
    fun `superscript and subscript attach to the preceding atom`() {
        val s = nodes("a_i^2").single() as Scripts
        assertEquals(glyph("a", Atom.ORD, MathVariant.ITALIC), s.base)
        assertEquals(glyph("i", Atom.ORD, MathVariant.ITALIC), s.sub)
        assertEquals(glyph("2", Atom.ORD), s.sup)
        assertNull(s.limits)
    }

    @Test
    fun `script argument is one token without braces`() {
        val s = nodes("x^23").first() as Scripts
        assertEquals(glyph("2", Atom.ORD), s.sup)
        assertEquals(glyph("3", Atom.ORD), nodes("x^23")[1])
    }

    @Test
    fun `braced script argument is a row`() {
        val s = nodes("e^{-x^2}").single() as Scripts
        val sup = s.sup as Row
        assertEquals(2, sup.nodes.size)
        assertTrue(sup.nodes[1] is Scripts)
    }

    @Test
    fun `primes become a superscript`() {
        val s = nodes("f'").single() as Scripts
        assertEquals("′", (s.sup as Glyphs).text)
        assertEquals("″", ((nodes("f''").single() as Scripts).sup as Glyphs).text)
    }

    @Test
    fun `fraction takes two arguments`() {
        val f = nodes("\\frac{a}{b}").single() as Frac
        assertEquals(Row(listOf(glyph("a", Atom.ORD, MathVariant.ITALIC))), f.num)
        assertTrue(f.bar)
        assertNull(f.style)
    }

    @Test
    fun `unbraced fraction arguments are single tokens`() {
        val f = nodes("\\frac12").single() as Frac
        assertEquals(glyph("1", Atom.ORD), f.num)
        assertEquals(glyph("2", Atom.ORD), f.den)
    }

    @Test
    fun `dfrac and tfrac force a style and binom is a delimited barless fraction`() {
        assertEquals(0, (nodes("\\dfrac{a}{b}").single() as Frac).style)
        assertEquals(1, (nodes("\\tfrac{a}{b}").single() as Frac).style)
        val b = nodes("\\binom{n}{k}").single() as Delimited
        assertEquals("(", b.left)
        assertEquals(")", b.right)
        assertEquals(false, (b.body as Frac).bar)
    }

    @Test
    fun `infix over splits the row`() {
        val f = nodes("{a \\over b}").single().let { (it as Row).nodes.single() as Frac }
        assertEquals(glyph("a", Atom.ORD, MathVariant.ITALIC), (f.num as Row).nodes.single())
    }

    @Test
    fun `sqrt with and without index`() {
        val plain = nodes("\\sqrt{x}").single() as Sqrt
        assertNull(plain.index)
        val cube = nodes("\\sqrt[3]{x}").single() as Sqrt
        assertEquals(Row(listOf(glyph("3", Atom.ORD))), cube.index)
    }

    @Test
    fun `greek and symbol commands`() {
        assertEquals(glyph("α", Atom.ORD, MathVariant.ITALIC), nodes("\\alpha").single())
        assertEquals(glyph("Γ", Atom.ORD), nodes("\\Gamma").single())
        assertEquals(glyph("≤", Atom.REL), nodes("\\leq").single())
        assertEquals(glyph("×", Atom.BIN), nodes("\\times").single())
        assertEquals(glyph("∞", Atom.ORD), nodes("\\infty").single())
        assertEquals(glyph("ϵ", Atom.ORD, MathVariant.ITALIC), nodes("\\epsilon").single())
        assertEquals(glyph("ε", Atom.ORD, MathVariant.ITALIC), nodes("\\varepsilon").single())
    }

    @Test
    fun `negated relation`() {
        assertEquals("≠", (nodes("\\not=").single() as Glyphs).text)
        assertEquals("∉", (nodes("\\not\\in").single() as Glyphs).text)
    }

    @Test
    fun `big operators carry limits and size flags`() {
        val sum = nodes("\\sum").single() as Glyphs
        assertTrue(sum.limits && sum.big)
        assertEquals(Atom.OP, sum.atom)
        val int = nodes("\\int").single() as Glyphs
        assertTrue(!int.limits && int.big)
    }

    @Test
    fun `named functions are upright operators`() {
        val sin = nodes("\\sin").single() as Glyphs
        assertEquals("sin", sin.text)
        assertEquals(Atom.OP, sin.atom)
        assertEquals(MathVariant.UPRIGHT, sin.variant)
        assertTrue((nodes("\\lim").single() as Glyphs).limits)
        assertTrue(!(sin.limits))
    }

    @Test
    fun `limits and nolimits modifiers are recorded`() {
        assertEquals(true, (nodes("\\sum\\limits_a^b").single() as Scripts).limits)
        assertEquals(false, (nodes("\\sum\\nolimits_a^b").single() as Scripts).limits)
    }

    @Test
    fun `operatorname makes an upright operator`() {
        val g = nodes("\\operatorname{Var}").single() as Glyphs
        assertEquals("Var", g.text)
        assertEquals(Atom.OP, g.atom)
        assertTrue((nodes("\\operatorname*{argmax}").single() as Glyphs).limits)
    }

    @Test
    fun `font commands restyle their argument`() {
        assertEquals(MathVariant.UPRIGHT, (nodes("\\mathrm{d}").single() as Glyphs).variant)
        assertEquals(MathVariant.BOLD, (nodes("\\mathbf{v}").single() as Glyphs).variant)
        assertEquals(MathVariant.BOLD_ITALIC, (nodes("\\boldsymbol{v}").single() as Glyphs).variant)
        assertEquals("ℝ", (nodes("\\mathbb{R}").single() as Glyphs).text)
        assertEquals("ℕ", (nodes("\\mathbb N").single() as Glyphs).text)
        assertEquals("𝔼", (nodes("\\mathbb{E}").single() as Glyphs).text)
        assertEquals("ℒ", (nodes("\\mathcal{L}").single() as Glyphs).text)
    }

    @Test
    fun `text keeps its spaces and resolves escapes`() {
        val t = nodes("\\text{if } x").first() as Glyphs
        assertEquals("if ", t.text)
        assertEquals(MathVariant.TEXT, t.variant)
        assertEquals("50% of", (nodes("\\text{50\\% of}").single() as Glyphs).text)
    }

    @Test
    fun `left right pair becomes a delimited node`() {
        val d = nodes("\\left( x \\right]").single() as Delimited
        assertEquals("(", d.left)
        assertEquals("]", d.right)
        val dot = nodes("\\left\\{ x \\right.").single() as Delimited
        assertEquals("{", dot.left)
        assertEquals("", dot.right)
        val angle = nodes("\\left\\langle x \\right\\rangle").single() as Delimited
        assertEquals("⟨", angle.left)
        assertEquals("⟩", angle.right)
    }

    @Test
    fun `missing right delimiter is tolerated`() {
        val d = nodes("\\left( x").single() as Delimited
        assertEquals("", d.right)
    }

    @Test
    fun `sized delimiters`() {
        val d = nodes("\\bigl( x \\bigr)")
        assertEquals(Delim("(", 1.2f, Atom.OPEN), d[0])
        assertEquals(Delim(")", 1.2f, Atom.CLOSE), d[2])
        assertEquals(Delim("|", 3.0f, Atom.ORD), nodes("\\Bigg|").single())
    }

    @Test
    fun `accents`() {
        assertEquals(AccentKind.HAT, (nodes("\\hat{x}").single() as Accent).kind)
        assertEquals(AccentKind.OVERLINE, (nodes("\\overline{AB}").single() as Accent).kind)
        assertEquals(AccentKind.VEC, (nodes("\\vec v").single() as Accent).kind)
    }

    @Test
    fun `spacing commands`() {
        assertEquals(Space(3f), nodes("\\,").single())
        assertEquals(Space(5f), nodes("\\;").single())
        assertEquals(Space(18f), nodes("\\quad").single())
        assertEquals(Space(36f), nodes("\\qquad").single())
        assertEquals(Space(-3f), nodes("\\!").single())
        assertEquals(Space(18f), nodes("\\hspace{1em}").single())
    }

    @Test
    fun `math class commands wrap their argument`() {
        val c = nodes("\\mathrel{R}").single() as Classed
        assertEquals(Atom.REL, c.atom)
    }

    @Test
    fun `unknown commands become literal text`() {
        val g = nodes("\\frobnicate").single() as Glyphs
        assertEquals("\\frobnicate", g.text)
        assertEquals(MathVariant.MONO, g.variant)
    }

    @Test
    fun `labels and numbering commands are dropped`() {
        val kinds = nodes("x \\label{eq:1} \\nonumber").filter { it !is Space }
        assertEquals(1, kinds.size)
    }

    @Test
    fun `comments are skipped`() {
        assertEquals(2, nodes("a % ignored \\frac\nb").size)
    }

    @Test
    fun `matrix environments`() {
        val t = nodes("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}").single() as Table
        assertEquals(TableKind.MATRIX, t.kind)
        assertEquals("(", t.left)
        assertEquals(")", t.right)
        assertEquals(2, t.rows.size)
        assertEquals(listOf(2, 2), t.rows.map { it.size })
    }

    @Test
    fun `trailing row separator does not add an empty row`() {
        val t = nodes("\\begin{matrix} a & b \\\\ c & d \\\\ \\end{matrix}").single() as Table
        assertEquals(2, t.rows.size)
    }

    @Test
    fun `cases environment`() {
        val t = nodes("\\begin{cases} 1 & x > 0 \\\\ 0 & \\text{else} \\end{cases}").single() as Table
        assertEquals(TableKind.CASES, t.kind)
        assertEquals("{", t.left)
        assertEquals("", t.right)
        assertEquals("l", t.aligns)
    }

    @Test
    fun `align environment`() {
        val t = nodes("\\begin{align} a &= b \\\\ c &= d \\end{align}").single() as Table
        assertEquals(TableKind.ALIGN, t.kind)
        assertEquals(2, t.rows.size)
        assertEquals(2, t.rows[0].size)
    }

    @Test
    fun `single line align collapses to a plain row`() {
        val n = nodes("\\begin{aligned} a = b \\end{aligned}").single()
        assertTrue(n is Row)
    }

    @Test
    fun `array reads its column spec`() {
        val t = nodes("\\begin{array}{lcr} a & b & c \\end{array}").single() as Table
        assertEquals("lcr", t.aligns)
        assertEquals(TableKind.ARRAY, t.kind)
    }

    @Test
    fun `top level row separators make a table`() {
        val t = parse("a \\\\ b") as Table
        assertEquals(TableKind.GATHER, t.kind)
        assertEquals(2, t.rows.size)
    }

    @Test
    fun `fragment body that is a whole environment parses`() {
        assertNotNull(LatexParser.parse("\\begin{equation*} x = 1 \\end{equation*}"))
    }

    @Test
    fun `stray closers and unbalanced groups do not throw`() {
        assertNotNull(LatexParser.parse("a } b"))
        assertNotNull(LatexParser.parse("{ a"))
        assertNotNull(LatexParser.parse("\\frac{a"))
        assertNotNull(LatexParser.parse("\\right) x"))
        assertNotNull(LatexParser.parse("^2"))
        assertNotNull(LatexParser.parse("x^"))
        assertNotNull(LatexParser.parse("\\"))
        assertNotNull(LatexParser.parse("\\begin{pmatrix} a & b"))
    }

    @Test
    fun `absurd nesting is rejected instead of overflowing the stack`() {
        val deep = "{".repeat(500) + "x" + "}".repeat(500)
        assertNull(LatexParser.parse(deep))
    }

    @Test
    fun `reasonable nesting is fine`() {
        val nested = "\\frac{".repeat(10) + "x" + "}{y}".repeat(10)
        assertNotNull(LatexParser.parse(nested))
    }
}
