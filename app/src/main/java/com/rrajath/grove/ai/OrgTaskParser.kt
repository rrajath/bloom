// i18n: org syntax written to disk and internal parse errors, never shown as UI text
package com.rrajath.grove.ai

/**
 * Turns the model's plain-org output into [DecomposePlan]s.
 *
 * A reply may carry several top-level `* TODO` trees — one-shot capture sends
 * every line of a multi-item note through the same round, and each item comes
 * back as its own tree. [parse] keeps the wizard's single-tree contract by
 * returning the first one; [parseMany] is what capture uses.
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

    private class PlanBuilder(
        var title: String,
        var tags: List<String> = emptyList(),
        var priority: Char? = null,
        var deadlineStamp: String? = null,
        var scheduledStamp: String? = null,
        var location: String? = null,
        val tasks: MutableList<TaskBuilder> = mutableListOf(),
    ) {
        fun build(): DecomposePlan = DecomposePlan(
            title = title,
            tags = tags,
            priority = priority,
            deadlineStamp = deadlineStamp,
            scheduledStamp = scheduledStamp,
            location = location,
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

    /**
     * The first tree in the reply. The wizard asks for exactly one, so this
     * keeps its single-plan contract.
     *
     * @param allowSingle when true, a reply carrying only the top TODO is a
     *   valid plan (one-shot natural-language capture); the wizard keeps the
     *   strict default and still rejects a reply with no task tree.
     */
    fun parse(text: String, allowSingle: Boolean = false): DecomposePlan =
        parseMany(text, allowSingle).first()

    /** Every top-level tree in the reply, in the order the model emitted them. */
    fun parseMany(text: String, allowSingle: Boolean = false): List<DecomposePlan> {
        val plans = mutableListOf<PlanBuilder>()
        var plan: PlanBuilder? = null
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
                        if (current == null) plan?.location = value else current.location = value
                    }
                }
                continue
            }

            if (PLANNING_LINE.containsMatchIn(line)) {
                PLANNING_PART.findAll(line).forEach { match ->
                    val stamp = match.groupValues[2]
                    when (match.groupValues[1].uppercase()) {
                        "DEADLINE" -> if (current == null) plan?.deadlineStamp = stamp else current.deadlineStamp = stamp
                        "SCHEDULED" -> if (current == null) plan?.scheduledStamp = stamp else current.scheduledStamp = stamp
                    }
                }
                continue
            }

            val heading = HEADING.find(line) ?: continue
            val level = heading.groupValues[1].length
            var content = KEYWORD.replace(heading.groupValues[2].trim(), "").trim()

            when (level) {
                1 -> {
                    var priority: Char? = null
                    PRIORITY.find(content)?.let { match ->
                        priority = match.groupValues[1].first().uppercaseChar()
                        content = content.removeRange(match.range).trim()
                    }
                    val (bare, tags) = AiTagPolicy.extractTrailingTags(content)
                    plan = PlanBuilder(bare.trim(), tags, priority).also { plans += it }
                    current = null
                }
                2 -> {
                    val (bare, tags) = AiTagPolicy.extractTrailingTags(content)
                    val task = TaskBuilder(bare.trim(), tags)
                    plan?.tasks?.add(task)
                    current = task
                }
                else -> {
                    val checkpoint = LEADING_MARKER.replace(content.trim(), "").trim()
                    if (checkpoint.isNotEmpty()) current?.checkpoints?.add(checkpoint)
                }
            }
        }

        val built = plans.filter { it.title.isNotBlank() }.map { it.build() }
        if (built.isEmpty() || (!allowSingle && built.first().tasks.isEmpty())) {
            throw AiException("The model did not return an org task tree")
        }
        return built
    }
}
