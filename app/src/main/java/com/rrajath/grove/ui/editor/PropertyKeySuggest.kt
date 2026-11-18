package com.rrajath.grove.ui.editor

import androidx.compose.ui.text.TextRange

/**
 * Property-key suggestions: on a line inside a `:PROPERTIES:` drawer, a typed
 * `:` (plus any part of a key) offers the vault's property keys as chips, so
 * keys like `CUSTOM_ID` needn't be typed in caps by hand.
 */

/**
 * A `:key` being typed inside a property drawer. [range] spans the `:` through
 * the cursor; [prefix] is what follows the `:`. [drawerKeys] holds the
 * (uppercased) keys already set on the drawer's other lines, which aren't offered.
 */
data class PropertyKeyTrigger(val range: TextRange, val prefix: String, val drawerKeys: Set<String>)

/** Well-known org drawer properties, offered even before the vault uses them. */
internal val BUILT_IN_PROPERTY_KEYS = listOf(
    "ID", "CUSTOM_ID", "CREATED", "CATEGORY", "EFFORT", "ORDERED", "STYLE",
    "LAST_REPEAT", "LOGGING", "ARCHIVE", "COOKIE_DATA", "DIR", "VISIBILITY",
)

/** Drawer markers, never offered as keys. */
private val RESERVED_KEYS = setOf("PROPERTIES", "END", "LOGBOOK")

/** Most chips the strip shows; the rest are a few more letters away. */
private const val MAX_PROPERTY_KEY_SUGGESTIONS = 20

/** How far up or down from the cursor line to look for the drawer's markers. */
private const val MAX_DRAWER_SCAN_LINES = 200

private val KEY_IN_PROGRESS = Regex("""^[ \t]*:([^:\s]*)$""")
private val PROPERTY_KEY_LINE = Regex("""^\s*:([^:\s]+):""")
private val HEADLINE = Regex("""^\*+\s""")

/**
 * The property key being typed at a collapsed cursor, or null. The line up to
 * the cursor must be just optional indentation, `:` and the key so far (no
 * closing `:` yet), only whitespace may follow the cursor, and the line must sit
 * inside a `:PROPERTIES:` drawer: a `:PROPERTIES:` line above it with no `:END:`
 * or headline in between. Only the cursor's own line is examined per keystroke
 * unless it looks like a key, so ordinary typing costs nothing extra.
 */
fun propertyKeyTriggerAt(text: CharSequence, selection: TextRange): PropertyKeyTrigger? {
    if (!selection.collapsed) return null
    val cursor = selection.start
    if (cursor < 1 || cursor > text.length) return null
    val lineStart = lineStartOf(text, cursor)
    val lineEnd = lineEndOf(text, cursor)
    if (text.subSequence(cursor, lineEnd).isNotBlank()) return null
    val match = KEY_IN_PROGRESS.matchEntire(text.subSequence(lineStart, cursor)) ?: return null
    val prefixGroup = match.groups[1] ?: return null
    val colon = cursor - prefixGroup.value.length - 1

    val drawerKeys = HashSet<String>()
    // Up: the drawer must open above, with no close or headline in between.
    var start = lineStart
    var opened = false
    for (step in 0 until MAX_DRAWER_SCAN_LINES) {
        if (start == 0) break
        val prevStart = lineStartOf(text, start - 1)
        val line = text.subSequence(prevStart, start - 1)
        val trimmed = line.trim()
        if (trimmed.contentEquals(":PROPERTIES:", ignoreCase = true)) {
            opened = true
            break
        }
        if (trimmed.contentEquals(":END:", ignoreCase = true) || HEADLINE.containsMatchIn(line)) return null
        collectKey(line, drawerKeys)
        start = prevStart
    }
    if (!opened) return null
    // Down: keys set below the cursor line, up to the drawer's end.
    var next = lineEnd + 1
    for (step in 0 until MAX_DRAWER_SCAN_LINES) {
        if (next > text.length) break
        val end = lineEndOf(text, next)
        val line = text.subSequence(next, end)
        val trimmed = line.trim()
        if (trimmed.contentEquals(":END:", ignoreCase = true) ||
            trimmed.contentEquals(":PROPERTIES:", ignoreCase = true) ||
            HEADLINE.containsMatchIn(line)
        ) {
            break
        }
        collectKey(line, drawerKeys)
        next = end + 1
    }
    return PropertyKeyTrigger(TextRange(colon, cursor), prefixGroup.value, drawerKeys)
}

/**
 * Keys set in any `:PROPERTIES:` drawer of [text] (the open buffer), as written.
 * Covers keys typed since the last save, which the vault index hasn't seen yet.
 */
fun propertyKeysIn(text: CharSequence): Set<String> {
    val keys = LinkedHashSet<String>()
    var inDrawer = false
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        when {
            trimmed.equals(":PROPERTIES:", ignoreCase = true) -> inDrawer = true
            trimmed.equals(":END:", ignoreCase = true) || HEADLINE.containsMatchIn(line) -> inDrawer = false
            inDrawer -> PROPERTY_KEY_LINE.find(line)?.let { keys.add(it.groupValues[1].removeSuffix("+")) }
        }
    }
    return keys
}

/**
 * The chips for [trigger]: every key in [vaultCounts] (key to number of drawers
 * using it) plus [extraKeys] (built-ins, the open buffer's keys) that starts with
 * the typed prefix, ignoring case, and isn't already in the drawer. Most-used
 * first, then alphabetical; keys the vault doesn't use yet count as 0. Keys are
 * matched case-insensitively, keeping the vault's spelling when it has one.
 */
fun rankPropertyKeys(
    vaultCounts: Map<String, Int>,
    extraKeys: Collection<String>,
    trigger: PropertyKeyTrigger,
    limit: Int = MAX_PROPERTY_KEY_SUGGESTIONS,
): List<String> {
    val byUpper = LinkedHashMap<String, Pair<String, Int>>()
    for ((key, count) in vaultCounts) byUpper[key.uppercase()] = key to count
    for (key in extraKeys) byUpper.putIfAbsent(key.uppercase(), key to 0)
    return byUpper.entries.asSequence()
        .filter { (upper, _) ->
            upper !in RESERVED_KEYS && upper !in trigger.drawerKeys &&
                upper.startsWith(trigger.prefix, ignoreCase = true)
        }
        .map { it.value }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.uppercase() })
        .take(limit)
        .map { it.first }
        .toList()
}

/** What a picked [key] replaces the typed `:prefix` with: the full `:KEY:` and a space for the value. */
fun formatPropertyKeyInsertion(key: String): String = ":$key: "

private fun collectKey(line: CharSequence, into: MutableSet<String>) {
    PROPERTY_KEY_LINE.find(line)?.let { into.add(it.groupValues[1].removeSuffix("+").uppercase()) }
}

private fun lineStartOf(text: CharSequence, offset: Int): Int =
    if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

private fun lineEndOf(text: CharSequence, offset: Int): Int =
    text.indexOf('\n', offset).let { if (it == -1) text.length else it }
