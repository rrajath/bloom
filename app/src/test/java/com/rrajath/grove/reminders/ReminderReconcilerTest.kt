package com.rrajath.grove.reminders

import com.rrajath.grove.data.ReminderDao
import com.rrajath.grove.data.ReminderEntity
import com.rrajath.grove.org.OrgParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * JVM regression tests for the scheduling logic in [ReminderReconciler.arm], with
 * the Android side-effects (notification/alarm) injected as recording lambdas and
 * an in-memory fake [ReminderDao]. Covers:
 *  - finding 2: boot re-arm must not re-fire an already-fired reminder;
 *  - finding 4: the overdue branch must always cancel any stale alarm first.
 */
class ReminderReconcilerTest {

    private val now = 10_000L

    private class FakeReminderDao : ReminderDao {
        val rows = LinkedHashMap<String, ReminderEntity>()
        override suspend fun forFile(fileName: String) = rows.values.filter { it.fileName == fileName }
        override suspend fun all() = rows.values.toList()
        override suspend fun get(key: String) = rows[key]
        override suspend fun pending() = rows.values.filter { it.pendingPermission }
        override suspend fun overdueUnfired(now: Long) =
            rows.values.filter { !it.pendingPermission && it.firedAt == null && it.triggerAtMillis <= now }
        override fun pendingCountFlow(now: Long): Flow<Int> =
            flowOf(rows.values.count { it.pendingPermission && it.triggerAtMillis > now })
        override suspend fun futureCount(now: Long) = rows.values.count { it.triggerAtMillis > now }
        override suspend fun upsert(reminder: ReminderEntity) { rows[reminder.key] = reminder }
        override suspend fun markFired(key: String, firedAt: Long) {
            rows[key]?.let { rows[key] = it.copy(firedAt = firedAt) }
        }
        override suspend fun delete(key: String) { rows.remove(key) }
        override suspend fun clearAll() { rows.clear() }
    }

    private fun entity(
        key: String,
        triggerAtMillis: Long,
        firedAt: Long? = null,
        pendingPermission: Boolean = false,
    ) = ReminderEntity(
        key = key,
        fileName = "a.org",
        headingPath = key,
        headingTitle = key,
        headingLevel = 1,
        planningType = PlanningType.SCHEDULED.storageKey,
        triggerAtMillis = triggerAtMillis,
        notificationId = ReminderKeys.notificationId(key),
        pendingPermission = pendingPermission,
        firedAt = firedAt,
    )

    private class Recorder {
        val notified = mutableListOf<String>()
        val scheduled = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
    }

    private fun reconciler(dao: ReminderDao, rec: Recorder) = ReminderReconciler(
        context = null,
        dao = dao,
        clock = { now },
        hasPermission = { true },
        notify = { rec.notified += it.key },
        scheduleAlarm = { rec.scheduled += it.key },
        cancelAlarm = { rec.cancelled += it.key },
    )

    @Test
    fun `access-change rearm reschedules only armed future reminders`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["future"] = entity("future", triggerAtMillis = now + 60_000L)
        dao.rows["overdue"] = entity("overdue", triggerAtMillis = now - 1_000L)
        dao.rows["fired"] = entity("fired", triggerAtMillis = now - 2_000L, firedAt = now - 1_500L)
        dao.rows["pending"] = entity("pending", triggerAtMillis = now + 60_000L, pendingPermission = true)
        val rec = Recorder()

        reconciler(dao, rec).rearmFuture()

