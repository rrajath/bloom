package com.rrajath.grove.org

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

enum class RepeaterType(val marker: String) {
    /** `+`: shift by exactly one interval from the old date. */
    CUMULATIVE("+"),

    /** `++`: shift by intervals until the date lands in the future. */
    CATCH_UP("++"),

    /** `.+`: shift one interval from *today*. */
    FUTURE(".+");

    companion object {
        fun fromMarker(marker: String): RepeaterType? = entries.firstOrNull { it.marker == marker }
    }
}

data class Repeater(val type: RepeaterType, val value: Int, val unit: Char) {
    override fun toString(): String = "${type.marker}$value$unit"
}

/**
 * An org-mode timestamp: `<2025-04-30 Wed>`, `[2025-06-11 Wed 14:32]`,
 * `<2025-04-30 Wed 10:00-11:30 +1w -2d>` …
 *
 * Parsing is lenient about the day-name token; formatting always emits the
 * canonical English three-letter abbreviation (what Emacs writes by default).
 */
data class OrgTimestamp(
    val date: LocalDate,
    val time: LocalTime? = null,
    val endTime: LocalTime? = null,
    val active: Boolean = true,
    val repeater: Repeater? = null,
    /** Warning/delay cookie such as `-2d`, kept verbatim. */
    val warning: String? = null,
    /**
     * End date of an org timestamp *range* (`<a …>--<b …>`), or null for a plain
     * single-day timestamp. A ranged event spans every day from [date] to
     * [rangeEnd] inclusive.
     */
    val rangeEnd: LocalDate? = null,
) {
    fun format(): String {
        val sb = StringBuilder()
        // A cross-day range with times writes them split across the two brackets
        // (`<a 09:00>--<b 12:00>`, org's canonical form), not both on the opener.
        val splitTimes = rangeEnd != null && endTime != null
        sb.append(if (active) '<' else '[')
        sb.append(date)
        sb.append(' ').append(dayAbbrev(date))
        if (time != null) {
            sb.append(' ').append(formatTime(time))
            if (endTime != null && !splitTimes) sb.append('-').append(formatTime(endTime))
        }
        if (repeater != null) sb.append(' ').append(repeater)
        if (warning != null) sb.append(' ').append(warning)
        sb.append(if (active) '>' else ']')
        if (rangeEnd != null) {
            sb.append("--")
            sb.append(if (active) '<' else '[')
            sb.append(rangeEnd)
            sb.append(' ').append(dayAbbrev(rangeEnd))
            if (splitTimes) sb.append(' ').append(formatTime(endTime))
            sb.append(if (active) '>' else ']')
        }
        return sb.toString()
    }

    override fun toString(): String = format()

    /**
     * Reader-facing rendering of the same timestamp: `Jul 30`, `Jul 30 12:00`,
     * `Jul 30 12:00-13:30`, `Jul 30, 2027` for a date outside [today]'s year.
     *
     * Drops the org brackets and the day abbreviation (the month name already
     * reads as a date) but keeps a repeater and warning cookie verbatim: those
     * change what the timestamp *means*, so hiding them would lose information.
     * [format] remains the canonical on-disk form; this is display only.
     */
    fun formatHuman(today: LocalDate = LocalDate.now()): String {
        val sb = StringBuilder()
        sb.append(humanDate(date, today))
        if (time != null) {
            sb.append(' ').append(formatTime(time))
            // A same-day time range stays joined (`09:00-12:00`); a cross-day
            // range carries its end time on the last date instead (below).
            if (endTime != null && rangeEnd == null) sb.append('-').append(formatTime(endTime))
        }
        if (rangeEnd != null) {
            sb.append(" – ").append(humanDate(rangeEnd, today))
            if (endTime != null) sb.append(' ').append(formatTime(endTime))
        }
        if (repeater != null) sb.append(' ').append(repeater)
        if (warning != null) sb.append(' ').append(warning)
        return sb.toString()
    }

    companion object {
        // <2025-04-30 Wed 10:00-11:30 +1w -2d> with every part after the date optional
        private val PATTERN = Regex(
            """([<\[])(\d{4})-(\d{2})-(\d{2})(?:\s+([^\s\d>\]]+))?(?:\s+(\d{1,2}):(\d{2})(?:-(\d{1,2}):(\d{2}))?)?((?:\s+[.+]?\+\d+[hdwmy])?)((?:\s+-{1,2}\d+[hdwmy])?)\s*([>\]])""",
        )

        private val REPEATER = Regex("""([.+]?\+)(\d+)([hdwmy])""")

        /**
         * `Jul 30` (`30. Juli` in German): the reader-facing date, see
         * [formatHuman]. The year is added only outside [today]'s year.
         */
        private fun humanDate(d: LocalDate, today: LocalDate): String =
            if (d.year == today.year) LocalDates.format(d, "MMMd", "MMM d")
            else LocalDates.format(d, "yMMMd", "MMM d, yyyy")

        private fun formatTime(t: LocalTime): String {
            val sb = StringBuilder(5)
            if (t.hour < 10) sb.append('0')
            sb.append(t.hour).append(':')
            if (t.minute < 10) sb.append('0')
            sb.append(t.minute)
            return sb.toString()
        }

        /**
         * The day name inside a written timestamp, in [orgLocale] as Emacs
         * writes it (`Mon`, `Mo`, `lun.`). The stand-alone form matches glibc's
         * `%a` where the in-sentence form differs (German `Mo` vs `Mo.`); a
         * provider without stand-alone names returns a digit, so fall back.
         */
        fun dayAbbrev(date: LocalDate): String {
            val locale = orgLocale()
            val standalone = date.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
            return if (standalone.any { it.isDigit() }) date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            else standalone
        }

        /** Parse the first timestamp in [text], or null. */
        fun parse(text: String): OrgTimestamp? = parseWithRange(text)?.first

        /** Parse the first timestamp in [text] returning it plus its char range. */
        fun parseWithRange(text: String): Pair<OrgTimestamp, IntRange>? = parseWithRange(text, 0)

        /** Same as the single-arg overload, but starts scanning [text] at [startIndex] (no substring copy). */
        private fun parseWithRange(text: String, startIndex: Int): Pair<OrgTimestamp, IntRange>? {
            val m = PATTERN.find(text, startIndex) ?: return null
            val (open, year, month, day) = m.destructured
            val close = m.groupValues[12]
            // Reject mismatched brackets like "<2025-01-01]"
            if ((open == "<") != (close == ">")) return null
            val date = try {
                LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            } catch (e: java.time.DateTimeException) {
                return null
            }
            // Guarded the same way as the date above: the regex's `\d{1,2}:\d{2}`
            // happily matches out-of-range clock values like "25:00" or "10:99"
            // (typo'd by hand, or a >24h CLOCK duration cookie that looks like a
            // time), and LocalTime.of throws DateTimeException for those. Left
            // unguarded, that exception used to escape all the way out of
            // OrgTimestamp.parse into whichever caller triggered the lazy
            // NoteMeta.scheduledTs/deadlineTs evaluation first — most visibly the
            // agenda ledger widget's provideGlance, which has no caller-side
            // try/catch and has no visible "app crashed" UI to report it, so it
            // silently fell into the AppWidgetHost's generic "Can't show content"
            // fallback on every recomposition, surviving delete/re-add because
            // the bad timestamp lives in the vault, not the widget instance.
            val time = m.groupValues[6].takeIf { it.isNotEmpty() }?.let {
                try {
                    LocalTime.of(it.toInt(), m.groupValues[7].toInt())
                } catch (e: java.time.DateTimeException) {
                    return null
                }
            }
            val endTime = m.groupValues[8].takeIf { it.isNotEmpty() }?.let {
                try {
                    LocalTime.of(it.toInt(), m.groupValues[9].toInt())
                } catch (e: java.time.DateTimeException) {
                    return null
                }
            }
            val repeater = m.groupValues[10].trim().takeIf { it.isNotEmpty() }?.let { rep ->
                val repMatch = REPEATER.matchEntire(rep) ?: return@let null
                val type = RepeaterType.fromMarker(repMatch.groupValues[1]) ?: return@let null
                Repeater(type, repMatch.groupValues[2].toInt(), repMatch.groupValues[3][0])
            }
            val warning = m.groupValues[11].trim().takeIf { it.isNotEmpty() }
            return OrgTimestamp(
                date = date,
                time = time,
                endTime = endTime,
                active = open == "<",
                repeater = repeater,
                warning = warning,
            ) to (m.range)
        }

        /**
         * [text] with every active timestamp that really repeats ([repeats])
         * advanced per [advanceRepeater] at [now], and every other byte left
         * exactly as it was. What Emacs's org-auto-repeat-maybe does to each
         * repeating timestamp in an entry when it is marked done. A range's
         * brackets each follow their own repeater, except that a closing bracket
         * with none (how this app writes ranges: `<a +1w>--<b>`) moves by the
         * opener's shift so the range keeps its length.
         */
        fun shiftRepeaters(text: String, now: LocalDateTime): String {
            val sb = StringBuilder(text.length)
            var offset = 0
            while (offset < text.length) {
                val (ts, range) = parseWithRange(text, offset) ?: break
                sb.append(text, offset, range.first)
                val shifted = shiftedAt(text, ts, range, now)
                if (shifted != null) {
                    sb.append(shifted.first)
                    offset = shifted.second
                } else {
                    sb.append(text, range.first, range.last + 1)
                    offset = range.last + 1
                }
            }
            sb.append(text, offset, text.length)
            return sb.toString()
        }

        /**
         * [text] with only the first repeating active timestamp dated [date]
         * advanced at [now] (its range's closing bracket too, as in
         * [shiftRepeaters]), or null when [text] has no such stamp.
         */
        fun shiftRepeaterOn(text: String, date: LocalDate, now: LocalDateTime): String? {
            var offset = 0
            while (offset < text.length) {
                val (ts, range) = parseWithRange(text, offset) ?: return null
                if (ts.date == date) {
                    shiftedAt(text, ts, range, now)?.let { (replacement, end) ->
                        return text.substring(0, range.first) + replacement + text.substring(end)
                    }
                }
                offset = range.last + 1
            }
            return null
        }

        /**
         * The advanced text for the repeating active stamp [ts] matched at
         * [range] in [text], plus the index just past what it replaces: the
         * stamp alone, or the whole `<a>--<b>` when it opens a range. Null when
         * [ts] doesn't repeat (a range's closer is handled with its opener).
         */
        private fun shiftedAt(
            text: String,
            ts: OrgTimestamp,
            range: IntRange,
            now: LocalDateTime,
        ): Pair<String, Int>? {
            if (!ts.active || !ts.repeats) return null
            val advanced = ts.advanceRepeater(now)
            val end = range.last + 1
            if (text.regionMatches(end, "--", 0, 2)) {
                val closerMatch = parseWithRange(text, end + 2)
                    ?.takeIf { (closer, r) -> r.first == end + 2 && closer.active }
                if (closerMatch != null) {
                    val (closer, r) = closerMatch
                    val newCloser = if (closer.repeats) closer.advanceRepeater(now)
                    else closer.copy(date = closer.date.plusDays(ChronoUnit.DAYS.between(ts.date, advanced.date)))
                    return advanced.format() + "--" + newCloser.format() to r.last + 1
                }
            }
            return advanced.format() to end
        }

        /**
         * Every timestamp in [text], in order. When a match is immediately
         * followed by `--<…>` the two are paired into one ranged [OrgTimestamp]
         * ([rangeEnd] set from the second bracket's date). Used to scan an
         * entry's body for bare active timestamps.
         */
        fun parseAll(text: String): List<OrgTimestamp> {
            val result = mutableListOf<OrgTimestamp>()
            var offset = 0
            while (offset < text.length) {
                val (ts, range) = parseWithRange(text, offset) ?: break
                val absEnd = range.last + 1
                if (ts.active && ts.rangeEnd == null && text.regionMatches(absEnd, "--", 0, 2)) {
                    val endMatch = parseWithRange(text, absEnd + 2)
                    if (endMatch != null && endMatch.second.first == absEnd + 2 && endMatch.first.active) {
                        result.add(
                            ts.copy(
                                rangeEnd = endMatch.first.date,
                                // Org's canonical timed range puts the end time on
                                // the closing bracket; fall back to any the opener
                                // carried (older `<a 09:00-12:00>--<b>` form).
                                endTime = endMatch.first.time ?: ts.endTime,
                            ),
                        )
                        offset = endMatch.second.last + 1
                        continue
                    }
                }
                result.add(ts)
                offset = absEnd
            }
            return result
        }
    }
}

