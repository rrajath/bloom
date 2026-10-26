package com.rrajath.grove.org

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * Span edits on a document's text. Every function returns the new full text;
 * only the lines belonging to the edit are touched: the rest of the file is
 * preserved byte-for-byte (the lossless-parser invariant).
 */
object OrgMutations {

    data class NewNoteOptions(
        val keyword: String? = null,
        /** Adds an :ID: property (caller supplies the UUID; pure function). */
        val id: String? = null,
        /** Adds a :CREATED: inactive timestamp. */
        val createdAt: LocalDateTime? = null,
        /** Body text placed under the heading (after any properties drawer). */
        val body: String? = null,
    )

    // --- headline line edits ---

    fun setKeyword(doc: OrgDocument, h: OrgHeadline, keyword: String?): String =
        replaceLine(doc, h.lineIndex, headlineLine(h.level, keyword, h.priority, h.title, h.tags))

    fun setPriority(doc: OrgDocument, h: OrgHeadline, priority: Char?): String =
        replaceLine(doc, h.lineIndex, headlineLine(h.level, h.keyword, priority, h.title, h.tags))

    fun setTags(doc: OrgDocument, h: OrgHeadline, tags: List<String>): String =
        replaceLine(doc, h.lineIndex, headlineLine(h.level, h.keyword, h.priority, h.title, tags))

    // --- planning edits ---

    fun setScheduled(doc: OrgDocument, h: OrgHeadline, ts: OrgTimestamp?): String =
        writePlanning(doc, h, h.planning.copy(scheduled = ts))

    fun setDeadline(doc: OrgDocument, h: OrgHeadline, ts: OrgTimestamp?): String =
        writePlanning(doc, h, h.planning.copy(deadline = ts))

    /**
     * Write SCHEDULED and DEADLINE in one edit: what the Dates screen's "Apply
     * both dates" commits. Doing it as a single [writePlanning] keeps the two
     * timestamps on one planning line and makes the pair a single undo step,
     * which chaining [setScheduled] into [setDeadline] would not.
     */
    fun setPlanningDates(
        doc: OrgDocument,
        h: OrgHeadline,
        scheduled: OrgTimestamp?,
        deadline: OrgTimestamp?,
    ): String = writePlanning(doc, h, h.planning.copy(scheduled = scheduled, deadline = deadline))

    /**
     * Write [h]'s bare active timestamps as one dedicated line right after any
     * planning line, above its drawers. If a "pure active-timestamp line"
     * (only `<…>` stamps + whitespace) is already there it is replaced;
     * otherwise one is inserted. An empty [stamps] removes the dedicated line.
     * A pre-flip file that still has the line below the drawers (this app's
     * old convention) gets it migrated up to the canonical slot on any edit.
     * Active timestamps typed inline in prose are left untouched — only this
     * one managed line is edited, mirroring [writePlanning].
     */
    fun setActiveTimestamps(doc: OrgDocument, h: OrgHeadline, stamps: List<OrgTimestamp>): String {
        val lines = doc.lines.toMutableList()
        val newSlot = planningAdjacentStart(doc, h)
        val hasNewSlotLine = newSlot < h.contentEnd && newSlot < lines.size &&
                isPureActiveTimestampLine(lines[newSlot])
        // Pre-flip position: the body's own first line, below the drawers —
        // only possible when the canonical slot doesn't already have one.
        val oldSlot = h.bodyStart
        val hasOldSlotLine = !hasNewSlotLine && oldSlot < h.contentEnd && oldSlot < lines.size &&
                isPureActiveTimestampLine(lines[oldSlot])
        val indent = when {
            hasNewSlotLine -> leadingWhitespace(lines[newSlot])
            hasOldSlotLine -> leadingWhitespace(lines[oldSlot])
            else -> metadataIndent(doc, h)
        }
        val newLine = stamps
            .filter { it.active }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ", prefix = indent) { it.format() }
        // oldSlot sits after newSlot, so removing it first doesn't shift newSlot's index.
        if (hasOldSlotLine) lines.removeAt(oldSlot)
        when {
            hasNewSlotLine && newLine != null -> lines[newSlot] = newLine
            hasNewSlotLine -> lines.removeAt(newSlot)
            newLine != null -> lines.add(newSlot, newLine)
        }
        return lines.joinToString("\n")
    }

    /**
     * SCHEDULED / DEADLINE and the dedicated active-timestamp line in one logical
     * edit: what the Dates editor's "Apply" commits. The planning line is written
     * first, then the document is re-parsed so [setActiveTimestamps] places its
     * line after the (possibly just-added) planning line — the same re-parse
     * dance [markDone] does.
     */
    fun setPlanningAndActiveTimestamps(
        doc: OrgDocument,
        h: OrgHeadline,
        scheduled: OrgTimestamp?,
        deadline: OrgTimestamp?,
        active: List<OrgTimestamp>,
    ): String {
        val withPlanning = setPlanningDates(doc, h, scheduled, deadline)
        val redoc = OrgParser.parse(withPlanning, doc.keywords)
        val again = redoc.headlines.first { it.lineIndex == h.lineIndex }
        return setActiveTimestamps(redoc, again, active)
    }

    /** True when [line] holds only active `<…>` timestamps (and whitespace). */
    private fun isPureActiveTimestampLine(line: String): Boolean {
        if (!PURE_ACTIVE_TS_LINE.matches(line)) return false
        val stamps = OrgTimestamp.parseAll(line.trim())
        return stamps.isNotEmpty() && stamps.all { it.active }
    }

    /**
     * Complete on a repeating bare active timestamp: among [h]'s dedicated
     * active timestamps (the managed line [setActiveTimestamps] rewrites, not
     * timestamps typed inline in prose), the one dated [date] gets
     * [OrgTimestamp.advanceRepeater] applied to it; every other stamp on that
     * line is left untouched. There is no keyword to preserve or transition
     * here (a bare timestamp reminder is keyword-less by definition), so unlike
     * [markDone] this never touches the heading line or logs a state change.
     * Returns null when [date] doesn't match a dedicated stamp with a repeater
     * -- nothing to advance.
     */
    fun advanceActiveTimestamp(
        doc: OrgDocument,
        h: OrgHeadline,
        date: LocalDate,
        now: LocalDateTime,
    ): String? {
        val stamps = h.dedicatedActiveTimestamps
        val idx = stamps.indexOfFirst { it.date == date && it.repeater != null }
        if (idx == -1) return null
        val advanced = stamps.toMutableList()
        advanced[idx] = advanced[idx].advanceRepeater(now)
        return setActiveTimestamps(doc, h, advanced)
    }

