package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Test

class MathSegmentsTest {

    private fun prose(s: String) = MathSegment.Prose(s)

    @Test
    fun `text without display math is a single prose segment`() {
        assertEquals(listOf(prose("plain words")), MathSegments.split("plain words"))
        assertEquals(listOf(prose("inline ${'$'}x${'$'} only")), MathSegments.split("inline ${'$'}x${'$'} only"))
        assertEquals(listOf(prose("inline \\(x\\) only")), MathSegments.split("inline \\(x\\) only"))
    }

    @Test
    fun `a display formula on its own is one segment`() {
        assertEquals(
            listOf(MathSegment.DisplayMath("x = 1", "${'$'}${'$'}x = 1${'$'}${'$'}")),
            MathSegments.split("${'$'}${'$'}x = 1${'$'}${'$'}"),
        )
    }

    @Test
    fun `prose before and after a display formula stays prose`() {
        val parts = MathSegments.split("so we get\n\\[ a^2 + b^2 = c^2 \\]\nwhich is Pythagoras")
        assertEquals(
            listOf(
                prose("so we get"),
                MathSegment.DisplayMath(" a^2 + b^2 = c^2 ", "\\[ a^2 + b^2 = c^2 \\]"),
                prose("which is Pythagoras"),
            ),
            parts,
        )
    }

    @Test
    fun `inline math next to display math stays inline`() {
        val parts = MathSegments.split("let ${'$'}x${'$'} be real: ${'$'}${'$'}x^2 \\ge 0${'$'}${'$'} always")
        assertEquals(3, parts.size)
        assertEquals(prose("let ${'$'}x${'$'} be real:"), parts[0])
        assertEquals(prose("always"), parts[2])
    }

    @Test
    fun `an environment is a display segment`() {
        val env = "\\begin{align}\na &= b \\\\\nc &= d\n\\end{align}"
        val parts = MathSegments.split(env)
        assertEquals(listOf(MathSegment.DisplayMath(env, env)), parts)
    }

    @Test
    fun `several display formulas`() {
        val parts = MathSegments.split("${'$'}${'$'}a${'$'}${'$'}\nthen\n${'$'}${'$'}b${'$'}${'$'}")
        assertEquals(
            listOf(
                MathSegment.DisplayMath("a", "${'$'}${'$'}a${'$'}${'$'}"),
                prose("then"),
                MathSegment.DisplayMath("b", "${'$'}${'$'}b${'$'}${'$'}"),
            ),
            parts,
        )
    }
}
