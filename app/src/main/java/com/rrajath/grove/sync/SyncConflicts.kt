package com.rrajath.grove.sync

import com.github.difflib.DiffUtils
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageResult
import com.rrajath.grove.vault.storage.isOk

/**
 * Syncthing conflict-copy handling. When both sides change a file, Syncthing
 * keeps the newer content in place and writes the loser as
 * `name.sync-conflict-YYYYMMDD-HHMMSS-DEVICEID.org` in the same directory.
 *
 * Names here are vault-relative paths. The `(.+)` head of [CONFLICT] spans the
 * directory prefix too, so `projects/acme.sync-conflict-….org` maps back to the
 * base `projects/acme.org` in its own directory.
 */
object SyncConflicts {

    private val CONFLICT = Regex("""^(.+)\.sync-conflict-(\d{8})-(\d{6})-([A-Za-z0-9]+)(\.[^.]+)?$""")

    fun isConflictFile(name: String): Boolean = CONFLICT.matches(name)

    /** Base notebook file name a conflict copy belongs to, or null. */
    fun baseName(conflictFileName: String): String? {
        val m = CONFLICT.matchEntire(conflictFileName) ?: return null
        return m.groupValues[1] + m.groupValues[5]
    }

    /** Human-readable "when" of a conflict copy: `2025-06-11 14:32`. */
    fun label(conflictFileName: String): String {
        val m = CONFLICT.matchEntire(conflictFileName) ?: return conflictFileName
        val d = m.groupValues[2]
        val t = m.groupValues[3]
        return "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)} " +
                "${t.substring(0, 2)}:${t.substring(2, 4)}"
    }

    /**
     * Map of base file → conflict copy for every base that has one.
     * If several copies exist, the newest (lexicographically greatest) wins.
     */
    fun detect(names: List<String>): Map<String, String> =
        names.filter { isConflictFile(it) }
            .sorted()
            .mapNotNull { copy -> baseName(copy)?.let { base -> base to copy } }
            .toMap()
}

object ConflictResolver {

    /**
     * Applies [resolution] to [baseName] and then removes the conflict copy
     * [copyName]. The copy is the only other copy of the text, so it is deleted
     * only once the base file really took the resolution: a refused write
     * returns its error and leaves both files as they were. A copy that then
     * can't be deleted still counts as resolved (the next sync flags it again
     * if it survives).
     */
    suspend fun apply(
        store: FileStore,
        baseName: String,
        copyName: String,
        resolution: ConflictResolution,
    ): StorageResult<Unit> {
        val written = when (resolution) {
            ConflictResolution.KEEP_CURRENT -> StorageResult.Ok("")
            ConflictResolution.KEEP_CONFLICT_COPY -> store.write(baseName, store.read(copyName))
            ConflictResolution.KEEP_BOTH -> store.write(
                baseName,
                keepBoth(mainText = store.read(baseName), conflictText = store.read(copyName)),
            )
        }
        if (written is StorageError) return written
        store.delete(copyName)
        return StorageResult.Ok(Unit)
    }

    /**
     * "Keep both": line-diffs [mainText] against [conflictText] and, at every
     * spot they diverge, keeps both versions back-to-back in place instead of
     * picking one (PRD §6.4) — e.g. a 2-line heading in the current file and a
     * different 3-line heading in the conflict copy both end up in the result,
     * right where they diverge, rather than the whole conflict copy being
     * appended at the end. Lines the two files agree on (the common case for
     * most of a file) are kept once.
     */
    fun keepBoth(mainText: String, conflictText: String): String {
        val mainLines = mainText.split("\n")
        val conflictLines = conflictText.split("\n")
        val deltas = DiffUtils.diff(mainLines, conflictLines).deltas.sortedBy { it.source.position }

        val result = mutableListOf<String>()
        var pos = 0
        for (delta in deltas) {
            result.addAll(mainLines.subList(pos, delta.source.position))
            result.addAll(delta.source.lines)
            result.addAll(delta.target.lines)
            pos = delta.source.position + delta.source.lines.size
        }
        result.addAll(mainLines.subList(pos, mainLines.size))
        return result.joinToString("\n")
    }
}