        assertEquals(listOf("future"), rec.scheduled)
        assertTrue("an access change must never fire a notification", rec.notified.isEmpty())
        assertNull("overdue row left for catch-up", dao.rows["overdue"]!!.firedAt)
        assertTrue("pending row left for reconcilePending", dao.rows["pending"]!!.pendingPermission)
    }

    @Test
    fun `access-change rearm without notification permission marks future reminders pending`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["future"] = entity("future", triggerAtMillis = now + 60_000L)
        val rec = Recorder()

        ReminderReconciler(
            context = null,
            dao = dao,
            clock = { now },
            hasPermission = { false },
            notify = { rec.notified += it.key },
            scheduleAlarm = { rec.scheduled += it.key },
            cancelAlarm = { rec.cancelled += it.key },
        ).rearmFuture()

        assertTrue(rec.scheduled.isEmpty())
        assertTrue(dao.rows["future"]!!.pendingPermission)
    }

    @Test
    fun `boot rearm does not re-fire an already-fired overdue reminder`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["k"] = entity("k", triggerAtMillis = 1_000L, firedAt = 1_500L)
        val rec = Recorder()

        reconciler(dao, rec).rearmAll()

        assertTrue("must not re-show a fired reminder", rec.notified.isEmpty())
        assertTrue("must not schedule an overdue reminder", rec.scheduled.isEmpty())
        assertEquals("firedAt must be preserved, not re-stamped", 1_500L, dao.rows["k"]!!.firedAt)
    }

    @Test
    fun `catch-up still fires a genuinely-unfired overdue reminder`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["k"] = entity("k", triggerAtMillis = 1_000L, firedAt = null)
        val rec = Recorder()

        reconciler(dao, rec).catchUpOverdue()

        assertEquals(listOf("k"), rec.notified)
        assertEquals(now, dao.rows["k"]!!.firedAt)
    }

    @Test
    fun `overdue branch cancels any stale alarm before showing`() = runTest {
        // A reschedule-into-the-past reaches arm() with an unfired, now-overdue entity;
        // the previously-armed future alarm for this key must be cancelled so it can't
        // fire again later as a duplicate.
        val dao = FakeReminderDao()
        dao.rows["k"] = entity("k", triggerAtMillis = 1_000L, firedAt = null)
        val rec = Recorder()

        reconciler(dao, rec).catchUpOverdue()

        assertEquals(listOf("k"), rec.cancelled)
        assertEquals(listOf("k"), rec.notified)
    }

    @Test
    fun `future reminder is scheduled, not fired`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["k"] = entity("k", triggerAtMillis = now + 5_000L)
        val rec = Recorder()

        reconciler(dao, rec).rearmAll()

        assertEquals(listOf("k"), rec.scheduled)
        assertTrue(rec.notified.isEmpty())
        assertNull(dao.rows["k"]!!.firedAt)
    }

    @Test
    fun `granting permission settles overdue-pending reminders instead of firing them`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["overdue"] = entity("overdue", triggerAtMillis = now - 5_000L, pendingPermission = true)
        dao.rows["future"] = entity("future", triggerAtMillis = now + 5_000L, pendingPermission = true)
        val rec = Recorder()

        reconciler(dao, rec).reconcilePending()

        assertTrue("must not fire a notification for a reminder overdue before permission was granted", rec.notified.isEmpty())
        assertEquals("only the future reminder should be scheduled", listOf("future"), rec.scheduled)
        assertEquals("overdue reminder must be settled (no longer pending)", false, dao.rows["overdue"]!!.pendingPermission)
        assertEquals("overdue reminder must be marked handled so catch-up doesn't pick it up later", now, dao.rows["overdue"]!!.firedAt)
        assertEquals(false, dao.rows["future"]!!.pendingPermission)
    }

    @Test
    fun `removeFile cancels the alarms and drops the rows for that file only`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["gone"] = entity("gone", triggerAtMillis = now + 5_000L).copy(fileName = "gone.org")
        dao.rows["keep"] = entity("keep", triggerAtMillis = now + 5_000L).copy(fileName = "keep.org")
        val rec = Recorder()

        reconciler(dao, rec).removeFile("gone.org")

        assertEquals(listOf("gone"), rec.cancelled)
        assertNull(dao.rows["gone"])
        assertEquals(setOf("keep"), dao.rows.keys)
    }

    @Test
    fun `pruneRemovedFiles drops reminders whose file is no longer indexed`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["gone"] = entity("gone", triggerAtMillis = now + 5_000L).copy(fileName = "gone.org")
        dao.rows["live"] = entity("live", triggerAtMillis = now + 5_000L).copy(fileName = "live.org")
        val rec = Recorder()

        reconciler(dao, rec).pruneRemovedFiles(setOf("live.org"))

        assertEquals(listOf("gone"), rec.cancelled)
        assertEquals(setOf("live"), dao.rows.keys)
    }

    @Test
    fun `setting a SCHEDULED time already in the past does not fire an immediate notification`() = runTest {
        val dao = FakeReminderDao()
        val rec = Recorder()
        // "now" is well after the SCHEDULED date below, so the freshly-computed reminder
        // reaches applyPlan()'s toSchedule list already overdue.
        val laterNow = LocalDateTime.of(2026, 1, 1, 0, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val reconciler = ReminderReconciler(
            context = null,
            dao = dao,
            clock = { laterNow },
            hasPermission = { true },
            notify = { rec.notified += it.key },
            scheduleAlarm = { rec.scheduled += it.key },
            cancelAlarm = { rec.cancelled += it.key },
        )
        val doc = OrgParser.parse("* TODO A\nSCHEDULED: <2020-01-01 Wed 09:00>\n")

        reconciler.reconcileFile("a.org", doc, LocalTime.of(9, 0), remindersEnabled = true)

        assertTrue("must not fire a notification for a task freshly scheduled into the past", rec.notified.isEmpty())
        assertTrue("must not leave a dangling alarm scheduled", rec.scheduled.isEmpty())
        assertEquals(1, dao.rows.size)
        assertTrue(
            "must still be marked settled so catch-up doesn't pick it up later",
            dao.rows.values.first().firedAt != null,
        )
    }

    // --- repeating reminders roll forward (report phase 8) ---

    private val utc = ZoneId.of("UTC")

    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(utc).toInstant().toEpochMilli()

    private fun repeating(key: String, trigger: Long, stamp: String?) =
        entity(key, triggerAtMillis = trigger).copy(repeatStamp = stamp)

    private fun reconcilerAt(clock: Long, dao: ReminderDao, rec: Recorder) = ReminderReconciler(
        context = null,
        dao = dao,
        clock = { clock },
        hasPermission = { true },
        notify = { rec.notified += it.key },
        scheduleAlarm = { rec.scheduled += it.key },
        cancelAlarm = { rec.cancelled += it.key },
        zone = { utc },
    )

    @Test
    fun `a fired repeating reminder re-arms for its next occurrence`() = runTest {
        val dao = FakeReminderDao()
        val fired = repeating("weekly", at(4, 12), "<2026-10-28 Wed 12:00 +1w>").copy(firedAt = at(4, 12))
        dao.rows["weekly"] = fired
        val rec = Recorder()

        reconcilerAt(at(4, 12), dao, rec).rollForward(fired)

        assertEquals(at(11, 12), dao.rows["weekly"]!!.triggerAtMillis)
        assertNull(dao.rows["weekly"]!!.firedAt)
        assertEquals(listOf("weekly"), rec.scheduled)
    }

    @Test
    fun `a date-only repeat keeps its reminder time of day when it rolls`() = runTest {
        val dao = FakeReminderDao()
        // Fired at 08:15 (the default reminder time when it was armed).
        val trigger = LocalDateTime.of(2026, 11, 4, 8, 15).atZone(utc).toInstant().toEpochMilli()
        val fired = repeating("daily", trigger, "<2026-11-01 Sun +1d>")
        dao.rows["daily"] = fired

        reconcilerAt(trigger, dao, Recorder()).rollForward(fired)

        assertEquals(
            LocalDateTime.of(2026, 11, 5, 8, 15).atZone(utc).toInstant().toEpochMilli(),
            dao.rows["daily"]!!.triggerAtMillis,
        )
    }

    @Test
    fun `an alarm delivered early never re-arms the same occurrence`() = runTest {
        val dao = FakeReminderDao()
        val fired = repeating("weekly", at(4, 12), "<2026-10-28 Wed 12:00 +1w>")
        dao.rows["weekly"] = fired

        reconcilerAt(at(4, 11), dao, Recorder()).rollForward(fired)

        assertEquals(at(11, 12), dao.rows["weekly"]!!.triggerAtMillis)
    }

    @Test
    fun `a one-off reminder is left as fired`() = runTest {
        val dao = FakeReminderDao()
        val fired = repeating("once", at(4, 12), null).copy(firedAt = at(4, 12))
        dao.rows["once"] = fired
        val rec = Recorder()

        reconcilerAt(at(4, 12), dao, rec).rollForward(fired)

        assertEquals(fired, dao.rows["once"])
        assertTrue(rec.scheduled.isEmpty())
    }

    @Test
    fun `catch-up fires a missed repeating reminder once and re-arms the next occurrence`() = runTest {
        val dao = FakeReminderDao()
        dao.rows["weekly"] = repeating("weekly", at(4, 12), "<2026-10-28 Wed 12:00 +1w>")
        val rec = Recorder()

        reconcilerAt(at(4, 15), dao, rec).catchUpOverdue()

        assertEquals(listOf("weekly"), rec.notified)
        assertEquals(at(11, 12), dao.rows["weekly"]!!.triggerAtMillis)
        assertNull(dao.rows["weekly"]!!.firedAt)
        assertEquals(listOf("weekly"), rec.scheduled)
    }
}
