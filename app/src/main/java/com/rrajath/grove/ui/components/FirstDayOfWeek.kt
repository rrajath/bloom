package com.rrajath.grove.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.settings.WeekStart
import java.time.DayOfWeek
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * The device region's first day of the week. On API 24+ this goes through ICU
 * with the format locale, which also carries Android 14's "First day of week"
 * regional preference (`-u-fw-`) where ICU honors it. Older releases fall back
 * to `java.util.Calendar`, which knows the region but not that override.
 */
fun systemFirstDayOfWeek(): DayOfWeek {
    // Calendar.SUNDAY = 1 .. SATURDAY = 7; DayOfWeek is MONDAY = 1 .. SUNDAY = 7.
    val calendarDay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        android.icu.util.Calendar
            .getInstance(android.icu.util.ULocale.getDefault(android.icu.util.ULocale.Category.FORMAT))
            .firstDayOfWeek
    } else {
        java.util.Calendar.getInstance(Locale.getDefault()).firstDayOfWeek
    }
    return DayOfWeek.SUNDAY.plus((calendarDay - 1).toLong())
}

/**
 * Last stored value seen this process, used as the first frame's value so a
 * calendar opened after the first doesn't flash the system default before
 * DataStore emits the user's choice.
 */
@Volatile
private var lastKnownWeekStart: WeekStart = WeekStart.SYSTEM

/**
 * The first column for month calendars, from Settings § Agenda "Start of week".
 * Read straight from the app's settings so every calendar host (the main app,
 * the widget quick-add and reminder reschedule activities) agrees without
 * threading a parameter through each caller.
 */
@Composable
fun rememberFirstDayOfWeek(): DayOfWeek {
    val app = LocalContext.current.applicationContext as GroveApplication
    val system = remember { systemFirstDayOfWeek() }
    val pref by remember(app) {
        app.settingsRepository.settings
            .map { it.weekStart }
            .distinctUntilChanged()
            .onEach { lastKnownWeekStart = it }
    }.collectAsState(initial = lastKnownWeekStart)
    return pref.resolve(system)
}
