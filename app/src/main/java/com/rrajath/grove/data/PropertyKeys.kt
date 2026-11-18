package com.rrajath.grove.data

/**
 * [keys] as the space-joined [NoteEntity.propertyKeys] form, or null when empty.
 * An org append key (`:FOO+:`) counts as `FOO`.
 */
internal fun joinPropertyKeys(keys: Collection<String>): String? =
    keys.map { it.removeSuffix("+") }.filter { it.isNotEmpty() }.distinct()
        .takeIf { it.isNotEmpty() }?.joinToString(" ")

/**
 * How many drawers use each key, from [IndexDao.allPropertyKeys] rows. Org keys
 * are case-insensitive, so `custom_id` and `CUSTOM_ID` are one key, reported
 * under whichever spelling the vault uses most (ties go to the alphabetically
 * first, so the result doesn't depend on row order).
 */
fun countPropertyKeys(rows: List<String>): Map<String, Int> {
    val spellings = HashMap<String, HashMap<String, Int>>()
    for (row in rows) {
        for (key in row.split(' ')) {
            if (key.isEmpty()) continue
            val bySpelling = spellings.getOrPut(key.uppercase()) { HashMap() }
            bySpelling[key] = (bySpelling[key] ?: 0) + 1
        }
    }
    return spellings.values.associate { bySpelling ->
        val spelling = bySpelling.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key
        spelling to bySpelling.values.sum()
    }
}
