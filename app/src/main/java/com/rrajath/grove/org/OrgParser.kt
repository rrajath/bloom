package com.rrajath.grove.org

/**
 * Sentinel line index for the "intro": a file's real content before its first
 * `*` heading (org's zeroth section), surfaced as its own note (outline row,
 * read view, search row). Distinct from the preface, which is the file's `#+KEY:`
 * lines. The intro has no headline line, so `-1` stands in wherever a note is addressed
 * by (fileName, lineIndex) — the index rows, [com.rrajath.grove.ui.vault.NoteRef],
 * and search results.
 */
const val INTRO_LINE_INDEX = -1

/**
 * A line that is nothing but active `<…>` timestamps (and whitespace): the
 * dedicated bare-timestamp line [OrgMutations.setActiveTimestamps] manages.
 * Shared with [OrgMutations], which writes/detects that line.
 */
internal val PURE_ACTIVE_TS_LINE = Regex("""^\s*(?:<[^<>\n]+>(?:--<[^<>\n]+>)?\s*)+$""")

/** SCHEDULED / DEADLINE / CLOSED metadata from a headline's planning line. */
data class Planning(
    val scheduled: OrgTimestamp? = null,
    val deadline: OrgTimestamp? = null,
    val closed: OrgTimestamp? = null,
) {
    val isEmpty: Boolean get() = scheduled == null && deadline == null && closed == null
}

/**
 * One headline in the document, indexing into the document's line list.
 * The document text is the source of truth; this is a parsed view over it.
 */
data class OrgHeadline(
    /** Position in document order (0-based). */
    val index: Int,
    /** Line index of the `*` headline line. */
    val lineIndex: Int,
    val level: Int,
    val keyword: String?,
    val priority: Char?,
    val title: String,
    /** The headline's own tags (not inherited). */
    val tags: List<String>,
    val planning: Planning,
    val properties: Map<String, String>,
    /** Raw lines inside a `:LOGBOOK: ... :END:` drawer, marker lines excluded. */
    val logbook: List<String> = emptyList(),
    /**
     * Bare active timestamps in the headline's *own* body (`<2026-09-08 Mon>`,
     * or a range `<a>--<b>`), descendants and drawers excluded. These are events
     * on their date, never overdue. The planning line's SCHEDULED/DEADLINE
     * stamps are parsed separately into [planning] and never appear here.
     */
    val activeTimestamps: List<OrgTimestamp> = emptyList(),
    /**
     * The active timestamps on the headline's dedicated timestamp line — a
     * line that is nothing but `<…>` stamps (and whitespace), living right
     * after the planning line and before any drawers. This is the subset the
     * tabbed Dates editor manages; [activeTimestamps] additionally includes any
     * stamp the note carries inline in its prose, which the editor leaves alone.
     * Also recognized in the pre-flip position (below the drawers, as the
     * body's own first line) for files written before that line moved above
     * the drawers — see [OrgMutations.setActiveTimestamps]. Empty when neither
     * position holds one.
     */
    val dedicatedActiveTimestamps: List<OrgTimestamp> = emptyList(),
    /**
     * First line of body content: after the planning line, the dedicated
     * active-timestamp line (only when it's in its canonical position above
     * the drawers), and any properties/logbook drawers.
     */
    val bodyStart: Int,
    /** Exclusive end: line index of the next headline (any level) or EOF. */
    val contentEnd: Int,
) {
    val id: String? get() = properties["ID"]
    val customId: String? get() = properties["CUSTOM_ID"]
}

/**
 * A parsed org file. [text] is verbatim file content: serialization returns
 * it unchanged, so parse → serialize is byte-identical by construction.
 */
