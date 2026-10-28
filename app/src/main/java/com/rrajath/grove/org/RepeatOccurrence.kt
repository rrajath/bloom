package com.rrajath.grove.org

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The date a recurring heading moved to when it was marked done: the earliest
 * repeating SCHEDULED / DEADLINE / dedicated active timestamp on [after] that
 * [before] didn't already have. Null when nothing repeating was advanced (a plain,
 * non-recurring mark-done).
 */
fun nextRepeatOccurrence(before: OrgHeadline, after: OrgHeadline): LocalDate? =
    (after.repeatingDates() - before.repeatingDates().toSet()).minOrNull()

private fun OrgHeadline.repeatingDates(): List<LocalDate> =
    (listOfNotNull(planning.scheduled, planning.deadline) + dedicatedActiveTimestamps)
        .filter { it.repeater != null }
        .map { it.date }

/**
 * The mark-done snackbar text: "Marked done", plus "Next occurrence Jun 5th" for a
 * recurring task. The year is added ("Jan 3rd, 2027") only when [next] falls in a
 * different year than [today].
 */
fun markedDoneMessage(next: LocalDate?, today: LocalDate): String =
    if (next == null) "Marked done" else "Marked done. Next occurrence ${formatOccurrence(next, today)}"

internal fun formatOccurrence(date: LocalDate, today: LocalDate): String {
    val locale = orgLocale()
    // Ordinal suffixes are English-only; other languages get their own date form.
    if (locale.language != Locale.ENGLISH.language) {
        return if (date.year == today.year) LocalDates.format(date, "MMMd", "MMM d")
        else LocalDates.format(date, "yMMMd", "MMM d, yyyy")
    }
    val month = date.month.getDisplayName(TextStyle.SHORT, locale)
    val day = date.dayOfMonth
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    val year = if (date.year != today.year) ", ${date.year}" else ""
    return "$month $day$suffix$year"
}

/** Which planning stamps [advancedRepeatingPlanning] found moved forward. */
data class AdvancedPlanning(val scheduled: Boolean, val deadline: Boolean) {
    val any: Boolean get() = scheduled || deadline

    companion object {
        val NONE = AdvancedPlanning(scheduled = false, deadline = false)
    }
}

/**
 * Which of a heading's repeating SCHEDULED / DEADLINE stamps a state change to
 * [keyword] just advanced, comparing the heading [before] and [after] it. Only a
 * done-type [keyword] counts (that is the only change that advances a
 * repeater), and only a stamp that had a repeater and now differs; CLOSED and
 * dedicated active timestamps are never reported. [AdvancedPlanning.NONE] for a
 * non-recurring task or a non-done keyword.
 */
fun advancedRepeatingPlanning(
    before: OrgHeadline,
    after: OrgHeadline,
    keyword: String?,
    keywords: OrgKeywords,
): AdvancedPlanning {
    if (keyword == null || !keywords.isDone(keyword)) return AdvancedPlanning.NONE
    fun moved(was: OrgTimestamp?, now: OrgTimestamp?) = was?.repeater != null && now != null && now != was
    return AdvancedPlanning(
        scheduled = moved(before.planning.scheduled, after.planning.scheduled),
        deadline = moved(before.planning.deadline, after.planning.deadline),
    )
}
