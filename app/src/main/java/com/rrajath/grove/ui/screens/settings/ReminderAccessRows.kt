package com.rrajath.grove.ui.screens.settings

import com.rrajath.grove.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.reminders.AlarmScheduler
import com.rrajath.grove.ui.reminders.ReminderAccessIntents
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.launch

/** Which reminder access is currently missing; each flag drives one "off" row. */
internal data class ReminderAccess(val exactAlarmsOff: Boolean, val notificationsOff: Boolean)

/**
 * Live read. `canScheduleExactAlarms` is always true below API 31, so the exact row
 * never shows there. `areNotificationsEnabled` covers every API level: the runtime
 * permission on 33+ and the app-level system switch below it.
 */
private fun readReminderAccess(context: Context) = ReminderAccess(
    exactAlarmsOff = !AlarmScheduler.canScheduleExactAlarms(context),
    notificationsOff = !NotificationManagerCompat.from(context).areNotificationsEnabled(),
)

/**
 * Settings › Reminders' access rows, shown only while access is off (a granted
 * permission shows nothing). Re-reads access on every ON_RESUME, so a row disappears
 * as soon as the user comes back from the system prompt or settings page. Exact-alarm
 * re-arming on return is handled app-wide by `ExactAlarmAccessWatcher`.
 */
@Composable
internal fun ReminderAccessSection() {
    val context = LocalContext.current
    val app = context.applicationContext as GroveApplication
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(readReminderAccess(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = readReminderAccess(context)
        // Notifications just turned on: arm the reminders that were waiting on them.
        if (!access.notificationsOff) scope.launch { app.reminderReconciler.reconcilePending() }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Denied with no rationale means the system won't show the prompt again
        // (denied twice): the settings page is the only remaining way to turn it on.
        val activity = context.findActivity()
        if (!granted && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            ReminderAccessIntents.openNotificationSettings(context)
        }
    }

    ReminderAccessRows(
        access = access,
        onAllowExactAlarms = { ReminderAccessIntents.openExactAlarmSettings(context) },
        onAllowNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !AlarmScheduler.hasNotificationPermission(context)
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Permission held (or not runtime-gated below 33) but notifications
                // are switched off for the app: only the system page can fix that.
                ReminderAccessIntents.openNotificationSettings(context)
            }
        },
    )
}

/**
 * Stateless rows, split out so UI tests can inject [access]. At most one row shows:
 * Notifications while they're off, and only then Exact timing. With notifications off,
 * reminders aren't armed at all (they wait as `pendingPermission`), so exact access
 * would have nothing to apply to. Renders nothing when nothing is off.
 */
@Composable
internal fun ReminderAccessRows(
    access: ReminderAccess,
    onAllowExactAlarms: () -> Unit,
    onAllowNotifications: () -> Unit,
) {
    if (!access.exactAlarmsOff && !access.notificationsOff) return
    SettingsGroup {
        if (access.notificationsOff) {
            SettingsRow(
                label = stringResource(R.string.settings_reminders_access_notifications),
                description = stringResource(R.string.settings_reminders_access_notifications_hint),
                onClick = onAllowNotifications,
            ) { AllowLabel() }
        } else {
            SettingsRow(
                label = stringResource(R.string.settings_reminders_access_exact),
                description = stringResource(R.string.settings_reminders_access_exact_hint),
                onClick = onAllowExactAlarms,
            ) { AllowLabel() }
        }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun AllowLabel() {
    Text(
        stringResource(R.string.settings_reminders_access_allow),
        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
        color = MaterialTheme.grove.accent,
    )
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
