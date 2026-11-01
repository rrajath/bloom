package com.rrajath.grove.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [orgInlineStartsRtl] picks a run's direction from its first strong character
 * as rendered (CSS `unicode-bidi: plaintext`), so Read mode can mirror a list
 * item's or heading's row to match the text inside it.
 */
class OrgInlineDirectionTest {

    @Test
    fun `persian hebrew and arabic text is rtl`() {
        assertTrue(orgInlineStartsRtl("این یک پاراگراف کوتاه است."))
        assertTrue(orgInlineStartsRtl("זוהי פסקה קצרה."))
        assertTrue(orgInlineStartsRtl("هذه فقرة قصيرة."))
    }

    @Test
    fun `latin text is ltr`() {
        assertFalse(orgInlineStartsRtl("Buy milk"))
    }

    @Test
    fun `first strong character wins in mixed text`() {
        assertTrue(orgInlineStartsRtl("من از Emacs استفاده می‌کنم"))
        assertFalse(orgInlineStartsRtl("Bloom یک برنامه است"))
    }

    @Test
    fun `leading digits and punctuation are skipped`() {
        assertTrue(orgInlineStartsRtl("3 کار برای امروز"))
        assertTrue(orgInlineStartsRtl("«نقل قول»"))
    }

    @Test
    fun `markup characters are skipped`() {
        assertTrue(orgInlineStartsRtl("*پررنگ* و /کج/"))
        assertTrue(orgInlineStartsRtl("~کد~ است"))
    }

    @Test
    fun `a link is judged by its rendered label, not its target`() {
        assertTrue(orgInlineStartsRtl("[[https://orgmode.org][سایت ارگ‌مود]] را ببینید"))
        assertFalse(orgInlineStartsRtl("[[https://orgmode.org]] را ببینید"))
    }

    @Test
    fun `text with no strong characters is ltr`() {
        assertFalse(orgInlineStartsRtl(""))
        assertFalse(orgInlineStartsRtl("123 - 456"))
    }
}