    /**
     * Complete on a repeating SCHEDULED/DEADLINE for a keyword-less heading: only
     * [kind]'s timestamp gets [OrgTimestamp.advanceRepeater] applied to it, the
     * other planning field (if any) is left untouched. Mirrors
     * [advanceActiveTimestamp]'s cleanliness -- no keyword, LOGBOOK entry, or
     * LAST_REPEAT property is touched, unlike [markDone]'s repeating branch,
     * which does all of that for an actual TODO-keyword task. Returns null when
     * [kind]'s timestamp is absent or has no repeater -- nothing to advance.
     */
    fun advanceRepeatingPlanning(
        doc: OrgDocument,
        h: OrgHeadline,
        kind: PlanningKind,
        now: LocalDateTime,
    ): String? {
        val ts = when (kind) {
            PlanningKind.SCHEDULED -> h.planning.scheduled
            PlanningKind.DEADLINE -> h.planning.deadline
            PlanningKind.ACTIVE -> null
        }?.takeIf { it.repeater != null } ?: return null
        return writePlanning(
            doc, h,
            when (kind) {
                PlanningKind.SCHEDULED -> h.planning.copy(scheduled = ts.advanceRepeater(now))
                PlanningKind.DEADLINE -> h.planning.copy(deadline = ts.advanceRepeater(now))
                PlanningKind.ACTIVE -> return null
            },
        )
    }

    /**
     * Mark done per org rules: a repeating SCHEDULED/DEADLINE advances its date
     * and the keyword stays active; otherwise the keyword becomes [doneKeyword]
     * and a CLOSED stamp is added. Leaving a todo-type keyword (or none) also
     * logs a `- State` entry at the top of the LOGBOOK drawer; a done-to-done
     * change (DONE to CANCELLED) only refreshes CLOSED.
     */
    fun markDone(
        doc: OrgDocument,
        h: OrgHeadline,
        doneKeyword: String,
        now: LocalDateTime,
    ): String {
        val today = now.toLocalDate()
        val hasRepeater = h.planning.scheduled?.repeater != null || h.planning.deadline?.repeater != null
        return if (hasRepeater) {
            val advanced = writePlanning(
                doc, h,
                h.planning.copy(
                    scheduled = h.planning.scheduled?.advanceRepeater(now),
                    deadline = h.planning.deadline?.advanceRepeater(now),
                ),
            )
            // The keyword itself stays unchanged for a repeater (org semantics),
            // but the transition is still recorded, same as Emacs logging the
            // DONE note before reverting the state back to TODO.
            val doc1 = OrgParser.parse(advanced, doc.keywords)
            val h1 = doc1.headlines.first { it.lineIndex == h.lineIndex }
            val stamp = OrgTimestamp(today, time = now.toLocalTime().withSecond(0).withNano(0), active = false)
            val logged = appendLogbookEntry(doc1, h1, logStateChangeLine(doneKeyword, h1.keyword ?: "", stamp))
            val doc2 = OrgParser.parse(logged, doc.keywords)
            val h2 = doc2.headlines.first { it.lineIndex == h.lineIndex }
            upsertProperty(doc2, h2, "LAST_REPEAT", stamp.format())
        } else {
            val stamp = OrgTimestamp(today, time = now.toLocalTime().withSecond(0).withNano(0), active = false)
            val withClosed = writePlanning(doc, h, h.planning.copy(closed = stamp))
            val redoc = OrgParser.parse(withClosed, doc.keywords)
            val again = redoc.headlines.first { it.lineIndex == h.lineIndex }
            val withKeyword = setKeyword(redoc, again, doneKeyword)
            val wasDone = h.keyword != null && doc.keywords.isDone(h.keyword)
            if (wasDone) return withKeyword
            val doc2 = OrgParser.parse(withKeyword, doc.keywords)
            val h2 = doc2.headlines.first { it.lineIndex == h.lineIndex }
            appendLogbookEntry(doc2, h2, logStateChangeLine(doneKeyword, h.keyword ?: "", stamp))
        }
    }

    /**
     * Inverse of [markDone] for the non-repeating case: restore [activeKeyword]
     * and drop the CLOSED stamp, the way `org-todo` back to TODO does. A
     * repeating heading never reaches a done keyword in the first place, so
     * there is nothing to invert there.
     */
    fun reopen(doc: OrgDocument, h: OrgHeadline, activeKeyword: String?): String {
        val cleared = writePlanning(doc, h, h.planning.copy(closed = null))
        val redoc = OrgParser.parse(cleared, doc.keywords)
        val again = redoc.headlines.first { it.lineIndex == h.lineIndex }
        return setKeyword(redoc, again, activeKeyword)
    }

    /**
     * Single entry point for every "change this heading's TODO state" UI action
     * (metadata sheet chips, Outline/Search swipe state pickers): picking a
     * done-type [newKeyword] applies full "mark done" semantics, and leaving a
     * done-type keyword (including clearing it to no keyword at all) always
     * drops the stale CLOSED stamp via [reopen], the way `org-todo` does. A
     * plain keyword-to-keyword change with neither end done-type is a bare
     * [setKeyword].
     */
    fun changeKeyword(
        doc: OrgDocument,
        h: OrgHeadline,
        newKeyword: String?,
        keywords: OrgKeywords,
        now: LocalDateTime,
    ): String = when {
        newKeyword != null && keywords.isDone(newKeyword) -> markDone(doc, h, newKeyword, now)
        h.keyword != null && keywords.isDone(h.keyword) -> reopen(doc, h, newKeyword)
        else -> setKeyword(doc, h, newKeyword)
    }

    private val CHECKBOX_LINE = Regex("""^(\s*(?:[-+]|\d+[.)])\s+)\[([ Xx-])\](.*)$""")

    /**
     * Read mode: tap a checklist item's box to toggle it done. `[X]` → `[ ]`;
     * anything else (`[ ]`, `[-]`) → `[X]`. [lineIndex] is absolute into
     * [doc.lines]: the caller resolves a [BlockParser.ListItem]'s body-relative
     * `line` against the owning headline's `bodyStart` first. Returns null when
     * the line isn't a checkbox list item.
     */
    fun toggleCheckboxDone(doc: OrgDocument, lineIndex: Int): String? =
        setCheckboxMark(doc, lineIndex) { current -> if (current == 'X') ' ' else 'X' }

    /**
     * Read mode: long-press a checklist item's box to toggle it in-progress.
     * `[-]` → `[ ]`; anything else (`[ ]`, `[X]`) → `[-]`. Same line-index and
     * parent-cookie semantics as [toggleCheckboxDone].
     */
    fun toggleCheckboxProgress(doc: OrgDocument, lineIndex: Int): String? =
        setCheckboxMark(doc, lineIndex) { current -> if (current == '-') ' ' else '-' }

