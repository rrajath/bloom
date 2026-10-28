package com.rrajath.grove.capture

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.orgLocale
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Inserts an expanded capture entry into a document at a [TargetLocation].
 * Pure text-splice operations: every line not touched by the insertion is
 * preserved byte-for-byte.
 */
object CaptureInserter {

    data class Insertion(val newText: String, val insertedAtLine: Int, val entryLineCount: Int = 1)

    private val STARS = Regex("""^(\*+)\s+(.*)$""")

    /**
     * [bodyOnly] (only meaningful for [TargetLocation.UnderHeading]; see
     * [isBodyOnly]) inserts [entry] verbatim into the target heading's own
     * body instead of re-leveling its first line into a child heading.
     */
    fun insert(
        docText: String,
        location: TargetLocation,
        entry: String,
        today: LocalDate,
        keywords: OrgKeywords = OrgKeywords.DEFAULT,
        bodyOnly: Boolean = false,
    ): Insertion {
        return when (location) {
            is TargetLocation.TopOfFile -> {
                val doc = OrgParser.parse(docText, keywords)
                spliceEntry(docText, doc.preambleEnd, normalizeEntry(entry, 1))
            }

            is TargetLocation.BottomOfFile ->
                spliceEntry(docText, endOfFileLine(docText), normalizeEntry(entry, 1))

            is TargetLocation.UnderHeading -> {
                val doc = OrgParser.parse(docText, keywords)
                val target = findTarget(doc, location)
                    ?: throw CaptureTargetNotFound(
                        location.headingKey?.let { "No heading with ID or CUSTOM_ID \"$it\"" }
                            ?: location.path.takeIf { it.isNotEmpty() }
                                ?.let { "No heading at \"${it.joinToString(" / ")}\"" }
                            ?: "No heading titled \"${location.title}\""
                    )
                if (bodyOnly) {
                    // Body text must sit above the target's first child heading,
                    // or it would belong to that child instead.
                    val line = if (location.appendLast) bodyEnd(doc, target) else target.bodyStart
                    spliceEntry(docText, line, entry.trimEnd('\n').split("\n"))
                } else {
                    val line = if (location.appendLast) subtreeEnd(doc, target) else firstChildLine(doc, target)
                    spliceEntry(docText, line, normalizeEntry(entry, target.level + 1))
                }
            }

            is TargetLocation.Datetree -> {
                val parent = if (location.hasHeading) {
                    findTarget(OrgParser.parse(docText, keywords), location)
                        ?: throw CaptureTargetNotFound(
                            location.id?.let { "No heading with ID or CUSTOM_ID \"$it\"" }
                                ?: "No heading at \"${location.path.joinToString(" / ")}\""
                        )
                } else null
                insertDatetree(docText, entry, today, keywords, parent, location.treeType)
            }
        }
    }

    class CaptureTargetNotFound(message: String) : Exception(message)

    /**
     * Inserts [entry] as the last child of the heading at [headingPath] (exact
     * titles, outermost first, matched like the archive location's path). An
     * empty [headingPath] means the bottom of the file. Returns null when any
     * heading along the path is missing, so the caller can choose a fallback
     * instead of silently creating headings.
     */
    fun insertUnderPath(
        docText: String,
        headingPath: List<String>,
        entry: String,
        keywords: OrgKeywords = OrgKeywords.DEFAULT,
    ): Insertion? {
        if (headingPath.isEmpty()) return spliceEntry(docText, endOfFileLine(docText), normalizeEntry(entry, 1))
        val doc = OrgParser.parse(docText, keywords)
        val heading = findByPath(doc, headingPath) ?: return null
        return spliceEntry(docText, subtreeEnd(doc, heading), normalizeEntry(entry, heading.level + 1))
    }

