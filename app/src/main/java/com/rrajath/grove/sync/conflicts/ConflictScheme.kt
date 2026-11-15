package com.rrajath.grove.sync.conflicts

/**
 * One sync tool's way of naming a conflict copy: the file it writes next to the
 * original when both sides changed it. Names are vault-relative paths; the
 * base a copy belongs to sits in the same folder.
 *
 * Every scheme runs on every listing ([ConflictSchemes.all]): a vault can hold
 * copies from more than one tool (a Seafile library a laptop also syncs with
 * Syncthing), and nothing about the provider says which tool wrote them.
 *
 * Patterns run under Android's ICU regex engine, which rejects an unescaped
 * literal `}` or `]` that the JVM accepts; JVM tests won't catch that.
 */
interface ConflictScheme {
    /** The tool's name, for "Seafile saved the other version as a conflict copy". */
    val displayName: String

    /** [name] as one of this tool's conflict copies, or null if it isn't one. */
    fun parse(name: String): ConflictCopy?
}

/**
 * A recognized conflict copy. [stamp] is `yyyy-MM-dd HH:mm:ss`, so copies of
 * one base sort oldest to newest as strings, across schemes. [author] is the
 * user or device the tool recorded, when it records a readable one.
 */
data class ConflictCopy(
    val scheme: ConflictScheme,
    val baseName: String,
    val stamp: String,
    val author: String? = null,
) {
    /** `2026-11-08 10:12`, plus `· alex@host` when the tool named who made it. */
    val label: String
        get() = stamp.substring(0, 16) + (author?.let { " · $it" } ?: "")
}

private fun stamp(y: String, mo: String, d: String, h: String, mi: String, s: String) = "$y-$mo-$d $h:$mi:$s"

/**
 * Syncthing: `name.sync-conflict-YYYYMMDD-HHMMSS-DEVICEID.ext`. The device id
 * is a short hash, not something a person recognizes, so it isn't the author.
 */
object SyncthingScheme : ConflictScheme {
    override val displayName = "Syncthing"
    private val PATTERN = Regex("""^(.+)\.sync-conflict-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})-([A-Za-z0-9]+)(\.[^./]+)?$""")

    override fun parse(name: String): ConflictCopy? {
        val g = PATTERN.matchEntire(name)?.groupValues ?: return null
        return ConflictCopy(this, g[1] + g[9], stamp(g[2], g[3], g[4], g[5], g[6], g[7]))
    }
}

/**
 * Seafile's desktop client: `name (SFConflict <modifier> YYYY-MM-DD-HH-MM-SS).ext`,
 * or without the modifier when the server didn't say who made the change
 * (`gen_conflict_path` in haiwen/seafile `common/vc-common.c`). Its Android app
 * uploads over the server copy and never makes one.
 */
object SeafileScheme : ConflictScheme {
    override val displayName = "Seafile"
    private val PATTERN = Regex(
        """^(.+) \(SFConflict (?:(\S+) )?(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})\)(\.[^./]+)?$""",
    )

    override fun parse(name: String): ConflictCopy? {
        val g = PATTERN.matchEntire(name)?.groupValues ?: return null
        return ConflictCopy(this, g[1] + g[9], stamp(g[3], g[4], g[5], g[6], g[7], g[8]), g[2].ifEmpty { null })
    }
}

/**
 * Nextcloud's desktop client: `name (conflicted copy [<user> ]YYYY-MM-DD HHMMSS).ext`
 * (`Utility::makeConflictFileName`; the user name is cut to 25 characters and
 * may contain spaces), and the older ownCloud-era `name_conflict-YYYYMMDD-HHMMSS.ext`.
 */
object NextcloudScheme : ConflictScheme {
    override val displayName = "Nextcloud"
    private val CURRENT = Regex(
        """^(.+) \(conflicted copy (?:(.+?) )?(\d{4})-(\d{2})-(\d{2}) (\d{2})(\d{2})(\d{2})\)(\.[^./]+)?$""",
    )
    private val LEGACY = Regex("""^(.+)_conflict-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})(\.[^./]+)?$""")

    override fun parse(name: String): ConflictCopy? {
        CURRENT.matchEntire(name)?.groupValues?.let { g ->
            return ConflictCopy(this, g[1] + g[9], stamp(g[3], g[4], g[5], g[6], g[7], g[8]), g[2].ifEmpty { null })
        }
        val g = LEGACY.matchEntire(name)?.groupValues ?: return null
        return ConflictCopy(this, g[1] + g[8], stamp(g[2], g[3], g[4], g[5], g[6], g[7]))
    }
}

/** Every known [ConflictScheme], run together over each listing. */
object ConflictSchemes {
    val all: List<ConflictScheme> = listOf(SyncthingScheme, SeafileScheme, NextcloudScheme)

    /** [name] as a conflict copy of any known tool, or null. */
    fun parse(name: String): ConflictCopy? = all.firstNotNullOfOrNull { it.parse(name) }

    fun isConflictFile(name: String): Boolean = parse(name) != null

    /** The file a conflict copy belongs to, or null if [name] isn't a copy. */
    fun baseName(name: String): String? = parse(name)?.baseName

    /**
     * Base file → its conflict copy, for every base in [names] that has one.
     * With several copies of one base the newest wins (ties broken by name), so
     * resolving it surfaces the next one on the following sync.
     */
    fun detect(names: List<String>): Map<String, String> =
        names.mapNotNull { name -> parse(name)?.let { name to it } }
            .sortedWith(compareBy({ it.second.stamp }, { it.first }))
            .associate { (name, copy) -> copy.baseName to name }
}