class OrgDocument(
    val text: String,
    val keywords: OrgKeywords,
    val lines: List<String>,
    val fileTags: List<String>,
    val headlines: List<OrgHeadline>,
) {
    /** Line index where the first headline starts; preamble is lines before it. */
    val preambleEnd: Int = headlines.firstOrNull()?.lineIndex ?: lines.size

    /**
     * File-level `#+KEY: value` lines: the leading keyword run before the
     * intro (up to [introStart]), in file order. Keyword-looking lines inside
     * the intro (e.g. `#+attr_org:` above an image) belong to that content,
     * not the preface. Key is rendered exactly as written, e.g. "#+TITLE:":
     * casing/format preserved.
     */
    val preambleKeywords: List<Pair<String, String>> by lazy {
        lines.subList(0, introStart).mapNotNull { line ->
            OrgParser.PREAMBLE_KEYWORD.matchEntire(line.trim())?.let { m ->
                "#+${m.groupValues[1]}:" to m.groupValues[2].trim()
            }
        }
    }

    /**
     * A leading file-level `:PROPERTIES: … :END:` drawer (before the first
     * headline and before any `#+KEY:` lines), as ordered `:KEY:` → value
     * pairs in file order. Empty when the preamble has no such drawer, or when
     * the drawer is unclosed (degrades exactly like [introStart], which
     * treats an unclosed drawer as prose). The drawer must start on the very
     * first line of the file, matching org-mode's file-level property drawer
     * rule and [introStart]'s own check.
     */
    val filePropertyDrawer: List<Pair<String, String>> by lazy {
        if (preambleEnd == 0 ||
            !lines[0].trim().equals(":PROPERTIES:", ignoreCase = true)
        ) {
            emptyList()
        } else {
            val end = (1 until preambleEnd).firstOrNull {
                lines[it].trim().equals(":END:", ignoreCase = true)
            }
            if (end == null) {
                emptyList()
            } else {
                (1 until end).mapNotNull { i ->
                    OrgParser.PROPERTY_LINE.matchEntire(lines[i])?.let { m ->
                        ":${m.groupValues[1]}:" to m.groupValues[2].trim()
                    }
                }
            }
        }
    }

    /**
     * The file-level `:ID:` from the leading [filePropertyDrawer], if any. This is
     * an org-id target for the whole file: a `[[id:…]]` link matching it resolves
     * to the file's outline.
     */
    val fileId: String? by lazy {
        filePropertyDrawer.firstOrNull { it.first.equals(":ID:", ignoreCase = true) }?.second
    }

    /**
     * First line index of the intro: the real content in the preamble (the region
     * before the first headline). Skips a leading file-level `:PROPERTIES: … :END:`
     * drawer, `#+KEY:` lines and blank lines. Equals [preambleEnd] when the preamble
     * is only a property drawer / keywords / blank lines (the common case).
     */
    val introStart: Int by lazy {
        var i = 0
        if (i < preambleEnd && lines[i].trim().equals(":PROPERTIES:", ignoreCase = true)) {
            val end = (i + 1 until preambleEnd).firstOrNull {
                lines[it].trim().equals(":END:", ignoreCase = true)
            }
            if (end != null) i = end + 1
        }
        while (i < preambleEnd) {
            val line = lines[i]
            val isKeyword = OrgParser.PREAMBLE_KEYWORD.matchEntire(line.trim()) != null
            if (line.isNotBlank() && !isKeyword) break
            i++
        }
        i
    }

    /** The intro: preamble lines from [introStart] up to the first headline. */
    val introBody: List<String> get() = lines.subList(introStart, preambleEnd)

    /** Whether the file has an intro: real (non-keyword, non-drawer) content before its first headline. */
    val hasIntro: Boolean get() = introBody.any { it.isNotBlank() }

    /** One-line label for the intro note: its first non-blank content line, trimmed. */
    val introTitle: String get() = introBody.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    fun serialize(): String = text

    /**
     * Whether [h] has any nested headline, i.e. anything folding would hide.
     * O(1): the next headline in document order is deeper iff a subtree exists.
     * Use this for foldability checks instead of `directChildren(h).isNotEmpty()`,
     * which is O(subtree) and becomes O(n²) when run across every headline.
     */
    fun hasDescendants(h: OrgHeadline): Boolean =
        (headlines.getOrNull(h.index + 1)?.level ?: 0) > h.level

    fun directChildren(h: OrgHeadline): List<OrgHeadline> {
        val children = mutableListOf<OrgHeadline>()
        for (i in (h.index + 1) until headlines.size) {
            val other = headlines[i]
            if (other.level <= h.level) break
            if (other.level == h.level + 1) children.add(other)
        }
        return children
    }

    /** All descendants of [h] (its subtree, excluding itself). */
    fun subtree(h: OrgHeadline): List<OrgHeadline> {
        val result = mutableListOf<OrgHeadline>()
        for (i in (h.index + 1) until headlines.size) {
            val other = headlines[i]
            if (other.level <= h.level) break
            result.add(other)
        }
        return result
    }

    fun parent(h: OrgHeadline): OrgHeadline? {
        for (i in (h.index - 1) downTo 0) {
            if (headlines[i].level < h.level) return headlines[i]
        }
        return null
    }

    /** Own tags + ancestor tags + file tags, deduplicated, own-first. */
    fun inheritedTags(h: OrgHeadline): List<String> {
        val result = LinkedHashSet(h.tags)
        var p = parent(h)
        while (p != null) {
            result.addAll(p.tags)
            p = parent(p)
        }
        result.addAll(fileTags)
        return result.toList()
    }

    /**
     * [inheritedTags] for every headline (indexed by [OrgHeadline.index]) in a
     * single forward pass with an ancestor stack. Indexing a whole file via
     * repeated [inheritedTags]/[parent] calls is O(n²); this is O(n·depth).
     */
    fun inheritedTagsAll(): List<List<String>> {
        val result = ArrayList<List<String>>(headlines.size)
        // (level, own tags + ancestor tags nearest-first) along the current path.
        val stack = ArrayDeque<Pair<Int, List<String>>>()
        for (h in headlines) {
            while (stack.isNotEmpty() && stack.last().first >= h.level) stack.removeLast()
            val parentChain = stack.lastOrNull()?.second ?: emptyList()
            val dedup = LinkedHashSet(h.tags)
            dedup.addAll(parentChain)
            dedup.addAll(fileTags)
            result.add(dedup.toList())
            stack.addLast(h.level to h.tags + parentChain)
        }
        return result
    }

    /** Body lines belonging to the headline itself (planning/properties excluded). */
    fun bodyOf(h: OrgHeadline): List<String> {
        val end = minOf(h.contentEnd, headlines.getOrNull(h.index + 1)?.lineIndex ?: lines.size)
        if (h.bodyStart >= end) return emptyList()
        return lines.subList(h.bodyStart, end)
    }

    /**
     * [bodyOf] without the leading bare active-timestamp line, when [h] still
     * carries one in the pre-flip position (below its drawers, as the body's
     * own first line — see [OrgMutations.setActiveTimestamps]). The current,
     * canonical position (right after the planning line, before any drawers)
     * already sits outside [bodyOf], so there is nothing to strip there. Read
     * mode and the Outline preview show [OrgHeadline.dedicatedActiveTimestamps]
     * as a chip, so that line would otherwise appear twice: once as the chip, once
     * as plain body text. Inline `<…>` stamps inside prose are left alone.
     */
    fun bodyWithoutDedicatedTimestamp(h: OrgHeadline): List<String> {
        val body = bodyOf(h)
        val firstLineIsDedicated = body.firstOrNull()?.let { PURE_ACTIVE_TS_LINE.matches(it) } == true
        return if (firstLineIsDedicated) body.drop(1) else body
    }

    /** [OrgHeadline.bodyStart] shifted past the line [bodyWithoutDedicatedTimestamp] drops, so
     *  body-relative block line numbers still resolve to absolute document lines. */
    fun bodyStartWithoutDedicatedTimestamp(h: OrgHeadline): Int {
        val firstLineIsDedicated = bodyOf(h).firstOrNull()?.let { PURE_ACTIVE_TS_LINE.matches(it) } == true
        return if (firstLineIsDedicated) h.bodyStart + 1 else h.bodyStart
    }

    /** Exclusive end line of [h]'s entire subtree (own content + descendants). */
    fun subtreeEndLine(h: OrgHeadline): Int =
        (subtree(h).lastOrNull() ?: h).contentEnd

    fun findById(id: String): OrgHeadline? = headlines.firstOrNull { it.id == id }
    fun findByCustomId(customId: String): OrgHeadline? =
        headlines.firstOrNull { it.customId == customId }
    fun findByTitle(title: String): OrgHeadline? =
        headlines.firstOrNull { it.title == title }
}

