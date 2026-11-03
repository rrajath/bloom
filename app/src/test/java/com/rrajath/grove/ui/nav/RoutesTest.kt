package com.rrajath.grove.ui.nav

import com.rrajath.grove.ui.vault.NoteRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun `outline route encodes notebook id`() {
        assertEquals("outline/travel.org", Routes.outline("travel.org"))
        // Spaces must encode as %20 (not +): androidx.navigation decodes via Uri.decode,
        // which leaves a literal + intact and would break exact-string matching.
        assertEquals("outline/my%20notes.org", Routes.outline("my notes.org"))
        assertEquals("outline/a%2Fb.org", Routes.outline("a/b.org"))
    }

    @Test
    fun `encode uses percent-20 for spaces and round-trips through Uri-style decode`() {
        // Regression for the reminder reschedule deep link (and multi-word note ids /
        // search queries): every real org heading has a space. Emulate Uri.decode
        // (only undoes %XX) to prove the encoded form survives NavDeepLink decoding.
        for (raw in listOf("Buy milk", "A/B path", "café résumé", "plus + and space")) {
            val encoded = Routes.encode(raw)
            assertFalse("space must not encode as '+': $encoded", encoded.contains('+'))
            assertEquals(raw, uriStyleDecode(encoded))
        }
    }

    /** Minimal stand-in for android.net.Uri.decode: undoes %XX (UTF-8), leaves + literal. */
    private fun uriStyleDecode(s: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                bytes.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(c.code)
                i += 1
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    @Test
    fun `nested notebook path round-trips through the outline path arg`() {
        // The vault is a tree now: ids like "projects/clients/acme.org" flow
        // through outline/{notebookId}. Every "/" must survive as %2F and come
        // back intact through androidx.navigation's Uri.decode.
        val id = "projects/clients/acme.org"
        val route = Routes.outline(id)
        assertEquals("outline/projects%2Fclients%2Facme.org", route)
        assertFalse(route.removePrefix("outline/").contains('/'))
        assertEquals(id, uriStyleDecode(route.removePrefix("outline/")))
    }

    @Test
    fun `nested note composite round-trips and NoteRef decodes it`() {
        val noteId = "projects/clients/acme.org@42"
        val route = Routes.note(noteId)
        assertEquals("note/projects%2Fclients%2Facme.org%4042?mode=read&isNew=false", route)

        val arg = route.removePrefix("note/").substringBefore('?')
        val decodedArg = uriStyleDecode(arg)
        assertEquals(noteId, decodedArg)

        val ref = NoteRef.decode(decodedArg)!!
        assertEquals("projects/clients/acme.org", ref.fileName)
        assertEquals(42, ref.lineIndex)
    }

    @Test
    fun `intro NoteRef round-trips through the note route`() {
        val ref = NoteRef.intro("a/b/roam.org")
        assertTrue(ref.isIntro)
        val decodedArg = uriStyleDecode(Routes.note(ref.encode()).removePrefix("note/").substringBefore('?'))
        val back = NoteRef.decode(decodedArg)!!
        assertEquals("a/b/roam.org", back.fileName)
        assertTrue(back.isIntro)
    }

    @Test
    fun `nested note composite with a custom id round-trips`() {
        val noteId = "a/b/c.org@7#my-heading"
        val decodedArg = uriStyleDecode(Routes.note(noteId).removePrefix("note/").substringBefore('?'))
        assertEquals(noteId, decodedArg)
        val ref = NoteRef.decode(decodedArg)!!
        assertEquals("a/b/c.org", ref.fileName)
        assertEquals(7, ref.lineIndex)
        assertEquals("my-heading", ref.customId)
    }

    @Test
    fun `note route defaults to read mode, isNew false`() {
        assertEquals("note/abc-123?mode=read&isNew=false", Routes.note("abc-123"))
        assertEquals("note/abc-123?mode=edit&isNew=false", Routes.note("abc-123", mode = "edit"))
        assertEquals(
            "note/abc-123?mode=edit&isNew=true",
            Routes.note("abc-123", mode = "edit", isNew = true),
        )
    }

    @Test
    fun `drawer route carries kind and an optional owning note id`() {
        assertEquals("drawer/notes.org?kind=fileProps", Routes.drawer("notes.org", "fileProps"))
        assertEquals(
            "drawer/notes.org?kind=headingProps&noteId=notes.org%4012%23h",
            Routes.drawer("notes.org", "headingProps", "notes.org@12#h"),
        )
        // The built form must still start with the path the NavHost registers.
        assertTrue(Routes.drawer("notes.org", "fileProps").startsWith(Routes.DRAWER.substringBefore("{")))
    }

    @Test
    fun `capture route with and without template`() {
        assertEquals("capture", Routes.capture())
        assertEquals("capture/journal", Routes.capture("journal"))
    }

    @Test
    fun `capture path matches launcher shortcut deep links`() {
        // ShortcutSyncer publishes bloom://capture/<id> per template; the path
        // after the scheme must equal the route the NavHost matches via Routes.capture(id).
        assertEquals("capture/builtin-journal", Routes.capture("builtin-journal"))
        assertEquals("capture/builtin-quick-note", Routes.capture("builtin-quick-note"))
    }

    @Test
    fun `search route carries an optional pinned notebook`() {
        assertEquals("search?q=", Routes.search())
        assertEquals("search?q=ramen", Routes.search("ramen"))
        // The outline's search action pins the notebook with no query typed yet.
        assertEquals("search?q=&notebook=travel.org", Routes.search(notebook = "travel.org"))
        assertEquals(
            "search?q=ramen&notebook=my%20notes.org",
            Routes.search("ramen", notebook = "my notes.org"),
        )
        // Every built form must still match the pattern the NavHost registers.
        assertEquals(
            Routes.SEARCH.substringBefore("{"),
            Routes.search().substringBefore("&"),
        )
    }

    @Test
    fun `conflict route encodes notebook id`() {
        assertEquals("conflict/journal.org", Routes.conflict("journal.org"))
    }

    @Test
    fun `preface route encodes the file name`() {
        assertEquals("preface/travel.org", Routes.preface("travel.org"))
        assertEquals("preface/my%20notes.org", Routes.preface("my notes.org"))
        assertEquals(
            Routes.PREFACE.substringBefore("{"),
            Routes.preface("x").substringBefore("x"),
        )
    }

    @Test
    fun `outline route omits narrowTo by default and appends it when set`() {
        assertEquals("outline/travel.org", Routes.outline("travel.org"))
        assertEquals("outline/travel.org?narrowTo=42", Routes.outline("travel.org", narrowTo = 42))
    }

    @Test
    fun `outline route carries auto only when opening the notebook itself`() {
        assertEquals("outline/travel.org?auto=true", Routes.outline("travel.org", autoOpen = true))
        assertEquals(
            "outline/travel.org?narrowTo=42&auto=true",
            Routes.outline("travel.org", narrowTo = 42, autoOpen = true),
        )
        assertFalse(Routes.outline("travel.org").contains("auto"))
    }

    @Test
    fun `outline route carries reveal only when set`() {
        assertEquals("outline/travel.org?reveal=7", Routes.outline("travel.org", reveal = 7))
        assertFalse(Routes.outline("travel.org").contains("reveal"))
        assertTrue(Routes.OUTLINE.contains("reveal={reveal}"))
    }

    @Test
    fun `file route encodes the file name and defaults to read mode`() {
        assertEquals("file/my%20notes.org?mode=read", Routes.file("my notes.org"))
        assertEquals("file/a%2Fb.org?mode=edit", Routes.file("a/b.org", mode = "edit"))
        assertEquals(Routes.FILE.substringBefore("{"), Routes.file("x").substringBefore("x"))
    }

    @Test
    fun `attachment viewer route encodes the vault path and title`() {
        val route = Routes.attachmentViewer("data/ab/cd-ef/torii path.jpg", "torii path.jpg")
        assertEquals("attachment/view?path=data%2Fab%2Fcd-ef%2Ftorii%20path.jpg&title=torii%20path.jpg", route)
        assertEquals("data/ab/cd-ef/torii path.jpg", uriStyleDecode(route.substringAfter("path=").substringBefore("&")))
        assertTrue(route.startsWith(Routes.ATTACHMENT_VIEWER.substringBefore("{")))
    }

    @Test
    fun `built routes match declared patterns`() {
        // outline/{notebookId}
        assertEquals(
            Routes.OUTLINE.substringBefore("{"),
            Routes.outline("x").substringBefore("x"),
        )
        // note/{noteId}?mode={mode}&isNew={isNew}
        assertEquals(
            Routes.NOTE.substringBefore("{"),
            Routes.note("x").substringBefore("x"),
        )
    }
}
