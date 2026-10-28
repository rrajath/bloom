package com.rrajath.grove.org

import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Locale for day and month names, both the ones written into .org files
 * (timestamp day abbreviations, datetree headings, %month/%day) and the
 * dates shown in the UI. It's the device's format locale, which matches
 * Emacs: `format-time-string` names days and months in the system locale,
 * so a German setup writes `<2026-10-26 Mo>` and `2026-10 Oktober`.
 */
// Locale.Category needs API 24; on Android the plain default is the same locale.
fun orgLocale(): Locale = Locale.getDefault()

/**
 * Reader-facing dates in [orgLocale]'s own word order ("Mon, Oct 26" in
 * English, "Mo., 26. Okt." in German), built from CLDR skeletons such as
 * `EEEMMMd`.
 */
object LocalDates {
    /**
     * Resolves a skeleton to the locale's best pattern, or null. Android
     * installs `DateFormat.getBestDateTimePattern` at startup; plain JVM code
     * (unit tests) gets null and falls back to the caller's English pattern.
     */
    @Volatile
    var bestPattern: (Locale, String) -> String? = { _, _ -> null }
        set(value) {
            field = value
            cache.clear()
        }

    private val cache = ConcurrentHashMap<Pair<Locale, String>, DateTimeFormatter>()

    /** [skeleton] resolved for [orgLocale]; [fallback] when no pattern is found or it won't compile. */
    fun formatter(skeleton: String, fallback: String): DateTimeFormatter {
        val locale = orgLocale()
        return cache.getOrPut(locale to skeleton) {
            val pattern = bestPattern(locale, skeleton)
            pattern?.let { runCatching { DateTimeFormatter.ofPattern(it, locale) }.getOrNull() }
                ?: DateTimeFormatter.ofPattern(fallback, locale)
        }
    }

    fun format(value: TemporalAccessor, skeleton: String, fallback: String): String =
        formatter(skeleton, fallback).format(value)

    /** A time of day as `14:05` or `2:05 PM`, per the device's 24-hour setting. */
    fun clock(is24Hour: Boolean): DateTimeFormatter =
        if (is24Hour) formatter("HHmm", "HH:mm") else formatter("hmma", "h:mm a")
}
