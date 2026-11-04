package com.rrajath.grove.org

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * One `CLOCK:` line, read the way org-element reads a `clock` element:
 * running (`CLOCK: [2026-10-30 Fri 09:15]`), closed
 * (`CLOCK: [a]--[b] =>  1:15`), or a naked duration (`CLOCK: => 1:15`).
 * A `[a]--[b]` range without its `=>` cookie is not a clock in Org, so it
 * isn't one here either.
 */
data class OrgClock(
    /** Absolute line index of the `CLOCK:` line in the document. */
    val lineIndex: Int,
    /** Null only for a naked duration. A date-only stamp starts at 00:00. */
    val start: LocalDateTime?,
    /** Null while the clock runs (and for a naked duration). */
    val end: LocalDateTime?,
    /** The stated minutes of a naked `CLOCK: => H:MM` line, else null. */
    val nakedMinutes: Int? = null,
) {
    val isRunning: Boolean get() = start != null && end == null && nakedMinutes == null

    /**
     * Whole minutes this clock contributes to a total over [from]..[to], by
     * `org-clock-sum`'s rules: the span is recomputed from the two stamps (the
     * `=>` text is ignored), clipped to the range, floored, and only counted
     * when positive. A naked duration counts in full, unclipped. A running
     * clock counts only when [runningUntil] is given, as if it had stopped then.
     */
    fun minutes(
        from: LocalDateTime? = null,
        to: LocalDateTime? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        runningUntil: LocalDateTime? = null,
    ): Int {
        nakedMinutes?.let { return it }
        val s = start ?: return 0
        val e = end ?: runningUntil ?: return 0
        var ts = epoch(s, zone)
        var te = epoch(e, zone)
        if (from != null) ts = maxOf(ts, epoch(from, zone))
        if (to != null) te = minOf(te, epoch(to, zone))
        val dt = te - ts
        return if (dt > 0) Math.floorDiv(dt, 60L).toInt() else 0
    }

    companion object {
        private const val STAMP = """\[\d{4}-\d{2}-\d{2}[^\]\n]*\]"""
        private const val COOKIE = """[ \t]+=>[ \t]+(\d+):(\d\d)"""

        /** `org-element-clock-line-re`. Groups: start, end, cookie h/m (closed); naked h/m. */
        private val LINE = Regex(
            """^[ \t]*CLOCK:(?:[ \t]+($STAMP)(?:--($STAMP)$COOKIE)?|$COOKIE)[ \t]*$""",
        )

        /**
         * The start stamp of an open clock line, up to and including its `]`:
         * what `org-clock-out` keeps verbatim before appending `--[end] => …`.
         */
        private val OPEN_PREFIX = Regex("""^([ \t]*CLOCK:[ \t]+$STAMP)[ \t]*$""")

        /** Parse [line] (document line [lineIndex]) as a clock, or null when it isn't one. */
        fun parse(line: String, lineIndex: Int): OrgClock? {
            val m = LINE.matchEntire(line) ?: return null
            val g = m.groupValues
            if (g[1].isEmpty()) {
                return OrgClock(lineIndex, null, null, nakedMinutes = g[5].toInt() * 60 + g[6].toInt())
            }
            val start = stamp(g[1]) ?: return null
            if (g[2].isEmpty()) return OrgClock(lineIndex, start, null)
            val end = stamp(g[2]) ?: return null
            return OrgClock(lineIndex, start, end)
        }

        /** `CLOCK: [start]`: the line `org-clock-in` writes. */
        fun runningLine(start: LocalDateTime): String = "CLOCK: " + inactive(start).format()

        /**
         * [openLine] closed at [end]: its own start text kept as written (so a
         * day name in another language survives), then `--[end] => H:MM`, the
         * duration being `org-clock-out`'s whole-minute difference of the two
         * stamps in [zone]. An end before the start is clamped to the start, so
         * a device clock lagging another's writes `0:00` rather than Emacs'
         * malformed negative cookie. Null when [openLine] isn't an open clock.
         */
        fun closedLine(openLine: String, end: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): String? {
            val prefix = OPEN_PREFIX.matchEntire(openLine)?.groupValues?.get(1) ?: return null
            val start = parse(openLine, 0)?.start ?: return null
            val stop = minute(end).let { if (it.isBefore(start)) start else it }
            val minutes = OrgClock(0, start, stop).minutes(zone = zone)
            return prefix + "--" + inactive(stop).format() + " => " + cookie(minutes)
        }

        /** The duration a closed CLOCK line ends in: `org-clock-out`'s `%2d:%02d` (` 1:15`, `12:05`). */
        fun cookie(minutes: Int): String = String.format(Locale.ROOT, "%2d:%02d", minutes / 60, minutes % 60)

        /** `org-duration-from-minutes` in its `h:mm` format (`1:15`, `0:05`): how Bloom shows a total. */
        fun hmm(minutes: Int): String = String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60)

        /** The section-level clock scan behind [OrgDocument.clocks]. */
        internal fun scan(doc: OrgDocument): List<List<OrgClock>> = doc.headlines.map { h ->
            val end = minOf(h.contentEnd, doc.lines.size)
            // Zero capacity: most headings have no clocks, and an empty
            // ArrayList allocates no backing array.
            val clocks = ArrayList<OrgClock>(0)
            var i = h.lineIndex + 1
            while (i < end) {
                val line = doc.lines[i]
                val t = line.trimStart()
                if (t.startsWith("#+") && BLOCK_BEGIN.matches(t)) {
                    // Skip a terminated block's interior: a CLOCK line in an
                    // example or src block is text, not a clock. An unterminated
                    // `#+BEGIN_` is a paragraph to org-element, so scan on.
                    val kind = BLOCK_BEGIN.matchEntire(t)!!.groupValues[1]
                    val close = (i + 1 until end).firstOrNull { j ->
                        BLOCK_END.matchEntire(doc.lines[j].trim())?.groupValues?.get(1)
                            ?.equals(kind, ignoreCase = true) == true
                    }
                    if (close != null) {
                        i = close + 1
                        continue
                    }
                } else if (t.startsWith("CLOCK:")) {
                    parse(line, i)?.let(clocks::add)
                }
                i++
            }
            clocks
        }

        private val BLOCK_BEGIN = Regex("""#\+(?i:BEGIN_)(\S+).*""")
        private val BLOCK_END = Regex("""#\+(?i:END_)(\S+)""")

        private fun stamp(text: String): LocalDateTime? {
            val ts = OrgTimestamp.parse(text)?.takeIf { !it.active } ?: return null
            return LocalDateTime.of(ts.date, ts.time ?: LocalTime.MIDNIGHT)
        }

        private fun inactive(at: LocalDateTime): OrgTimestamp =
            OrgTimestamp(at.toLocalDate(), time = at.toLocalTime().withSecond(0).withNano(0), active = false)

        private fun minute(at: LocalDateTime): LocalDateTime = at.withSecond(0).withNano(0)

        private fun epoch(at: LocalDateTime, zone: ZoneId): Long = at.atZone(zone).toEpochSecond()
    }
}