    /**
     * The heading at [path] (exact titles, outermost first, each a direct
     * child of the one before), or null when any step is missing.
     */
    fun findByPath(doc: OrgDocument, path: List<String>): OrgHeadline? {
        if (path.isEmpty()) return null
        var target: OrgHeadline? = null
        for (title in path) {
            val siblings = target?.let { doc.directChildren(it) }
                ?: doc.headlines.filter { doc.parent(it) == null }
            target = siblings.firstOrNull { it.title == title } ?: return null
        }
        return target
    }

    /**
     * The heading an [TargetLocation.UnderHeading] capture lands under. One id
     * field matches either property, so legacy templates that saved a
     * CUSTOM_ID keep resolving after the switch to ID; the outline path, then
     * the exact title, are the fallbacks.
     */
    fun findTarget(doc: OrgDocument, location: TargetLocation.UnderHeading): OrgHeadline? =
        findHeading(doc, location.headingKey, location.path)
            ?: location.title?.let { doc.findByTitle(it) }

    /** The heading a [TargetLocation.Datetree] grows inside, or null for the top level / not found. */
    fun findTarget(doc: OrgDocument, location: TargetLocation.Datetree): OrgHeadline? =
        findHeading(doc, location.id, location.path)

    private fun findHeading(doc: OrgDocument, key: String?, path: List<String>): OrgHeadline? =
        key?.let { doc.findById(it) ?: doc.findByCustomId(it) } ?: findByPath(doc, path)

    /**
     * [target]'s outline path: the titles of its ancestors, outermost first,
     * ending with [target]'s own title. Drives the capture editor's target bar.
     */
    fun outlinePath(doc: OrgDocument, target: OrgHeadline): List<String> {
        val chain = ArrayDeque<OrgHeadline>()
        for (h in doc.headlines) {
            if (h.index > target.index) break
            while (chain.isNotEmpty() && chain.last().level >= h.level) chain.removeLast()
            chain.addLast(h)
        }
        return chain.map { it.title }
    }

    /**
     * True when an "under heading" capture adds content to the target
     * heading's body rather than a new child heading: the expanded template's
     * first non-blank line isn't a heading (e.g. a `- [ ] %?` checklist item
     * captured under "Shopping List"). Decided from the template once, when
     * the capture opens, not re-checked as the user types.
     */
    fun isBodyOnly(expandedText: String, location: TargetLocation): Boolean {
        if (location !is TargetLocation.UnderHeading) return false
        val first = expandedText.lineSequence().firstOrNull { it.isNotBlank() } ?: return true
        return STARS.matchEntire(first) == null
    }

    /**
     * Where appended body content goes: the end of [h]'s own body (before its
     * first child heading), above any trailing blank lines so a new list item
     * joins the list instead of sitting apart from it.
     */
    private fun bodyEnd(doc: OrgDocument, h: OrgHeadline): Int {
        var end = h.contentEnd
        while (end > h.bodyStart && doc.lines[end - 1].isBlank()) end--
        return end
    }

    /**
     * Appends [entry] verbatim at the bottom of [docText] with no re-leveling —
     * unlike [insert], which always turns the entry's first line into a
     * heading. Used for a Roam node's continuation body, which is plain
     * text/structure the template itself defines, not a new top-level entry.
     */
    fun appendVerbatim(docText: String, entry: String): Insertion =
        spliceEntry(docText, endOfFileLine(docText), entry.trimEnd('\n').split("\n"))

    /**
     * Remove a previously spliced-in entry (e.g. an autosaved draft) so it can
     * be replaced by a fresh [insert] rather than duplicated.
     */
    fun removeInsertion(text: String, insertion: Insertion): String {
        val lines = text.split("\n").toMutableList()
        val start = insertion.insertedAtLine.coerceIn(0, lines.size)
        val end = (start + insertion.entryLineCount).coerceAtMost(lines.size)
        repeat(end - start) { lines.removeAt(start) }
        return lines.joinToString("\n")
    }

    /**
     * Returns true if [text] has no meaningful heading title, i.e. the first
     * headline is missing or its title (after stripping any keyword/priority) is
     * blank. Used by the capture editor's save-guard to block empty headings.
     */
    fun hasBlankHeading(text: String, keywords: OrgKeywords = OrgKeywords.DEFAULT): Boolean {
        val doc = OrgParser.parse(text, keywords)
        return doc.headlines.firstOrNull()?.title.isNullOrBlank()
    }

