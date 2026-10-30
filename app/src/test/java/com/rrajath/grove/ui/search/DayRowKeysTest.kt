package com.rrajath.grove.ui.search

import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test

class DayRowKeysTest {

    private fun result(file: String, line: Int) = SearchResult(
        fileName = file,
        lineIndex = line,
        title = "t",
        keyword = null,
        isDone = false,
        priority = null,
        scheduledLabel = null,
        scheduledOverdue = false,
        deadlineLabel = null,
        deadlineOverdue = false,
        tagLine = "",
        scheduledTs = null,
        deadlineTs = null,
    )

    private fun day(vararg rows: SearchResult) = SearchDayGroup("2026-10-30", "Today", rows.toList().toImmutableList())

    @Test
    fun `a row keeps its key when an earlier row is inserted`() {
        val before = dayRowKeys(day(result("a.org", 3)))
        val after = dayRowKeys(day(result("b.org", 1), result("a.org", 3)))
        assertEquals(before[0], after[1])
    }

    @Test
    fun `a heading on the day twice gets distinct keys`() {
        val keys = dayRowKeys(day(result("a.org", 3), result("b.org", 1), result("a.org", 3)))
        assertEquals(listOf("2026-10-30-a.org@3", "2026-10-30-b.org@1", "2026-10-30-a.org@3#1"), keys)
    }
}
