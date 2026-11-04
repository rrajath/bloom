package com.rrajath.grove.ui.agenda

import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.org.cookieDays
import com.rrajath.grove.org.cookieFirstOnly
import com.rrajath.grove.org.occurrenceCovering
import com.rrajath.grove.org.repeats
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.settings.AgendaGrouping
import com.rrajath.grove.settings.AgendaStateFilter
import com.rrajath.grove.org.LocalDates
import com.rrajath.grove.org.orgLocale
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Pure bucketing for the Agenda screen: which day a heading lands on, which
 * headings a "Show" filter admits, and how the visible set splits into
 * sections. Deliberately free of Android, coroutines, and `AgendaViewModel` so
 * the rules are unit-testable on the JVM: the ViewModel only wires settings
 * and the index flow into these functions and maps the results to rows.
 *
 * The central rule for *planned* rows: **a heading belongs to exactly one
 * day**, its SCHEDULED date if it has one, otherwise its DEADLINE. That is
 * what makes the "Group by · Date" sections disjoint. A heading carrying both
 * dates appears once, on its scheduled day, with the deadline surfaced as a
 * `⚑` chip.
 *
 * Bare active timestamps (`<2026-09-08 Mon>` in the body, no keyword) are the
 * exception: they are *events*, not plans. One heading with N active dates is
 * N separate agenda entries, each shown on its own day (and on every repeat of
 * a repeating stamp) via [activeEventsOn], and an event never ages into
 * [overdue] the way a past SCHEDULED does.
 *
 * Two more ways a heading reaches a day other than its stored one, both from
 * the Emacs agenda: a repeating SCHEDULED or DEADLINE previews each later
 * repeat in Upcoming ([repeatsOn]), and a DEADLINE inside its prewarning
 * window shows under Today's "Due soon" ([dueSoon]). Neither breaks "one row
 * per heading per day": a preview never lands on a day the heading is already
 * planned for. A SCHEDULED delay cookie (`-2d`) hides the plan until N days
 * after its date ([delayed]).
 */
internal object AgendaBuckets {

    /** One "Group by" section, still in [NoteMeta] terms. */
    data class Bucket(val key: String, val notes: List<NoteMeta>)

    /** The day a heading lands on: its SCHEDULED date, else its DEADLINE. */
    fun whenDate(m: NoteMeta): LocalDate? = m.scheduledDate ?: m.deadlineDate

    /**
     * The "Show" levers. [AgendaStateFilter.Open] is state-agnostic (anything
     * not done-type, headings with no keyword included) while
     * [AgendaStateFilter.Keyword] pins one of the vault's own active keywords.
     */
    fun keep(m: NoteMeta, filter: AgendaStateFilter): Boolean = when (filter) {
        AgendaStateFilter.All -> true
        AgendaStateFilter.Open -> !m.isDoneKeyword
        is AgendaStateFilter.Keyword -> m.keyword == filter.name
    }

    /**
     * Whether a SCHEDULED delay cookie still holds this heading's plan back on
     * [today]: `SCHEDULED: <2026-11-03 Tue -2d>` stays off the agenda until
     * Nov 5, then shows as overdue (Emacs). Callers drop delayed headings from
     * their planned rows; the heading's events and deadline prewarning stay.
     */
    fun delayed(m: NoteMeta, today: LocalDate): Boolean {
        val s = m.scheduledTs ?: return false
        val days = s.cookieDays ?: return false
        return days > 0 && today.isBefore(s.date.plusDays(days.toLong()))
    }

    /** A future repeat of a heading's SCHEDULED or DEADLINE, placed on one day. */
    data class Repeat(val meta: NoteMeta, val kind: PlanningKind, val ts: OrgTimestamp)

    /**
     * The headings whose repeating SCHEDULED or DEADLINE recurs on [day], for
     * days after [today]: Emacs previews every later repeat of an open entry
     * while the stored date stays the one you act on (marking a preview done
     * runs on the heading). Only repeats after the stored date count; the stored
     * date itself is the heading's planned row. One entry per heading, the
     * SCHEDULED repeat winning when both land on [day], and none on the day the
     * heading is already planned. Done headings never preview, and a
     * single-dash SCHEDULED delay (`-2d`) holds back every repeat.
     */
    fun repeatsOn(notes: List<NoteMeta>, day: LocalDate, today: LocalDate): List<Repeat> {
        if (!day.isAfter(today)) return emptyList()
        return notes.mapNotNull { m ->
            if (m.isDoneKeyword || whenDate(m) == day) return@mapNotNull null
            val scheduled = m.scheduledTs
                ?.takeUnless { (it.cookieDays ?: 0) > 0 && !it.cookieFirstOnly }
                ?.let { repeatOn(it, day) }
            when {
                scheduled != null -> Repeat(m, PlanningKind.SCHEDULED, scheduled)
                else -> m.deadlineTs?.let { repeatOn(it, day) }?.let { Repeat(m, PlanningKind.DEADLINE, it) }
            }
        }
    }

    /** [ts] moved onto [day] when one of its repeats after its stored date starts there. */
    private fun repeatOn(ts: OrgTimestamp, day: LocalDate): OrgTimestamp? {
        if (!ts.repeats || !day.isAfter(ts.date)) return null
        return if (ts.occurrenceCovering(day) == day) ts.copy(date = day, rangeEnd = null) else null
    }