object OrgParser {

    private val HEADLINE = Regex("""^(\*+)\s+(.*)$""")
    private val TAGS_AT_END = Regex("""\s+(:[A-Za-z0-9_@#%-]+(?::[A-Za-z0-9_@#%-]+)*:)\s*$""")
    private val PRIORITY = Regex("""^\[#([A-Za-z])\]\s*""")
    private val FILETAGS = Regex("""^#\+(?i:FILETAGS):\s*(.*)$""")
    internal val PROPERTY_LINE = Regex("""^\s*:([^:\s]+):\s*(.*)$""")
    private val PLANNING_PART = Regex("""(SCHEDULED|DEADLINE|CLOSED):\s*""")

    internal val PREAMBLE_KEYWORD = Regex("""^#\+([A-Za-z][A-Za-z0-9_-]*):(.*)$""")

    fun parse(text: String, keywords: OrgKeywords = OrgKeywords.DEFAULT): OrgDocument {
        val lines = text.split("\n")
        val fileTags = mutableListOf<String>()
        data class Raw(val lineIndex: Int, val level: Int, val rest: String)

        val rawHeadlines = mutableListOf<Raw>()
        lines.forEachIndexed { i, line ->
            val m = if (line.isNotEmpty() && line[0] == '*') HEADLINE.matchEntire(line) else null
            if (m != null) {
                rawHeadlines.add(Raw(i, m.groupValues[1].length, m.groupValues[2]))
            } else if (rawHeadlines.isEmpty()) {
                FILETAGS.matchEntire(line.trim())?.let { ft ->
                    fileTags.addAll(parseTagList(ft.groupValues[1]))
                }
            }
        }

        val headlines = rawHeadlines.mapIndexed { idx, raw ->
            val contentEnd = rawHeadlines.getOrNull(idx + 1)?.lineIndex ?: lines.size
            parseHeadline(idx, raw.lineIndex, raw.level, raw.rest, lines, contentEnd, keywords)
        }

        return OrgDocument(text, keywords, lines, fileTags, headlines)
    }

