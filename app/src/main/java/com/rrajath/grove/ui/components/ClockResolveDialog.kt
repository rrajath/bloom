package com.rrajath.grove.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.clock.ClockFix
import com.rrajath.grove.clock.RunningClock
import com.rrajath.grove.org.LocalDates
import com.rrajath.grove.org.OrgClock
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Asked before a clock-in would close open clocks that look dangling (several
 * open at once, or one running for half a day; see `ClockService.STALE_AFTER`),
 * after `org-resolve-clocks`: each can be clocked out now or at a picked time,
 * cancelled, or left open. Confirming clocks in with those answers.
 */
@Composable
fun ClockResolveDialog(
    clocks: List<RunningClock>,
    now: LocalDateTime,
    onResolve: (Map<RunningClock, ClockFix>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val fixes = remember(clocks) { mutableStateMapOf<RunningClock, ClockFix>().apply { clocks.forEach { put(it, ClockFix.OutAt(now)) } } }
    var picking by remember { mutableStateOf<RunningClock?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                if (clocks.size == 1) "A clock is still running" else "${clocks.size} clocks are still running",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                clocks.forEach { clock ->
                    val fix = fixes[clock]
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            orgInlinePlainText(clock.title),
                            fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Since ${stampLabel(clock.start, now, is24Hour)} · " +
                                OrgClock.hmm(Duration.between(clock.start, now).toMinutes().toInt().coerceAtLeast(0)),
                            fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val outAt = fix as? ClockFix.OutAt
                            FixChip("Out now", selected = outAt?.at == now) { fixes[clock] = ClockFix.OutAt(now) }
                            FixChip(
                                if (outAt != null && outAt.at != now) "Out at ${stampLabel(outAt.at, now, is24Hour)}" else "Out at…",
                                selected = outAt != null && outAt.at != now,
                            ) { picking = clock }
                            FixChip("Cancel it", selected = fix == ClockFix.Cancel) { fixes[clock] = ClockFix.Cancel }
                            FixChip("Leave open", selected = fix == ClockFix.Keep) { fixes[clock] = ClockFix.Keep }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(fixes.toMap()) }) {
                Text("Clock in", fontFamily = PlexSans, color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", fontFamily = PlexSans, color = c.ink2) }
        },
    )

    picking?.let { clock ->
        SimpleTimePicker(
            initial = clock.start.toLocalTime(),
            onDismiss = { picking = null },
            onConfirm = { time ->
                fixes[clock] = ClockFix.OutAt(clockOutAt(clock.start, time, now))
                picking = null
            },
        )
    }
}

/**
 * The stop moment a time picked for [start]'s clock means: that time on the
 * clock's start day, or on the next day when it would come before the start
 * (a clock left running past midnight), never later than [now].
 */
internal fun clockOutAt(start: LocalDateTime, time: LocalTime, now: LocalDateTime): LocalDateTime {
    var at = start.toLocalDate().atTime(time)
    if (at.isBefore(start)) at = at.plusDays(1)
    return if (at.isAfter(now)) now else at
}

private fun stampLabel(at: LocalDateTime, now: LocalDateTime, is24Hour: Boolean): String {
    val time = at.toLocalTime().format(LocalDates.clock(is24Hour))
    return if (at.toLocalDate() == now.toLocalDate()) time
    else LocalDates.format(at.toLocalDate(), "EEEMMMd", "EEE MMM d") + " " + time
}

@Composable
private fun FixChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Text(
        label,
        fontFamily = PlexSans, fontSize = 12.5.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) c.accent else c.ink2,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.accentSoft else c.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