    /**
     * Rewrites the checkbox mark on [lineIndex] to `next(currentMark)` (a lowercase
     * `x` is normalized to `X` before [next] sees it). Also refreshes the immediate
     * parent's statistics cookie and, if the parent is itself a checkbox item,
     * checks/unchecks it to match whether all of its direct children are now done;
     * see [updateParentCookie]. Returns null when the line isn't a checkbox list item.
     */
    private fun setCheckboxMark(doc: OrgDocument, lineIndex: Int, next: (Char) -> Char): String? {
        if (lineIndex !in doc.lines.indices) return null
        val m = CHECKBOX_LINE.matchEntire(doc.lines[lineIndex]) ?: return null
        val (prefix, mark, rest) = m.destructured
        val current = if (mark == "x") 'X' else mark.first()
        val lines = doc.lines.toMutableList()
        lines[lineIndex] = "$prefix[${next(current)}]$rest"
        updateParentCookie(lines, lineIndex)
        return lines.joinToString("\n")
    }

    private val LIST_ITEM_LINE = Regex("""^(\s*)(?:[-+]|\d+[.)])\s+(?:\[([ Xx-])\]\s+)?(.*)$""")
    private val FRACTION_COOKIE = Regex("""\[\d*/\d*\]""")
    private val PERCENT_COOKIE = Regex("""\[\d*%\]""")

    /**
     * Statistics cookies (org's `[/]`, `[x/y]`, `[%]`) on a parent item
     * summarize the checkbox state of its direct children: only refreshes a
     * cookie that's already present in the parent's text (org never invents
     * one) and only counts the shallowest indent level right under the
     * parent, so a nested sub-list with its own cookie isn't double-counted
     * into the outer one. Independently, when the parent is itself a
     * checkbox item, completing (or un-completing) all of its direct
     * children checks/unchecks the parent's own box too, same treatment as
     * any other checklist item finishing. This only ever touches the
     * immediate parent, not further ancestors.
     */
    private fun updateParentCookie(lines: MutableList<String>, toggledIndex: Int) {
        val toggled = LIST_ITEM_LINE.matchEntire(lines[toggledIndex]) ?: return
        val itemIndent = toggled.groupValues[1].length

        var parentIndex = -1
        for (i in toggledIndex - 1 downTo 0) {
            val m = LIST_ITEM_LINE.matchEntire(lines[i]) ?: break
            if (m.groupValues[1].length < itemIndent) {
                parentIndex = i
                break
            }
        }
        if (parentIndex == -1) return
        val parentMatch = LIST_ITEM_LINE.matchEntire(lines[parentIndex])!!
        val parentIndent = parentMatch.groupValues[1].length

        data class Child(val indent: Int, val checkbox: Char?)
        val below = mutableListOf<Child>()
        var i = parentIndex + 1
        while (i < lines.size) {
            val m = LIST_ITEM_LINE.matchEntire(lines[i]) ?: break
            val indent = m.groupValues[1].length
            if (indent <= parentIndent) break
            below.add(Child(indent, m.groupValues[2].firstOrNull()))
            i++
        }
        val directIndent = below.minOfOrNull { it.indent } ?: return
        val direct = below.filter { it.indent == directIndent && it.checkbox != null }
        if (direct.isEmpty()) return
        val total = direct.size
        val done = direct.count { it.checkbox == 'X' || it.checkbox == 'x' }

        val textRange = parentMatch.groups[3]!!.range
        val text = parentMatch.groupValues[3]
        val newText = FRACTION_COOKIE.find(text)?.let { text.replaceRange(it.range, "[$done/$total]") }
            ?: PERCENT_COOKIE.find(text)?.let {
                val pct = (done * 100.0 / total).roundToInt()
                text.replaceRange(it.range, "[$pct%]")
            }
        if (newText != null) {
            lines[parentIndex] = lines[parentIndex].replaceRange(textRange, newText)
        }

        val parentCheckbox = parentMatch.groups[2]
        if (parentCheckbox != null) {
            val newMark = if (done == total) "X" else " "
            lines[parentIndex] = lines[parentIndex].replaceRange(parentCheckbox.range, newMark)
        }
    }

    // --- structural edits ---

    fun subtreeText(doc: OrgDocument, h: OrgHeadline): String =
        doc.lines.subList(h.lineIndex, doc.subtreeEndLine(h)).joinToString("\n")

    /**
     * Inclusive line range of the file's preface: the leading run of `#+KEY:` lines
     * (and any blanks between them) after an optional file-level property drawer,
     * up to where the intro begins. Trailing blank lines are excluded so a save
     * can't swallow the separator before the intro or the first heading. Null when
     * the file has no such leading keyword run. The raw region the preface editor
     * scopes itself to.
     */
    fun prefaceRange(doc: OrgDocument): IntRange? {
        val start = fileDrawerRange(doc)?.let { it.last + 1 } ?: 0
        val end = (start until doc.introStart).lastOrNull { doc.lines[it].isNotBlank() }
            ?: return null
        return start..end
    }

    /**
     * Inclusive line range of the file's intro: its heading-less content before the
     * first headline ([OrgDocument.introStart] onward), trailing blank lines excluded
     * so a save leaves the separator before the first heading alone. Null when the
     * file has no intro. The raw region the intro editor scopes itself to.
     */
    fun introRange(doc: OrgDocument): IntRange? {
        if (!doc.hasIntro) return null
        val end = (doc.introStart until doc.preambleEnd).last { doc.lines[it].isNotBlank() }
        return doc.introStart..end
    }

    /**
     * Inclusive line range (drawer markers included) of the leading file-level
     * `:PROPERTIES: … :END:` drawer, or null when there is no such closed drawer
     * on the very first line. Mirrors [OrgDocument.filePropertyDrawer]'s rules;
     * this is the raw region a drawer editor scopes itself to.
     */
    fun fileDrawerRange(doc: OrgDocument): IntRange? {
        if (doc.preambleEnd == 0 ||
            !doc.lines[0].trim().equals(":PROPERTIES:", ignoreCase = true)
        ) {
            return null
        }
        val end = (1 until doc.preambleEnd).firstOrNull {
            doc.lines[it].trim().equals(":END:", ignoreCase = true)
        } ?: return null
        return 0..end
    }

    /**
     * Inclusive line range (drawer markers included) of [h]'s `:PROPERTIES:` or
     * `:LOGBOOK:` drawer ([marker] is the uppercase drawer name, e.g. `":LOGBOOK:"`),
     * or null when [h] has no such closed drawer in the run right after its
     * planning line. The raw region a drawer editor scopes itself to.
     */
    fun headingDrawerRange(doc: OrgDocument, h: OrgHeadline, marker: String): IntRange? {
        val start = drawerScanStart(doc, h)
        val markerLine = findDrawerMarker(doc.lines, start, h.bodyStart, marker) ?: return null
        var endLine = markerLine + 1
        while (endLine < doc.lines.size &&
            !doc.lines[endLine].trim().equals(":END:", ignoreCase = true)
        ) {
            endLine++
        }
        if (endLine >= doc.lines.size) return null
        return markerLine..endLine
    }

