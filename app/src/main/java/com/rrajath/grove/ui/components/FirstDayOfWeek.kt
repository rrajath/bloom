package com.rrajath.grove.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.org.orgLocale
import com.rrajath.grove.settings.WeekStart
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import java.time.DayOfWeek
import java.time.format.TextStyle
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

/**
 * The standalone narrow weekday name for [dow] in [locale] ("S" for Sunday in
 * English, "日" in Chinese).
 *
 * Reads ICU's CLDR data on API 24+. `java.time`'s NARROW styles can't be used:
 * with minSdk 23 core library desugaring routes them through `j$.time`, which
 * has no narrow names and keeps only the first character of the full name, so
 * every Chinese day (星期日, 星期一, ...) becomes "星". API 23 has no
 * `android.icu`, so it keeps the `java.time` result.
 */
fun narrowWeekdayName(dow: DayOfWeek, locale: Locale): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val names = android.icu.text.DateFormatSymbols.getInstance(locale).getWeekdays(
            android.icu.text.DateFormatSymbols.STANDALONE,
            android.icu.text.DateFormatSymbols.NARROW,
        )
        // ICU indexes by Calendar.SUNDAY = 1 .. SATURDAY = 7; DayOfWeek is MONDAY = 1 .. SUNDAY = 7.
        names.getOrNull(dow.value % 7 + 1)?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return dow.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
}

/**
 * One narrow weekday letter per column, starting at [firstDay], in the device
 * language. Columns are equal weights, so it lines up with any 7-column grid
 * that uses the same [spacing] between cells. Each letter reads out as the full
 * day name, since "T" and "S" alone are ambiguous.
 */
@Composable
fun WeekdayHeaderRow(firstDay: DayOfWeek, modifier: Modifier = Modifier, spacing: Dp = 0.dp) {
    val c = MaterialTheme.grove
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        WeekStart.weekdays(firstDay).forEach { dow ->
            val full = dow.getDisplayName(TextStyle.FULL, orgLocale())
            Text(
                narrowWeekdayName(dow, orgLocale()),
                fontFamily = PlexSans, fontSize = 10.sp, color = c.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 4.dp)
                    .semantics { contentDescription = full },
            )
        }
    }
}