    /** Heading depth an entry gets when inserted at [location], if statically known. */
    fun entryLevel(location: TargetLocation): Int? = when (location) {
        is TargetLocation.TopOfFile, is TargetLocation.BottomOfFile -> 1
        // Depends on the target heading's depth in the file; not known here.
        is TargetLocation.UnderHeading -> null
        // Inside a heading, the tree's depth depends on that heading's level.
        is TargetLocation.Datetree -> if (location.hasHeading) null else datetreeDepth(location.treeType) + 1
    }

    /** Number of date levels a [type] tree has: year, month/week, day. */
    fun datetreeDepth(type: DatetreeType): Int = if (type == DatetreeType.MONTH) 2 else 3

    /**
     * Make the capture editor WYSIWYG: prefix the expanded entry with the
     * stars its heading will get on insert, so the user types the heading
     * right after them. Skipped when the template already supplies a heading
     * (insertion re-levels it anyway) or the depth isn't statically known.
     */
    fun withHeadingStars(expanded: ExpandedTemplate, location: TargetLocation): ExpandedTemplate {
        val level = entryLevel(location) ?: return expanded
        if (STARS.matchEntire(expanded.text.substringBefore('\n')) != null) return expanded
        val stars = "*".repeat(level) + " "
        return ExpandedTemplate(stars + expanded.text, expanded.cursorOffset + stars.length)
    }

    // --- datetree ---

    fun yearTitle(d: LocalDate) = d.year.toString()
    fun monthTitle(d: LocalDate): String =
        "%04d-%02d %s".format(Locale.ROOT, d.year, d.monthValue, d.month.getDisplayName(TextStyle.FULL, orgLocale()))
    fun dayTitle(d: LocalDate): String =
        "%04d-%02d-%02d %s".format(
            Locale.ROOT,
            d.year, d.monthValue, d.dayOfMonth,
            d.dayOfWeek.getDisplayName(TextStyle.FULL, orgLocale()),
        )

