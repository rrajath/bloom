// i18n: parses the English CHANGELOG.md, never translate
package com.rrajath.grove.whatsnew

/**
 * One `### Added`/`### Fixed`/etc. block under a version heading. [heading] is `""` for bullets
 * that sit directly under a version heading with no `###` category above them (the format most
 * current CHANGELOG.md entries use).
 */
data class ChangelogSubsection(val heading: String, val items: List<String>)

/**
 * One `## [Unreleased]` or `## [1.0.3] - <date>` block. [versionCode] is null for Unreleased,
 * otherwise the numeric versionCode the release shipped as: the version title parsed as
 * `MAJOR.MINOR.PATCH` into `MAJOR*10000 + MINOR*100 + PATCH` (so "1.2.3" -> 10203), matching
 * `BuildConfig.VERSION_CODE` (see `app/build.gradle.kts`).
 *
 * Two kinds of historical entry are handled specially:
 *  - Entries archived in mid-2026 carry a legacy `(build N)` suffix (N = the git-commit-count
 *    versionCode of that era); when present it is read verbatim and wins over the title.
 *  - Pre-1.0.0 entries titled `1.0.<commit-count>` parse the same way as any other version; their
 *    computed value is only ever compared against a last-seen versionCode for ordering, and for
 *    any realistic last-seen value it never changes which entries the What's New modal shows.
 */
data class ChangelogVersion(
    val title: String,
    val versionCode: Int?,
    val subsections: List<ChangelogSubsection>,
    /** The ISO date from the version heading (`## [1.2.3] - 2026-09-09`), with any trailing
     *  `(build N)` stripped; null for `## [Unreleased]` and any heading with no date. */
    val date: String? = null,
)

/**
 * Parses `CHANGELOG.md`'s Keep-a-Changelog-style structure (see the file's own header comment)
 * into a What's New modal's data. Pure Kotlin, no Android imports, so it's JVM-testable directly
 * against the real file.
 */
object ChangelogParser {
    private val versionHeading = Regex("""^## \[(.+?)](?: - (.+?))?$""")
    private val buildSuffix = Regex("""\(build (\d+)\)\s*$""")
    private val semver = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
    private val subHeading = Regex("""^### (.+)$""")
    private val bulletStart = Regex("""^- (.+)$""")

    // The order the What's New screen shows a merged version's categories in, matching
    // Keep a Changelog. Uncategorised bullets ("") sort last. Any other heading keeps its
    // first-seen position after these (stable sort).
    private val subsectionOrder = listOf("Added", "Changed", "Fixed", "Removed", "Deprecated", "Security", "")

    fun parse(text: String): List<ChangelogVersion> {
        val versions = mutableListOf<ChangelogVersion>()
        var title: String? = null
        var date: String? = null
        var explicitBuild: Int? = null
        var subsections = mutableListOf<ChangelogSubsection>()
        var subHeadingText: String? = null
        var items = mutableListOf<StringBuilder>()

        fun flushSubsection() {
            // Bullets with no `### Category` above them are kept under an empty heading rather
            // than dropped (most current entries are written this way). An empty `### Category`
            // with no bullets is dropped.
            if (items.isNotEmpty()) {
                subsections.add(ChangelogSubsection(subHeadingText ?: "", items.map { i -> i.toString().trim() }))
            }
            subHeadingText = null
            items = mutableListOf()
        }
        fun flushVersion() {
            flushSubsection()
            title?.let {
                versions.add(ChangelogVersion(it, explicitBuild ?: versionCodeFromTitle(it), subsections.toList(), date))
            }
            title = null
            date = null
            explicitBuild = null
            subsections = mutableListOf()
        }

        for (line in text.lines()) {
            val versionMatch = versionHeading.find(line)
            val subMatch = subHeading.find(line)
            val bulletMatch = bulletStart.find(line)
            when {
                versionMatch != null -> {
                    flushVersion()
                    title = versionMatch.groupValues[1]
                    date = versionMatch.groupValues.getOrNull(2)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { buildSuffix.replace(it, "") }
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    explicitBuild = buildSuffix.find(line)?.groupValues?.get(1)?.toIntOrNull()
                }
                title == null -> Unit // prose before the first heading (format explainer, etc.)
                subMatch != null -> {
                    flushSubsection()
                    subHeadingText = subMatch.groupValues[1]
                }
                bulletMatch != null -> items.add(StringBuilder(bulletMatch.groupValues[1]))
                // Bullet continuation line (indented wrap); blank lines and anything before the
                // first bullet of a subsection are ignored.
                line.isNotBlank() && items.isNotEmpty() && (line.startsWith("  ") || line.startsWith("\t")) ->
                    items.last().append(' ').append(line.trim())
            }
        }
        flushVersion()
        return versions
    }

