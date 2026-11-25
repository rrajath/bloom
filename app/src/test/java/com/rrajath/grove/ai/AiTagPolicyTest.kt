package com.rrajath.grove.ai

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTagPolicyTest {

    @Test
    fun `extracts both pseudo-org and canonical trailing tag groups`() {
        val pseudo = AiTagPolicy.extractTrailingTags("搭建CI流程 :planning: @computer")
        assertEquals("搭建CI流程", pseudo.first)
        assertEquals(listOf("planning", "@computer"), pseudo.second)

        val canonical = AiTagPolicy.extractTrailingTags("搭建CI流程 :planning:@computer:")
        assertEquals("搭建CI流程", canonical.first)
        assertEquals(listOf("planning", "@computer"), canonical.second)
    }

    @Test
    fun `infers top-level categories from title keywords`() {
        assertEquals(listOf("coding", "@computer"), AiTagPolicy.inferTopLevel("修复 Qt 无边框窗口"))
        assertEquals(listOf("meeting", "@office"), AiTagPolicy.inferTopLevel("季度周会"))
        assertEquals(listOf("planning", "@computer"), AiTagPolicy.inferTopLevel("下月 OKR 规划"))
        assertEquals(listOf("health"), AiTagPolicy.inferTopLevel("预约体检"))
        assertEquals(listOf("planning", "@computer"), AiTagPolicy.inferTopLevel("搭建CI流程"))
    }

    @Test
    fun `subtasks inherit tags and add their own category`() {
        val inherited = listOf("planning", "@computer")
        val tags = AiTagPolicy.inferSubtask("实施与联调", inherited)
        assertTrue(tags.contains("planning"))
        assertTrue(tags.contains("coding"))
        assertTrue(tags.contains("@computer"))
        assertTrue(AiTagPolicy.inferSubtask("评审与验收", inherited).contains("review"))
        assertTrue(AiTagPolicy.inferSubtask("整理文档", inherited).contains("writing"))
    }

    @Test
    fun `urgent titles get priority A and a two-day deadline`() {
        assertTrue(AiTagPolicy.isUrgent("紧急修复登录阻塞"))
        assertFalse(AiTagPolicy.isUrgent("搭建CI流程"))
        val today = LocalDate.of(2026, 11, 25)
        val plan = DecomposePlan(
            title = "紧急修复登录阻塞",
            tasks = listOf(DecomposeTask("实施与修复")),
        )
        val enriched = AiTagPolicy.enrich(plan, plan.title, today)
        assertEquals('A', enriched.priority)
        assertEquals("<2026-11-27 Fri>", enriched.deadlineStamp)
        assertEquals("<2026-11-26 Thu>", enriched.tasks.first().deadlineStamp)
    }

    @Test
    fun `enrich preserves model output and fills in the gaps`() {
        val today = LocalDate.of(2026, 11, 25)
        val plan = DecomposePlan(
            title = "搭建CI流程",
            tags = listOf("p1"),
            deadlineStamp = "<2026-12-20 Sun>",
            tasks = listOf(
                DecomposeTask(title = "需求澄清与范围", checkpoints = listOf("确认边界")),
                DecomposeTask(title = "环境与仓库准备"),
            ),
        )
        val enriched = AiTagPolicy.enrich(plan, plan.title, today)
        assertEquals("<2026-12-20 Sun>", enriched.deadlineStamp)
        assertNull(enriched.priority)
        assertTrue(enriched.tags.contains("p1"))
        assertTrue(enriched.tags.contains("planning"))
        assertEquals("确认边界", enriched.tasks[0].checkpoints.single())
        assertEquals(2, enriched.tasks[1].checkpoints.size)
        assertEquals("coding", enriched.tasks[1].tags.first())
        assertTrue(enriched.tasks[1].tags.contains("@computer"))
    }

    @Test
    fun `deadline uses the org timestamp shape`() {
        assertEquals("<2026-12-02 Wed>", AiTagPolicy.formatDeadline(LocalDate.of(2026, 12, 2)))
    }
}
