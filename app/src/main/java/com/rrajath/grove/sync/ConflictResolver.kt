package com.rrajath.grove.sync

import com.github.difflib.DiffUtils
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageResult
import com.rrajath.grove.vault.storage.isOk

/** What happened to the conflict copy once its base file took a resolution. */
enum class CopyCleanup {
    DELETED,

    /** The provider wouldn't delete it (Seafile lets no other app delete); the user removes it there. */
    REMAINS,
}

object ConflictResolver {

    /**
     * Applies [resolution] to [baseName] and then removes the conflict copy
     * [copyName]. The copy is the only other copy of the text, so it is deleted
     * only once the base file really took the resolution: a refused write
     * returns its error and leaves both files as they were. A copy that then
     * can't be deleted still counts as resolved, reported as [CopyCleanup.REMAINS]:
     * the next sync flags it again until it's gone.
     */
    suspend fun apply(
        store: FileStore,
        baseName: String,
        copyName: String,
        resolution: ConflictResolution,
    ): StorageResult<CopyCleanup> {
        val written = when (resolution) {
            ConflictResolution.KEEP_CURRENT -> StorageResult.Ok("")
            ConflictResolution.KEEP_CONFLICT_COPY -> store.write(baseName, store.read(copyName))
            ConflictResolution.KEEP_BOTH -> store.write(
                baseName,
                keepBoth(mainText = store.read(baseName), conflictText = store.read(copyName)),
            )
        }
        if (written is StorageError) return written
        val deleted = store.delete(copyName)
        return StorageResult.Ok(if (deleted.isOk) CopyCleanup.DELETED else CopyCleanup.REMAINS)
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
