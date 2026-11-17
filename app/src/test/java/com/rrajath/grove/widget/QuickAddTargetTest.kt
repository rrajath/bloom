package com.rrajath.grove.widget

import com.rrajath.grove.settings.GroveSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickAddTargetTest {

    @Test
    fun `unset target follows the share target`() {
        val target = QuickAddTarget.from(GroveSettings(shareTargetFile = "links"))
        assertEquals(QuickAddTarget("links.org", emptyList()), target)
        assertEquals("links", target.label)
    }

    @Test
    fun `unset target follows only the share target's file, not its heading`() {
        val settings = GroveSettings(shareTargetFile = "inbox.org", shareTargetHeadingPath = "Projects/Reading")
        val target = QuickAddTarget.from(settings)
        assertEquals(QuickAddTarget("inbox.org", emptyList()), target)
        assertEquals("inbox", target.label)
    }

    @Test
    fun `set target ignores the share target`() {
        val settings = GroveSettings(
            shareTargetFile = "links.org",
            quickAddTargetFile = "tasks.org",
            quickAddHeadingPath = "Work/Inbox",
        )
        val target = QuickAddTarget.from(settings)
        assertEquals(QuickAddTarget("tasks.org", listOf("Work", "Inbox")), target)
        assertEquals("tasks › Work › Inbox", target.label)
    }

    @Test
    fun `file-level target has no heading path`() {
        val target = QuickAddTarget.from(GroveSettings(quickAddTargetFile = "tasks.org"))
        assertEquals(emptyList<String>(), target.headingPath)
        assertEquals("tasks", target.label)
    }
}
