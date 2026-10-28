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
    val rep = repeater ?: return this
    if (rep.unit == 'h' && time != null) return advanceHourRepeater(rep, time, now)
    val today = now.toLocalDate()
    fun LocalDate.plusUnit(n: Long): LocalDate = when (rep.unit) {
        'd' -> plusDays(n)
        'w' -> plusWeeks(n)
        'm' -> plusMonths(n)
        'y' -> plusYears(n)
        else -> this // an hour repeater on a time-less stamp has no time to shift
    }

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
