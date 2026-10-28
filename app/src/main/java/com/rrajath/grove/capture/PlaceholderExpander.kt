package com.rrajath.grove.capture

import com.rrajath.grove.org.OrgTimestamp
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Everything dynamic a template expansion can draw on, injected for testability. */
data class CaptureContext(
    val now: LocalDateTime,
    val clipboard: String = "",
    val sharedText: String = "",
    val sharedUrl: String = "",
    /** Answers for `%^{prompt}` placeholders, keyed by prompt text. */
    val promptValues: Map<String, String> = emptyMap(),
    /** `%(id)` value — generated once per capture, not regenerated per [PlaceholderExpander.expand] call. */
    val id: String = "",
)

data class ExpandedTemplate(
    val text: String,
    /** Char offset where the cursor should land (`%cursor` / `%?`), or end of text. */
    val cursorOffset: Int,
)

/** An unsupported `%...`-looking token found in a template body (PLACEHOLDER_DOCS). */
data class InvalidPlaceholder(val token: String, val range: IntRange)

/** Expands org-capture style placeholders (PRD §7.3). */
object PlaceholderExpander {

    // Closing brace must be escaped: Android's ICU regex engine rejects a bare
    // `}` outside a character class (the JVM engine used in unit tests allows it).
    private val PROMPT = Regex("""%\^\{([^}]*)\}""")

    // A `%`-looking token that isn't a recognized placeholder: optional `^` (a
    // malformed prompt missing its `{...}`) followed by word chars. Used only to
    // report what the user probably meant; `expand` never consults this.
    private val ATTEMPTED_PLACEHOLDER = Regex("""%\^?[A-Za-z_]*""")

    // Longest first so e.g. %shared_text wins over a stray %s, %time wins over %t.
    // Shared with findInvalid so the two can never disagree about what's supported.
    private val SIMPLE_KEYS = listOf(
        "%shared_text", "%shared_url", "%clipboard", "%cursor", "%month", "%date",
        "%time", "%year", "%day", "%t", "%T", "%u", "%U", "%?", "%(id)",
    )

    /** Prompts the UI must collect (in order, deduplicated) before expanding. */
    fun prompts(template: String): List<String> =
        PROMPT.findAll(template).map { it.groupValues[1] }.distinct().toList()

    fun expand(template: String, ctx: CaptureContext): ExpandedTemplate {
        val date = ctx.now.toLocalDate()
        val time = ctx.now.toLocalTime()
        val dateStamp = OrgTimestamp(date)
        val dateTimeStamp = OrgTimestamp(date, time = time.withSecond(0).withNano(0))
        val timeText = "%d:%02d".format(time.hour, time.minute)

        val values: Map<String, (() -> String)?> = mapOf(
            "%shared_text" to { ctx.sharedText },
            "%shared_url" to { ctx.sharedUrl },
            "%clipboard" to { ctx.clipboard },
            "%cursor" to null,
            "%month" to { date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) },
            "%date" to { date.toString() },
            "%time" to { timeText },
            "%year" to { date.year.toString() },
            "%day" to { date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) },
            // Org manual: lowercase = date only, uppercase = date + time;
            // t/T are active <...>, u/U inactive [...].
            "%t" to { dateStamp.format() },
            "%T" to { dateTimeStamp.format() },
            "%u" to { dateStamp.copy(active = false).format() },
            "%U" to { dateTimeStamp.copy(active = false).format() },
            "%?" to null,
            "%(id)" to { ctx.id },
        )

        val sb = StringBuilder()
        var cursor: Int? = null
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch != '%') {
                sb.append(ch)
                i++
                continue
            }
            val promptMatch = PROMPT.matchAt(template, i)
            if (promptMatch != null) {
                sb.append(ctx.promptValues[promptMatch.groupValues[1]] ?: "")
                i += promptMatch.value.length
                continue
            }
            val key = SIMPLE_KEYS.firstOrNull { template.startsWith(it, i) }
            if (key == null) {
                sb.append(ch)
                i++
                continue
            }
            val value = values.getValue(key)
            if (value == null) {
                if (cursor == null) cursor = sb.length
            } else {
                sb.append(value())
            }
            i += key.length
        }
        return ExpandedTemplate(sb.toString(), cursor ?: sb.length)
    }

    /**
     * [template] with every `%T`/`%U` placeholder swapped for its date-only
     * form (`%t`/`%u`), tokenized exactly like [expand] so prompt labels and
     * longer keys are left alone. Migrates templates saved with the retired
     * "Datetree (date)" location, which stripped the time from `%T`/`%U`.
     */
    fun dateOnlyStamps(template: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            if (template[i] != '%') {
                sb.append(template[i])
                i++
                continue
            }
            val promptMatch = PROMPT.matchAt(template, i)
            if (promptMatch != null) {
                sb.append(promptMatch.value)
                i += promptMatch.value.length
                continue
            }
            val key = SIMPLE_KEYS.firstOrNull { template.startsWith(it, i) }
            if (key == null) {
                sb.append('%')
                i++
                continue
            }
            sb.append(
                when (key) {
                    "%T" -> "%t"
                    "%U" -> "%u"
                    else -> key
                }
            )
            i += key.length
        }
        return sb.toString()
    }

    /**
     * `%...`-looking tokens in [template] that aren't `%^{prompt}` and don't match
     * any [SIMPLE_KEYS] entry — i.e. would silently pass through [expand] verbatim
     * instead of being substituted. A lone `%` not followed by `^`/a letter (as in
     * `50% off`) is ordinary prose, not an attempted placeholder, and isn't flagged.
     */
    fun findInvalid(template: String): List<InvalidPlaceholder> {
        val invalid = mutableListOf<InvalidPlaceholder>()
        var i = 0
        while (i < template.length) {
            if (template[i] != '%') {
                i++
                continue
            }
            val promptMatch = PROMPT.matchAt(template, i)
            if (promptMatch != null) {
                i += promptMatch.value.length
                continue
            }
            val key = SIMPLE_KEYS.firstOrNull { template.startsWith(it, i) }
            if (key != null) {
                i += key.length
                continue
            }
            val attempt = ATTEMPTED_PLACEHOLDER.matchAt(template, i)?.value.orEmpty()
            if (attempt.length > 1) {
                invalid += InvalidPlaceholder(attempt, i until i + attempt.length)
                i += attempt.length
            } else {
                i++
            }
        }
        return invalid
    }
}
