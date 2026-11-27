// i18n: model output parsing rules and internal errors, never shown as UI text
package com.rrajath.grove.ai

/**
 * Lightweight plain-text parsers for the first three wizard stages.
 *
 * The prompts ask for plain text rather than JSON: small/local models are much
 * more reliable at "one question per line" than at strict JSON, and every
 * parser degrades to a paragraph split instead of failing the whole wizard.
 */
object AiResponseParsers {

    private val LIST_MARKER = Regex("""^(?:\d+[.)、]|[-*•])\s*""")
    private val BLOCK_START = Regex("""^\s*(?:\d+[.)、]|#{1,3}\s|\*\*)\s*""")
    private val BLOCK_MARKER = "<<<BLOCK>>>"
    private val BLANK_LINE = Regex("""\n\s*\n""")

    fun stripFences(text: String): String =
        text.lines()
            .filterNot { it.trimStart().startsWith("```") || it.trimStart().startsWith("~~~") }
            .joinToString("\n")
            .trim()

    /** 3–5 clarifying questions, one per line, with numbering/bullets removed. */
    fun questions(reply: String): List<String> {
        val lines = stripFences(reply).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val picked = lines.mapNotNull { line ->
            val listed = LIST_MARKER.containsMatchIn(line)
            val cleaned = LIST_MARKER.replace(line, "").trim()
            cleaned.takeIf {
                it.isNotEmpty() && (listed || it.endsWith("?") || it.endsWith("？"))
            }
        }.distinct().take(5)
        val result = picked.ifEmpty {
            lines.filter { !it.endsWith(":") && !it.endsWith("：") }.take(3)
        }
        if (result.isEmpty()) throw AiException("The model did not return any questions")
        return result
    }

    /** 2–3 implementation paths, each with its trade-off in [Approach.detail]. */
    fun approaches(reply: String): List<Approach> {
        val chunks = mutableListOf<MutableList<String>>()
        for (line in stripFences(reply).lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (chunks.isNotEmpty()) chunks.last() += ""
                continue
            }
            if (BLOCK_START.containsMatchIn(line)) {
                chunks += mutableListOf(BLOCK_START.replace(line, "").trim())
            } else if (chunks.isNotEmpty()) {
                chunks.last() += trimmed
            }
        }
        val parsed = chunks.mapNotNull { chunk ->
            val content = chunk.filter { it.isNotEmpty() }
            if (content.isEmpty()) return@mapNotNull null
            val title = content.first().trim().trimEnd(':', '：').trim()
            val detail = content.drop(1).joinToString("\n").trim()
            Approach(title, detail)
        }
        val result = parsed.ifEmpty {
            stripFences(reply).split(BLANK_LINE)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { paragraph ->
                    val lines = paragraph.lines()
                    Approach(
                        title = lines.first().trim().trimEnd(':', '：'),
                        detail = lines.drop(1).joinToString("\n").trim(),
                    )
                }
        }.take(3)
        if (result.isEmpty()) throw AiException("The model did not return any implementation paths")
        return result
    }

    /** Design blocks separated by `<<<BLOCK>>>`, or by blank lines as a fallback. */
    fun blocks(reply: String): List<String> {
        val text = stripFences(reply)
        val parts = if (text.contains(BLOCK_MARKER)) {
            text.split(BLOCK_MARKER).map { it.trim() }
        } else {
            text.split(BLANK_LINE).map { it.trim() }
        }.filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(text) }
    }
}
