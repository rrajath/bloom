package com.rrajath.grove.reminders

import com.rrajath.grove.data.ReminderEntity
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.settings.ReminderLeadTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Report phase 8 (G15): a repeating stamp reminds on its next occurrence, not
 * only on the date written in the file.
 */
class ReminderRepeatPlanningTest {

    private val zone = ZoneId.of("UTC")
    private val nineAm = LocalTime.of(9, 0)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // Wed Nov 4 2026, 10:00.
    private val now = millis(2026, 11, 4, 10)

    private fun next(stamp: String, after: Long = now, lead: ReminderLeadTime = ReminderLeadTime.AT_TIME) =
        ReminderPlanning.nextTriggerMillis(OrgTimestamp.parse(stamp)!!, nineAm, after, zone, lead)

    @Test
    fun `the issue 97 meeting reminds on today's occurrence while it is still ahead`() {
        assertEquals(millis(2026, 11, 4, 12), next("<2026-10-28 Wed 12:00-14:00 +1w>"))
    }

    @Test
    fun `once today's occurrence has passed the next one is armed`() {
        assertEquals(millis(2026, 11, 11, 12), next("<2026-10-28 Wed 12:00 +1w>", after = millis(2026, 11, 4, 12)))
    }

    @Test
    fun `a date-only repeat fires at the default time on its next occurrence`() {
        // 09:00 today has passed at 10:00, so tomorrow's daily occurrence.
        assertEquals(millis(2026, 11, 5, 9), next("<2026-11-01 Sun +1d>"))
    }

    @Test
    fun `month repeats overflow like the agenda`() {
        assertEquals(millis(2026, 12, 1, 9), next("<2026-08-31 Mon +1m>"))
    }

    @Test
    fun `lead time can pick tomorrow's occurrence today`() {
        // Tomorrow 09:00 with a one-day lead fires today at 09:00, already past;
        // the day after's occurrence fires tomorrow at 09:00.
        assertEquals(
            millis(2026, 11, 5, 9),
            next("<2026-11-01 Sun 09:00 +1d>", lead = ReminderLeadTime.DAY_1),
        )
    }

    @Test
    fun `hour repeats step from the stored start`() {
        assertEquals(millis(2026, 11, 4, 11), next("<2026-11-04 Wed 09:00 +2h>"))
    }

    @Test
    fun `a stamp still in the future and a one-off past stamp keep their stored trigger`() {
        assertEquals(millis(2026, 11, 20, 9), next("<2026-11-20 Fri +1w>"))
        assertEquals(millis(2026, 10, 1, 9), next("<2026-10-01 Thu>"))
    }

    @Test
    fun `desiredReminders arms repeating stamps for the next occurrence and records the stamp`() {
        val doc = OrgParser.parse(
            """
            * TODO Weekly review
            SCHEDULED: <2026-10-21 Wed 16:00 +1w>
            * Standup
            <2026-11-02 Mon 09:30 +1d>
            * TODO One-off
            DEADLINE: <2026-11-20 Fri 10:00>
            """.trimIndent(),
        )
        val result = ReminderPlanning.desiredReminders(
            "a.org", doc, nineAm, remindersEnabled = true, zone = zone, nowMillis = now,
        ).associateBy { it.headingTitle }

        assertEquals(millis(2026, 11, 4, 16), result.getValue("Weekly review").triggerAtMillis)
        assertEquals("<2026-10-21 Wed 16:00 +1w>", result.getValue("Weekly review").repeatStamp)
        assertEquals(millis(2026, 11, 5, 9, 30), result.getValue("Standup").triggerAtMillis)
        // The ACTIVE row keeps its stored date, which Complete uses to find the stamp.
        assertEquals("2026-11-02", result.getValue("Standup").activeTimestampDate)
        assertNull(result.getValue("One-off").repeatStamp)
        assertEquals(millis(2026, 11, 20, 10), result.getValue("One-off").triggerAtMillis)
    }

    // --- digest ---

    private fun reminder(
        key: String,
        type: PlanningType,
        trigger: Long,
        repeatStamp: String?,
    ) = ReminderEntity(
        key = key, fileName = "a.org", headingPath = key, headingTitle = key, headingLevel = 1,
        planningType = type.storageKey, triggerAtMillis = trigger,
        notificationId = ReminderKeys.notificationId(key), repeatStamp = repeatStamp,
    )

    @Test
    fun `the digest counts a rolled-forward overdue task by its stored date`() {
        val today = LocalDate.of(2026, 11, 4)
        val overdue = reminder(
            "weekly", PlanningType.SCHEDULED, millis(2026, 11, 11, 16), "<2026-10-21 Wed 16:00 +1w>",
        )
        assertEquals(1, ReminderDigest.count(listOf(overdue), today, zone))
    }

    @Test
    fun `the digest counts a repeating event on each day it recurs`() {
        val event = reminder("standup", PlanningType.ACTIVE, millis(2026, 11, 5, 9, 30), "<2026-11-02 Mon 09:30 +1d>")
        assertEquals(1, ReminderDigest.count(listOf(event), LocalDate.of(2026, 11, 4), zone))
        val weekly = reminder("weekly", PlanningType.ACTIVE, millis(2026, 11, 11, 12), "<2026-10-28 Wed 12:00 +1w>")
        assertEquals(0, ReminderDigest.count(listOf(weekly), LocalDate.of(2026, 11, 5), zone))
    }
}
