package com.rrajath.grove.ui.attach

import com.rrajath.grove.attach.AttachmentLink
import com.rrajath.grove.attach.AttachmentStatus
import com.rrajath.grove.attach.AttachmentStatusCache
import com.rrajath.grove.search.Snippets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M8 (1i/1j) logic: chip spans in rendered lines, link-safe preview cuts, chip-aware snippet windows. */
class AttachmentPreviewChipsTest {

    @Test
    fun `rendered line swaps each attachment link for its label and records the span`() {
        val line = "  Kept the [[attachment:receipt.pdf]] and a [[attachment:menu.jpg][menu photo]], see [[https://x.com][site]]"
        val r = renderedWithChips(line)
        assertEquals("Kept the receipt.pdf and a menu photo, see site", r.text)
        assertEquals(listOf("receipt.pdf", "menu photo"), r.chips.map { r.text.substring(it.range.first, it.range.last + 1) })
        assertEquals(listOf("attachment:receipt.pdf", "attachment:menu.jpg"), r.chips.map { it.rawTarget })
    }

    @Test
    fun `preview chips cover the raw link syntax`() {
        val text = "Lunch [[attachment:receipt.pdf]] done"
        val chip = rawPreviewChips(text).single()
        assertEquals("[[attachment:receipt.pdf]]", text.substring(chip.range.first, chip.range.last + 1))
    }

    @Test
    fun `link safe take cuts before a link it would split`() {
        val text = "abc [[attachment:receipt.pdf]] tail"
        assertEquals("abc ", linkSafeTake(text, 10))
        assertEquals("abc [[attachment:receipt.pdf]]", linkSafeTake(text, 30))
        assertEquals("abc [[attachment:receipt.pdf]] t", linkSafeTake(text, 32))
        assertEquals("plain text", linkSafeTake("plain text that is long", 10))
        assertEquals("short", linkSafeTake("short", 240))
    }

    @Test
    fun `snippet window widens to keep a multi-word chip whole`() {
        // Radius 1 around "receipt" would end inside "menu photo"; the chip pulls the window to its end.
        val line = "receipt and menu photo here then more"
        val chip = line.indexOf("menu")..(line.indexOf("photo") + 4)
        val w = Snippets.windowRanges(line, listOf("receipt"), radius = 2, atomic = listOf(chip)).single()
        assertEquals("receipt and menu photo…", w.text(line))
        val plain = Snippets.windowRanges(line, listOf("receipt"), radius = 2).single()
        assertEquals("receipt and menu…", plain.text(line))
    }

    @Test
    fun `snippet window widens backwards too`() {
        val line = "start menu photo receipt end"
        val chip = line.indexOf("menu")..(line.indexOf("photo") + 4)
        val w = Snippets.windowRanges(line, listOf("receipt"), radius = 1, atomic = listOf(chip)).single()
        assertEquals("…menu photo receipt end", w.text(line))
        assertEquals(1, w.offset)
    }

    @Test
    fun `known missing only answers what read mode already found`() {
        val cache = AttachmentStatusCache()
        assertFalse(cache.knownMissing("n.org", "attachment:a.jpg"))
        cache.putStatus(AttachmentStatusCache.key("n.org", "attachment:a.jpg", 7), AttachmentStatus.Missing)
        assertTrue(cache.knownMissing("n.org", "attachment:a.jpg"))
        assertFalse(cache.knownMissing("other.org", "attachment:a.jpg"))
        assertFalse(cache.knownMissing("n.org", "attachment:a.jp"))
    }

    @Test
    fun `image links draw as chips in previews`() {
        val chip = renderedWithChips("[[attachment:torii-path.jpg]]").chips.single()
        assertTrue(chip.link.isImage)
        assertEquals(AttachmentLink.classify("attachment:torii-path.jpg", null), chip.link)
    }
}
