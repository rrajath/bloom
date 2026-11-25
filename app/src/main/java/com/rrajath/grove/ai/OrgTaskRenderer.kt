// i18n: org syntax written to disk, never shown as UI text
package com.rrajath.grove.ai

/** Renders a [DecomposePlan] as a single `* / ** / ***` org subtree. */
object OrgTaskRenderer {

    /**
     * Emits the metadata in the shape Bloom's own `OrgParser` reads:
     * SCHEDULED and DEADLINE share one planning line directly under the
     * headline; LOCATION lives in a `:PROPERTIES:` drawer right after it.
     */
    fun render(plan: DecomposePlan): String {
        val lines = mutableListOf<String>()
        lines += headline(1, "TODO", plan.priority, plan.title, plan.tags)
        planning(plan.scheduledStamp, plan.deadlineStamp)?.let { lines += it }
        properties(plan.location)?.let { lines += it }
        for (task in plan.tasks) {
            lines += headline(2, "TODO", null, task.title, task.tags)
            planning(task.scheduledStamp, task.deadlineStamp)?.let { lines += it }
            properties(task.location)?.let { lines += it }
            task.checkpoints.forEach { lines += headline(3, null, null, it, emptyList()) }
        }
        return lines.joinToString("\n") + "\n"
    }

    private fun planning(scheduled: String?, deadline: String?): String? {
        val parts = buildList {
            scheduled?.let { add("SCHEDULED: $it") }
            deadline?.let { add("DEADLINE: $it") }
        }
        return parts.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun properties(location: String?): List<String>? {
        val value = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return listOf(":PROPERTIES:", ":LOCATION: $value", ":END:")
    }

    /**
     * Mirrors `OrgMutations.headlineLine`, but keeps the AI package free of any
     * dependency on the (Android-flavoured) org mutations module. Tags are
     * emitted as one canonical trailing group: `:planning:@computer:`.
     */
    private fun headline(
        level: Int,
        keyword: String?,
        priority: Char?,
        title: String,
        tags: List<String>,
    ): String = buildString {
        append("*".repeat(level))
        keyword?.let { append(' ').append(it) }
        priority?.let { append(" [#").append(it).append(']') }
        if (title.isNotEmpty()) append(' ').append(title)
        if (tags.isNotEmpty()) {
            append("  ").append(tags.joinToString(":", prefix = ":", postfix = ":"))
        }
    }
}

/**
 * Appends a generated subtree to `inbox.org` after exactly one blank line.
 * Pure text splice: untouched lines stay byte-for-byte identical. Line endings
 * are `\n` above the vault boundary; `LineEndingFileStore` restores CRLF for
 * files that already use it.
 */
object InboxAppender {

    const val TARGET_FILE = "inbox.org"

    fun append(existing: String, entry: String): String {
        val block = entry.replace("\r\n", "\n").trim().trimEnd('\n')
        if (block.isBlank()) throw AiException("Nothing to insert into $TARGET_FILE")
        val base = existing.replace("\r\n", "\n").trimEnd('\n')
        return if (base.isEmpty()) block + "\n" else base + "\n\n" + block + "\n"
    }
}
