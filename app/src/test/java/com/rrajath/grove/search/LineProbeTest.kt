package com.rrajath.grove.search

import com.rrajath.grove.org.InlineTokenizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineProbeTest {

    private fun rendered(raw: String) = InlineTokenizer.tokenize(raw).joinToString("") { it.text }.trim()

    @Test
    fun `never rejects a line whose rendered text holds the term`() {
        val cases = listOf(
            "call *the bank* today" to "the bank",
            "a /quiet/ word" to "quiet word",
            "ring 833-806-1627 now" to "(833) 806",
            "ring +1 833.806.1627" to "806-1627",
            "see =code= here" to "code here",
            "visit https://example.com/path soon" to "example.com",
            "meet <2026-10-30 Fri 14:00> sharp" to "Fri 14:00",
            "Mixed CASE text" to "case TEXT",
            "c++ and more" to "c++",
            "café au lait" to "CAFÉ",
        )
        for ((raw, term) in cases) {
            assertTrue("precondition: '$term' in rendered '$raw'", rendered(raw).contains(term, ignoreCase = true))
            assertTrue("'$term' rejected for '$raw'", LineProbe(term, inTags = false).mayBeIn(raw))
        }
    }

    @Test
    fun `rejects a line missing a word of the term`() {
        assertFalse(LineProbe("magic", inTags = false).mayBeIn("nothing to see here"))
        assertFalse(LineProbe("the bank", inTags = false).mayBeIn("call the office"))
    }

    @Test
    fun `a group needs every term in the line or tags, and one in the line`() {
        val inLine = LineProbe("alpha", inTags = false)
        val tagged = LineProbe("work", inTags = true)
        assertTrue(LineProbe.mayMatch("alpha beta", listOf(inLine, tagged)))
        assertFalse(LineProbe.mayMatch("beta gamma", listOf(inLine, tagged)))
        assertFalse(LineProbe.mayMatch("beta gamma", listOf(tagged)))
    }
}
