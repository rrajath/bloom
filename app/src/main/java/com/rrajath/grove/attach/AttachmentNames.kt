package com.rrajath.grove.attach

/**
 * Makes an incoming file name safe to write into an attach dir and to embed in
 * an `[[attachment:…]]` link: drops `[`, `]`, `/`, `\` and control characters,
 * trims, and falls back to `attachment` when nothing is left. Spaces and
 * Unicode are kept.
 */
fun sanitizeAttachmentName(name: String): String {
    val cleaned = buildString {
        for (ch in name) {
            if (ch == '[' || ch == ']' || ch == '/' || ch == '\\' || ch.isISOControl()) continue
            append(ch)
        }
    }.trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "attachment" else cleaned
}

/**
 * [wanted], or `name-1.ext`, `name-2.ext`, … when a file with that name already
 * exists in [existing]. Compared case-insensitively, because SAF providers and
 * the laptop on the other end of Syncthing may be case-insensitive.
 */
fun uniqueAttachmentName(existing: Collection<String>, wanted: String): String {
    val taken = existing.mapTo(HashSet()) { it.lowercase() }
    if (wanted.lowercase() !in taken) return wanted
    val dot = wanted.lastIndexOf('.')
    val (stem, ext) = if (dot > 0) wanted.substring(0, dot) to wanted.substring(dot) else wanted to ""
    var n = 1
    while ("$stem-$n$ext".lowercase() in taken) n++
    return "$stem-$n$ext"
}
