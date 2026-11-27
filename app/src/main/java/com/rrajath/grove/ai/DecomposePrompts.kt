// i18n: prompts are instructions sent to a language model, never shown as UI text
package com.rrajath.grove.ai

import java.time.LocalDate

/**
 * Prompt construction for the four wizard stages. The conversation transcript
 * is accumulated by [DecomposeEngine], so each stage sees everything the user
 * already confirmed without the app having to restate it in full.
 *
 * The keep-it-plain-text format is intentional: see `AiResponseParsers`.
 */
object DecomposePrompts {

    fun system(): AiMessage = AiMessage.system(
        """
        你是 Org-mode 专家。把用户给出的任务标题拆解为详细、可执行的 Org-mode 子任务清单。
        硬要求：
        1. 顶层用「* TODO」，子任务用「** TODO」，检查点用「***」且不带 TODO。
        2. 每个子任务下 2-3 个检查点；子任务按依赖顺序（澄清→环境→设计→实施→收尾）排序。
        3. 所有标签写成标题尾部一个连续的冒号组，例如「:planning:@computer:」；
           不要写成「:planning: @computer」，那样无法被 Org 解析器识别。
        4. DEADLINE 写成「  DEADLINE: <YYYY-MM-DD Ddd>」，紧跟标题行下一行。
        5. 最终清单阶段只输出纯 Org 文本，不要解释、不要总结、不要额外说明。
        6. 只生成清单文本，不执行任何文件操作；不要涉及 archive/ 目录。
        """.trimIndent(),
    )

    fun clarifyUser(title: String): String = """
        任务标题：$title
        请针对这个任务提出 3-5 个关键问题，覆盖目标用户、成功标准、约束条件、依赖与风险。
        每行一个问题，可用「1. 」编号。不要输出其它内容。
        """.trimIndent()

    fun compareUser(title: String, qa: List<Qa>): String = """
        任务标题：$title
        已确认的需求：
        ${formatQa(qa)}
        请给出 2-3 种实现路径。每条以「1. 路径名」开头，随后用 1-2 句说明 trade-off
        （成本、风险、可逆性、工期）。路径之间空一行。不要输出其它内容。
        """.trimIndent()

    fun designUser(title: String, qa: List<Qa>, approach: Approach): String = """
        任务标题：$title
        已确认的需求：
        ${formatQa(qa)}
        用户选择的路径：${approach.title}
        ${approach.detail}
        请分块输出详细设计，每块 200-300 字，块与块之间单独一行写「<<<BLOCK>>>」。
        不要输出该分隔符以外的其它标记，不要输出总结。
        """.trimIndent()

    fun generateUser(
        title: String,
        qa: List<Qa>,
        approach: Approach,
        blocks: List<String>,
        today: LocalDate,
    ): String = """
        任务标题：$title
        今天是 $today。
        已确认的需求：
        ${formatQa(qa)}
        用户选择的路径：${approach.title}
        最终设计：
        ${blocks.joinToString("\n\n")}
        请把最终设计拆解为 Org-mode 任务清单：
        - 顶层一行「* TODO 任务标题 :类别标签:@上下文标签:」，可带 [#A] 与 DEADLINE。
        - 若干（建议 5 个）「** TODO 子任务」，每个带 2-3 个「*** 检查点」。
        - 子任务继承顶层标签，并按语义补充 :planning: / :coding: @computer /
          :writing: @computer / :review:，全部合并进同一个尾部冒号组。
        - 给出合理的 DEADLINE 建议（今天起 7 天内），按优先级与依赖顺序排列。
        - 只输出纯 Org 文本，不要解释。
        """.trimIndent()

    private fun formatQa(qa: List<Qa>): String = qa.mapIndexed { index, item ->
        "${index + 1}. ${item.question} → ${item.answer.ifBlank { "未填写" }}"
    }.joinToString("\n")
}
