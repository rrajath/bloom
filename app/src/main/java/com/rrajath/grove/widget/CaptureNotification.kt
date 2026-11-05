package com.rrajath.grove.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.rrajath.grove.MainActivity
import com.rrajath.grove.R
import com.rrajath.grove.icon.NotificationAppearance
import com.rrajath.grove.ui.nav.DeepLinks

/**
 * Optional persistent "Capture" notification (PRD §8): a low-priority,
 * silent, ongoing notification whose tap opens the capture flow.
 */
object CaptureNotification {

    private const val CHANNEL_ID = "capture-shortcut"
    private const val NOTIFICATION_ID = 200

    // Below API 33 POST_NOTIFICATIONS isn't a runtime permission, and
    // checkSelfPermission always reports it denied, so the notification could
    // never be shown there.
    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun show(context: Context) {
        if (!canShow(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Capture shortcut", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val intent = Intent(Intent.ACTION_VIEW, DeepLinks.capture().toUri())
            .setClass(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NotificationAppearance.markColor(context))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Capture a note")
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun hide(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }
}