/**
 * Advance a repeating timestamp per org rules when its task is marked done at
 * [now]. Returns the timestamp unchanged if it has no repeater.
 */
fun OrgTimestamp.advanceRepeater(now: LocalDateTime): OrgTimestamp {
    // A zero interval is no repeater (Emacs), and would loop forever under `++`.
    val rep = repeater?.takeIf { it.value > 0 } ?: return this
    if (rep.unit == 'h') {
        // A time-less stamp has no hour to shift (Emacs refuses with a user-error);
        // leaving it alone also keeps `++` below from looping on a no-op step.
        return if (time != null) advanceHourRepeater(rep, time, now) else this
    }
    val today = now.toLocalDate()
    fun LocalDate.plusUnit(n: Long): LocalDate = plusOrgUnits(n, rep.unit)

    val n = rep.value.toLong()
    val newDate = when (rep.type) {
        RepeaterType.CUMULATIVE -> date.plusUnit(n)
        RepeaterType.CATCH_UP -> {
            var d = date.plusUnit(n)
            while (!d.isAfter(today)) d = d.plusUnit(n)
            d
        }
        RepeaterType.FUTURE -> today.plusUnit(n)
    }
    val dayDelta = ChronoUnit.DAYS.between(date, newDate)
    return copy(date = newDate, rangeEnd = rangeEnd?.plusDays(dayDelta))
}

