package com.rrajath.grove.reminders

import com.rrajath.grove.data.ReminderEntity
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.occurrenceCovering
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure logic (no Android imports, JVM-testable) for the daily reminder digest
 * ("You have X tasks due today"), fired at the default reminder time.
 *
 * X must agree with what the Agenda screen shows for today (overdue + due
 * today), so this mirrors its rule: a heading belongs to exactly one day, its
 * SCHEDULED date if it has one, otherwise its DEADLINE. A heading carrying
 * both a SCHEDULED and a DEADLINE reminder is therefore collapsed to one task
 * here too, even though the reminders table tracks them as two distinct rows.
 * Bare active timestamps are the exception: they are events, counted per stamp
 * on their own date and never collapsed into a heading's task anchor.
 */
object ReminderDigest {

    fun count(reminders: List<ReminderEntity>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int {
        fun triggerDate(r: ReminderEntity): LocalDate =
            Instant.ofEpochMilli(r.triggerAtMillis).atZone(zone).toLocalDate()

        // A repeating reminder's trigger has rolled on to its next occurrence,
        // but the Agenda still places the heading by the date written in the
        // file (a weekly task left undone stays overdue), so count by that.
        fun repeatStamp(r: ReminderEntity): OrgTimestamp? = r.repeatStamp?.let { OrgTimestamp.parse(it) }

        fun dateOf(r: ReminderEntity): LocalDate = repeatStamp(r)?.date ?: triggerDate(r)

        val (active, planning) = reminders.partition { it.planningType == PlanningType.ACTIVE.storageKey }

        // One SCHEDULED and one DEADLINE row can both belong to the same heading;
        // collapse them to that heading's single anchor so this picks the same
        // anchor AgendaBuckets.whenDate would: a heading whose SCHEDULED carries
        // a time-of-day still anchors there, and a DEADLINE must not stand in for
        // it, or the heading gets counted here on a date it would never appear
        // under in the Agenda.
        val anchors = planning
            .groupBy { Triple(it.fileName, it.headingPath, it.headingLevel) }
            .mapNotNull { (_, entries) ->
                val scheduled = entries.firstOrNull { it.planningType == PlanningType.SCHEDULED.storageKey }
                val deadline = entries.firstOrNull { it.planningType == PlanningType.DEADLINE.storageKey }
                scheduled ?: deadline
            }

        // Every overdue anchor plus every anchor due today, regardless of whether
        // it fires its own notification (see [ReminderEntity.firesOwnNotification]).
        // An anchor due today that also pings individually is counted here *and*
        // fires its own notification later (ReminderAlarmReceiver) - that's
        // intentional, not a double-count bug, so this total always matches what
        // the Agenda screen shows for today.
        val planningCount = anchors.count { !dateOf(it).isAfter(today) }

        // Active timestamps are events on their exact date, never overdue
        // (matching Agenda), so only ones landing on today count. Each is its
        // own anchor - keyed distinctly by its date discriminator - never
        // collapsed into a heading's scheduled/deadline grouping, so a heading
        // that has both an event and a task today contributes to both totals.
        // A repeating event counts on every day one of its occurrences covers.
        val activeCount = active.count { r ->
            repeatStamp(r)?.let { it.occurrenceCovering(today) != null } ?: triggerDate(r).isEqual(today)
        }

        return planningCount + activeCount
    }
}