    /**
     * Sections strictly newer than [lastSeenCode] (a versionCode), in the file's existing
     * newest-first order, with empty sections (e.g. a just-cut, still-empty "Unreleased") dropped.
     * Returns everything when [lastSeenCode] is null — the caller decides what null means for its
     * situation (e.g. suppress on a fresh install).
     */
    fun entriesSince(text: String, lastSeenCode: Int?): List<ChangelogVersion> {
        val all = parse(text).filter { it.subsections.any { s -> s.items.isNotEmpty() } }
        val since = if (lastSeenCode == null) all
        else all.takeWhile { it.versionCode == null || it.versionCode > lastSeenCode }
        return coalesceSameVersion(since)
    }

    /** How many recent releases the What's New screen shows. The tail of the real CHANGELOG.md
     *  is dozens of pre-1.0 `1.0.<commit-count>` and `1.0.0 (build N)` entries from a superseded
     *  versioning scheme; nobody scrolls that far and they add no value to a "what changed"
     *  screen. */
    const val WHATS_NEW_SCREEN_LIMIT = 12

    /**
     * The [WHATS_NEW_SCREEN_LIMIT] most recent shipped releases (drops `## [Unreleased]` and any
     * release whose bullets are all empty), newest-first, for the What's New screen. Consecutive
     * headings that ship as the same version — the real file has seven `## [1.5.0]` blocks cut on
     * different days — are merged into one entry: their categories are combined (in
     * [subsectionOrder]) and the newest heading's date is kept.
     */
    fun shippedReleases(text: String): List<ChangelogVersion> {
        val shipped = parse(text).filter { v ->
            v.versionCode != null && v.subsections.any { it.items.isNotEmpty() }
        }
        return coalesceSameVersion(shipped).take(WHATS_NEW_SCREEN_LIMIT)
    }

    /** Fold consecutive entries that ship as the same version into one, combining categories and
     *  keeping the newest heading's date (the list is newest-first). Matched on [versionCode]
     *  rather than title so the modern `1.0.3 (build 316)` and the pre-1.0 `1.0.<commit-count>`
     *  entry that also happens to be titled `1.0.3` stay separate. */
    private fun coalesceSameVersion(versions: List<ChangelogVersion>): List<ChangelogVersion> {
        val merged = mutableListOf<ChangelogVersion>()
        for (v in versions) {
            val prev = merged.lastOrNull()
            if (prev != null && prev.title == v.title && prev.versionCode == v.versionCode) {
                merged[merged.lastIndex] = prev.copy(
                    date = prev.date ?: v.date,
                    subsections = mergeSubsections(prev.subsections, v.subsections),
                )
            } else {
                merged.add(v)
            }
        }
        return merged
    }

    private fun mergeSubsections(
        a: List<ChangelogSubsection>,
        b: List<ChangelogSubsection>,
    ): List<ChangelogSubsection> {
        val byHeading = LinkedHashMap<String, MutableList<String>>()
        for (s in a + b) byHeading.getOrPut(s.heading) { mutableListOf() }.addAll(s.items)
        return byHeading.entries
            .sortedBy { subsectionOrder.indexOf(it.key).let { i -> if (i == -1) subsectionOrder.size else i } }
            .map { ChangelogSubsection(it.key, it.value.toList()) }
            .filter { it.items.isNotEmpty() }
    }

    // "MAJOR.MINOR.PATCH" -> MAJOR*10000 + MINOR*100 + PATCH, matching app/build.gradle.kts.
    // Null for anything that isn't a three-part numeric version (e.g. "Unreleased").
    private fun versionCodeFromTitle(title: String): Int? =
        semver.find(title)?.destructured?.let { (major, minor, patch) ->
            major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
        }
}
