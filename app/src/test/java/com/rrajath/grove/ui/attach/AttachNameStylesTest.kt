package com.rrajath.grove.ui.attach

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachNameStylesTest {

    private val style = SpanStyle(fontWeight = FontWeight.Bold)

    @Test
    fun `the argument is filled in and is the only styled run`() {
        val out = withStyledArg(":DIR: property detected. Attachment(s) will be moved to %1\$s", "photos", style)
        assertEquals(":DIR: property detected. Attachment(s) will be moved to photos", out.text)
        val span = out.spanStyles.single()
        assertEquals("photos", out.text.substring(span.start, span.end))
        assertEquals(style, span.item)
    }

    @Test
    fun `an argument mid-sentence keeps the text on both sides`() {
        val out = withStyledArg("Moved to %1\$s, done", "a/b", style)
        assertEquals("Moved to a/b, done", out.text)
        val span = out.spanStyles.single()
        assertEquals(9 to 12, span.start to span.end)
    }

    @Test
    fun `a template without the placeholder is returned unstyled`() {
        val out = withStyledArg("No folder here", "photos", style)
        assertEquals("No folder here", out.text)
        assertEquals(0, out.spanStyles.size)
    }
}
