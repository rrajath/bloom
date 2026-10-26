package com.rrajath.grove.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPagingTest {

    private fun plan(vararg lists: Pair<String, Int>, pages: Map<String, Int> = emptyMap()) =
        WidgetPaging.plan(lists.toList(), pages)

    @Test
    fun `a short list shows everything with no more row`() {
        val p = plan("a" to 7).single()
        assertEquals(7, p.visible)
        assertEquals(0, p.remaining)
        assertFalse(p.canLoadMore)
    }

    @Test
    fun `a long list starts at one page and counts the rest`() {
        val p = plan("a" to 45).single()
        assertEquals(20, p.visible)
        assertEquals(25, p.remaining)
        assertTrue(p.canLoadMore)
    }

    @Test
    fun `each expansion adds a page and the remaining count goes down`() {
        val once = WidgetPaging.expand(emptyMap(), "a")
        assertEquals(mapOf("a" to 2), once)
        assertEquals(5, plan("a" to 45, pages = once).single().remaining)
        val twice = WidgetPaging.expand(once, "a")
        val p = plan("a" to 45, pages = twice).single()
        assertEquals(45, p.visible)
        assertEquals(0, p.remaining)
    }

    @Test
    fun `expanding one section leaves the others at one page`() {
        val plans = plan("a" to 30, "b" to 30, pages = mapOf("b" to 2))
        assertEquals(listOf(20, 30), plans.map { it.visible })
    }

    @Test
    fun `rows across the widget never exceed the cap`() {
        val plans = plan("a" to 500, pages = mapOf("a" to 10))
        assertEquals(WidgetPaging.MAX_TOTAL_ROWS, plans.single().visible)
        assertEquals(400, plans.single().remaining)
        // Past the cap only "view all" is offered.
        assertFalse(plans.single().canLoadMore)
    }

    @Test
    fun `the cap is shared across sections in order`() {
        val plans = plan("a" to 60, "b" to 60, "c" to 60, pages = mapOf("a" to 3, "b" to 3))
        assertEquals(listOf(60, 40, 0), plans.map { it.visible })
        assertEquals(listOf(false, false, false), plans.map { it.canLoadMore })
        assertEquals(listOf(0, 20, 60), plans.map { it.remaining })
    }

    @Test
    fun `more is still offered while some budget is left`() {
        val plans = plan("a" to 70, "b" to 30, pages = mapOf("a" to 4))
        assertEquals(listOf(70, 20), plans.map { it.visible })
        assertTrue(plans[1].canLoadMore)
        // The budget runs out exactly where b would have stopped anyway.
        val spent = plan("a" to 90, "b" to 30, pages = mapOf("a" to 5))
        assertEquals(listOf(90, 10), spent.map { it.visible })
        assertFalse(spent[1].canLoadMore)
    }

    @Test
    fun `page counts round-trip and garbage reads as nothing expanded`() {
        val pages = mapOf("Today · Jun 11" to 3, "results" to 2)
        assertEquals(pages, WidgetPaging.decode(WidgetPaging.encode(pages)))
        assertEquals(emptyMap<String, Int>(), WidgetPaging.decode("not json"))
        assertEquals(emptyMap<String, Int>(), WidgetPaging.decode(null))
    }

    @Test
    fun `expansion only applies to the view it was made in`() {
        val stored = WidgetPaging.encode(mapOf("results" to 3))
        assertEquals(mapOf("results" to 3), WidgetPaging.pagesFor("s-work", "s-work", stored))
        assertEquals(emptyMap<String, Int>(), WidgetPaging.pagesFor("", "s-work", stored))
        assertEquals(emptyMap<String, Int>(), WidgetPaging.pagesFor("s-week", null, stored))
    }
}