/**
 * This date moved by [n] repeater units of [unit] (`d`, `w`, `m`, `y`) the way
 * Emacs does it: months and years keep the day of month and let one the target
 * month lacks overflow into the next (Jan 31 + 1m = Mar 3, Feb 29 + 1y = Mar 1),
 * where java.time's plusMonths/plusYears would clamp to the month's last day.
 * Any other unit (an hour repeater) leaves the date unchanged.
 */
fun LocalDate.plusOrgUnits(n: Long, unit: Char): LocalDate = when (unit) {
    'd' -> plusDays(n)
    'w' -> plusWeeks(n)
    'm' -> withDayOfMonth(1).plusMonths(n).plusDays(dayOfMonth - 1L)
    'y' -> withDayOfMonth(1).plusYears(n).plusDays(dayOfMonth - 1L)
    else -> this
}

/** Whether this stamp really repeats: Emacs treats a zero interval (`+0d`) as no repeater. */
val OrgTimestamp.repeats: Boolean get() = (repeater?.value ?: 0) > 0

/**
 * The start date of this timestamp's occurrence that covers [day], or null when
 * none does. A plain stamp covers its own date (through [OrgTimestamp.rangeEnd]
 * for a range). A repeating one also covers every later occurrence, projected
 * from [OrgTimestamp.date] the way the Emacs agenda does: `+`, `++` and `.+`
 * all display alike (the type only matters when the entry is marked done),
 * months and years overflow per [plusOrgUnits], and an hour repeater covers each
 * day one of its hourly starts lands on. Never before the stamp's own date.
 *
 * Deliberate departure from the Emacs agenda, which shows a repeating range
 * only once and ignores a repeater followed by a warning cookie (`+1w -1d`):
 * both repeat here, matching what Emacs's own mark-done does to them. A range
 * repeats as a block of the same length.
 */
