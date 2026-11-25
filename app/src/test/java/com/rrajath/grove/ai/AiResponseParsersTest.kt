package com.rrajath.grove.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseParsersTest {

    @Test
    fun `strips code fences`() {
        assertEquals(
            "* TODO 任务\n** TODO 子任务",
            AiResponseParsers.stripFences("```org\n* TODO 任务\n** TODO 子任务\n```\n"),
        )
    }

    @Test
    fun `questions keep at most five and drop numbering`() {
        val reply = """
            以下是问题：
            1. 目标用户是谁？
            2. 成功标准是什么？
            3. 有哪些约束？
            4. 依赖哪些系统？
            5. 风险在哪？
            6. 还有补充吗？
            """.trimIndent()
        val questions = AiResponseParsers.questions(reply)
        assertEquals(5, questions.size)
        assertEquals("目标用户是谁？", questions.first())
        assertTrue(questions.none { it.startsWith("1.") })
    }

    @Test
    fun `approaches split on numbered blocks with trade-off details`() {
        val reply = """
            1. 自建脚本
               成本低，但维护负担重，需要自己处理重试。
            2. 托管 CI
               省事，但依赖厂商，迁移成本高。

            3. 混合方案
               折中，先托管后自建。
            """.trimIndent()
        val approaches = AiResponseParsers.approaches(reply)
        assertEquals(3, approaches.size)
        assertEquals("自建脚本", approaches[0].title)
        assertTrue(approaches[0].detail.contains("维护负担重"))
        assertEquals("混合方案", approaches[2].title)
    }

    @Test
    fun `design blocks use the explicit marker, with a paragraph fallback`() {
        val marked = "第一块设计内容\n<<<BLOCK>>>\n第二块设计内容"
        assertEquals(2, AiResponseParsers.blocks(marked).size)
        assertEquals(2, AiResponseParsers.blocks("第一段\n\n第二段").size)
        assertEquals(1, AiResponseParsers.blocks("只有一段").size)
    }
}
