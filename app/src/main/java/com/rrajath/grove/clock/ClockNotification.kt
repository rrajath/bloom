package com.rrajath.grove.clock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.rrajath.grove.MainActivity
import com.rrajath.grove.R
import com.rrajath.grove.icon.NotificationAppearance
import com.rrajath.grove.org.LocalDates
import com.rrajath.grove.reminders.AlarmScheduler
import com.rrajath.grove.ui.components.orgInlinePlainText
import com.rrajath.grove.ui.nav.DeepLinks
import com.rrajath.grove.ui.nav.Routes
import com.rrajath.grove.ui.vault.NoteRef
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** What the clock notification says, apart from Android plumbing (JVM-testable). */
internal data class ClockNotificationContent(val title: String, val text: String, val chronometerBase: Long)

/**
 * [clock]'s notification text. The chronometer counts from [clock]'s start
 * moved back by [priorMinutes], the time already closed on the heading, so it
 * reads as the total, the way Emacs' mode line does.
 */
internal fun clockNotificationContent(
    clock: RunningClock,
    priorMinutes: Int,
    others: Int,
    today: LocalDate,
    is24Hour: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): ClockNotificationContent {
    val time = clock.start.toLocalTime().format(LocalDates.clock(is24Hour))
    val since = if (clock.start.toLocalDate() == today) time
    else LocalDates.format(clock.start.toLocalDate(), "MMMd", "MMM d") + ", " + time
    val text = buildString {
        append("Clocked in since ").append(since)
        if (others > 0) append(" · ").append(others).append(if (others == 1) " other open clock" else " other open clocks")
    }
    val startMillis = clock.start.atZone(zone).toInstant().toEpochMilli()
    return ClockNotificationContent(
        title = orgInlinePlainText(clock.title).ifBlank { clock.fileName.substringAfterLast('/') },
        text = text,
        chronometerBase = startMillis - priorMinutes * 60_000L,
    )
}

/**
 * The running clock as an ongoing, silent notification: Android's stand-in
 * for Emacs' mode line. Tapping opens the heading; Clock out and Cancel run
 * headlessly through [ClockActionReceiver]. Not a foreground service: the
 * clock lives in the file, so nothing has to keep running for it to count.
 */
object ClockNotification {

    private const val CHANNEL_ID = "clock"
    private const val NOTIFICATION_ID = 300

    fun show(context: Context, clock: RunningClock, priorMinutes: Int, others: Int) {
        if (!AlarmScheduler.hasNotificationPermission(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Clock", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }
        val content = clockNotificationContent(
            clock, priorMinutes, others, LocalDateTime.now().toLocalDate(), DateFormat.is24HourFormat(context),
        )
        val noteId = Routes.encode(NoteRef(clock.fileName, clock.lineIndex).encode())
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, DeepLinks.note(noteId, mode = "read", isNew = false).toUri())
                .setClass(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NotificationAppearance.markColor(context))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setWhen(content.chronometerBase)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Clock out", action(context, ClockActionReceiver.ACTION_CLOCK_OUT, clock, 1))
            .addAction(0, "Cancel", action(context, ClockActionReceiver.ACTION_CANCEL, clock, 2))
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun hide(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    private fun action(context: Context, action: String, clock: RunningClock, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            ClockActionReceiver.intent(context, action, clock),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
