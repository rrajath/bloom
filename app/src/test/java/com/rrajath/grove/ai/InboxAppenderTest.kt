package com.rrajath.grove.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InboxAppenderTest {

    private val entry = """
        * TODO 搭建CI流程 :planning:@computer:
        ** TODO 需求澄清与范围
        *** 与干系人确认目标
        """.trimIndent()

    @Test
    fun `creates a new inbox with just the subtree`() {
        assertEquals("inbox.org", InboxAppender.TARGET_FILE)
        assertEquals(entry + "\n", InboxAppender.append("", entry))
    }

    @Test
    fun `appends after exactly one blank line and keeps earlier content`() {
        val existing = "* TODO 旧任务\n** TODO 旧子任务\n"
        val result = InboxAppender.append(existing, entry)
        assertTrue(result.startsWith("* TODO 旧任务\n** TODO 旧子任务\n\n"))
        assertTrue(result.endsWith(entry + "\n"))
        assertEquals(2, result.split("\n\n").size)
    }

    @Test
    fun `normalizes CRLF input so the vault can restore its own convention`() {
        val existing = "* TODO 旧任务\r\n\r\n"
        val result = InboxAppender.append(existing, entry)
        assertTrue(!result.contains("\r"))
        assertEquals("* TODO 旧任务\n\n" + entry + "\n", result)
    }

    @Test
    fun `blank entries are rejected`() {
        try {
            InboxAppender.append("", "   \n\n")
            fail("expected AiException")
        } catch (error: AiException) {
            assertTrue(error.message!!.isNotBlank())
        }
    }
}
