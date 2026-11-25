package com.rrajath.grove.ai

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrgTaskParserRendererTest {

    private val sample = """
        ```org
        * TODO 搭建CI流程 :planning: @computer
          DEADLINE: <2026-12-02 Wed>
        ** TODO 需求澄清与范围 :planning:
        *** 与干系人确认目标与成果边界
        *** 记录依赖与风险
        ** TODO 实施与联调
          DEADLINE: <2026-11-30 Mon>
        *** 逐步执行并记录变更
        *** 完成联调与功能验证
        ```
        """.trimIndent()

    @Test
    fun `parses fenced org output into a plan`() {
        val plan = OrgTaskParser.parse(sample)
        assertEquals("搭建CI流程", plan.title)
        assertEquals(listOf("planning", "@computer"), plan.tags)
        assertEquals("<2026-12-02 Wed>", plan.deadlineStamp)
        assertEquals(2, plan.tasks.size)
        assertEquals("需求澄清与范围", plan.tasks[0].title)
        assertEquals(2, plan.tasks[0].checkpoints.size)
        assertEquals("<2026-11-30 Mon>", plan.tasks[1].deadlineStamp)
    }

    @Test
    fun `renders one canonical trailing tag group and preserves levels`() {
        val plan = AiTagPolicy.enrich(
            OrgTaskParser.parse(sample),
            "搭建CI流程",
            LocalDate.of(2026, 11, 25),
        )
        val org = OrgTaskRenderer.render(plan)
        val lines = org.lines()
        assertTrue(lines.first().startsWith("* TODO 搭建CI流程 "))
        assertTrue(lines.first().endsWith(":planning:@computer:"))
        assertTrue(lines.any { it == "DEADLINE: <2026-12-02 Wed>" })
        assertTrue(lines.any { it.startsWith("** TODO 实施与联调") })
        assertTrue(lines.any { it.startsWith("*** 完成联调与功能验证") })
        assertTrue(org.endsWith("\n"))

        // Round-trip: the parser sees exactly the levels and tags we emitted.
        val reparsed = OrgTaskParser.parse(org)
        assertEquals(2, reparsed.tasks.size)
        assertTrue(reparsed.tags.contains("@computer"))
    }

    @Test
    fun `a reply without a task tree is rejected`() {
        try {
            OrgTaskParser.parse("Here is a summary of the work, without any org headings.")
            fail("expected AiException")
        } catch (error: AiException) {
            assertTrue(error.message!!.contains("org task tree"))
        }
    }
}