    /** ISO week node title, e.g. `2026-W41` (org's week tree). */
    fun weekTitle(d: LocalDate): String =
        "%04d-W%02d".format(Locale.ROOT, d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    /**
     * The node titles of [today]'s branch of a [type] date tree, outermost
     * first. A week tree's year is the ISO week-based year, as in org, so the
     * first days of January can still sit under the previous year's last week.
     */
    fun datetreeTitles(today: LocalDate, type: DatetreeType): List<String> = when (type) {
        DatetreeType.DAY -> listOf(yearTitle(today), monthTitle(today), dayTitle(today))
        DatetreeType.WEEK ->
            listOf(today.get(IsoFields.WEEK_BASED_YEAR).toString(), weekTitle(today), dayTitle(today))
        DatetreeType.MONTH -> listOf(yearTitle(today), monthTitle(today))
    }

    /**
     * Finds or creates [today]'s branch of a [type] date tree, at the top level
     * or inside [parent], then appends [entry] as the last child of its last node.
     */
    private fun insertDatetree(
        docText: String,
        entry: String,
        today: LocalDate,
        keywords: OrgKeywords,
        parent: OrgHeadline?,
        type: DatetreeType,
    ): Insertion {
        var text = docText
        // Nodes are only ever spliced in below [parent], so its line index holds.
        var parentLine = parent?.lineIndex
        var level = parent?.level ?: 0
        for (title in datetreeTitles(today, type)) {
            level++
            // A node's numeric prefix is its title up to the first space ("2026-10").
            val node = ensureDateChild(text, keywords, parentLine, level, title, prefix = title.substringBefore(' '))
            text = node.first
            parentLine = node.second
        }
        val doc = OrgParser.parse(text, keywords)
        val leaf = doc.headlines.first { it.lineIndex == parentLine }
        return spliceEntry(text, subtreeEnd(doc, leaf), normalizeEntry(entry, level + 1))
    }

    /**
     * Find or create a date-tree node. [parent] is the line index of the parent
     * headline (null = top level). Returns (newText, headlineLineIndex).
     */
    private fun ensureDateChild(
        text: String,
        keywords: OrgKeywords,
        parent: Int?,
        level: Int,
        title: String,
        prefix: String,
    ): Pair<String, Int> {
        val doc = OrgParser.parse(text, keywords)
        val siblings: List<OrgHeadline> = if (parent == null) {
            doc.headlines.filter { it.level == 1 }
        } else {
            val p = doc.headlines.first { it.lineIndex == parent }
            doc.directChildren(p)
        }

        // Matched and ordered on the numeric part only, like org-datetree, so a
        // node whose name is in another language ("2026-10 Oktober") is reused.
        val dateSiblings = siblings.filter { looksLikeDate(it.title) }
        dateSiblings.firstOrNull { datePart(it.title) == prefix }?.let { return text to it.lineIndex }

        // Chronological position among date-shaped siblings; others left in place.
        val insertBefore = dateSiblings.firstOrNull { datePart(it.title) > prefix }
        val insertLine = when {
            insertBefore != null -> insertBefore.lineIndex
            dateSiblings.isNotEmpty() -> subtreeEnd(doc, dateSiblings.last())
            parent != null -> subtreeEnd(doc, doc.headlines.first { it.lineIndex == parent })
            else -> endOfFileLine(text)
        }
        val headlineLine = "*".repeat(level) + " " + title
        val spliced = spliceEntry(text, insertLine, listOf(headlineLine))
        return spliced.newText to spliced.insertedAtLine
    }

    /** A date-tree title's numeric part: `2026-10` of `2026-10 October`. */
    private fun datePart(title: String): String = title.substringBefore(' ').substringBefore('\t')

    private fun looksLikeDate(title: String): Boolean =
        Regex("""^\d{4}(-W?\d{2}){0,2}([ \t].*)?$""").matches(title)

    // --- splicing helpers ---

    /** First line after [h]'s own content, before its first child headline. */
    private fun firstChildLine(doc: OrgDocument, h: OrgHeadline): Int = h.contentEnd

    /** Line index just past [h]'s entire subtree. */
    private fun subtreeEnd(doc: OrgDocument, h: OrgHeadline): Int {
        val last = doc.subtree(h).lastOrNull() ?: h
        return last.contentEnd
    }

    /** Line index where bottom-of-file content goes (before a trailing-newline sentinel). */
    private fun endOfFileLine(text: String): Int {
        if (text.isEmpty()) return 0
        val lines = text.split("\n")
        return if (lines.last().isEmpty()) lines.size - 1 else lines.size
    }

    /**
     * Re-level the entry's first line to a headline at [level]; body lines are
     * kept verbatim. A first line without stars becomes the heading text.
     */
    private fun normalizeEntry(entry: String, level: Int): List<String> {
        val lines = entry.trimEnd('\n').split("\n")
        val first = lines.first()
        val m = STARS.matchEntire(first)
        val headingText = (m?.groupValues?.get(2) ?: first).trim()
        val heading = "*".repeat(level) + " " + headingText
        return listOf(heading) + lines.drop(1)
    }

    private fun spliceEntry(text: String, atLine: Int, entryLines: List<String>): Insertion {
        if (text.isEmpty()) {
            return Insertion(entryLines.joinToString("\n") + "\n", 0, entryLines.size)
        }
        val lines = text.split("\n").toMutableList()
        var at = atLine.coerceIn(0, lines.size)
        // A trailing empty element is the file's final newline: insert before
        // it so the entry stays newline-terminated instead of creating a blank
        // line plus an unterminated last line.
        if (at == lines.size && lines.last().isEmpty()) at--
        lines.addAll(at, entryLines)
        return Insertion(lines.joinToString("\n"), at, entryLines.size)
    }
}
