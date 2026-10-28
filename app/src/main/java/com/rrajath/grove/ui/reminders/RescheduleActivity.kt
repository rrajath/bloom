package com.rrajath.grove.ui.reminders

import android.text.format.DateFormat
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.reminders.ReminderKeys
import com.rrajath.grove.reminders.ReminderNotification
import com.rrajath.grove.ui.components.PlanningDatesScreen
import com.rrajath.grove.ui.theme.ContentFontScale
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.org.LocalDates
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * Host for a reminder notification's "Reschedule" action.
 *
 * Deliberately *not* a route inside `MainActivity`: rescheduling from the shade
 * is a one-shot errand started from whatever app the user was in, and it has to
 * end back there. Routing it through the main task landed the user in the editor
 * afterwards, and finishing that task instead would have torn down wherever they
 * had been in Grove. This activity declares an empty `taskAffinity`, so it opens
 * in a task of its own: picking dates and confirming closes it, the app
 * underneath comes back, and Grove's own task is never touched.
 *
 * The write is headless in the same sense as `ReminderActionReceiver`'s
 * "Complete": re-read the file, re-locate the heading by its stored composite
 * key, mutate, save, sync, and runs on [GroveApplication.appScope] rather than
 * any activity-scoped scope, so finishing the moment the user taps Apply cannot
 * cancel a write that is still in flight.
 */
class RescheduleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getStringExtra(EXTRA_KEY)
        if (key.isNullOrEmpty()) {
            finish()
            return
        }
        setContent {
            val app = LocalContext.current.applicationContext as GroveApplication
            val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(null)
            // Nothing is drawn until the theme resolves; this window sits over
            // the previous app, so an unthemed flash would be very visible.
            settings?.let { s ->
                GroveTheme(theme = s.theme) {
                    ContentFontScale(s.appFontSize) {
                        RescheduleFlow(reminderKey = key, onDone = ::finish)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_KEY = "key"

        /** Explicit intent for [com.rrajath.grove.data.ReminderEntity.key]. */
        fun intent(context: Context, reminderKey: String): Intent =
            Intent(context, RescheduleActivity::class.java).putExtra(EXTRA_KEY, reminderKey)
    }
}

/** What the dialog needs on screen, once the reminder resolves to a live heading. */
private data class Target(val fileName: String, val headline: OrgHeadline, val isDeadline: Boolean)

/**
 * Resolves the reminder row into its heading's *current* on-disk state, shows
 * the dates dialog prefilled with it, and commits the picked pair.
 *
 * The confirm path deliberately re-opens the file instead of writing back the
 * document parsed here: the dialog can sit open for a while, and a sync landing
 * underneath in the meantime would otherwise be clobbered.
 */
@Composable
private fun RescheduleFlow(reminderKey: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GroveApplication
    var target by remember { mutableStateOf<Target?>(null) }

    LaunchedEffect(reminderKey) {
        val reminder = app.database.reminderDao().get(reminderKey)
        // Launching from the shade may have cold-started the process, so the
        // vault (Eagerly shared over an async combine) is very likely still null
        // for the first frames; wait for it rather than failing outright. No
        // timeout is needed here, unlike the receiver's goAsync() budget: the
        // user can always dismiss the window.
        val vault = app.vault.filterNotNull().first()
        val doc = reminder?.let { vault.open(it.fileName) }
        val headline = doc?.let { ReminderKeys.findHeadline(it, reminder.headingPath, reminder.headingLevel) }
        if (reminder == null || headline == null) {
            Toast.makeText(context, "Task no longer exists", Toast.LENGTH_SHORT).show()
            onDone()
        } else {
            target = Target(
                fileName = reminder.fileName,
                headline = headline,
                isDeadline = reminder.planningType.equals("DEADLINE", ignoreCase = true),
            )
        }
    }

    val resolved = target ?: return
    PlanningDatesScreen(
        title = resolved.headline.title,
        scheduled = resolved.headline.planning.scheduled,
        deadline = resolved.headline.planning.deadline,
        active = resolved.headline.dedicatedActiveTimestamps,
        focus = if (resolved.isDeadline) PlanningKind.DEADLINE else PlanningKind.SCHEDULED,
        onDismiss = onDone,
        onConfirm = { sched, dead, active ->
            app.appScope.launch { writePlanning(app, reminderKey, sched, dead, active) }
            // The reminder that sent us here was for one of the two dates, so
            // the toast reports that one, not whichever else was also edited.
            val reported = if (resolved.isDeadline) dead else sched
            Toast.makeText(
                context,
                if (reported == null) "Task rescheduled"
                else "Task rescheduled to ${formatRescheduled(reported, DateFormat.is24HourFormat(context))}",
                Toast.LENGTH_SHORT,
            ).show()
            // Toasts are owned by the system, so this one outlives the window;
            // the write is on appScope for the same reason. Nothing here has to
            // wait, and waiting would leave a dead dialog on screen.
            onDone()
        },
    )
}

/**
 * Applies the picked dates to the reminder's heading and puts the file back on
 * disk. Re-reads everything from the vault so the edit is against the file as it
 * is *now*; the reminder row itself is left alone, since re-indexing the saved
 * file is what re-derives it (new trigger time, new alarm) through
 * `ReminderReconciler`.
 */
private suspend fun writePlanning(
    app: GroveApplication,
    reminderKey: String,
    scheduled: OrgTimestamp?,
    deadline: OrgTimestamp?,
    active: List<OrgTimestamp>,
) {
    val reminder = app.database.reminderDao().get(reminderKey) ?: return
    val vault = app.vault.value ?: return
    val doc = vault.open(reminder.fileName) ?: return
    val headline = ReminderKeys.findHeadline(doc, reminder.headingPath, reminder.headingLevel) ?: return
    val newText = OrgMutations.setPlanningAndActiveTimestamps(doc, headline, scheduled, deadline, active)
    vault.save(reminder.fileName, newText)
    app.syncManager.requestReindex(reminder.fileName, newText, "reminder rescheduled")
    // Only now that the new date is durably on disk: the shown notification is
    // stale. Its alarm and row are the reconciler's to update, and it re-derives
    // them from the file the sync above re-indexes.
    ReminderNotification.cancel(app, reminder.notificationId)
}

private val RESCHEDULED_DATE: DateTimeFormatter get() = LocalDates.formatter("EEEMMMd", "EEE, MMM d")

/** "Task rescheduled to <date[, time]>": matches the app's "EEE, MMM d" convention. */
private fun formatRescheduled(ts: OrgTimestamp, is24Hour: Boolean): String {
    val date = ts.date.format(RESCHEDULED_DATE)
    return if (ts.time != null) "$date at ${ts.time.format(LocalDates.clock(is24Hour))}" else date
}
