package com.rrajath.grove.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-mode paragraph splitting around image links (tech design 7.1). */
class ParagraphImagesTest {

    private fun shape(segs: List<ParagraphSegment>): List<String> = segs.map {
        when (it) {
            is ParagraphSegment.Text -> "T:" + it.lines.joinToString("|")
            is ParagraphSegment.Image -> "I:${it.link.fileName}@${it.line}" + (it.widthAttr?.let { w -> "w$w" } ?: "")
        }
    }

    @Test
    fun `a paragraph without image links stays one text segment`() {
        val segs = splitParagraphAtImages(listOf("plain", "[[attachment:a.pdf]] chip"))
        assertEquals(listOf("T:plain|[[attachment:a.pdf]] chip"), shape(segs))
    }

    @Test
    fun `an image alone on its line becomes an image segment`() {
        val segs = splitParagraphAtImages(listOf("Before.", "[[attachment:torii.jpg]]", "After."))
        assertEquals(listOf("T:Before.", "I:torii.jpg@1", "T:After."), shape(segs))
    }

    @Test
    fun `an image inside a line splits the line`() {
        val segs = splitParagraphAtImages(listOf("Look [[file:img/a.png]] here"))
        assertEquals(listOf("T:Look", "I:a.png@0", "T:here"), shape(segs))
    }

    @Test
    fun `two images on one line, bare file links included`() {
        val segs = splitParagraphAtImages(listOf("[[attachment:a.jpg]] and file:b.webp"))
        assertEquals(listOf("I:a.jpg@0", "T:and", "I:b.webp@0"), shape(segs))
    }

    @Test
    fun `a described image link is a chip, not an image`() {
        val segs = splitParagraphAtImages(listOf("[[attachment:menu.jpg][menu photo]]"))
        assertEquals(1, segs.size)
        assertTrue(segs[0] is ParagraphSegment.Text)
    }

    @Test
    fun `ATTR_ORG width goes to an image that opens the paragraph only`() {
        assertEquals(listOf("I:a.png@0w300"), shape(splitParagraphAtImages(listOf("[[attachment:a.png]]"), 300)))
        assertEquals(
            listOf("T:text", "I:a.png@1"),
            shape(splitParagraphAtImages(listOf("text", "[[attachment:a.png]]"), 300)),
        )
    }

    @Test
    fun `svg and org targets never render as images`() {
        assertFalse(hasImageLink("[[attachment:diagram.svg]]"))
        assertFalse(hasImageLink("[[file:other.org]]"))
        assertFalse(hasImageLink("[[https://x.com/a.png]]"))
        assertTrue(hasImageLink("[[attachment:PHOTO.HEIC]]"))
    }

    @Test
    fun `attr runs and widths`() {
        assertTrue(isAttrOnlyRun(listOf("#+ATTR_ORG: :width 300", "#+attr_html: :width 50%")))
        assertFalse(isAttrOnlyRun(listOf("#+ATTR_ORG: :width 300", "#+CAPTION: x")))
        assertEquals(300, attrOrgWidth(listOf("#+ATTR_HTML: :width 900", "#+ATTR_ORG: :align left :width 300")))
        assertNull(attrOrgWidth(listOf("#+ATTR_ORG: :width 50%")))
        assertNull(attrOrgWidth(listOf("#+ATTR_HTML: :width 300")))
    }
}
