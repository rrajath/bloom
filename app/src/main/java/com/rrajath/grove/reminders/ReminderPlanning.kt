package com.rrajath.grove.reminders

import com.rrajath.grove.data.ReminderEntity
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.repeats
import com.rrajath.grove.settings.ReminderLeadTime
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure logic (no Android imports, JVM-testable): which reminders a notebook's
 * headlines should have, and when each should fire.
 */
object ReminderPlanning {

    /**
     * The trigger instant for [ts]: its own time-of-day if it has one, else
     * [defaultReminderTime] on its date (Settings › Reminders › "Send reminder
     * at", for date-only stamps). [leadTime] pulls that instant earlier
     * (Settings › Reminders › "Notify me"), but only when [ts] carries its own
     * time-of-day: a date-only stamp fires at [defaultReminderTime] exactly (or
     * just feeds the digest), so shifting its trigger would only misplace which
     * day it lands on for no benefit.
     */
    fun triggerAtMillis(
        ts: OrgTimestamp,
        defaultReminderTime: LocalTime,
        zone: ZoneId = ZoneId.systemDefault(),
        leadTime: ReminderLeadTime = ReminderLeadTime.AT_TIME,
    ): Long {
        val time = ts.time ?: defaultReminderTime
        val base = LocalDateTime.of(ts.date, time).atZone(zone).toInstant().toEpochMilli()
        val offsetMillis = if (ts.time != null) leadTime.offsetMinutes * 60_000L else 0L
        return base - offsetMillis
    }

    /**
     * Every reminder [fileName]'s headlines should have: one per non-done
     * heading's SCHEDULED and/or DEADLINE timestamp, plus one per bare active
     * timestamp in the heading's own body. A ranged active stamp
     * (`<a>--<b>`) gets a single reminder on its start date. Returns an empty
     * list when reminders are disabled, so callers can feed this straight into
     * [ReminderDiff] to cancel everything that previously existed for the file.
     *
     * [notifyUntimed] mirrors Settings › Reminders › "Notify for tasks without a
     * time": when on, a date-only timestamp fires its own notification (at
     * [defaultReminderTime] on its date) instead of only feeding the digest.
     */
    fun desiredReminders(
        fileName: String,
        doc: OrgDocument,
        defaultReminderTime: LocalTime,
        remindersEnabled: Boolean,
        leadTime: ReminderLeadTime = ReminderLeadTime.AT_TIME,
        notifyUntimed: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ReminderEntity> {
        if (!remindersEnabled) return emptyList()
        val result = mutableListOf<ReminderEntity>()
        doc.headlines.forEach { h ->
            if (h.keyword != null && doc.keywords.isDone(h.keyword)) return@forEach
            val path = ReminderKeys.headingPath(doc, h)
            val isTask = h.keyword != null
            h.planning.scheduled?.let { ts ->
                result.add(
                    entity(
                        fileName, path, h.title, h.level, PlanningType.SCHEDULED, ts,
                        defaultReminderTime, leadTime, notifyUntimed, zone,
                        isTask = isTask, hasRepeater = ts.repeater != null,
                    )
                )
            }
            h.planning.deadline?.let { ts ->
                result.add(
                    entity(
                        fileName, path, h.title, h.level, PlanningType.DEADLINE, ts,
                        defaultReminderTime, leadTime, notifyUntimed, zone,
                        isTask = isTask, hasRepeater = ts.repeater != null,
                    )
                )
            }
            h.activeTimestamps.forEach { ts ->
                // A real repeater earns the Complete action, wherever the stamp
                // sits: com.rrajath.grove.org.OrgMutations.advanceActiveTimestamp
                // advances one on the dedicated line or typed inline in the body.
                val repeats = ts.repeats
                result.add(
                    entity(
                        fileName, path, h.title, h.level, PlanningType.ACTIVE, ts,
                        defaultReminderTime, leadTime, notifyUntimed, zone,
                        discriminator = ts.date.toString(),
                        isTask = isTask,
                        hasRepeater = repeats,
                        activeTimestampDate = ts.date.toString(),
                    )
                )
            }
        }
        return result
    }

    private fun entity(
        fileName: String,
        headingPath: String,
        headingTitle: String,
        level: Int,
        type: PlanningType,
        ts: OrgTimestamp,
        defaultReminderTime: LocalTime,
        leadTime: ReminderLeadTime,
        notifyUntimed: Boolean,
        zone: ZoneId,
        discriminator: String? = null,
        isTask: Boolean = false,
        hasRepeater: Boolean = false,
        activeTimestampDate: String? = null,
    ): ReminderEntity {
        val key = ReminderKeys.reminderKey(fileName, headingPath, level, type, discriminator)
        val hasOwnTime = ts.time != null
        return ReminderEntity(
            key = key,
            fileName = fileName,
            headingPath = headingPath,
            headingTitle = headingTitle,
            headingLevel = level,
            planningType = type.storageKey,
            triggerAtMillis = triggerAtMillis(ts, defaultReminderTime, zone, leadTime),
            notificationId = ReminderKeys.notificationId(key),
            firesOwnNotification = hasOwnTime || notifyUntimed,
            leadTime = (if (hasOwnTime) leadTime else ReminderLeadTime.AT_TIME).storageKey,
            isTask = isTask,
            hasRepeater = hasRepeater,
            activeTimestampDate = activeTimestampDate,
        )
    }
}
