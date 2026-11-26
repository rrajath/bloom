package com.rrajath.grove.dailies

import com.rrajath.grove.capture.CaptureContext
import com.rrajath.grove.capture.ExpandedTemplate
import com.rrajath.grove.capture.FilenamePattern
import com.rrajath.grove.capture.PlaceholderExpander
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.vault.FileStore
import java.time.LocalDate
import java.time.LocalDateTime

/** How far back [DailiesRepository.previousExistingDate]'s day-by-day fallback
 *  (used only when the configured pattern can't be reverse-parsed) will walk
 *  before giving up. Fixed, not a setting — same precedent as WHOLE_FILE_LINE_LIMIT. */
private const val FALLBACK_WALK_YEARS = 5L

/** Date/filename resolution for Dailies: where a given day's note lives, whether
 *  it exists, and the sorted set of days that have one. Pure aside from the
 *  [FileStore] it's handed — no ViewModel/Compose dependencies. */
class DailiesRepository(private val store: FileStore) {

    fun resolveFileName(directory: String, pattern: String, date: LocalDate): String {
        val leaf = FilenamePattern.expand(pattern, date.atStartOfDay(), slug = "")
        return if (directory.isBlank()) leaf else "${directory.trim('/')}/$leaf"
    }

    suspend fun existsForDate(directory: String, pattern: String, date: LocalDate): Boolean =
        store.exists(resolveFileName(directory, pattern, date))

    /** Every date under [directory] with a file matching [pattern], ascending.
     *  Empty (not an error) when the pattern can't be reverse-parsed, the
     *  directory has nothing, or nothing matches. */
    suspend fun existingDates(directory: String, pattern: String): List<LocalDate> {
        // Compiled once for the whole listing, not per file name.
        val dateRegex = FilenamePattern.compileDatePattern(pattern) ?: return emptyList()
        val dir = directory.trim('/')
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        // Only the dailies folder itself: a whole-vault listing here was the ~1s
        // first-open delay on a large SAF tree.
        return store.listDir(dir)
            .asSequence()
            .mapNotNull { entry -> dateRegex.parse(entry.name.removePrefix(prefix)) }
            .distinct()
            .sorted()
            .toList()
    }

    /** Nearest date before [before] with a file, or the absolute previous day if none. */
    suspend fun previousExistingDate(directory: String, pattern: String, before: LocalDate): LocalDate {
        val existing = existingDates(directory, pattern)
        if (existing.isNotEmpty()) {
            existing.lastOrNull { it < before }?.let { return it }
        } else if (FilenamePattern.toDateRegex(pattern) == null) {
            // Unparseable pattern: fall back to generating candidates day by day
            // and checking existence directly, bounded so a sparse/empty
            // directory can't spin forever.
            val floor = before.minusYears(FALLBACK_WALK_YEARS)
            var candidate = before.minusDays(1)
            while (candidate >= floor) {
                if (existsForDate(directory, pattern, candidate)) return candidate
                candidate = candidate.minusDays(1)
            }
        }
        return before.minusDays(1)
    }

    /** [ids] feed the template's `%id` / `%(id)` placeholders in order (see [CaptureContext.extraIds]). */
    fun expandHeaderTemplate(template: String, date: LocalDate, ids: List<String> = listOf(newOrgId())): ExpandedTemplate =
        PlaceholderExpander.expand(
            template,
            CaptureContext(now = date.atStartOfDay(), id = ids.firstOrNull() ?: newOrgId(), extraIds = ids.drop(1)),
        )
}