    /** Raw text of the line [range] (as returned by [fileDrawerRange] / [headingDrawerRange]). */
    fun regionText(doc: OrgDocument, range: IntRange): String =
        doc.lines.subList(range.first, range.last + 1).joinToString("\n")

    private val BLOCK_BEGIN = Regex("""^\s*#\+(?i:BEGIN_)(\S+)""")
    private val BLOCK_END = Regex("""^\s*#\+(?i:END_)(\S+)\s*$""")

    /** Any standalone `#+KEYWORD:` line — mirrors `BlockParser.KEYWORD`. */
    private val BLOCK_AFFILIATED = Regex("""^\s*#\+(?!(?i:BEGIN_|END_))[A-Za-z][\w-]*:.*$""")

    /**
     * Inclusive line range of the `#+BEGIN_x … #+END_x` block whose `#+BEGIN`
     * line is at or nearest [approxBeginLine], markers included, plus any leading
     * run of affiliated-keyword lines (`#+ATTR_*`, `#+CAPTION:`, `#+NAME:`, …)
     * directly above it — matching what the read-mode drawer folds in. An
     * unterminated block runs to the end of the file.
     *
     * When [approxBeginLine] instead points at a standalone `#+KEYWORD:` run that
     * has no `#+BEGIN` below it, the range is that consecutive keyword run —
     * matching the collapsible keyword block read mode renders for it.
     *
     * Null when the file has no `#+BEGIN` line and no keyword run at the tapped
     * line. The raw region the block editor scopes itself to.
     */
    fun blockRange(doc: OrgDocument, approxBeginLine: Int): IntRange? {
        val lines = doc.lines

        // Standalone keyword run: the tapped line is a `#+KEYWORD:` line whose
        // run isn't immediately followed by a `#+BEGIN` (which would make these
        // the block's affiliated keywords, handled by the `#+BEGIN` path below).
        if (approxBeginLine in lines.indices && BLOCK_AFFILIATED.matches(lines[approxBeginLine])) {
            var s = approxBeginLine
            var e = approxBeginLine
            while (s > 0 && BLOCK_AFFILIATED.matches(lines[s - 1])) s--
            while (e < lines.size - 1 && BLOCK_AFFILIATED.matches(lines[e + 1])) e++
            val foldsIntoBlock = e + 1 < lines.size && BLOCK_BEGIN.containsMatchIn(lines[e + 1])
            if (!foldsIntoBlock) return s..e
        }

        val beginLines = lines.indices.filter { BLOCK_BEGIN.containsMatchIn(lines[it]) }
        if (beginLines.isEmpty()) return null
        // Prefer a `#+BEGIN` at or after the tapped line, then fall back to the
        // nearest one either side (an external edit may have shifted things).
        val begin = beginLines.filter { it >= approxBeginLine }.minByOrNull { it - approxBeginLine }
            ?: beginLines.minByOrNull { kotlin.math.abs(it - approxBeginLine) }!!
        val kind = BLOCK_BEGIN.find(lines[begin])!!.groupValues[1]
        var end = begin + 1
        while (end < lines.size) {
            val m = BLOCK_END.find(lines[end])
            if (m != null && m.groupValues[1].equals(kind, ignoreCase = true)) break
            end++
        }
        if (end >= lines.size) end = lines.size - 1
        var start = begin
        while (start > 0 && BLOCK_AFFILIATED.matches(lines[start - 1])) start--
        return start..end
    }

    /** Replace the line [range] with [newText] (a scoped drawer editor's save path). */
    fun replaceLines(doc: OrgDocument, range: IntRange, newText: String): String {
        val lines = doc.lines.toMutableList()
        repeat(range.last - range.first + 1) { lines.removeAt(range.first) }
        lines.addAll(range.first, newText.trimEnd('\n').split("\n"))
        return lines.joinToString("\n")
    }

    /**
     * Insert a blank top-level heading (`* `) directly above the file's
     * heading-less content ([OrgDocument.introStart]) so that content
     * becomes the new heading's body. `#+KEY:` lines and any leading property
     * drawer stay above it. Returns the new text and the new heading's line
     * index. Caller should have checked [OrgDocument.hasIntro].
     */
    fun wrapIntroInHeading(doc: OrgDocument): Pair<String, Int> {
        val at = doc.introStart
        val lines = doc.lines.toMutableList()
        lines.add(at, "* ")
        return lines.joinToString("\n") to at
    }

    fun deleteSubtree(doc: OrgDocument, h: OrgHeadline): String {
        val lines = doc.lines.toMutableList()
        repeat(doc.subtreeEndLine(h) - h.lineIndex) { lines.removeAt(h.lineIndex) }
        return lines.joinToString("\n")
    }

    /**
     * Replace [h]'s whole subtree with [newText] (the editor's save path).
     *
     * The subtree's trailing blank lines (the separator before the next heading,
     * or the file's final newline) are kept verbatim unless [newText] ends in
     * more of them, so an edit never swallows the gap before the next heading.
     */
    fun replaceSubtree(doc: OrgDocument, h: OrgHeadline, newText: String): String {
        val lines = doc.lines.toMutableList()
        val end = doc.subtreeEndLine(h)
        val oldLines = lines.subList(h.lineIndex, end).toList()
        val oldTrailing = oldLines.takeLastWhile { it.isBlank() }
        val newLines = newText.split("\n")
        val newTrailing = newLines.takeLastWhile { it.isBlank() }
        // A subtree is never all blank (its first line is the headline), but guard anyway.
        val content = newLines.dropLast(newTrailing.size).ifEmpty { listOf("") }
        val trailing = if (newTrailing.size > oldTrailing.size) newTrailing else oldTrailing
        repeat(end - h.lineIndex) { lines.removeAt(h.lineIndex) }
        lines.addAll(h.lineIndex, content + trailing)
        return lines.joinToString("\n")
    }

    /**
     * Swap [h]'s subtree with its previous (delta=-1) or next (+1) sibling.
     * Returns the new text plus [h]'s new lineIndex, or null at an edge.
     */
    fun moveSubtree(doc: OrgDocument, h: OrgHeadline, delta: Int): Pair<String, Int>? {
        val siblings = siblingsOf(doc, h)
        val idx = siblings.indexOfFirst { it.lineIndex == h.lineIndex }
        val other = siblings.getOrNull(idx + delta) ?: return null

        val (first, second) = if (other.lineIndex < h.lineIndex) other to h else h to other
        val lines = doc.lines
        val firstEnd = doc.subtreeEndLine(first)
        val secondEnd = doc.subtreeEndLine(second)
        val rebuilt = lines.subList(0, first.lineIndex) +
                lines.subList(second.lineIndex, secondEnd) +
                lines.subList(firstEnd, second.lineIndex) +
                lines.subList(first.lineIndex, firstEnd) +
                lines.subList(secondEnd, lines.size)
        val newLine = if (other.lineIndex < h.lineIndex) {
            first.lineIndex // moved up: h now starts where the previous sibling did
        } else {
            // moved down: everything between h's old start and the next
            // sibling's subtree end now sits in front of h
            first.lineIndex + (secondEnd - firstEnd)
        }
        return rebuilt.joinToString("\n") to newLine
    }

