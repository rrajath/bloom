// i18n: tag vocabulary and org syntax written to disk, never shown as UI text
package com.rrajath.grove.ai

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Deterministic half of the tag strategy in `docs/design-tag-system.md`.
 *
 * The model decides *what* the tasks are; this policy decides the tags, the
 * priority cookie and the DEADLINE suggestions, so a model that forgets the
 * format (or writes `:planning: @computer` as two groups) still produces a
 * checklist Bloom can parse. Explicit tags on the user's title are preserved.
 */
object AiTagPolicy {

    private val COLON_TAGS = Regex("""\s+(:[\p{L}\p{M}\p{N}_@#%-]+(?::[\p{L}\p{M}\p{N}_@#%-]+)*:)\s*$""")
    private val AT_TAG = Regex("""\s+(@[\p{L}\p{M}\p{N}_-]+)\s*$""")

    private val CATEGORY_ORDER = listOf("coding", "meeting", "planning", "health", "writing", "review", "p1")

    private val CODING = listOf(
        "C++", "Qt", "CMake", "构建", "编译", "UI", "窗口", "无边框", "QWindowKit",
        "framelesshelper", "实现", "修复", "重构", "依赖", "环境", "联调", "接口",
        "代码", "部署", "脚本", "测试", "数据库", "API", "SDK",
    )
    private val MEETING = listOf("会议", "周会", "评审准备", "沟通", "对齐", "汇报", "宣讲")
    private val PLANNING = listOf(
        "计划", "规划", "周报", "月报", "OKR", "复盘", "需求", "范围", "风险",
        "方案", "拆解", "设计", "里程碑", "调研",
    )
    private val HEALTH = listOf("健康", "医院", "体检", "预约", "就医", "疫苗", "挂号", "就诊", "打针")

    private val SUB_PLANNING = listOf("需求澄清", "澄清", "范围", "风险", "方案设计", "拆解", "调研", "评估")
    private val SUB_CODING = listOf("环境", "依赖", "构建", "实现", "联调", "修复", "重构", "部署", "开发", "编码", "迁移", "验证")
    private val SUB_WRITING = listOf("文档", "说明", "发布说明", "变更记录", "整理", "记录", "收尾", "归档")
    private val SUB_REVIEW = listOf("评审", "验收", "Review", "review", "检查")

    private val URGENT = listOf("紧急", "阻塞", "必须今天", "今天必须", "立刻", "马上", "asap", "urgent", "blocker")

    /** Peels `:planning:@computer:` / `@computer` off a headline, returning the bare title. */
    fun extractTrailingTags(raw: String): Pair<String, List<String>> {
        var rest = raw.trim()
        val found = mutableListOf<String>()
        repeat(4) {
            val colon = COLON_TAGS.find(rest)
            if (colon != null) {
                found.addAll(0, colon.groupValues[1].split(':').filter { it.isNotBlank() })
                rest = rest.removeRange(colon.range).trim()
                return@repeat
            }
            val at = AT_TAG.find(rest)
            if (at != null) {
                found.add(0, at.groupValues[1])
                rest = rest.removeRange(at.range).trim()
                return@repeat
            }
        }
        return rest to found.distinct()
    }

    fun inferTopLevel(title: String): List<String> = when {
        matches(title, CODING) -> listOf("coding", "@computer")
        matches(title, MEETING) -> listOf("meeting", "@office")
        matches(title, PLANNING) -> listOf("planning", "@computer")
        matches(title, HEALTH) -> listOf("health")
        else -> listOf("planning", "@computer")
    }

    fun inferSubtask(title: String, inherited: List<String>): List<String> {
        val additions = mutableListOf<String>()
        if (matches(title, SUB_PLANNING)) additions += "planning"
        if (matches(title, SUB_CODING)) additions += listOf("coding", "@computer")
        if (matches(title, SUB_WRITING)) additions += listOf("writing", "@computer")
        if (matches(title, SUB_REVIEW)) additions += "review"
        return orderTags(inherited + additions)
    }

    fun isUrgent(title: String): Boolean = URGENT.any { title.contains(it, ignoreCase = true) }

    /** `<YYYY-MM-DD Ddd>` in the same shape as `OrgTimestamp.format()`. */
    fun formatDeadline(date: LocalDate): String {
        val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(3)
        return "<$date $day>"
    }

    /** Adds missing tags, priority and DEADLINEs without overwriting model output. */
    fun enrich(plan: DecomposePlan, originalTitle: String, today: LocalDate): DecomposePlan {
        val topTags = orderTags(plan.tags + inferTopLevel(plan.title.ifBlank { originalTitle }))
        val urgent = isUrgent(originalTitle) || isUrgent(plan.title)
        val span = if (urgent) 2L else 7L
        val total = plan.tasks.size.coerceAtLeast(1)
        return plan.copy(
            tags = topTags,
            priority = plan.priority ?: if (urgent) 'A' else null,
            deadlineStamp = plan.deadlineStamp ?: formatDeadline(today.plusDays(span)),
            tasks = plan.tasks.mapIndexed { index, task ->
                val stamp = task.deadlineStamp
                    ?: formatDeadline(today.plusDays(1 + index.toLong() * (span - 1) / total))
                val checkpoints = task.checkpoints.ifEmpty { fallbackCheckpoints(task.title) }
                task.copy(
                    tags = orderTags(task.tags + inferSubtask(task.title, topTags)),
                    deadlineStamp = stamp,
                    checkpoints = checkpoints,
                )
            },
        )
    }

    /**
     * Lighter enrichment for one-shot natural-language capture. Unlike [enrich]
     * it never invents a DEADLINE or checkpoints: the user's words are the only
     * source of dates and structure. It only normalises tags and derives a
     * priority when the text is explicitly urgent.
     */
    fun enrichQuick(plan: DecomposePlan, originalText: String): DecomposePlan {
        val topTags = orderTags(plan.tags + inferTopLevel(plan.title.ifBlank { originalText }))
        val urgent = isUrgent(originalText) || isUrgent(plan.title)
        return plan.copy(
            tags = topTags,
            priority = plan.priority ?: if (urgent) 'A' else null,
            tasks = plan.tasks.map { task ->
                task.copy(tags = orderTags(task.tags + inferSubtask(task.title, topTags)))
            },
        )
    }

    private fun fallbackCheckpoints(title: String): List<String> =
        listOf("完成：$title", "验证：$title")

    private fun orderTags(tags: List<String>): List<String> {
        val categories = CATEGORY_ORDER.filter { it in tags }
        val contexts = tags.filter { it.startsWith("@") }.distinct()
        val others = tags.filter { it !in CATEGORY_ORDER && !it.startsWith("@") }.distinct()
        return (categories + contexts + others).distinct()
    }

    private fun matches(title: String, keywords: List<String>): Boolean =
        keywords.any { title.contains(it, ignoreCase = true) }
}