fun OrgTimestamp.occurrenceCovering(day: LocalDate): LocalDate? {
    if (day.isBefore(date)) return null
    val span = rangeEnd?.let { ChronoUnit.DAYS.between(date, it).coerceAtLeast(0) } ?: 0L
    val rep = repeater?.takeIf { it.value > 0 }
        ?: return date.takeIf { !day.isAfter(date.plusDays(span)) }
    if (rep.unit == 'h') return hourOccurrenceOn(rep.value.toLong(), day)
    val step = rep.value.toLong()
    // Index of the last occurrence starting on or before [day]: estimated from
    // the calendar distance (est + 1 always starts after [day]), then walked down.
    // Occurrences only move forward with n, overflowed month-ends included.
    val est = when (rep.unit) {
        'd' -> ChronoUnit.DAYS.between(date, day) / step
        'w' -> ChronoUnit.WEEKS.between(date, day) / step
        'm' -> ChronoUnit.MONTHS.between(date.withDayOfMonth(1), day.withDayOfMonth(1)) / step
        'y' -> (day.year - date.year).toLong() / step
        else -> return null
    }
    var n = est + 1
    while (n >= 0) {
        val start = date.plusOrgUnits(n * step, rep.unit)
        if (!start.isAfter(day)) return start.takeIf { !day.isAfter(it.plusDays(span)) }
        n--
    }
    return null
}