    /**
     * Shift [h] and its entire subtree one level shallower.
     * Returns null when [h] is already at level 1.
     */
    fun promoteSubtree(doc: OrgDocument, h: OrgHeadline): String? {
        if (h.level == 1) return null
        return shiftSubtreeLevels(doc, h, -1)
    }

    /**
     * Shift [h] and its entire subtree one level deeper. Returns null when
     * [h] has no previous same-level sibling to become its parent (demoting
     * would create an orphaned level jump).
     */
    fun demoteSubtree(doc: OrgDocument, h: OrgHeadline): String? {
        val siblings = siblingsOf(doc, h)
        val idx = siblings.indexOfFirst { it.lineIndex == h.lineIndex }
        if (idx <= 0) return null
        return shiftSubtreeLevels(doc, h, +1)
    }

    /** A structural outline edit that [applyToEach] can run on several headings at once. */
    enum class StructureOp { MOVE_UP, MOVE_DOWN, PROMOTE, DEMOTE, DELETE }

    /**
     * Result of [applyToEach]: the final text and parse, how many headings the op
     * was [applied] to out of [attempted], and [remap], which carries a line index
     * of the original document to where that line sits now (null once deleted).
     */
    class BulkEdit(
        val text: String,
        val doc: OrgDocument,
        val applied: Int,
        val attempted: Int,
        val remap: (Int) -> Int?,
    )

    /**
     * Run [op] on each heading starting at one of [lines], one heading at a time,
     * re-parsing in between so every step sees the previous step's result.
     *
     * A selected heading inside another selected heading's subtree rides along
     * with that ancestor rather than being acted on again (a promote would
     * otherwise shift it twice). Moves go in the direction of travel (top-down
     * for up, bottom-up for down) and never swap two selected siblings with each
     * other, so a run of adjacent selected siblings moves as a block and stops
     * as a block. Demote likewise never nests a selected heading under a selected
     * previous sibling, so a block whose first heading can't demote stays put
     * whole. Returns null when [op] applied to none of them.
     */
    fun applyToEach(doc: OrgDocument, lines: Set<Int>, op: StructureOp): BulkEdit? {
        val roots = selectedRoots(doc, lines)
        val ordered = roots.map { it.lineIndex }.sorted()
            .let { if (op == StructureOp.MOVE_DOWN) it.reversed() else it }

        var cur = doc
        var remap: (Int) -> Int? = { it }
        var applied = 0
        for (original in ordered) {
            val line = remap(original) ?: continue
            val h = cur.headlines.firstOrNull { it.lineIndex == line } ?: continue
            val step: Pair<String, (Int) -> Int?> = when (op) {
                StructureOp.PROMOTE -> promoteSubtree(cur, h)?.let { it to { l: Int -> l } }
                StructureOp.DEMOTE -> {
                    val siblings = siblingsOf(cur, h)
                    val previous = siblings.getOrNull(siblings.indexOfFirst { it.lineIndex == h.lineIndex } - 1)
                    val selectedNow = ordered.mapNotNull(remap).toSet()
                    if (previous != null && previous.lineIndex in selectedNow) null
                    else demoteSubtree(cur, h)?.let { it to { l: Int -> l } }
                }
                StructureOp.DELETE -> {
                    val start = h.lineIndex
                    val end = cur.subtreeEndLine(h)
                    deleteSubtree(cur, h) to { l: Int ->
                        when {
                            l < start -> l
                            l < end -> null
                            else -> l - (end - start)
                        }
                    }
                }
                StructureOp.MOVE_UP, StructureOp.MOVE_DOWN -> {
                    val delta = if (op == StructureOp.MOVE_UP) -1 else +1
                    val selectedNow = ordered.mapNotNull(remap).toSet()
                    moveTracked(cur, h, delta, selectedNow)
                }
            } ?: continue
            cur = OrgParser.parse(step.first, doc.keywords)
            val previous = remap
            remap = { l -> previous(l)?.let(step.second) }
            applied++
        }
        if (applied == 0) return null
        return BulkEdit(cur.text, cur, applied, roots.size, remap)
    }

    /**
     * Insert [subtree] into [doc], releveled: as the last child of [target],
     * or appended at the end of the file at level 1 when [target] is null.
     * Returns the new text plus the inserted root's lineIndex.
     */
    fun refileInsert(doc: OrgDocument, target: OrgHeadline?, subtree: String): Pair<String, Int> {
        val releveled = relevelSubtree(subtree.trimEnd('\n').split("\n"), (target?.level ?: 0) + 1)
        if (target == null && doc.lines.all { it.isEmpty() }) {
            return releveled.joinToString("\n") + "\n" to 0
        }
        val lines = doc.lines.toMutableList()
        var insertAt = target?.let { doc.subtreeEndLine(it) } ?: lines.size
        if (insertAt == lines.size && lines.isNotEmpty() && lines.last().isEmpty()) insertAt--
        lines.addAll(insertAt, releveled)
        return lines.joinToString("\n") to insertAt
    }

    /**
     * Refile [source]'s subtree within the same document, under the headline
     * starting at [targetLine] (or to the top level when null). One text
     * pipeline: the target's line is re-resolved after the delete so stale
     * indexes can't corrupt the file. Returns null when [targetLine] falls
     * inside [source]'s own subtree or no longer resolves to a headline.
     */
    fun refileWithinFile(doc: OrgDocument, source: OrgHeadline, targetLine: Int?): Pair<String, Int>? {
        val sourceEnd = doc.subtreeEndLine(source)
        if (targetLine != null && targetLine in source.lineIndex until sourceEnd) return null
        val subtree = subtreeText(doc, source)
        val redoc = OrgParser.parse(deleteSubtree(doc, source), doc.keywords)
        val target = targetLine?.let { line ->
            val adjusted = if (line > source.lineIndex) line - (sourceEnd - source.lineIndex) else line
            redoc.headlines.firstOrNull { it.lineIndex == adjusted } ?: return null
        }
        return refileInsert(redoc, target, subtree)
    }

    /**
     * The headings at [lines] that aren't inside another one's subtree, in
     * document order: a selected heading under a selected ancestor travels with
     * that ancestor's subtree in any subtree operation.
     */
    fun selectedRoots(doc: OrgDocument, lines: Set<Int>): List<OrgHeadline> {
        val selected = doc.headlines.filter { it.lineIndex in lines }
        return selected.filter { h ->
            selected.none { o -> o.lineIndex < h.lineIndex && h.lineIndex < doc.subtreeEndLine(o) }
        }
    }

