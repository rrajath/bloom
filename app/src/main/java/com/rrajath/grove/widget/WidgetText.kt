package com.rrajath.grove.widget

import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import com.rrajath.grove.R
import java.util.Locale

/**
 * The view picker's subtitle for the Agenda row: "Overdue + next N days", N
 * being Settings › Widget › Days ahead. Below 1 reads as 1, matching how
 * [LedgerBuckets.build] treats it (the window always includes today).
 */
fun agendaWindowSubtitle(resources: Resources, daysAhead: Int): String {
    val days = daysAhead.coerceAtLeast(1)
    return resources.getQuantityString(R.plurals.widget_view_agenda_window, days, days)
}

/**
 * The Agenda widget's subtitle, "3 today · 12 in 7 days": [todayCount] items on
 * today, [totalCount] across the [windowDays] the widget covers (incl. Overdue).
 */
fun agendaSubtitle(resources: Resources, todayCount: Int, totalCount: Int, windowDays: Int): String =
    resources.getString(
        R.string.widget_agenda_subtitle,
        resources.getQuantityString(R.plurals.widget_agenda_today_count, todayCount, todayCount),
        resources.getQuantityString(R.plurals.widget_agenda_window_count, windowDays, totalCount, windowDays),
    )

/**
 * Uppercases a section header with the UI language's rules, for Glance, which has
 * no Compose `LocalConfiguration` (see `ui/util/UiUppercase.kt` for the Compose one).
 */
fun String.uppercaseForWidget(resources: Resources): String =
    uppercase(ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.ROOT)
