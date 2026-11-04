package com.rrajath.grove.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rrajath.grove.GroveApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Fired by `AlarmManager` at a reminder's trigger time: shows the "due now" notification. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val app = context.applicationContext as GroveApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                val reminder = app.database.reminderDao().get(key) ?: return@launch
                // The notebook may have been deleted (from a file manager, or
                // Syncthing) after this alarm was armed and before it fired, with
                // no sync pass since to prune the row. Showing the notification
                // anyway strands the user on the notebook list when they tap it.
                // The alarm can wake a cold process, so give fileStore a moment
                // to finish its async DataStore read before giving up the check.
                val store = withTimeoutOrNull(3_000) { app.fileStore.first { it != null } }
                if (store != null && !store.exists(reminder.fileName)) {
                    app.database.reminderDao().delete(key)
                    return@launch
                }
                // A date-only reminder only shows its own notification when the
                // user opted in ("Notify for tasks without a time"); otherwise it
                // is silent here and counted into the daily digest instead.
                if (reminder.firesOwnNotification) ReminderNotification.show(context, reminder)
                app.database.reminderDao().markFired(key, System.currentTimeMillis())
                // A repeating stamp re-arms for its next occurrence.
                app.reminderReconciler.rollForward(reminder)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
