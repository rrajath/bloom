// i18n: org syntax written to disk and internal parse errors, never shown as UI text
package com.rrajath.grove.ai

/**
 * Turns the model's plain-org output into a [DecomposePlan].
 *
 * Deliberately forgiving: models wrap output in code fences, add numbering,
 * emit extra planning lines or forget a checkpoint. Anything that isn't a
 * heading, a checkpoint or planning metadata is ignored; the strict shape is
 * then restored by [AiTagPolicy.enrich] / [AiTagPolicy.enrichQuick] and
 * [OrgTaskRenderer.render].
 *
 * Planning metadata follows Bloom's own `OrgParser`: SCHEDULED / DEADLINE /
 * CLOSED share one line immediately under the headline, and a `:PROPERTIES:`
 * drawer may carry `:LOCATION:`.
 */
object OrgTaskParser {

    private val HEADING = Regex("""^(\*+)\s+(.*)$""")
    /** One planning part; a line may carry several, in any order. */
    private val PLANNING_PART = Regex("""(SCHEDULED|DEADLINE|CLOSED):\s*(<[^>]+>|\[[^\]]+\])""")
    private val PLANNING_LINE = Regex("""^\s*(?:SCHEDULED|DEADLINE|CLOSED):""")
    private val PRIORITY = Regex("""^\[#([A-Za-z])\]\s*""")
    private val KEYWORD = Regex(
        """^(?:TODO|IN-PROGRESS|DONE|CANCELLED|NEXT|WAITING)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_MARKER = Regex("""^(?:[-*•]|\d+[.)、])\s*""")
    private val PROPERTIES_START = Regex("""^\s*:PROPERTIES:\s*$""", RegexOption.IGNORE_CASE)
    private val PROPERTIES_END = Regex("""^\s*:END:\s*$""", RegexOption.IGNORE_CASE)
    private val LOCATION = Regex("""^\s*:LOCATION:\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)

    private class TaskBuilder(
        var title: String,
        var tags: List<String> = emptyList(),
        var deadlineStamp: String? = null,
        var scheduledStamp: String? = null,
        var location: String? = null,
        val checkpoints: MutableList<String> = mutableListOf(),
    )

    /**
     * @param allowSingle when true, a reply carrying only the top TODO is a
     *   valid plan (one-shot natural-language capture); the wizard keeps the
     *   strict default and still rejects a reply with no task tree.
     */
    fun parse(text: String, allowSingle: Boolean = false): DecomposePlan {
        var title = ""
        var topTags: List<String> = emptyList()
        var priority: Char? = null
        var topDeadline: String? = null
        var topScheduled: String? = null
        var topLocation: String? = null
        val tasks = mutableListOf<TaskBuilder>()
        var current: TaskBuilder? = null
        var inProperties = false

        for (raw in AiResponseParsers.stripFences(text).lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue

            if (PROPERTIES_START.matches(line)) {
                inProperties = true
                continue
            }
            if (PROPERTIES_END.matches(line)) {
                inProperties = false
                continue
            }
            if (inProperties) {
                LOCATION.find(line)?.let { match ->
                    val value = match.groupValues[1].trim()
                    if (value.isNotEmpty()) {
                        if (current == null) topLocation = value else current.location = value
                    }
                }
                continue
            }

            if (PLANNING_LINE.containsMatchIn(line)) {
                PLANNING_PART.findAll(line).forEach { match ->
                    val stamp = match.groupValues[2]
                    when (match.groupValues[1].uppercase()) {
                        "DEADLINE" -> if (current == null) topDeadline = stamp else current.deadlineStamp = stamp
                        "SCHEDULED" -> if (current == null) topScheduled = stamp else current.scheduledStamp = stamp
                    }
                }
                continue
            }

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
                    current = null
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

        if (title.isBlank() || (!allowSingle && tasks.isEmpty())) {
            throw AiException("The model did not return an org task tree")
        }
        return DecomposePlan(
            title = title,
            tags = topTags,
            priority = priority,
            deadlineStamp = topDeadline,
            scheduledStamp = topScheduled,
            location = topLocation,
            tasks = tasks.map {
                DecomposeTask(
                    title = it.title,
                    tags = it.tags,
                    deadlineStamp = it.deadlineStamp,
                    scheduledStamp = it.scheduledStamp,
                    location = it.location,
                    checkpoints = it.checkpoints.toList(),
                )
            },
        )
    }
}