    /**
     * [doc] with the subtrees of [selectedRoots] lifted out: what's left, the
     * lifted subtree texts in document order, and [remap], which carries a line
     * of [doc] to its line in what's left (null inside a lifted subtree).
     */
    class LiftedSubtrees(val doc: OrgDocument, val subtrees: List<String>, val remap: (Int) -> Int?)

    fun liftSubtrees(doc: OrgDocument, lines: Set<Int>): LiftedSubtrees {
        val roots = selectedRoots(doc, lines)
        val ranges = roots.map { it.lineIndex until doc.subtreeEndLine(it) }
        val kept = doc.lines.filterIndexed { i, _ -> ranges.none { i in it } }
        return LiftedSubtrees(
            doc = OrgParser.parse(kept.joinToString("\n"), doc.keywords),
            subtrees = roots.map { subtreeText(doc, it) },
            remap = { l ->
                if (ranges.any { l in it }) null
                else l - ranges.filter { it.last < l }.sumOf { it.last - it.first + 1 }
            },
        )
    }

    /**
     * Insert each of [subtrees], in order, as the last children of the heading
     * at [targetLine] in [doc] (appended at level 1 when null). Every insert
     * lands below the target, so its line holds throughout. Null when
     * [targetLine] isn't a heading.
     */
    fun insertEach(doc: OrgDocument, targetLine: Int?, subtrees: List<String>): OrgDocument? {
        var cur = doc
        for (subtree in subtrees) {
            val target = targetLine?.let { line -> cur.headlines.firstOrNull { it.lineIndex == line } ?: return null }
            cur = OrgParser.parse(refileInsert(cur, target, subtree).first, doc.keywords)
        }
        return cur
    }

    /**
     * [refileWithinFile] for the headings at [lines] at once: their subtrees end
     * up under the heading at [targetLine] (or the top level) in their original
     * order. Null when [targetLine] lies inside one of the subtrees being moved.
     */
    fun refileEachWithinFile(doc: OrgDocument, lines: Set<Int>, targetLine: Int?): OrgDocument? {
        val lifted = liftSubtrees(doc, lines)
        val target = targetLine?.let { lifted.remap(it) ?: return null }
        return insertEach(lifted.doc, target, lifted.subtrees)
    }

    /**
     * Apply [edit] to each heading starting at one of [lines], for edits confined
     * to a heading's own section that never add or remove headings (keyword,
     * priority, tags, planning, drawers). They run bottom-up, so each edit leaves
     * the lines of the headings still to come untouched. [edit] returns null to
     * leave a heading as it is. [BulkEdit.remap] carries any heading line of
     * [doc] to that heading's line now; other lines map to null. Returns null
     * when no heading changed.
     */
    fun editEach(doc: OrgDocument, lines: Set<Int>, edit: (OrgDocument, OrgHeadline) -> String?): BulkEdit? {
        val targets = doc.headlines.filter { it.lineIndex in lines }.sortedByDescending { it.lineIndex }
        var cur = doc
        var applied = 0
        for (target in targets) {
            val h = cur.headlines.getOrNull(target.index) ?: continue
            val text = edit(cur, h)?.takeIf { it != cur.text } ?: continue
            cur = OrgParser.parse(text, doc.keywords)
            applied++
        }
        if (applied == 0) return null
        val indexByLine = doc.headlines.associate { it.lineIndex to it.index }
        val final = cur
        return BulkEdit(cur.text, cur, applied, targets.size) { l ->
            indexByLine[l]?.let { final.headlines.getOrNull(it)?.lineIndex }
        }
    }

    /**
     * [moveSubtree], plus where each line of [doc] lands afterwards. Null at an
     * edge or when the sibling to swap with is in [selected].
     */
    private fun moveTracked(
        doc: OrgDocument,
        h: OrgHeadline,
        delta: Int,
        selected: Set<Int>,
    ): Pair<String, (Int) -> Int?>? {
        val siblings = siblingsOf(doc, h)
        val other = siblings.getOrNull(siblings.indexOfFirst { it.lineIndex == h.lineIndex } + delta) ?: return null
        if (other.lineIndex in selected) return null
        val first = if (other.lineIndex < h.lineIndex) other else h
        val second = if (other.lineIndex < h.lineIndex) h else other
        val f = first.lineIndex
        val fe = doc.subtreeEndLine(first)
        val s = second.lineIndex
        val se = doc.subtreeEndLine(second)
        val (text, _) = moveSubtree(doc, h, delta) ?: return null
        // The swap rebuilds [f, se) as second ++ gap ++ first.
        return text to { l: Int ->
            when (l) {
                in s until se -> f + (l - s)
                in fe until s -> f + (se - s) + (l - fe)
                in f until fe -> f + (se - fe) + (l - f)
                else -> l
            }
        }
    }

    private fun siblingsOf(doc: OrgDocument, h: OrgHeadline): List<OrgHeadline> =
        doc.parent(h)?.let { doc.directChildren(it) }
            ?: doc.headlines.filter { it.level == h.level && doc.parent(it) == null }

    /**
     * Re-star [h]'s subtree by [delta] using the parsed headline positions,
     * so literal star lines inside blocks are never touched.
     */
    private fun shiftSubtreeLevels(doc: OrgDocument, h: OrgHeadline, delta: Int): String {
        val end = doc.subtreeEndLine(h)
        val headlineLines = doc.headlines
            .filter { it.lineIndex in h.lineIndex until end }
            .associateBy { it.lineIndex }
        val lines = doc.lines.mapIndexed { i, line ->
            val head = headlineLines[i] ?: return@mapIndexed line
            val m = STARS_LINE.matchEntire(line) ?: return@mapIndexed line
            "*".repeat((head.level + delta).coerceAtLeast(1)) + m.groupValues[2]
        }
        return lines.joinToString("\n")
    }

    /** Insert a new child note at the end of [h]'s subtree; returns text + new headline's line. */
    fun newChild(
        doc: OrgDocument,
        h: OrgHeadline,
        title: String,
        options: NewNoteOptions = NewNoteOptions(),
    ): Pair<String, Int> = insertNote(doc, doc.subtreeEndLine(h), h.level + 1, title, options)

    /** Append a new top-level note at the end of the file (outline FAB, PRD §5.3). */
    fun newTopLevel(
        doc: OrgDocument,
        title: String,
        options: NewNoteOptions = NewNoteOptions(),
    ): Pair<String, Int> = insertNote(doc, doc.lines.size, 1, title, options)

    /** Insert a new sibling note immediately above [h] (same level). */
    fun insertSiblingAbove(
        doc: OrgDocument,
        h: OrgHeadline,
        title: String = "",
        options: NewNoteOptions = NewNoteOptions(),
    ): Pair<String, Int> = insertNote(doc, h.lineIndex, h.level, title, options)

