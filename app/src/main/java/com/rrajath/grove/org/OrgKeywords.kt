package com.rrajath.grove.org

/**
 * TODO keyword configuration. Settings format mirrors org/Orgzly:
 * `"TODO IN-PROGRESS | DONE CANCELLED"`: keywords after `|` are done-type.
 */
data class OrgKeywords(
    val active: List<String>,
    val done: List<String>,
) {
    val all: List<String> = active + done

    fun isDone(keyword: String): Boolean = keyword in done

    /** Next state when cycling: none → active… → done… → none. */
    fun next(current: String?): String? {
        if (current == null) return all.firstOrNull()
        val idx = all.indexOf(current)
        if (idx == -1 || idx == all.lastIndex) return null
        return all[idx + 1]
    }

    companion object {
        val DEFAULT = parse("TODO IN-PROGRESS | DONE CANCELLED")  // i18n: org keywords, never translate

        fun parse(config: String): OrgKeywords {
            val parts = config.split('|', limit = 2)
            val active = parts[0].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val done = parts.getOrNull(1)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
                ?: emptyList()
            return OrgKeywords(active, done)
        }
    }
}