    /**
     * Open headings whose DEADLINE falls after [today] but inside its
     * prewarning window: the deadline's own `-Nd` cookie, else
     * [defaultWarningDays] (Settings › Agenda, Emacs's
     * `org-deadline-warning-days`). Soonest first, then priority, then title.
     */
    fun dueSoon(notes: List<NoteMeta>, today: LocalDate, defaultWarningDays: Int): List<NoteMeta> =
        notes.filter { m ->
            val dl = m.deadlineTs ?: return@filter false
            if (m.isDoneKeyword || !dl.date.isAfter(today)) return@filter false
            val window = dl.cookieDays ?: defaultWarningDays
            ChronoUnit.DAYS.between(today, dl.date) <= window
        }.sortedWith(
            compareBy<NoteMeta> { it.deadlineDate }
                .thenBy { it.priority ?: NO_PRIORITY_SORT_KEY }
                .thenBy { it.title.lowercase() }
        )

    /** Past-dated headings, oldest first, then priority, then title. */
    fun overdue(notes: List<NoteMeta>, today: LocalDate): List<NoteMeta> =
        notes.filter { whenDate(it)?.isBefore(today) == true }
            .sortedWith(
                compareBy<NoteMeta> { whenDate(it) ?: LocalDate.MAX }
                    .thenBy { it.priority ?: NO_PRIORITY_SORT_KEY }
                    .thenBy { it.title.lowercase() }
            )

    fun onDay(notes: List<NoteMeta>, day: LocalDate): List<NoteMeta> =
        notes.filter { whenDate(it) == day }

    /**
     * The bare active timestamps with an occurrence covering [day], paired with
     * their heading — one pair per covering stamp, so a heading with two active
     * dates on [day] yields two entries. These are the agenda's *events*: they
     * show on every day they span, on every later repeat of a repeating stamp
     * (`<… +1w>` recurs weekly from its date, as the Emacs agenda shows it; see
     * [occurrenceCovering]), and never contribute to [overdue].
     */
    fun activeEventsOn(notes: List<NoteMeta>, day: LocalDate): List<Pair<NoteMeta, OrgTimestamp>> =
        notes.flatMap { m ->
            m.activeTimestamps
                .filter { ts -> ts.occurrenceCovering(day) != null }
                .map { ts -> m to ts }
        }

    /** Everything after today, out to a [windowDays]-wide horizon starting today. */
    fun upcoming(notes: List<NoteMeta>, today: LocalDate, windowDays: Int): List<NoteMeta> {
        val horizon = today.plusDays((windowDays - 1).coerceAtLeast(0).toLong())
        return notes.filter {
            val d = whenDate(it)
            d != null && d.isAfter(today) && !d.isAfter(horizon)
        }
    }

    /**
     * Splits [list] into sections per [grouping]. [isTodayTab] collapses date
     * grouping into a single "Scheduled today" bucket, since every row already
     * shares one date.
     */
    fun group(
        list: List<NoteMeta>,
        today: LocalDate,
        grouping: AgendaGrouping,
        isTodayTab: Boolean,
    ): List<Bucket> = when (grouping) {
        AgendaGrouping.DATE ->
            if (isTodayTab) {
                if (list.isEmpty()) emptyList()
                else listOf(Bucket("Scheduled today", list.sortedWith(BY_TIME)))
            } else {
                list.mapNotNull { whenDate(it) }.distinct().sorted().map { day ->
                    Bucket(dayLabel(day, today), onDay(list, day).sortedWith(BY_TIME))
                }
            }

        AgendaGrouping.PRIORITY ->
            PRIORITY_BUCKETS.mapNotNull { (key, label) ->
                val notes = list.filter { it.priority == key }
                if (notes.isEmpty()) null else Bucket(label, notes.sortedWith(BY_TIME))
            }

        AgendaGrouping.TAG -> {
            val keys = LinkedHashSet<String>()
            list.forEach { keys += tagsOf(it) }
            keys.map { tag -> Bucket(":$tag:", list.filter { tag in tagsOf(it) }.sortedWith(BY_PRIORITY)) }
        }

        AgendaGrouping.FILE ->
            list.map { it.fileName }.distinct().map { file ->
                Bucket(file, list.filter { it.fileName == file }.sortedWith(BY_PRIORITY))
            }
    }

    /** Tags a heading can be grouped under; an untagged one gets its own bucket. */
    fun tagsOf(m: NoteMeta): List<String> = m.tags.ifEmpty { listOf(UNTAGGED) }

    /** "Today" / "Tomorrow" / "Yesterday" / "Friday" / "Friday, Aug 14" past a week out. */
    fun dayLabel(date: LocalDate, today: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today, date)
        if (n == 0L) return "Today"
        if (n == 1L) return "Tomorrow"
        if (n == -1L) return "Yesterday"
        return if (abs(n) > 6) LocalDates.format(date, "EEEEMMMd", "EEEE, MMM d")
        else date.dayOfWeek.getDisplayName(TextStyle.FULL, orgLocale())
    }

    /** Time on whichever timestamp [whenDate] chose; untimed rows sort last. */
    private fun timeOf(m: NoteMeta): LocalTime =
        (if (m.scheduledDate != null) m.scheduledTime else m.deadlineTime) ?: LocalTime.MAX

    val BY_TIME: Comparator<NoteMeta> =
        compareBy<NoteMeta> { timeOf(it) }.thenBy { it.title.lowercase() }

    val BY_PRIORITY: Comparator<NoteMeta> =
        compareBy<NoteMeta> { it.priority ?: NO_PRIORITY_SORT_KEY }
            .thenBy { timeOf(it) }
            .thenBy { it.title.lowercase() }

    val SHORT_DATE: DateTimeFormatter get() = LocalDates.formatter("MMMd", "MMM d")

    /** Sorts after "A"/"B"/"C", so unprioritised rows land at the bottom of a bucket. */
    private const val NO_PRIORITY_SORT_KEY = "Z"

    private const val UNTAGGED = "untagged"

    private val PRIORITY_BUCKETS = listOf(
        "A" to "Priority A",
        "B" to "Priority B",
        "C" to "Priority C",
        null to "No priority",
    )
}