    private fun parseTagList(value: String): List<String> =
        value.split(':', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseHeadline(
        index: Int,
        lineIndex: Int,
        level: Int,
        restIn: String,
        lines: List<String>,
        contentEnd: Int,
        keywords: OrgKeywords,
    ): OrgHeadline {
        var rest = restIn

        // Trailing tags
        var tags = emptyList<String>()
        TAGS_AT_END.find(rest)?.let { m ->
            tags = m.groupValues[1].trim(':').split(':').filter { it.isNotEmpty() }
            rest = rest.removeRange(m.range)
        }

        // TODO keyword (must be a configured keyword followed by space/EOL)
        var keyword: String? = null
        val firstWord = rest.substringBefore(' ')
        if (firstWord in keywords.all && (rest.length == firstWord.length || rest[firstWord.length] == ' ')) {
            keyword = firstWord
            rest = rest.drop(firstWord.length).trimStart()
        }

        // Priority cookie
        var priority: Char? = null
        PRIORITY.find(rest)?.let { m ->
            priority = m.groupValues[1][0]
            rest = rest.removeRange(m.range)
        }

        val title = rest.trim()

        // Planning line (single line immediately after the headline)
        var cursor = lineIndex + 1
        var planning = Planning()
        if (cursor < contentEnd && isPlanningLine(lines[cursor])) {
            planning = parsePlanning(lines[cursor])
            cursor++
        }

        // Dedicated active-timestamp line, in its canonical slot right after
        // the planning line and before any drawers. A pre-flip file with the
        // line below the drawers instead is picked up further down, once the
        // drawer run has been scanned.
        var dedicatedActiveTimestamps = emptyList<OrgTimestamp>()
        if (cursor < contentEnd && PURE_ACTIVE_TS_LINE.matches(lines[cursor])) {
            val stamps = OrgTimestamp.parseAll(lines[cursor].trim()).filter { it.active }
            if (stamps.isNotEmpty()) {
                dedicatedActiveTimestamps = stamps
                cursor++
            }
        }

        // Drawers (:PROPERTIES: and/or :LOGBOOK:), immediately after the planning
        // line/dedicated timestamp, in either order: org-mode allows both
        // orderings depending on whether log notes are configured to insert
        // above or below properties.
        val properties = linkedMapOf<String, String>()
        val logbook = mutableListOf<String>()
        var scanningDrawers = true
        while (scanningDrawers && cursor < contentEnd) {
            val marker = lines[cursor].trim()
            when {
                marker.equals(":PROPERTIES:", ignoreCase = true) -> {
                    val pending = linkedMapOf<String, String>()
                    var i = cursor + 1
                    var closed = false
                    while (i < contentEnd) {
                        val line = lines[i].trim()
                        if (line.equals(":END:", ignoreCase = true)) {
                            closed = true
                            break
                        }
                        PROPERTY_LINE.matchEntire(lines[i])?.let { m ->
                            pending[m.groupValues[1]] = m.groupValues[2].trim()
                        }
                        i++
                    }
                    // An unclosed drawer is body text, not properties.
                    if (closed) {
                        properties.putAll(pending)
                        cursor = i + 1
                    } else {
                        scanningDrawers = false
                    }
                }
                marker.equals(":LOGBOOK:", ignoreCase = true) -> {
                    val pending = mutableListOf<String>()
                    var i = cursor + 1
                    var closed = false
                    while (i < contentEnd) {
                        val line = lines[i].trim()
                        if (line.equals(":END:", ignoreCase = true)) {
                            closed = true
                            break
                        }
                        pending.add(lines[i])
                        i++
                    }
                    // An unclosed drawer is body text, not a logbook.
                    if (closed) {
                        logbook.addAll(pending)
                        cursor = i + 1
                    } else {
                        scanningDrawers = false
                    }
                }
                else -> scanningDrawers = false
            }
        }

        val body = if (cursor < contentEnd) lines.subList(cursor, contentEnd) else emptyList()
        val bodyActiveTimestamps = OrgTimestamp.parseAll(body.joinToString("\n"))
            .filter { it.active }
        // Pre-flip layout: the dedicated line sits below the drawers, as the
        // body's own first line, so it's still part of [body] above.
        val dedicatedLineInBody = body.firstOrNull()?.let { PURE_ACTIVE_TS_LINE.matches(it) } == true
        if (dedicatedActiveTimestamps.isEmpty() && dedicatedLineInBody) {
            dedicatedActiveTimestamps = OrgTimestamp.parseAll(body.first().trim()).filter { it.active }
        }
        // Avoid double-counting: a pre-flip dedicated line is already folded
        // into bodyActiveTimestamps via the body text itself.
        val activeTimestamps = if (dedicatedLineInBody) bodyActiveTimestamps else bodyActiveTimestamps + dedicatedActiveTimestamps

        return OrgHeadline(
            index = index,
            lineIndex = lineIndex,
            level = level,
            keyword = keyword,
            priority = priority,
            title = title,
            tags = tags,
            planning = planning,
            properties = properties,
            logbook = logbook,
            activeTimestamps = activeTimestamps,
            dedicatedActiveTimestamps = dedicatedActiveTimestamps,
            bodyStart = cursor,
            contentEnd = contentEnd,
        )
    }

    private fun parsePlanning(line: String): Planning {
        var scheduled: OrgTimestamp? = null
        var deadline: OrgTimestamp? = null
        var closed: OrgTimestamp? = null
        val parts = PLANNING_PART.findAll(line).toList()
        parts.forEachIndexed { i, m ->
            val valueEnd = parts.getOrNull(i + 1)?.range?.first ?: line.length
            val value = line.substring(m.range.last + 1, valueEnd)
            val ts = OrgTimestamp.parse(value)
            when (m.groupValues[1]) {
                "SCHEDULED" -> scheduled = ts
                "DEADLINE" -> deadline = ts
                "CLOSED" -> closed = ts
            }
        }
        return Planning(scheduled, deadline, closed)
    }
}

/** Shared by [OrgParser] and [OrgMutations]. */
internal fun isPlanningLine(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("SCHEDULED:") || t.startsWith("DEADLINE:") || t.startsWith("CLOSED:")
}
