// i18n: org syntax written to disk and internal parse errors, never shown as UI text
package com.rrajath.grove.ai

/**
 * Turns the model's plain-org output into a [DecomposePlan].
 *
 * Deliberately forgiving: models wrap output in code fences, add numbering,
 * emit extra planning lines or forget a checkpoint. Anything that isn't a
 * heading, a checkpoint or a DEADLINE is ignored; the strict shape is then
 * restored by [AiTagPolicy.enrich] and [OrgTaskRenderer.render].
 */
object OrgTaskParser {

    private val HEADING = Regex("""^(\*+)\s+(.*)$""")
    private val DEADLINE = Regex("""^\s*DEADLINE:\s*(<[^>]+>|\[[^\]]+\])\s*$""")
    private val PRIORITY = Regex("""^\[#([A-Za-z])\]\s*""")
    private val KEYWORD = Regex(
        """^(?:TODO|IN-PROGRESS|DONE|CANCELLED|NEXT|WAITING)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val OTHER_PLANNING = Regex("""^\s*(?:SCHEDULED|CLOSED):""", RegexOption.IGNORE_CASE)
    private val LEADING_MARKER = Regex("""^(?:[-*•]|\d+[.)、])\s*""")

    private class TaskBuilder(
        var title: String,
        var tags: List<String> = emptyList(),
        var deadlineStamp: String? = null,
        val checkpoints: MutableList<String> = mutableListOf(),
    )

    fun parse(text: String): DecomposePlan {
        var title = ""
        var topTags: List<String> = emptyList()
        var priority: Char? = null
        var topDeadline: String? = null
        val tasks = mutableListOf<TaskBuilder>()
        var current: TaskBuilder? = null

        for (raw in AiResponseParsers.stripFences(text).lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue

            val deadline = DEADLINE.find(line)
            if (deadline != null) {
                if (current == null) topDeadline = deadline.groupValues[1]
                else current.deadlineStamp = deadline.groupValues[1]
                continue
            }
            if (OTHER_PLANNING.containsMatchIn(line)) continue
            val heading = HEADING.find(line) ?: continue
            val level = heading.groupValues[1].length
            var content = KEYWORD.replace(heading.groupValues[2].trim(), "").trim()

            when (level) {
                1 -> {
                    PRIORITY.find(content)?.let { match ->
                        priority = match.groupValues[1].first().uppercaseChar()
                        content = content.removeRange(match.range).trim()
                    }
                    val (bare, tags) = AiTagPolicy.extractTrailingTags(content)
                    title = bare.trim()
                    topTags = tags
                }
                2 -> {
                    val (bare, tags) = AiTagPolicy.extractTrailingTags(content)
                    current = TaskBuilder(bare.trim(), tags).also { tasks += it }
                }
                else -> {
                    val checkpoint = LEADING_MARKER.replace(content.trim(), "").trim()
                    if (checkpoint.isNotEmpty()) current?.checkpoints?.add(checkpoint)
                }
            }
        }

        if (title.isBlank() || tasks.isEmpty()) {
            throw AiException("The model did not return an org task tree")
        }
        return DecomposePlan(
            title = title,
            tags = topTags,
            priority = priority,
            deadlineStamp = topDeadline,
            tasks = tasks.map {
                DecomposeTask(
                    title = it.title,
                    tags = it.tags,
                    deadlineStamp = it.deadlineStamp,
                    checkpoints = it.checkpoints.toList(),
                )
            },
        )
    }
}
