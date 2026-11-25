package com.rrajath.grove.ai

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCaptureEngineTest {

    private class FakeClient(private val reply: String) : AiClient {
        var lastMessages: List<AiMessage> = emptyList()
        override suspend fun complete(messages: List<AiMessage>): String {
            lastMessages = messages
            return reply
        }
        override suspend fun testConnection(): String = "ok"
    }

    private val today = LocalDate.of(2026, 11, 25)

    @Test
    fun `single TODO with all metadata is parsed and rendered in Bloom shape`() = runBlocking {
        val reply = """
            * TODO [#A] 和张三评审 Q4 规划 :meeting:@office:
            SCHEDULED: <2026-11-27 Fri 15:00> DEADLINE: <2026-11-28 Sat>
            :PROPERTIES:
            :LOCATION: 302 会议室
            :END:
        """.trimIndent()
        val client = FakeClient(reply)
        val engine = QuickCaptureEngine(client, today)

        val plan = engine.capture("下周五 3 点和张三在 302 会议室评审 Q4 规划，高优先级", CaptureGranularity.AUTO)
            .single()

        assertEquals("和张三评审 Q4 规划", plan.title)
        assertEquals('A', plan.priority)
        assertEquals("<2026-11-27 Fri 15:00>", plan.scheduledStamp)
        assertEquals("<2026-11-28 Sat>", plan.deadlineStamp)
        assertEquals("302 会议室", plan.location)
        assertTrue(plan.tasks.isEmpty())
        assertTrue(plan.tags.contains("meeting"))
        assertTrue(plan.tags.contains("@office"))

        // The prompt carries the user's words, today and the requested shape.
        val userTurn = client.lastMessages.last().content
        assertTrue(userTurn.contains("今天是 2026-11-25"))
        assertTrue(userTurn.contains("302 会议室"))
        assertTrue(userTurn.contains("自己判断"))

        val org = engine.render(listOf(plan))
        val lines = org.lines()
        assertTrue(lines[0].startsWith("* TODO [#A] 和张三评审 Q4 规划 "))
        assertEquals("SCHEDULED: <2026-11-27 Fri 15:00> DEADLINE: <2026-11-28 Sat>", lines[1])
        assertEquals(listOf(":PROPERTIES:", ":LOCATION: 302 会议室", ":END:"), lines.drop(2).take(3))

        // Bloom's own parser reads exactly this shape.
        val doc = com.rrajath.grove.org.OrgParser.parse(org)
        val h = doc.headlines.single()
        assertEquals('A', h.priority)
        assertEquals("302 会议室", h.properties["LOCATION"])
        assertEquals("<2026-11-27 Fri 15:00>", h.planning.scheduled?.format())
        assertEquals("<2026-11-28 Sat>", h.planning.deadline?.format())
    }

    @Test
    fun `multi mode keeps subtasks and their metadata`() = runBlocking {
        val reply = """
            ```
            * TODO 准备季度评审 :planning:@computer:
            DEADLINE: <2026-12-02 Wed>
            ** TODO 收集数据 :coding:@computer:
            SCHEDULED: <2026-11-26 Thu>
            *** 拉取上季度报表
            *** 校验口径
            ** TODO 撰写材料 :writing:@computer:
            ```
        """.trimIndent()
        val engine = QuickCaptureEngine(FakeClient(reply), today)

        val plan = engine.capture("准备季度评审", CaptureGranularity.MULTI).single()

        assertEquals("准备季度评审", plan.title)
        assertEquals("<2026-12-02 Wed>", plan.deadlineStamp)
        assertEquals(2, plan.tasks.size)
        assertEquals("<2026-11-26 Thu>", plan.tasks[0].scheduledStamp)
        assertEquals(2, plan.tasks[0].checkpoints.size)
        assertTrue(plan.tasks[0].tags.contains("coding"))
        assertTrue(plan.tasks[1].tags.contains("writing"))
        // No invented priority or location.
        assertNull(plan.priority)
        assertNull(plan.location)
    }

    @Test
    fun `prompt switches wording with the requested granularity`() = runBlocking {
        val client = FakeClient("* TODO 散步\n")
        val engine = QuickCaptureEngine(client, today)

        engine.capture("散步", CaptureGranularity.SINGLE)
        assertTrue(client.lastMessages.last().content.contains("每条事情各输出一条顶层 TODO"))

        engine.capture("散步", CaptureGranularity.MULTI)
        assertTrue(client.lastMessages.last().content.contains("3-6 个「** TODO」子任务"))
    }

    @Test
    fun `no invented dates or checkpoints when the user gave none`() = runBlocking {
        val engine = QuickCaptureEngine(FakeClient("* TODO 买牛奶\n"), today)
        val plan = engine.capture("买牛奶", CaptureGranularity.AUTO).single()
        assertNull(plan.deadlineStamp)
        assertNull(plan.scheduledStamp)
        assertNull(plan.location)
        assertNull(plan.priority)
        assertTrue(plan.tasks.isEmpty())
    }

    @Test
    fun `two lines become two sibling trees instead of one`() = runBlocking {
        val reply = """
            * TODO [#A] 去打疫苗 :health:
            SCHEDULED: <2026-11-25 Wed 14:30>

            * TODO 安装 Bloom 到主力手机 :coding:@computer:
            ** TODO 确认可安装
            *** 检查存储空间
        """.trimIndent()
        val client = FakeClient(reply)
        val engine = QuickCaptureEngine(client, today)

        val plans = engine.capture(
            "下午2:30去打疫苗，高优先级\n一会就将bloom安装到我的主力手机，在真机上使用",
            CaptureGranularity.AUTO,
        )

        // The prompt must tell the model that several items stay several trees.
        assertTrue(client.lastMessages.first().content.contains("必须输出多棵顶层树"))
        assertEquals(2, plans.size)
        assertEquals("去打疫苗", plans[0].title)
        assertEquals("<2026-11-25 Wed 14:30>", plans[0].scheduledStamp)
        assertEquals('A', plans[0].priority)
        assertEquals("安装 Bloom 到主力手机", plans[1].title)
        // The first item's priority must not leak into the second tree.
        assertNull(plans[1].priority)
        assertEquals(1, plans[1].tasks.size)

        // Sibling trees land in inbox.org separated by exactly one blank line.
        val org = engine.render(plans)
        assertTrue(org.contains("\n\n* TODO 安装 Bloom 到主力手机"))
        assertTrue(org.endsWith("\n"))
        assertEquals(2, com.rrajath.grove.org.OrgParser.parse(org).headlines.count { it.level == 1 })
    }
}
