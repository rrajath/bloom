package com.rrajath.grove.reminders

import com.rrajath.grove.data.ReminderEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderDigestTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 7, 31)

    private fun entity(
        key: String,
        date: LocalDate,
        type: PlanningType,
        time: java.time.LocalTime = java.time.LocalTime.of(9, 0),
        firesOwnNotification: Boolean = false,
        headingPath: String = key,
    ) = ReminderEntity(
        key = key,
        fileName = "a.org",
        headingPath = headingPath,
        headingTitle = headingPath,
        headingLevel = 1,
        planningType = type.storageKey,
        triggerAtMillis = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli(),
        notificationId = ReminderKeys.notificationId(key),
        firesOwnNotification = firesOwnNotification,
    )

    @Test
    fun `counts overdue plus scheduled-today plus deadline-today`() {
        val reminders = listOf(
            entity("overdue1", today.minusDays(2), PlanningType.SCHEDULED),
            entity("overdue2", today.minusDays(1), PlanningType.DEADLINE),
            entity("sched-today", today, PlanningType.SCHEDULED),
            entity("deadline-today", today, PlanningType.DEADLINE),
            entity("future", today.plusDays(3), PlanningType.SCHEDULED),
        )
        assertEquals(4, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `distinct headings scheduled and due today each count once`() {
        val reminders = listOf(
            entity("sched-today", today, PlanningType.SCHEDULED),
            entity("deadline-today", today, PlanningType.DEADLINE),
        )
        assertEquals(2, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `a single heading with both SCHEDULED and DEADLINE due today counts once, matching Agenda`() {
        val reminders = listOf(
            entity("task-sched", today, PlanningType.SCHEDULED, headingPath = "task"),
            entity("task-deadline", today, PlanningType.DEADLINE, headingPath = "task"),
        )
        assertEquals(1, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `a single heading overdue on both SCHEDULED and DEADLINE counts once`() {
        val reminders = listOf(
            entity("task-sched", today.minusDays(3), PlanningType.SCHEDULED, headingPath = "task"),
            entity("task-deadline", today.minusDays(1), PlanningType.DEADLINE, headingPath = "task"),
        )
        assertEquals(1, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `a missed deadline counts even when its heading is scheduled later, matching Agenda's overdue card`() {
        val reminders = listOf(
            entity("task-sched", today.plusDays(2), PlanningType.SCHEDULED, headingPath = "task"),
            entity("task-deadline", today.minusDays(1), PlanningType.DEADLINE, headingPath = "task"),
        )
        assertEquals(1, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `an active event today is counted`() {
        val reminders = listOf(entity("evt", today, PlanningType.ACTIVE))
        assertEquals(1, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `a past active event is not counted (events are never overdue)`() {
        val reminders = listOf(entity("evt", today.minusDays(1), PlanningType.ACTIVE))
        assertEquals(0, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `a future active event is not counted`() {
        val reminders = listOf(entity("evt", today.plusDays(1), PlanningType.ACTIVE))
        assertEquals(0, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `an event and a task on the same heading today each count`() {
        val reminders = listOf(
            entity("h-sched", today, PlanningType.SCHEDULED, headingPath = "h"),
            entity("h-evt", today, PlanningType.ACTIVE, headingPath = "h"),
        )
        assertEquals(2, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `empty table counts zero`() {
        assertEquals(0, ReminderDigest.count(emptyList(), today, zone))
    }

    @Test
    fun `future-only reminders count zero`() {
        val reminders = listOf(entity("future", today.plusDays(1), PlanningType.SCHEDULED))
        assertEquals(0, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `reminders with an explicit time are included too, matching Agenda`() {
        val reminders = listOf(
            entity("timed-overdue", today.minusDays(1), PlanningType.SCHEDULED, firesOwnNotification = true),
            entity("timed-today", today, PlanningType.DEADLINE, firesOwnNotification = true),
            entity("date-only-today", today, PlanningType.SCHEDULED),
        )
        assertEquals(3, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `a heading with a future timed SCHEDULED and a date-only DEADLINE due today is not counted`() {
        // Regression test: the digest must anchor on the heading's SCHEDULED
        // date (per AgendaBuckets.whenDate) rather than falling back to the
        // date-only DEADLINE as a substitute anchor. Agenda would bucket this
        // heading under its future SCHEDULED date, never under "today" or
        // "overdue", so the digest must agree and count it zero even though
        // the heading also has a DEADLINE row due today.
        val reminders = listOf(
            entity(
                "task-sched", today.plusDays(3), PlanningType.SCHEDULED,
                firesOwnNotification = true, headingPath = "task",
            ),
            entity(
                "task-deadline", today, PlanningType.DEADLINE,
                headingPath = "task",
            ),
        )
        assertEquals(0, ReminderDigest.count(reminders, today, zone))
    }

    @Test
    fun `a heading with an overdue timed SCHEDULED and a date-only DEADLINE due today counts once, via the SCHEDULED anchor`() {
        // Same anchor-selection guard, but with the SCHEDULED date itself
        // already in the past: it still wins as the anchor (matching Agenda),
        // so this counts once via that anchor - not zero, and not twice via
        // the heading's DEADLINE row.
        val reminders = listOf(
            entity(
                "task-sched", today.minusDays(1), PlanningType.SCHEDULED,
                firesOwnNotification = true, headingPath = "task",
            ),
            entity(
                "task-deadline", today, PlanningType.DEADLINE,
                headingPath = "task",
            ),
        )
        assertEquals(1, ReminderDigest.count(reminders, today, zone))
    }
}
