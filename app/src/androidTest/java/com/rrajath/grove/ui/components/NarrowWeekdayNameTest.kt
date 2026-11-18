package com.rrajath.grove.ui.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device because the bug (issue #122) only shows there: desugared
 * `java.time` truncates full day names to their first character, so every
 * Chinese weekday came out as "星". A desktop JVM has real CLDR narrow names
 * and would pass either way.
 */
@RunWith(AndroidJUnit4::class)
class NarrowWeekdayNameTest {

    private val sundayFirst = listOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
    )

    private fun names(locale: Locale) = sundayFirst.map { narrowWeekdayName(it, locale) }

    @Test
    fun simplifiedChinese() {
        assertEquals(listOf("日", "一", "二", "三", "四", "五", "六"), names(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun traditionalChinese() {
        assertEquals(listOf("日", "一", "二", "三", "四", "五", "六"), names(Locale.TRADITIONAL_CHINESE))
    }

    @Test
    fun english() {
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), names(Locale.US))
    }
}
