package com.rrajath.grove.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.reminders.AlarmScheduler
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.launch

/**
 * Amber banner shown when one or more reminders couldn't be scheduled for lack
 * of POST_NOTIFICATIONS/exact-alarm access (reconciliation always persists what
 * *should* be scheduled; this is the surface that lets the user grant access so
 * it actually gets scheduled). Requests whichever permission is still missing,
 * then re-runs reconciliation for everything that was pending.
 */
// InlinedApi: each permission is only requested after AlarmScheduler reports it
// missing, which it never does below the API level that introduced it.
@SuppressLint("InlinedApi")
@Composable
fun ReminderPermissionBanner(pendingCount: Int, modifier: Modifier = Modifier) {
    if (pendingCount <= 0) return
    val c = MaterialTheme.grove
    val context = LocalContext.current
    val app = context.applicationContext as GroveApplication
    val scope = rememberCoroutineScope()

    fun reconcile() {
        scope.launch { app.reminderReconciler.reconcilePending() }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { reconcile() }

    // reconcilePending() only touches pending rows; the watcher also moves
    // already-armed alarms onto the exact path once access is granted.
    val exactAlarmSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        scope.launch {
            app.reminderReconciler.reconcilePending()
            app.exactAlarmAccessWatcher.check()
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.amberSoft)
            .border(1.dp, c.amber, RoundedCornerShape(11.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (pendingCount == 1) "1 reminder needs permission to notify you"
            else "$pendingCount reminders need permission to notify you",
            fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Grant",
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
            color = c.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .clickable {
                    when {
                        !AlarmScheduler.hasNotificationPermission(context) ->
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

                        !AlarmScheduler.canScheduleExactAlarms(context) ->
                            exactAlarmSettings.launch(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    "package:${context.packageName}".toUri(),
                                )
                            )

                        else -> reconcile()
                    }
                }
                .padding(6.dp),
        )
    }
}
