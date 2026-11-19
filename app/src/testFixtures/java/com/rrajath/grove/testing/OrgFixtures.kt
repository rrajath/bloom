package com.rrajath.grove.testing

/**
 * Canonical `.org` sample content shared by every test layer (integration,
 * UI, Maestro, benchmark). Keep these small and stable — assertions elsewhere
 * pin their exact text (e.g. the unique word "photosynthesis" in
 * [READING_LIST], the `capture-inbox` CUSTOM_ID in [INBOX]).
 *
 * The bodies live as resource files under `src/testFixtures/resources/fixtures/`
 * so there is a single source of truth: this object reads them off the test
 * classpath, and the debug build copies the same directory into its APK assets
 * (`copyDebugTestFixtures` in `app/build.gradle.kts`) so the Maestro debug-vault
 * hook seeds from identical content.
 */
object OrgFixtures {

    private fun load(name: String): String =
        OrgFixtures::class.java.getResourceAsStream("/fixtures/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing test fixture resource: fixtures/$name")

    /** Small note with a capture-target heading the default template inserts under. */
    val INBOX: String by lazy { load("inbox.org") }

    /** Multi-level TODO tree for outline / edit / agenda coverage. */
    val PROJECTS: String by lazy { load("projects.org") }

    /** A heading whose body contains the unique search word "photosynthesis". */
    val READING_LIST: String by lazy { load("reading-list.org") }

    /** Read-mode org table: a header row above a `|---|` rule, then body rows. */
    val TABLE: String by lazy { load("table.org") }

    /** Large subtree (> LARGE_SUBTREE_THRESHOLD headings) to exercise fold-on-open and scroll. */
    val LARGE_SUBTREE: String by lazy { load("large-subtree.org") }

    /**
     * Hub note whose "Link Hub" heading body holds one of every org link form
     * (star/fuzzy heading, `#custom-id`, `id:` heading, `id:` file, `file:` with
     * and without `::` search, path spellings, external, unresolved). Pairs with
     * [LINKS_FAR] for the cross-file cases. Anchor word: "linkhub".
     */
    val LINKS_HUB: String by lazy { load("links-hub.org") }

    /** Cross-file target for [LINKS_HUB]: a file-level `:ID:`, plus a heading with both `:ID:` and `:CUSTOM_ID:`. */
    val LINKS_FAR: String by lazy { load("links-far.org") }

    /**
     * Bare active timestamps: a timed single-day event, a ranged event
     * (`<a>--<b>`), and a date-only one. Anchor word: "eventfixture".
     */
    val EVENTS: String by lazy { load("events.org") }

    /** The "keep local" side of a Syncthing sync-conflict pair. */
    val CONFLICT_LOCAL = """
        #+TITLE: Notes

        * Meeting notes
          Local edit: decided to postpone.
    """.trimIndent() + "\n"

    /** The "keep remote" side — same file, a `.sync-conflict-*` sibling on disk. */
    val CONFLICT_REMOTE = """
        #+TITLE: Notes

        * Meeting notes
          Remote edit: decided to proceed.
    """.trimIndent() + "\n"

    const val CONFLICT_FILE = "notes.org"
    const val CONFLICT_SIBLING = "notes.sync-conflict-20260903-120000-ABCDEF1.org"

    /** Every fixture keyed by its vault-relative path — the default seed set. */
    val all: Map<String, String> by lazy {
        mapOf(
            "inbox.org" to INBOX,
            "projects.org" to PROJECTS,
            "reading-list.org" to READING_LIST,
            "table.org" to TABLE,
            "large-subtree.org" to LARGE_SUBTREE,
            "links-hub.org" to LINKS_HUB,
            "links-far.org" to LINKS_FAR,
            "events.org" to EVENTS,
        )
    }

    /** [all] plus a live sync-conflict pair, for conflict-resolution tests. */
    val withConflict: Map<String, String> by lazy {
        all + mapOf(
            CONFLICT_FILE to CONFLICT_LOCAL,
            CONFLICT_SIBLING to CONFLICT_REMOTE,
        )
    }
}
