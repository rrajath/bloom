package com.rrajath.grove.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class PlaceholderExpanderTest {

    // Wednesday 2025-06-11, 14:32
    private val ctx = CaptureContext(
        now = LocalDateTime.of(2025, 6, 11, 14, 32, 45),
        clipboard = "CLIP",
        sharedText = "SHARED",
        sharedUrl = "https://shared.example",
        promptValues = mapOf("Title" to "My title"),
    )

    private fun expand(template: String) = PlaceholderExpander.expand(template, ctx)

    @Test
    fun `timestamp placeholders`() {
        // Org manual: lowercase = date only, uppercase = date + time;
        // t/T active <...>, u/U inactive [...].
        assertEquals("<2025-06-11 Wed>", expand("%t").text)
        assertEquals("<2025-06-11 Wed 14:32>", expand("%T").text)
        assertEquals("[2025-06-11 Wed]", expand("%u").text)
        assertEquals("[2025-06-11 Wed 14:32]", expand("%U").text)
    }

    @Test
    fun `date and time fragments`() {
        assertEquals("2025-06-11", expand("%date").text)
        assertEquals("14:32", expand("%time").text)
        assertEquals("Wednesday", expand("%day").text)
        assertEquals("2025", expand("%year").text)
        assertEquals("June", expand("%month").text)
    }

    @Test
    fun `longest placeholder wins`() {
        // %time must not parse as %t + "ime"
        assertEquals("14:32 <2025-06-11 Wed>", expand("%time %t").text)
    }

    @Test
    fun `external content placeholders`() {
        assertEquals("CLIP", expand("%clipboard").text)
        assertEquals("SHARED", expand("%shared_text").text)
        assertEquals("https://shared.example", expand("%shared_url").text)
    }

    @Test
    fun `prompts are extracted in order without duplicates`() {
        assertEquals(
            listOf("Title", "Where"),
            PlaceholderExpander.prompts("* %^{Title} at %^{Where} (%^{Title})"),
        )
    }

    @Test
    fun `prompt values are substituted`() {
        assertEquals("* My title", expand("* %^{Title}").text)
        assertEquals("* ", expand("* %^{Unknown}").text)
    }

    @Test
    fun `cursor position is where the marker was`() {
        val result = expand("* head\n%cursor\ntail")
        assertEquals("* head\n\ntail", result.text)
        assertEquals(7, result.cursorOffset)
        val q = expand("ab%?cd")
        assertEquals("abcd", q.text)
        assertEquals(2, q.cursorOffset)
    }

    @Test
    fun `cursor defaults to end of text`() {
        val result = expand("no marker")
        assertEquals("no marker".length, result.cursorOffset)
    }

    @Test
    fun `unknown percent sequences pass through`() {
        assertEquals("100% sure %x", expand("100% sure %x").text)
    }

    @Test
    fun `journal default template expands`() {
        val result = expand("%U\n%cursor")
        assertEquals("[2025-06-11 Wed 14:32]\n", result.text)
        assertEquals(result.text.length, result.cursorOffset)
    }

    @Test
    fun `dateOnlyStamps turns datetime stamps into their date-only forms`() {
        val rewritten = PlaceholderExpander.dateOnlyStamps("%T %U %t %u %time %date")
        assertEquals("%t %u %t %u %time %date", rewritten)
        assertEquals("<2025-06-11 Wed> [2025-06-11 Wed] <2025-06-11 Wed> [2025-06-11 Wed] 14:32 2025-06-11", expand(rewritten).text)
    }

    @Test
    fun `dateOnlyStamps leaves prompt labels and other placeholders alone`() {
        val template = "* %^{Use %U here} %shared_url %clipboard %(id)\n%U %cursor 50%Used"
        assertEquals(
            "* %^{Use %U here} %shared_url %clipboard %(id)\n%u %cursor 50%used",
            PlaceholderExpander.dateOnlyStamps(template),
        )
    }

    @Test
    fun `journal template puts the cursor on the heading line`() {
        val result = expand("%U %cursor")
        assertEquals("[2025-06-11 Wed 14:32] ", result.text)
        assertEquals(result.text.length, result.cursorOffset)
    }

    @Test
    fun `id placeholder substitutes the context id`() {
        val idCtx = ctx.copy(id = "ABCD-1234")
        assertEquals(
            "ABCD-1234",
            PlaceholderExpander.expand("%(id)", idCtx).text,
        )
    }

    @Test
    fun `id placeholder is blank when context has no id`() {
        assertEquals("", expand("%(id)").text)
    }
}