/**
 * What closing a clock does: `org-clock-out-when-done` ([whenDone], whether a
 * done transition clocks the heading out) and
 * `org-clock-out-remove-zero-time-clocks` ([removeZero]). The defaults are
 * Bloom's setting defaults; Emacs' own default for [removeZero] is off.
 */
data class ClockOutPolicy(val whenDone: Boolean = true, val removeZero: Boolean = true)

/** The clocks in [h]'s own section (not its descendants'), in document order. */
fun OrgDocument.clocks(h: OrgHeadline): List<OrgClock> = clockIndex[h.index]

/**
 * [h]'s open clock: the first running one in document order, which is the one
 * `org-clock-find-position` resumes and `org-clock-in` writes at the top of the
 * drawer. Null when nothing runs there.
 */
fun OrgDocument.runningClock(h: OrgHeadline): OrgClock? = clocks(h).firstOrNull { it.isRunning }

/**
 * Minutes clocked into [h] (its subtree too, unless [includeSubtree] is false) over
 * [from]..[to]: `org-clock-sum`, with running clocks counted up to
 * [runningUntil] when one is given (`org-clock-report-include-clocking-task`).
 */
fun OrgDocument.clockedMinutes(
    h: OrgHeadline,
    from: LocalDateTime? = null,
    to: LocalDateTime? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    runningUntil: LocalDateTime? = null,
    includeSubtree: Boolean = true,
): Int {
    val headings = if (includeSubtree) listOf(h) + subtree(h) else listOf(h)
    return headings.sumOf { x -> clocks(x).sumOf { it.minutes(from, to, zone, runningUntil) } }
}

/**
 * A live total for [h]: minutes closed since [from] (all time when null) in its
 * subtree, plus the whole elapsed time of any clock still running there, which
 * is how Emacs' mode line adds the running instance (`org-clock-get-clocked-time`).
 * Read mode passes no [from] (`org-clock-display`'s all-time subtree sums); the
 * clock notification passes [clockTotalStart] (the mode line's figure).
 */
fun OrgDocument.clockDisplayMinutes(
    h: OrgHeadline,
    now: LocalDateTime,
    from: LocalDateTime? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    val closed = clockedMinutes(h, from = from, zone = zone)
    val running = (listOf(h) + subtree(h)).sumOf { x ->
        clocks(x).sumOf { if (it.isRunning) it.minutes(zone = zone, runningUntil = now) else 0 }
    }
    return closed + running
}

/**
 * Where [h]'s displayed total starts counting, `org-clock-get-sum-start`: its
 * `CLOCK_MODELINE_TOTAL` property (`current` / `today` / `repeat` / `all` /
 * `auto`) or `auto`, which is all time (null) unless a `LAST_REPEAT` property
 * dates the last repeat. `today` starts at midnight (`org-extend-today-until` 0).
 */
fun clockTotalStart(h: OrgHeadline, now: LocalDateTime): LocalDateTime? {
    val mode = h.property("CLOCK_MODELINE_TOTAL")?.lowercase()
    val lastRepeat = h.property("LAST_REPEAT")?.let(OrgTimestamp::parse)
        ?.let { LocalDateTime.of(it.date, it.time ?: LocalTime.MIDNIGHT) }
    return when (mode) {
        "current" -> now
        "today" -> now.toLocalDate().atStartOfDay()
        "all" -> null
        "repeat" -> lastRepeat
        null, "auto" -> lastRepeat
        else -> null
    }
}

/** A property of [this] heading by key, case-insensitively like `org-entry-get`. */
fun OrgHeadline.property(key: String): String? =
    properties[key] ?: properties.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
