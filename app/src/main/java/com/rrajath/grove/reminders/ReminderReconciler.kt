package com.rrajath.grove.reminders

import android.content.Context
import com.rrajath.grove.data.ReminderDao
import com.rrajath.grove.data.ReminderEntity
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.settings.ReminderLeadTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Android-side orchestration around [ReminderPlanning]/[ReminderDiff]: keeps
 * the `reminders` table and the actual `AlarmManager` alarms in sync.
 *
 * Two entry points, split by whether they need the vault:
 *  - [reconcileFile]/[reconcileAll] recompute desired reminders from a parsed
 *    [OrgDocument]: used right after a notebook (re)parses, or when
 *    reminders/the default reminder time setting changes.
 *  - [catchUpOverdue]/[rearmAll]/[reconcilePending] only touch the DB + alarms
 *    (no vault access), so they're cheap to call on app start, after a sync,
 *    and from `BOOT_COMPLETED`.
 */
class ReminderReconciler(
    private val context: Context?,
    private val dao: ReminderDao,
    private val clock: () -> Long = System::currentTimeMillis,
    // Android side-effects behind lambda seams so the scheduling logic in arm()/
    // applyPlan() is JVM-unit-testable with a fake dao and no Context. Production
    // uses the defaults (which delegate to the AlarmManager/notification objects).
    private val hasPermission: () -> Boolean = { AlarmScheduler.hasNotificationPermission(context!!) },
    // A date-only reminder only fires its own notification when the user opted in
    // ("Notify for tasks without a time"); otherwise it's counted into the daily
    // digest instead. [ReminderPlanning] bakes that choice into firesOwnNotification.
    private val notify: (ReminderEntity) -> Unit = { if (it.firesOwnNotification) ReminderNotification.show(context!!, it) },
    private val scheduleAlarm: (ReminderEntity) -> Unit = { AlarmScheduler.schedule(context!!, it) },
    private val cancelAlarm: (ReminderEntity) -> Unit = { AlarmScheduler.cancel(context!!, it) },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    /** Recompute and apply the diff for one file's current headlines. */
    suspend fun reconcileFile(
        fileName: String,
        doc: OrgDocument,
        defaultReminderTime: LocalTime,
        remindersEnabled: Boolean,
        leadTime: ReminderLeadTime = ReminderLeadTime.AT_TIME,
        notifyUntimed: Boolean = false,
    ) {
        val existing = dao.forFile(fileName)
        val desired = ReminderPlanning.desiredReminders(
            fileName, doc, defaultReminderTime, remindersEnabled, leadTime, notifyUntimed,
            zone = zone(), nowMillis = clock(),
        )
        applyPlan(ReminderDiff.diff(existing, desired))
    }

    /** [reconcileFile] for every file in [documents] (fileName → parsed doc). */
    suspend fun reconcileAll(
        documents: Map<String, OrgDocument>,
        defaultReminderTime: LocalTime,
        remindersEnabled: Boolean,
        leadTime: ReminderLeadTime = ReminderLeadTime.AT_TIME,
        notifyUntimed: Boolean = false,
    ) {
        documents.forEach { (fileName, doc) ->
            reconcileFile(fileName, doc, defaultReminderTime, remindersEnabled, leadTime, notifyUntimed)
        }
    }

    /** Settings › Reminders toggled off: cancel every alarm and drop the table. */
    suspend fun disableAll() {
        dao.all().forEach { cancelAlarm(it) }
        dao.clearAll()
    }

    /**
     * Cancel the alarms and drop the rows for one file's reminders. For when a
     * notebook is deleted or renamed/moved away: [reconcileFile] only ever runs
     * against a file that still exists, so nothing else would ever clear a gone
     * file's pre-armed alarms — they'd keep firing stale "due now" notifications
     * that resolve to nothing (landing the user on the notebook list).
     */
    suspend fun removeFile(fileName: String) {
        dao.forFile(fileName).forEach { entity ->
            cancelAlarm(entity)
            dao.delete(entity.key)
        }
    }

    /**
     * DB-only sweep: [removeFile] every reminder whose file is no longer in
     * [liveFileNames] (the current `notebooks` index). Cheap catch-all for
     * deletes/renames that happened outside the app (Syncthing, a file manager)
     * or while it was dead — run after every sync and on app start.
     */
    suspend fun pruneRemovedFiles(liveFileNames: Set<String>) {
        dao.all()
            .filter { it.fileName !in liveFileNames }
            .forEach { entity ->
                cancelAlarm(entity)
                dao.delete(entity.key)
            }
    }

    /**
     * Re-run scheduling for reminders that were waiting on a permission. Only
     * reminders whose trigger time is still in the future get scheduled; ones
     * that went overdue while permission was missing are settled silently
     * instead of firing as a flood of stale notifications the moment access is
     * granted (the user only wants notifications for what's ahead, not backlog).
     */
    suspend fun reconcilePending() {
        val now = clock()
        dao.pending().forEach { entity ->
            if (entity.triggerAtMillis <= now) {
                dao.upsert(entity.copy(pendingPermission = false, firedAt = now))
            } else {
                arm(entity)
            }
        }
    }

    /** DB-only safety net: fire anything overdue that hasn't shown its notification yet. */
    suspend fun catchUpOverdue() {
        dao.overdueUnfired(clock()).forEach { arm(it) }
    }

    /** `BOOT_COMPLETED`: AlarmManager alarms don't survive a reboot; re-arm everything stored. */
    suspend fun rearmAll() {
        dao.all().forEach { arm(it) }
    }

    /**
     * Exact-alarm access changed: re-arm every still-future reminder so its alarm
     * moves to the path [AlarmScheduler.schedule] now picks (exact once granted;
     * inexact after a revoke, which has already made the OS cancel the exact ones).
     * Overdue rows are left to [catchUpOverdue] and permission-pending rows to
     * [reconcilePending], so this never fires a notification.
     */
    suspend fun rearmFuture() {
        val now = clock()
        dao.all()
            .filter { !it.pendingPermission && it.triggerAtMillis > now }
            .forEach { arm(it) }
    }

    /**
     * After [entity] fired: a repeating reminder ([ReminderEntity.repeatStamp])
     * re-arms for its next occurrence, so a weekly stamp the user never advances
     * keeps reminding every week. Runs without the vault: the stamp, lead time
     * and (for a date-only stamp) the reminder time of day all come off the row.
     * A one-off reminder is left as fired.
     */
    suspend fun rollForward(entity: ReminderEntity) {
        val stamp = entity.repeatStamp?.let { OrgTimestamp.parse(it) } ?: return
        val z = zone()
        // A date-only stamp fires at the default reminder time, which the row's
        // own trigger already carries; a timed stamp ignores this value.
        val timeOfDay = Instant.ofEpochMilli(entity.triggerAtMillis).atZone(z).toLocalTime()
        // Never before the trigger that just fired, so an alarm delivered a
        // little early can't re-arm the same occurrence.
        val after = maxOf(clock(), entity.triggerAtMillis)
        val next = ReminderPlanning.nextTriggerMillis(
            stamp, timeOfDay, after, z, ReminderLeadTime.fromStorage(entity.leadTime),
        )
        if (next <= after) return
        arm(entity.copy(triggerAtMillis = next, firedAt = null))
    }

    private suspend fun applyPlan(plan: ReminderPlan) {
        plan.toCancel.forEach { entity ->
            cancelAlarm(entity)
            dao.delete(entity.key)
        }
        // notifyIfOverdue = false: a toSchedule entry is either brand new or just had its
        // trigger time changed by this reconcile (e.g. the user set a SCHEDULED/DEADLINE
        // to a past date/time). There's no prior period during which this exact trigger
        // was armed and missed, so unlike catchUpOverdue()/rearmAll() (genuine "the device
        // was off and this should have fired" catch-up), it must not fire a notification.
        plan.toSchedule.forEach { arm(it, notifyIfOverdue = false) }
        // plan.unchanged: leave the row and its alarm exactly as they are.
    }

    /**
     * Persist [entity] and either fire it now (already overdue) or schedule its
     * alarm, only if permitted; otherwise mark it pending so a permission
     * banner can prompt the user and [reconcilePending] can pick it up later.
     */
    private suspend fun arm(entity: ReminderEntity, notifyIfOverdue: Boolean = true) {
        val permitted = hasPermission()
        if (!permitted) {
            dao.upsert(entity.copy(pendingPermission = true, firedAt = null))
            return
        }
        if (entity.triggerAtMillis <= clock()) {
            // Always clear any previously-armed alarm for this reminder first: on a
            // reschedule-into-the-past the old future alarm shares this key's
            // PendingIntent and would otherwise still fire later (duplicate).
            cancelAlarm(entity)
            // Skip re-showing an already-fired reminder (e.g. BOOT_COMPLETED re-arming
            // a fired-but-not-completed reminder via rearmAll() → dao.all()). The
            // catchUpOverdue()/reconcilePending() paths pass entities with firedAt == null
            // by construction, so genuinely-unfired overdue reminders still fire.
            if (entity.firedAt != null) {
                dao.upsert(entity.copy(pendingPermission = false))
                rollForward(entity)
                return
            }
            dao.upsert(entity.copy(pendingPermission = false))
            if (notifyIfOverdue) notify(entity)
            dao.markFired(entity.key, clock())
            // A repeating reminder moves on to its next occurrence right away.
            rollForward(entity)
        } else {
            dao.upsert(entity.copy(pendingPermission = false, firedAt = null))
            scheduleAlarm(entity)
        }
    }
}
