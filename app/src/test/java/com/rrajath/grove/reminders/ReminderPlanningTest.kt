package com.rrajath.grove.reminders

import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.settings.ReminderLeadTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderPlanningTest {

    private val zone = ZoneId.of("UTC")
    private val nineAm = LocalTime.of(9, 0)

    @Test
    fun `timestamp with its own time uses that time, ignoring the default`() {
        val ts = OrgTimestamp(LocalDate.of(2026, 7, 24), time = LocalTime.of(14, 30))
        val expected = LocalDateTime.of(2026, 7, 24, 14, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, ReminderPlanning.triggerAtMillis(ts, nineAm, zone))
    }

    @Test
    fun `date-only timestamp falls back to the default reminder time`() {
        val ts = OrgTimestamp(LocalDate.of(2026, 7, 24))
        val expected = LocalDateTime.of(2026, 7, 24, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, ReminderPlanning.triggerAtMillis(ts, nineAm, zone))
    }

    @Test
    fun `lead time pulls an explicit-time timestamp's trigger earlier`() {
        val ts = OrgTimestamp(LocalDate.of(2026, 7, 24), time = LocalTime.of(14, 30))
        val expected = LocalDateTime.of(2026, 7, 24, 14, 15).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, ReminderPlanning.triggerAtMillis(ts, nineAm, zone, ReminderLeadTime.MIN_15))
    }

    @Test
    fun `lead time is ignored for date-only timestamps`() {
        val ts = OrgTimestamp(LocalDate.of(2026, 7, 24))
        val expected = LocalDateTime.of(2026, 7, 24, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, ReminderPlanning.triggerAtMillis(ts, nineAm, zone, ReminderLeadTime.DAY_1))
    }

    @Test
    fun `desiredReminders bakes the current lead time into explicit-time entries only`() {
        val doc = OrgParser.parse(
            "* TODO A\nSCHEDULED: <2026-07-24 Fri 14:30>\n" +
                "* TODO B\nSCHEDULED: <2026-07-24 Fri>\n"
        )
        val result = ReminderPlanning.desiredReminders(
            "a.org", doc, nineAm, remindersEnabled = true, leadTime = ReminderLeadTime.HOUR_1, zone = zone,
        )
        assertEquals(ReminderLeadTime.HOUR_1.storageKey, result.single { it.headingTitle == "A" }.leadTime)
        assertEquals(ReminderLeadTime.AT_TIME.storageKey, result.single { it.headingTitle == "B" }.leadTime)
    }

    @Test
    fun `a heading with both SCHEDULED and DEADLINE gets two independent reminders`() {
        val doc = OrgParser.parse("* TODO A\nSCHEDULED: <2026-07-24 Fri> DEADLINE: <2026-07-25 Sat>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(2, result.size)
        assertTrue(result.any { it.planningType == PlanningType.SCHEDULED.storageKey })
        assertTrue(result.any { it.planningType == PlanningType.DEADLINE.storageKey })
        assertTrue(result.all { it.headingTitle == "A" && it.fileName == "a.org" })
    }

    @Test
    fun `done headings are skipped`() {
        val doc = OrgParser.parse("* DONE A\nSCHEDULED: <2026-07-24 Fri>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `headings with no planning produce no reminders`() {
        val doc = OrgParser.parse("* TODO A\nJust a body line.\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `disabled reminders yields nothing regardless of planning`() {
        val doc = OrgParser.parse("* TODO A\nSCHEDULED: <2026-07-24 Fri>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = false, zone = zone)
        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `custom done keywords are honored`() {
        val keywords = OrgKeywords.parse("TODO NEXT | DONE CANCELLED")
        val doc = OrgParser.parse("* CANCELLED A\nSCHEDULED: <2026-07-24 Fri>\n", keywords)
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `firesOwnNotification is true only for a timestamp that carries a time-of-day`() {
        val doc = OrgParser.parse(
            "* TODO A\nSCHEDULED: <2026-07-24 Fri>\n" +
                "* TODO B\nSCHEDULED: <2026-07-24 Fri 14:30>\n"
        )
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(false, result.single { it.headingTitle == "A" }.firesOwnNotification)
        assertEquals(true, result.single { it.headingTitle == "B" }.firesOwnNotification)
    }

    @Test
    fun `notifyUntimed makes a date-only timestamp fire its own notification at the default time`() {
        val doc = OrgParser.parse(
            "* TODO A\nSCHEDULED: <2026-07-24 Fri>\n" +
                "* Holiday\n<2026-07-24 Fri>\n"
        )
        val result = ReminderPlanning.desiredReminders(
            "a.org", doc, nineAm, remindersEnabled = true, notifyUntimed = true, zone = zone,
        )
        assertTrue(result.all { it.firesOwnNotification })
        // Still triggered at the default reminder time, no lead-time shift.
        val expected = LocalDateTime.of(2026, 7, 24, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(result.all { it.triggerAtMillis == expected })
        // A date-only row never bakes in a lead time even when it now notifies.
        assertTrue(result.all { it.leadTime == ReminderLeadTime.AT_TIME.storageKey })
    }

    @Test
    fun `a bare active timestamp produces an ACTIVE reminder`() {
        val doc = OrgParser.parse("* Standup\n<2026-07-24 Fri 09:30>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        val row = result.single()
        assertEquals(PlanningType.ACTIVE.storageKey, row.planningType)
        assertEquals(true, row.firesOwnNotification)
        assertEquals(
            LocalDateTime.of(2026, 7, 24, 9, 30).atZone(zone).toInstant().toEpochMilli(),
            row.triggerAtMillis,
        )
    }

    @Test
    fun `a date-only active timestamp is digest-only unless notifyUntimed is on`() {
        val doc = OrgParser.parse("* Holiday\n<2026-07-24 Fri>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(false, result.single().firesOwnNotification)
    }

    @Test
    fun `two active timestamps on one heading yield two rows with distinct keys`() {
        val doc = OrgParser.parse("* Trip\n<2026-07-24 Fri> <2026-07-26 Sun>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(2, result.size)
        assertEquals(2, result.map { it.key }.toSet().size)
        assertTrue(result.all { it.planningType == PlanningType.ACTIVE.storageKey })
    }

    @Test
    fun `a ranged active timestamp gets a single reminder on its start date`() {
        val doc = OrgParser.parse("* Conference\n<2026-07-24 Fri>--<2026-07-27 Mon>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(
            LocalDate.of(2026, 7, 24),
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(result.single().triggerAtMillis), zone,
            ).toLocalDate(),
        )
    }

    @Test
    fun `a done heading with an active timestamp gets no reminder`() {
        val doc = OrgParser.parse("* DONE Party\n<2026-07-24 Fri 18:00>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `a repeating bare active timestamp is flagged hasRepeater with its date`() {
        val doc = OrgParser.parse("* Water plants\n<2026-07-24 Fri +1w>\n")
        val row = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone).single()
        assertEquals(true, row.hasRepeater)
        assertEquals("2026-07-24", row.activeTimestampDate)
    }

    @Test
    fun `a non-repeating bare active timestamp is not flagged hasRepeater`() {
        val doc = OrgParser.parse("* Holiday\n<2026-07-24 Fri>\n")
        val row = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone).single()
        assertEquals(false, row.hasRepeater)
    }

    @Test
    fun `a repeater typed inline in prose is flagged hasRepeater too`() {
        // advanceActiveTimestamp can rewrite an inline stamp in place, so Complete works for it.
        val doc = OrgParser.parse("* Standup\nSee you at <2026-07-24 Fri +1w>.\n")
        val row = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone).single()
        assertEquals(true, row.hasRepeater)
    }

    @Test
    fun `a zero-interval repeater is not flagged hasRepeater`() {
        val doc = OrgParser.parse("* Holiday\n<2026-07-24 Fri +0d>\n")
        val row = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone).single()
        assertEquals(false, row.hasRepeater)
    }

    @Test
    fun `SCHEDULED and DEADLINE rows never set activeTimestampDate`() {
        val doc = OrgParser.parse("* TODO A\nSCHEDULED: <2026-07-24 Fri +1w>\n")
        val row = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone).single()
        assertEquals(null, row.activeTimestampDate)
    }

    @Test
    fun `isTask is true for a heading with a live todo keyword, regardless of planning type`() {
        val doc = OrgParser.parse(
            "* TODO A\nSCHEDULED: <2026-07-24 Fri> DEADLINE: <2026-07-25 Sat>\n" +
                "* TODO B\n<2026-07-24 Fri>\n"
        )
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertTrue(result.all { it.isTask })
    }

    @Test
    fun `isTask is false for a heading with no todo keyword, regardless of planning type`() {
        val doc = OrgParser.parse(
            "* A\nSCHEDULED: <2026-07-24 Fri> DEADLINE: <2026-07-25 Sat>\n" +
                "* B\n<2026-07-24 Fri>\n"
        )
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertTrue(result.none { it.isTask })
    }

    @Test
    fun `a repeating SCHEDULED or DEADLINE is flagged hasRepeater even with no todo keyword`() {
        val doc = OrgParser.parse(
            "* A\nSCHEDULED: <2026-07-24 Fri +1w>\n" +
                "* B\nDEADLINE: <2026-07-24 Fri +1w>\n"
        )
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertTrue(result.all { it.hasRepeater })
        assertTrue(result.none { it.isTask })
    }

    @Test
    fun `a non-repeating SCHEDULED or DEADLINE is not flagged hasRepeater`() {
        val doc = OrgParser.parse("* A\nSCHEDULED: <2026-07-24 Fri>\n")
        val row = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone).single()
        assertEquals(false, row.hasRepeater)
    }

    @Test
    fun `nested headings key by their ancestor path`() {
        val doc = OrgParser.parse("* Project\n** TODO Sub task\nSCHEDULED: <2026-07-24 Fri>\n")
        val result = ReminderPlanning.desiredReminders("a.org", doc, nineAm, remindersEnabled = true, zone = zone)
        assertEquals(1, result.size)
        assertEquals(
            ReminderKeys.reminderKey("a.org", "Project/Sub task", 2, PlanningType.SCHEDULED),
            result.single().key,
        )
    }
}
