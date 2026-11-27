package com.rrajath.grove.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrgTaskParserMetadataTest {

    @Test
    fun `parses combined planning line and properties drawer`() {
        val org = """
            * TODO 看牙医 :health:
            SCHEDULED: <2026-12-01 Tue 09:30> DEADLINE: <2026-12-03 Thu>
            :PROPERTIES:
            :LOCATION: 口腔医院 3 楼
            :END:
        """.trimIndent()
        val plan = OrgTaskParser.parse(org, allowSingle = true)
        assertEquals("看牙医", plan.title)
        assertEquals(listOf("health"), plan.tags)
        assertEquals("<2026-12-01 Tue 09:30>", plan.scheduledStamp)
        assertEquals("<2026-12-03 Thu>", plan.deadlineStamp)
        assertEquals("口腔医院 3 楼", plan.location)
        assertNull(plan.priority)
    }

    @Test
    fun `subtask metadata stays on its own task`() {
        val org = """
            * TODO 旅行准备
            ** TODO 订机票
            SCHEDULED: <2026-12-10 Thu>
            :PROPERTIES:
            :LOCATION: 机场
            :END:
            *** 比价
            ** TODO 打包
        """.trimIndent()
        val plan = OrgTaskParser.parse(org, allowSingle = true)
        assertEquals(2, plan.tasks.size)
        assertEquals("<2026-12-10 Thu>", plan.tasks[0].scheduledStamp)
        assertEquals("机场", plan.tasks[0].location)
        assertEquals(1, plan.tasks[0].checkpoints.size)
        assertNull(plan.tasks[1].scheduledStamp)
        assertNull(plan.tasks[1].location)
    }

    @Test
    fun `sibling top-level trees stay separate`() {
        val org = """
            * TODO 去打疫苗 :health:
            SCHEDULED: <2026-11-25 Wed 14:30>

            * TODO 安装 Bloom 到主力手机
            ** TODO 备份旧机数据
            ** TODO 安装并登录
        """.trimIndent()

        val plans = OrgTaskParser.parseMany(org, allowSingle = true)
        assertEquals(2, plans.size)
        assertEquals("去打疫苗", plans[0].title)
        assertEquals("<2026-11-25 Wed 14:30>", plans[0].scheduledStamp)
        assertTrue(plans[0].tasks.isEmpty())
        assertEquals("安装 Bloom 到主力手机", plans[1].title)
        assertEquals(listOf("备份旧机数据", "安装并登录"), plans[1].tasks.map { it.title })
        assertNull(plans[1].scheduledStamp)

        // The wizard keeps its single-tree contract by taking the first tree.
        assertEquals("去打疫苗", OrgTaskParser.parse(org, allowSingle = true).title)
    }

    @Test
    fun `allowSingle gates a top-only tree`() {
        val single = "* TODO 买牛奶\n"
        assertEquals("买牛奶", OrgTaskParser.parse(single, allowSingle = true).title)
        try {
            OrgTaskParser.parse(single)
            fail("expected AiException for wizard's strict parser")
        } catch (error: AiException) {
            assertEquals(true, error.message!!.contains("org task tree"))
        }
    }
}
