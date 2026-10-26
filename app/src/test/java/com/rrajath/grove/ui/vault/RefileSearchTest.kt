package com.rrajath.grove.ui.vault

import com.rrajath.grove.data.NoteOutlineRow
import com.rrajath.grove.data.NotebookEntity
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.ui.editor.LinkFileHit
import com.rrajath.grove.ui.editor.LinkHeadingHit
import com.rrajath.grove.ui.editor.LinkSearchItem
import com.rrajath.grove.ui.editor.buildLinkSearchIndex
import com.rrajath.grove.ui.editor.filterLinkSearch
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refile picker live search: source-subtree exclusion and the drill-down state a picked hit leaves. */
class RefileSearchTest {

    private val projectsText = """
        * Ship release
        ** Cut changelog
        ** Tag release
        *** Push tag
        * Backlog
        ** Release notes draft
    """.trimIndent() + "\n"

    private val inboxText = """
        * Release ideas
        * Errands
    """.trimIndent() + "\n"

    private val projects: OrgDocument = OrgParser.parse(projectsText)
    private val inbox: OrgDocument = OrgParser.parse(inboxText)

    private fun outlines(file: String, doc: OrgDocument) =
        doc.headlines.map { NoteOutlineRow(file, it.lineIndex, it.level, it.title) }

    private val index: List<LinkSearchItem> = buildLinkSearchIndex(
        notebooks = listOf(
            NotebookEntity("inbox.org", "r", 2, 0L, null),
            NotebookEntity("projects.org", "r", 2, 0L, null),
        ),
        headings = outlines("inbox.org", inbox) + outlines("projects.org", projects),
    )

    private fun line(doc: OrgDocument, title: String) = doc.headlines.first { it.title == title }.lineIndex

    private fun headingHit(file: String, doc: OrgDocument, title: String): LinkHeadingHit =
        index.filterIsInstance<LinkHeadingHit>().first { it.fileName == file && it.lineIndex == line(doc, title) }

    private fun titles(hits: List<LinkSearchItem>) = hits.map { it.title }

    // --- exclusion ---------------------------------------------------------

    @Test
    fun `excluded ranges span each source headline through its last descendant`() {
        val ranges = refileExcludedRanges(projects, listOf(line(projects, "Ship release")))
        assertEquals(listOf(line(projects, "Ship release") until line(projects, "Backlog")), ranges)
    }

    @Test
    fun `no active note means no exclusion`() {
        assertEquals(emptyList<IntRange>(), refileExcludedRanges(null, listOf(0)))
    }

    @Test
    fun `search drops the refiled heading and every descendant, keeping the rest of the file`() {
        val excluded = refileExcludedRanges(projects, listOf(line(projects, "Tag release")))
        val hits = filterRefileSearch(index, "release", "projects.org", excluded)
        assertEquals(listOf("Release ideas", "Ship release", "Release notes draft"), titles(hits))
        assertFalse(titles(filterRefileSearch(index, "push", "projects.org", excluded)).contains("Push tag"))
    }

    @Test
    fun `same line numbers in another file are never excluded`() {
        // "Release ideas" is line 0 of inbox.org, inside projects.org's line-0 subtree's range.
        val excluded = refileExcludedRanges(projects, listOf(line(projects, "Ship release")))
        assertTrue(titles(filterRefileSearch(index, "release ideas", "projects.org", excluded)).contains("Release ideas"))
    }

    @Test
    fun `the source file itself stays a valid top-level target`() {
        val excluded = refileExcludedRanges(projects, listOf(line(projects, "Ship release")))
        val hits = filterRefileSearch(index, "projects", "projects.org", excluded)
        assertEquals(listOf("projects"), titles(hits))
        assertTrue(hits.single() is LinkFileHit)
    }

    @Test
    fun `bulk refile excludes every selected subtree`() {
        val excluded = refileExcludedRanges(
            projects, listOf(line(projects, "Ship release"), line(projects, "Backlog")),
        )
        assertEquals(listOf("Release ideas"), titles(filterRefileSearch(index, "release", "projects.org", excluded)))
    }

    @Test
    fun `excluded hits never count toward the result limit`() {
        // "Release ideas" (inbox.org line 0) would be the first match; excluded, the limit-1 slot goes to the next one.
        val hits = filterLinkSearch(index, "release", limit = 1) { isExcludedRefileHit(it, "inbox.org", listOf(0..0)) }
        assertEquals(listOf("Ship release"), titles(hits))
    }

    @Test
    fun `a blank query returns nothing`() {
        assertEquals(emptyList<LinkSearchItem>(), filterRefileSearch(index, "  ", null, emptyList()))
    }

    // --- picking a hit -----------------------------------------------------

    private val picker = RefileUiState(sourceLine = 0)

    @Test
    fun `picking a heading drills its full ancestor path, ending at the heading`() {
        val hit = headingHit("projects.org", projects, "Push tag")
        val next = picker.withSearchHit(hit, projects, emptyList())
        assertNotNull(next)
        assertEquals("projects.org", next!!.pickedFile)
        assertEquals(
            listOf(line(projects, "Ship release"), line(projects, "Tag release"), line(projects, "Push tag")),
            next.path,
        )
    }

    @Test
    fun `picking a file sits at its top level`() {
        val hit = index.filterIsInstance<LinkFileHit>().first { it.fileName == "inbox.org" }
        val next = picker.copy(pickedFile = "projects.org", pickedDoc = projects, path = persistentListOf(4))
            .withSearchHit(hit, inbox, emptyList())
        assertEquals("inbox.org", next!!.pickedFile)
        assertTrue(next.path.isEmpty())
    }

    @Test
    fun `picking keeps the rest of the picker state`() {
        val withSources = picker.copy(sourceLines = listOf(0, 4))
        val next = withSources.withSearchHit(headingHit("inbox.org", inbox, "Errands"), inbox, emptyList())
        assertEquals(listOf(0, 4), next!!.sourceLines)
    }

    @Test
    fun `a hit inside a refiled subtree is rejected even if it slipped through the filter`() {
        val excluded = refileExcludedRanges(projects, listOf(line(projects, "Ship release")))
        assertNull(picker.withSearchHit(headingHit("projects.org", projects, "Push tag"), projects, excluded))
    }

    @Test
    fun `a stale hit whose line now holds a different heading is rejected`() {
        val hit = headingHit("projects.org", projects, "Backlog")
        val edited = OrgParser.parse("* Something else\n" + projectsText)
        assertNull(picker.withSearchHit(hit, edited, emptyList()))
    }

    @Test
    fun `a hit past the end of the file is rejected`() {
        val hit = LinkHeadingHit("inbox.org", 99, "Gone", "inbox")
        assertNull(picker.withSearchHit(hit, inbox, emptyList()))
    }
}
