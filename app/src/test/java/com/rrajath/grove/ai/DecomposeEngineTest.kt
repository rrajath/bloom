package com.rrajath.grove.ai

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecomposeEngineTest {

    private class ScriptedClient(private val replies: List<String>) : AiClient {
        val transcripts = mutableListOf<List<AiMessage>>()
        private var index = 0
        override suspend fun complete(messages: List<AiMessage>): String {
            transcripts += messages
            return replies[index++]
        }
        override suspend fun testConnection(): String = "OK"
    }

    private val replies = listOf(
        "1. 目标用户是谁？\n2. 成功标准是什么？\n3. 有哪些约束？",
        "1. 自建脚本\n   成本低，但维护负担重。\n\n2. 托管 CI\n   省事，但依赖厂商。",
        "第一块：目标与边界设计\n<<<BLOCK>>>\n第二块：实施与验收设计",
        """
        ```org
        * TODO 搭建CI流程 :planning: @computer
        ** TODO 需求澄清与范围
        *** 与干系人确认目标与成果边界
        *** 记录依赖与风险
        ** TODO 实施与联调
        *** 逐步执行并记录变更
        *** 完成联调与功能验证
        ```
        """.trimIndent(),
    )

    @Test
    fun `four gates build on the same conversation transcript`() = runBlocking {
        val client = ScriptedClient(replies)
        val engine = DecomposeEngine(client, LocalDate.of(2026, 11, 25))
        val title = "搭建CI流程"

        val questions = engine.clarify(title)
        assertEquals(3, questions.size)
        val qa = questions.map { Qa(it, "已确认") }

        val approaches = engine.compare(title, qa)
        assertEquals(2, approaches.size)

        val blocks = engine.design(title, qa, approaches.first())
        assertEquals(2, blocks.size)

        val plan = engine.generate(title, qa, approaches.first(), blocks)
        assertEquals("搭建CI流程", plan.title)
        assertEquals(2, plan.tasks.size)
        assertTrue(plan.tags.contains("@computer"))
        assertTrue(plan.deadlineStamp!!.startsWith("<2026-12-02"))

        val org = engine.render(plan)
        assertTrue(org.startsWith("* TODO 搭建CI流程"))
        assertTrue(org.contains("** TODO 实施与联调"))
        assertTrue(org.contains("*** 完成联调与功能验证"))
        assertTrue(org.contains(":planning:@computer:"))

        // Every stage saw the system prompt and all earlier turns.
        assertEquals(4, client.transcripts.size)
        assertEquals("system", client.transcripts.first().first().role)
        assertEquals(8, client.transcripts.last().size)
    }

    @Test
    fun `reset starts a fresh transcript`() = runBlocking {
        val client = ScriptedClient(replies + replies)
        val engine = DecomposeEngine(client, LocalDate.of(2026, 11, 25))
        engine.clarify("任务一")
        engine.reset()
        engine.clarify("任务二")
        assertEquals(2, client.transcripts.last().size)
    }
}