    /** Insert a new sibling note immediately below [h]'s whole subtree (same level). */
    fun insertSiblingBelow(
        doc: OrgDocument,
        h: OrgHeadline,
        title: String = "",
        options: NewNoteOptions = NewNoteOptions(),
    ): Pair<String, Int> = insertNote(doc, doc.subtreeEndLine(h), h.level, title, options)

    private fun insertNote(
        doc: OrgDocument,
        atLine: Int,
        level: Int,
        title: String,
        options: NewNoteOptions,
    ): Pair<String, Int> {
        val entry = buildList {
            // A blank-title note (created for immediate editing) needs the
            // trailing space so the cursor lands after "* " ready for typing.
            val header = headlineLine(level, options.keyword, null, title, emptyList())
            add(if (title.isEmpty() && options.keyword == null) "$header " else header)
            if (options.id != null || options.createdAt != null) {
                add(":PROPERTIES:")
                options.id?.let { add(":ID: $it") }
                options.createdAt?.let {
                    add(
                        ":CREATED: " + OrgTimestamp(
                            it.toLocalDate(),
                            time = it.toLocalTime().withSecond(0).withNano(0),
                            active = false,
                        ).format()
                    )
                }
                add(":END:")
            }
            options.body?.takeIf { it.isNotBlank() }?.let { body ->
                body.trimEnd('\n').split("\n").forEach { add(it) }
            }
        }
        // An empty document is a single empty line: replace it instead of
        // stacking the note under a leading blank.
        if (doc.lines.all { it.isEmpty() }) {
            return entry.joinToString("\n") + "\n" to 0
        }
        val lines = doc.lines.toMutableList()
        var insertAt = atLine.coerceIn(0, lines.size)
        if (insertAt == lines.size && lines.isNotEmpty() && lines.last().isEmpty()) insertAt--
        lines.addAll(insertAt, entry)
        return lines.joinToString("\n") to insertAt
    }

    /** Paste a cut/copied subtree as the last child of [h], releveled. */
    fun pasteUnder(doc: OrgDocument, h: OrgHeadline, subtree: String): String {
        val releveled = relevelSubtree(subtree.trimEnd('\n').split("\n"), h.level + 1)
        val lines = doc.lines.toMutableList()
        var insertAt = doc.subtreeEndLine(h)
        if (insertAt == lines.size && lines.isNotEmpty() && lines.last().isEmpty()) insertAt--
        lines.addAll(insertAt, releveled)
        return lines.joinToString("\n")
    }

    // --- helpers ---

    fun headlineLine(
        level: Int,
        keyword: String?,
        priority: Char?,
        title: String,
        tags: List<String>,
    ): String = buildString {
        append("*".repeat(level))
        keyword?.let { append(' ').append(it) }
        priority?.let { append(" [#").append(it).append(']') }
        if (title.isNotEmpty()) append(' ').append(title)
        if (tags.isNotEmpty()) {
            append("  ").append(tags.joinToString(":", prefix = ":", postfix = ":"))
        }
    }

