package com.rrajath.grove.widget

import android.content.res.Resources
import com.rrajath.grove.R

/**
 * The view picker's subtitle for the Agenda row: "Overdue + next N days", N
 * being Settings › Widget › Days ahead. Below 1 reads as 1, matching how
 * [LedgerBuckets.build] treats it (the window always includes today).
 */
fun agendaWindowSubtitle(resources: Resources, daysAhead: Int): String {
    val days = daysAhead.coerceAtLeast(1)
    return resources.getQuantityString(R.plurals.widget_view_agenda_window, days, days)
}
