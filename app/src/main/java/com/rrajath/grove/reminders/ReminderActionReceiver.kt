package com.rrajath.grove.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.PlanningKind
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Handles the notification's "Complete" action headlessly (no open ViewModel):
 * loads the file's current text from the vault, re-locates the heading by its
 * stored composite key, and applies a mutation, saves, and triggers a sync,
 * then cleans up the notification/alarm/row. Three mutations, chosen by the
 * reminder's shape:
 *  - a bare active timestamp ([ReminderEntity.activeTimestampDate] set) has no
 *    keyword to change, so [OrgMutations.advanceActiveTimestamp] advances just
 *    that stamp's date;
 *  - a keyword-less SCHEDULED/DEADLINE repeater ([ReminderEntity.isTask] false,
 *    [ReminderEntity.hasRepeater] true) has no keyword either, so
 *    [OrgMutations.advanceRepeatingPlanning] advances just that one planning
 *    field, the same clean path as the bare-active case -- unlike routing it
 *    through [OrgMutations.changeKeyword], which would fabricate a DONE
 *    keyword/LOGBOOK/LAST_REPEAT on a heading that never had a keyword;
 *  - otherwise (an actual TODO-keyword task) [OrgMutations.changeKeyword] with
 *    the first done-type keyword -- same entry point the metadata sheet uses,
 *    so a repeating SCHEDULED/DEADLINE advances its date and stays TODO rather
 *    than being marked DONE outright.
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMPLETE) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val app = context.applicationContext as GroveApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                complete(app, context, key)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun complete(app: GroveApplication, context: Context, key: String) {
        val reminder = app.database.reminderDao().get(key) ?: return
        // The broadcast itself may have cold-started the process, in which case
        // `vault` (Eagerly-shared over an async combine) is very likely still null
        // at this instant. Wait briefly for it to become non-null rather than
        // silently no-op-ing the mutation. goAsync() gives us a limited execution
        // budget, so bound the wait.
        val vault = withTimeoutOrNull(VAULT_WAIT_MILLIS) { app.vault.filterNotNull().first() }

        val completed = if (vault != null) {
            val doc = vault.open(reminder.fileName)
            val headline = doc?.let { ReminderKeys.findHeadline(it, reminder.headingPath, reminder.headingLevel) }
            val newText = if (doc == null || headline == null) {
                null
            } else {
                val activeDate = reminder.activeTimestampDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                when {
                    activeDate != null ->
                        OrgMutations.advanceActiveTimestamp(doc, headline, activeDate, LocalDateTime.now())
                    !reminder.isTask && reminder.hasRepeater -> {
                        val kind = runCatching { PlanningKind.valueOf(reminder.planningType) }.getOrNull()
                        kind?.let { OrgMutations.advanceRepeatingPlanning(doc, headline, it, LocalDateTime.now()) }
                    }
                    else -> app.keywords.value.done.firstOrNull()?.let { doneKeyword ->
                        val clock = app.settingsRepository.settings.first().clockOutPolicy
                        OrgMutations.changeKeyword(doc, headline, doneKeyword, doc.keywords, LocalDateTime.now(), clock)
                    }
                }
            }
            if (newText != null) {
                vault.save(reminder.fileName, newText)
                app.syncManager.requestReindex(reminder.fileName, newText, "reminder completed")
                true
            } else false
        } else false

        // Only tear down the notification/alarm/row after a CONFIRMED mutation+save.
        // If the mutation didn't happen (vault never became ready, heading couldn't be
        // relocated, no done keyword), leave the reminder row/alarm and the shown
        // notification intact so the user can re-tap Complete or re-open the app;
        // never lose the reminder without actually completing the task.
        if (completed) {
            ReminderNotification.cancel(context, reminder.notificationId)
            AlarmScheduler.cancel(context, reminder)
            app.database.reminderDao().delete(key)
        }
    }

    companion object {
        const val ACTION_COMPLETE = "com.rrajath.grove.action.REMINDER_COMPLETE"
        const val EXTRA_KEY = "key"

        /** Bounded wait for the vault on a cold-started process (goAsync budget is ~10s). */
        private const val VAULT_WAIT_MILLIS = 5_000L
    }
}