/** Whether any occurrence of this stamp ([occurrenceCovering]) touches a day in [from]..[to]. */
fun OrgTimestamp.occursBetween(from: LocalDate, to: LocalDate): Boolean {
    if (!repeats) return !date.isAfter(to) && !(rangeEnd ?: date).isBefore(from)
    var day = maxOf(from, date)
    while (!day.isAfter(to)) {
        if (occurrenceCovering(day) != null) return true
        day = day.plusDays(1)
    }
    return false
}

/**
 * An hour repeater's agenda days: [day] counts when one of the starts
 * `date time + k * hours` (k >= 0) falls on it. Every day for an interval of a
 * day or less, every other day for `+48h`, and so on (Emacs's org-closest-date).
 */
private fun OrgTimestamp.hourOccurrenceOn(hours: Long, day: LocalDate): LocalDate? {
    val start = LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT)
    val dayStart = day.atStartOfDay()
    if (!dayStart.isAfter(start)) return day.takeIf { it == date }
    val stepMinutes = hours * 60
    val k = (ChronoUnit.MINUTES.between(start, dayStart) + stepMinutes - 1) / stepMinutes
    return day.takeIf { start.plusMinutes(k * stepMinutes).toLocalDate() == day }
}

/**
 * Hour repeaters shift the full date-time, so crossing midnight moves the date:
 * `+` adds one interval to the old start, `++` adds intervals until the start is
 * after [now], and `.+` restarts from [now] (Emacs keeps now's minutes too).
 */
private fun OrgTimestamp.advanceHourRepeater(rep: Repeater, time: LocalTime, now: LocalDateTime): OrgTimestamp {
    val hours = rep.value.toLong()
    val start = LocalDateTime.of(date, time)
    val newStart = when (rep.type) {
        RepeaterType.CUMULATIVE -> start.plusHours(hours)
        RepeaterType.CATCH_UP -> {
            var t = start.plusHours(hours)
            // A zero interval would never pass now; stop after one step instead.
            while (hours > 0 && !t.isAfter(now)) t = t.plusHours(hours)
            t
        }
        RepeaterType.FUTURE -> now.withSecond(0).withNano(0).plusHours(hours)
    }
    val delta = Duration.between(start, newStart)
    val newEnd = endTime?.let { LocalDateTime.of(rangeEnd ?: date, it).plus(delta) }
    return copy(
        date = newStart.toLocalDate(),
        time = newStart.toLocalTime(),
        endTime = newEnd?.toLocalTime(),
        rangeEnd = when {
            rangeEnd == null -> null
            newEnd != null -> newEnd.toLocalDate()
            else -> rangeEnd.plusDays(ChronoUnit.DAYS.between(date, newStart.toLocalDate()))
        },
    )
}
