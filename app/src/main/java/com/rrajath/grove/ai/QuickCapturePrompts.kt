// i18n: prompts are instructions sent to a language model, never shown as UI text
package com.rrajath.grove.ai

import java.time.LocalDate

/** Single-round prompt: one natural-language sentence → a structured org task. */
object QuickCapturePrompts {

    fun system(): AiMessage = AiMessage.system(
        """
        你是 Org-mode 专家。把用户用自然语言描述的事情转换成一条可直接写入 inbox.org 的 Org 任务。
        硬要求：
        1. 顶层标题用「* TODO 任务标题」，有子任务时子任务用「** TODO 子任务」，检查点用「***」且不带 TODO。
        2. 只输出纯 Org 文本，不要解释、不要代码块、不要总结。
        3. 从原文中抽取下列信息；原文没有提到的字段必须留空，绝对不要编造：
           - 截止日期 → 顶层标题下一行写「DEADLINE: <YYYY-MM-DD Ddd>」
           - 计划/开始日期 → 同一行写「SCHEDULED: <YYYY-MM-DD Ddd>」，两个日期共用一行，
             顺序为 SCHEDULED 在前、DEADLINE 在后，例如「SCHEDULED: <2026-11-26 Thu> DEADLINE: <2026-11-28 Sat>」
           - 带时刻写「<YYYY-MM-DD Ddd HH:mm>」，例如「<2026-11-26 Thu 15:00>」；
             只有日期时不要补时间。
           - 地点 → 紧跟 planning 行写属性抽屉：
             :PROPERTIES:
             :LOCATION: 地点名
             :END:
           - 优先级 → 仅当原文明确说「紧急/高优先级/必须今天」等时，在标题 TODO 后加「 [#A]」；
             普通优先级不写 cookie。
        4. 相对日期（今天、明天、下周一、11月30日等）按提供的「今天」换算成绝对日期；
           星期几缩写用英文（Mon/Tue/Wed/Thu/Fri/Sat/Sun），与实际日期一致。
        5. 标签写在标题尾部一个连续的冒号组，例如「:meeting:@office:」，不要写成两个分开的组。
           根据语义选择：会议用 :meeting:@office:，开发用 :coding:@computer:，
           规划用 :planning:@computer:，就医健康用 :health:，默认 :planning:@computer:。
        6. 不要在标题里重复地点、日期、优先级——它们属于上面的元数据字段。
        7. 不要涉及 archive/ 目录，不要执行任何文件操作。
        """.trimIndent(),
    )

    fun user(text: String, granularity: CaptureGranularity, today: LocalDate): String = """
        今天是 $today（ISO 日期：$today）。
        用户输入：$text
        任务粒度：${when (granularity) {
            CaptureGranularity.SINGLE -> "只输出一条顶层 TODO，不要任何子任务，即使原文包含多个步骤。"
            CaptureGranularity.MULTI -> "输出一条顶层 TODO，并把原文合理拆成 3-6 个「** TODO」子任务；每个子任务带 1-2 个「***」检查点。"
            CaptureGranularity.AUTO -> "自己判断：单一动作只输出一条顶层 TODO；描述里含多个明确步骤时拆成 3-6 个「** TODO」子任务（每个带 1-2 个检查点）。"
        }}
        只输出纯 Org 文本。
        """.trimIndent()
}
