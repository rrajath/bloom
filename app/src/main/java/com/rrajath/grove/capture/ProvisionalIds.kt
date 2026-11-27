package com.rrajath.grove.capture

import androidx.compose.ui.text.TextRange

/*
 * The capture editor renders on its first frame with provisional org IDs (plain
 * [com.rrajath.grove.org.newOrgId]s, not yet checked against the vault), then has
 * [com.rrajath.grove.org.OrgIdAllocator.claim] confirm them. A provisional ID that
 * turns out to be taken is swapped for its replacement, in the buffer and in
 * everything the capture saves. These are the pure parts of that swap.
 */

/** Provisional-to-confirmed pairs that differ; empty when every provisional ID was free. */
fun idSwaps(provisional: List<String>, confirmed: List<String>): Map<String, String> =
    provisional.zip(confirmed).filter { (from, to) -> from != to }.toMap()

/** [this] with every occurrence of each [swaps] key replaced by its value. */
fun String.withIdSwaps(swaps: Map<String, String>): String =
    swaps.entries.fold(this) { text, (from, to) -> text.replace(from, to) }

/** [context] with its own [CaptureContext.id] and [CaptureContext.extraIds] swapped. */
fun CaptureContext.withIdSwaps(swaps: Map<String, String>): CaptureContext =
    if (swaps.isEmpty()) this
    else copy(id = swaps[id] ?: id, extraIds = extraIds.map { swaps[it] ?: it })

/**
 * [text] with [swaps] applied, and [selection] moved to follow the text around it.
 * An end inside a swapped ID lands at the end of its replacement. Null when no key
 * of [swaps] occurs in [text].
 */
fun swapIdsKeepingSelection(text: String, selection: TextRange, swaps: Map<String, String>): Pair<String, TextRange>? {
    if (swaps.isEmpty()) return null
    // Each match as (offset in text, length, replacement), left to right.
    val matches = ArrayList<Triple<Int, Int, String>>()
    var i = 0
    while (i < text.length) {
        val hit = swaps.entries.firstOrNull { (from, _) -> from.isNotEmpty() && text.startsWith(from, i) }
        if (hit == null) {
            i++
            continue
        }
        matches += Triple(i, hit.key.length, hit.value)
        i += hit.key.length
    }
    if (matches.isEmpty()) return null
    val out = StringBuilder(text.length)
    var last = 0
    for ((at, length, to) in matches) {
        out.append(text, last, at).append(to)
        last = at + length
    }
    out.append(text, last, text.length)

    fun moved(offset: Int): Int {
        var shift = 0
        for ((at, length, to) in matches) {
            if (offset <= at) break
            if (offset < at + length) return at + shift + to.length
            shift += to.length - length
        }
        return offset + shift
    }
    return out.toString() to TextRange(moved(selection.start), moved(selection.end))
}