    private fun leadingWhitespace(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    private fun isMetadataLine(line: String): Boolean {
        val t = line.trim().uppercase()
        return isPlanningLine(line) || t == ":PROPERTIES:" || t == ":LOGBOOK:"
    }

    /**
     * Indentation for a metadata line (planning, drawer, active-timestamp line)
     * newly added under [h], so it matches how the file already writes them:
     * [h]'s own metadata first, else the first other heading's. A file that
     * indents to heading level + 1 (Emacs' `org-adapt-indentation`) gets
     * [h]'s level + 1; a fixed indent is copied as-is; none means column 0.
     */
    private fun metadataIndent(doc: OrgDocument, h: OrgHeadline): String {
        fun firstMetadataIndent(of: OrgHeadline): String? =
            (of.lineIndex + 1 until minOf(of.contentEnd, doc.lines.size))
                .firstOrNull { !doc.lines[it].isBlank() }
                ?.let { doc.lines[it] }
                ?.takeIf(::isMetadataLine)
                ?.let(::leadingWhitespace)
        firstMetadataIndent(h)?.let { return it }
        for (other in doc.headlines) {
            if (other.lineIndex == h.lineIndex) continue
            val indent = firstMetadataIndent(other) ?: continue
            return when {
                indent.isEmpty() -> ""
                indent.all { it == ' ' } && indent.length == other.level + 1 -> " ".repeat(h.level + 1)
                else -> indent
            }
        }
        return ""
    }

    private fun planningLine(p: Planning): String? {
        val parts = buildList {
            p.scheduled?.let { add("SCHEDULED: ${it.format()}") }
            p.deadline?.let { add("DEADLINE: ${it.format()}") }
            p.closed?.let { add("CLOSED: ${it.format()}") }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    private fun writePlanning(doc: OrgDocument, h: OrgHeadline, planning: Planning): String {
        val lines = doc.lines.toMutableList()
        val planningLineIndex = h.lineIndex + 1
        val hadPlanning = planningLineIndex < doc.subtreeEndLine(h) &&
                planningLineIndex < lines.size &&
                isPlanningLine(lines[planningLineIndex])
        val newLine = planningLine(planning)?.let {
            val indent = if (hadPlanning) leadingWhitespace(lines[planningLineIndex]) else metadataIndent(doc, h)
            indent + it
        }
        when {
            hadPlanning && newLine != null -> lines[planningLineIndex] = newLine
            hadPlanning -> lines.removeAt(planningLineIndex)
            newLine != null -> lines.add(planningLineIndex, newLine)
        }
        return lines.joinToString("\n")
    }

    /** Emacs' `org-log-note-headings` state-change note: `- State %-12s from %-12s %t`. */
    private fun logStateChangeLine(newState: String, oldState: String, at: OrgTimestamp): String {
        fun pad(s: String) = "\"$s\"".padEnd(12)
        return "- State ${pad(newState)} from ${pad(oldState)} ${at.format()}"
    }

    /**
     * First line right after any planning line: the dedicated active-timestamp
     * line's canonical slot ([setActiveTimestamps]), and where the drawer run
     * starts when there's no such line there.
     */
    private fun planningAdjacentStart(doc: OrgDocument, h: OrgHeadline): Int {
        val planningLineIndex = h.lineIndex + 1
        val hasPlanning = planningLineIndex < doc.subtreeEndLine(h) &&
                planningLineIndex < doc.lines.size && isPlanningLine(doc.lines[planningLineIndex])
        return if (hasPlanning) planningLineIndex + 1 else h.lineIndex + 1
    }

    /**
     * First line right after any planning line AND a dedicated active-timestamp
     * line in its canonical slot there: where the drawer run (if any) starts.
     */
    private fun drawerScanStart(doc: OrgDocument, h: OrgHeadline): Int {
        val start = planningAdjacentStart(doc, h)
        val hasDedicatedLine = start < doc.subtreeEndLine(h) &&
                start < doc.lines.size && isPureActiveTimestampLine(doc.lines[start])
        return if (hasDedicatedLine) start + 1 else start
    }

    /**
     * Line index of [marker]'s own drawer-open line, if it appears in the
     * contiguous `:PROPERTIES:`/`:LOGBOOK:` run starting at [start]: mirrors
     * [OrgParser]'s drawer-scanning rules (drawers must immediately follow
     * the planning line, in either order, with no gap). Returns null if
     * [marker] isn't present or the run is interrupted before reaching it.
     */
    private fun findDrawerMarker(lines: List<String>, start: Int, end: Int, marker: String): Int? {
        var cursor = start
        while (cursor < end) {
            val trimmed = lines[cursor].trim().uppercase()
            if (trimmed == marker) return cursor
            if (trimmed != ":PROPERTIES:" && trimmed != ":LOGBOOK:") return null
            var i = cursor + 1
            while (i < end && !lines[i].trim().equals(":END:", ignoreCase = true)) i++
            if (i >= end) return null
            cursor = i + 1
        }
        return null
    }

    /**
     * Prepend [entry] to [h]'s `:LOGBOOK:` drawer (newest first, matching
     * Emacs' default `org-log-states-order-reversed`), creating the drawer
     * right after `:PROPERTIES:` (or at the top of the drawer run if there
     * isn't one) when it doesn't exist yet.
     */
    fun appendLogbookEntry(doc: OrgDocument, h: OrgHeadline, entry: String): String {
        val lines = doc.lines.toMutableList()
        val start = drawerScanStart(doc, h)
        val logbookMarker = findDrawerMarker(lines, start, h.bodyStart, ":LOGBOOK:")
        if (logbookMarker != null) {
            lines.add(logbookMarker + 1, leadingWhitespace(lines[logbookMarker]) + entry)
        } else {
            val propertiesMarker = findDrawerMarker(lines, start, h.bodyStart, ":PROPERTIES:")
            val insertAt = if (propertiesMarker != null) {
                var end = propertiesMarker + 1
                while (!lines[end].trim().equals(":END:", ignoreCase = true)) end++
                end + 1
            } else start
            val indent = metadataIndent(doc, h)
            lines.addAll(insertAt, listOf(":LOGBOOK:", entry, ":END:").map { indent + it })
        }
        return lines.joinToString("\n")
    }

    /**
     * Add a free-text note to [h]'s `:LOGBOOK:` drawer (org's `C-c C-z`), creating
     * the drawer if it doesn't exist yet. Placed above the most recently added
     * note but below any other drawer entries (state-change lines, clock
     * entries, ...), i.e. right before the first existing "Note taken on" line,
     * or at the very end of the drawer (before `:END:`) when there isn't one, so
     * a run of notes reads newest-first while staying a single contiguous block
     * after the drawer's history.
     */
    fun appendLogbookNote(doc: OrgDocument, h: OrgHeadline, note: String, at: OrgTimestamp): String {
        val rawEntry = listOf("- Note taken on ${at.format()} \\\\") +
                note.trim('\n').split("\n").map { "  $it" }
        val lines = doc.lines.toMutableList()
        val start = drawerScanStart(doc, h)
        val logbookMarker = findDrawerMarker(lines, start, h.bodyStart, ":LOGBOOK:")
        if (logbookMarker != null) {
            var end = logbookMarker + 1
            while (!lines[end].trim().equals(":END:", ignoreCase = true)) end++
            val firstNoteLine = (logbookMarker + 1 until end)
                .firstOrNull { lines[it].trim().startsWith("- Note taken on", ignoreCase = true) }
            val indent = leadingWhitespace(lines[logbookMarker])
            lines.addAll(firstNoteLine ?: end, rawEntry.map { indent + it })
        } else {
            val propertiesMarker = findDrawerMarker(lines, start, h.bodyStart, ":PROPERTIES:")
            val insertAt = if (propertiesMarker != null) {
                var end = propertiesMarker + 1
                while (!lines[end].trim().equals(":END:", ignoreCase = true)) end++
                end + 1
            } else start
            val indent = metadataIndent(doc, h)
            lines.addAll(insertAt, (listOf(":LOGBOOK:") + rawEntry + listOf(":END:")).map { indent + it })
        }
        return lines.joinToString("\n")
    }

    private fun propertyKeyLine(key: String, line: String): Boolean {
        val m = Regex("""^\s*:([^:\s]+):""").find(line) ?: return false
        return m.groupValues[1].equals(key, ignoreCase = true)
    }

    /**
     * Set [key] to [value] in [h]'s `:PROPERTIES:` drawer, replacing an
     * existing entry for that key or inserting a new one, creating the
     * drawer at the top of the drawer run if it doesn't exist yet.
     */
    fun upsertProperty(doc: OrgDocument, h: OrgHeadline, key: String, value: String): String {
        val lines = doc.lines.toMutableList()
        val start = drawerScanStart(doc, h)
        val propertiesMarker = findDrawerMarker(lines, start, h.bodyStart, ":PROPERTIES:")
        val newLine = ":$key: $value"
        if (propertiesMarker != null) {
            var end = propertiesMarker + 1
            while (!lines[end].trim().equals(":END:", ignoreCase = true)) end++
            val existing = (propertiesMarker + 1 until end).firstOrNull { propertyKeyLine(key, lines[it]) }
            if (existing != null) {
                lines[existing] = leadingWhitespace(lines[existing]) + newLine
            } else {
                lines.add(end, leadingWhitespace(lines[propertiesMarker]) + newLine)
            }
        } else {
            val indent = metadataIndent(doc, h)
            lines.addAll(start, listOf(":PROPERTIES:", newLine, ":END:").map { indent + it })
        }
        return lines.joinToString("\n")
    }

    private val STARS_PREFIX = Regex("""^(\*+)\s""")
    private val STARS_LINE = Regex("""^(\*+)(\s.*)$""")

    /** Re-star every headline line of a detached subtree so its root sits at [targetLevel]. */
    private fun relevelSubtree(subtreeLines: List<String>, targetLevel: Int): List<String> {
        val srcLevel = STARS_PREFIX.find(subtreeLines.first())?.groupValues?.get(1)?.length ?: 1
        val shift = targetLevel - srcLevel
        return subtreeLines.map { line ->
            val m = STARS_LINE.matchEntire(line)
            if (m != null) {
                val newLevel = (m.groupValues[1].length + shift).coerceAtLeast(1)
                "*".repeat(newLevel) + m.groupValues[2]
            } else line
        }
    }

    private fun replaceLine(doc: OrgDocument, lineIndex: Int, newLine: String): String {
        val lines = doc.lines.toMutableList()
        lines[lineIndex] = newLine
        return lines.joinToString("\n")
    }
}
