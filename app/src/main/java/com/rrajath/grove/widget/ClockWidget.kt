package com.rrajath.grove.widget

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.MainActivity
import com.rrajath.grove.R
import com.rrajath.grove.clock.ClockActionReceiver
import com.rrajath.grove.clock.RunningClock
import com.rrajath.grove.clock.toRunningClock
import com.rrajath.grove.ui.components.orgInlinePlainText
import com.rrajath.grove.ui.nav.DeepLinks
import com.rrajath.grove.ui.nav.Routes
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.groveColorsFor
import com.rrajath.grove.ui.vault.NoteRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/**
 * Home-screen "Clock" widget: the running clock's heading and its time so far,
 * with a stop button, or "Not clocked in". The open clocks are collected from
 * the index inside the composition, as [LedgerWidget] collects its rows (see
 * its doc for why that placement matters); the elapsed time is a RemoteViews
 * Chronometer, which the launcher advances itself.
 */
class ClockWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GroveApplication
        val dao = app.database.indexDao()
        val initialSettings = app.settingsRepository.settings.first()
        val initialClocks = dao.runningClocks().first().mapNotNull { it.toRunningClock() }

        provideContent {
            val settings by app.settingsRepository.settings.collectAsState(initial = initialSettings)
            val clocks by remember { dao.runningClocks().map { rows -> rows.mapNotNull { it.toRunningClock() } } }
                .collectAsState(initial = initialClocks)
            ClockTile(context, groveColorsFor(settings.theme), clocks.firstOrNull())
        }
    }
}

@Composable
private fun ClockTile(context: Context, colors: GroveColors, clock: RunningClock?) {
    val open = if (clock != null) {
        val noteId = Routes.encode(NoteRef(clock.fileName, clock.lineIndex).encode())
        Intent(Intent.ACTION_VIEW, DeepLinks.note(noteId, mode = "read", isNew = false).toUri())
            .setClass(context, MainActivity::class.java)
    } else {
        Intent(context, MainActivity::class.java)
    }
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(17.dp)
            .background(ColorProvider(colors.surface))
            .clickable(actionStartActivity(open))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "◷",
            style = TextStyle(
                color = ColorProvider(if (clock != null) colors.accent else colors.ink3),
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Spacer(GlanceModifier.width(10.dp))
        if (clock == null) {
            Text(
                "Not clocked in",
                style = TextStyle(color = ColorProvider(colors.ink3), fontSize = 13.5.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
        } else {
            RunningClockContent(context, colors, clock)
        }
    }
}

/** The running clock's title, elapsed time and stop button. */
@Composable
private fun RowScope.RunningClockContent(context: Context, colors: GroveColors, clock: RunningClock) {
    Column(GlanceModifier.defaultWeight()) {
        Text(
            orgInlinePlainText(clock.title),
            maxLines = 1,
            style = TextStyle(color = ColorProvider(colors.ink), fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
        )
        val chronometer = remember(clock.start, colors) { chronometerViews(context, clock, colors) }
        AndroidRemoteViews(chronometer)
    }
    Box(
        modifier = GlanceModifier
            .size(40.dp)
            .cornerRadius(12.dp)
            .clickable(actionSendBroadcast(ClockActionReceiver.intent(context, ClockActionReceiver.ACTION_CLOCK_OUT, clock))),
        contentAlignment = Alignment.Center,
    ) {
        Text("◼", style = TextStyle(color = ColorProvider(colors.accent), fontSize = 16.sp, fontFamily = FontFamily.Monospace))
    }
}

/** A Chronometer counting from [clock]'s start, in the widget's muted ink. */
private fun chronometerViews(context: Context, clock: RunningClock, colors: GroveColors): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_clock_chronometer).apply {
        val elapsedMillis = System.currentTimeMillis() -
            clock.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setChronometer(R.id.clock_chronometer, SystemClock.elapsedRealtime() - elapsedMillis, null, true)
        setTextColor(R.id.clock_chronometer, colors.ink2.toArgb())
    }

class ClockWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClockWidget()
}
