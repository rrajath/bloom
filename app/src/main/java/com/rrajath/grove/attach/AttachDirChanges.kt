package com.rrajath.grove.attach

import com.rrajath.grove.data.IndexedAttachOwner
import com.rrajath.grove.org.OrgDocument

/**
 * A heading's or file's attach dir changed because its `:DIR:` (or `:ID:`)
 * changed, while the old dir still holds files. Found by [AttachDirChangeDetector]
 * when an editor saves; the editor shows each in a notice and, on OK, hands the
 * moves to [AttachDirMoves.apply].
 */
sealed interface AttachDirChange {
    /** The owner as it is after the edit. */
    val owner: AttachOwner
    /** The org file the owner lives in (attach dirs resolve next to it). */
    val orgFile: String
    /** The dir that holds the files now (vault-relative). */
    val from: String

    enum class Kind { DIR_ADDED, DIR_REMOVED, DIR_CHANGED }

    data class Move(
        override val owner: AttachOwner,
        override val orgFile: String,
        override val from: String,
        /** Where the files go (vault-relative). */
        val to: String,
        val kind: Kind,
        /** Another owner still resolves to [from]: copy instead of move, so its links keep working. */
        val copy: Boolean,
    ) : AttachDirChange

    /** The owner lost both `:ID:` and `:DIR:`: its `[[attachment:]]` links now resolve nowhere. */
    data class Broken(
        override val owner: AttachOwner,
        override val orgFile: String,
        override val from: String,
    ) : AttachDirChange
}

/** [to] as the notice shows it: relative to the org file's directory, like a `:DIR:` value. */
fun AttachDirChange.Move.shownTarget(): String {
    val orgDir = dirOf(orgFile)
    return if (orgDir.isNotEmpty() && to.startsWith("$orgDir/")) to.removePrefix("$orgDir/") else to
}

/** Every attach owner in [doc]: the file itself, then each heading. */
fun OrgDocument.attachOwners(): List<AttachOwner> =
    listOf(fileOwner()) + headlines.map { ownerOf(it) }

/**
 * Records [owner] (typically just given a new `:ID:` by an attach) in [baseline]:
 * replaces the entry it matches (the file owner, or a heading with the same
 * `:ID:` or title path), else appends it.
 */
fun withAttachedOwner(baseline: List<AttachOwner>, owner: AttachOwner): List<AttachOwner> {
    val i = baseline.indexOfFirst { b ->
        when {
            owner is AttachOwner.File -> b is AttachOwner.File
            b !is AttachOwner.Heading || owner !is AttachOwner.Heading -> false
            owner.id != null && b.id == owner.id -> true
            else -> b.titlePath == owner.titlePath
        }
    }
    return if (i >= 0) baseline.toMutableList().also { it[i] = owner } else baseline + owner
}

/**
 * Compares owners as they were (the editor's baseline) with [after], the saved
 * text, and reports each owner whose `:DIR:`/`:ID:` change moved its attach dir
 * away from a dir that holds files (design: `:DIR:` wins over `:ID:`, as in
 * org-attach). Owners are re-found in [after] by `:ID:`, then title path. An
 * `:ID:`-only change isn't reported.
 */
class AttachDirChangeDetector(
    private val files: AttachmentFiles,
    private val config: AttachConfig = AttachConfig.Default,
    /** Owners in other files (the note index), to tell when a dir is shared. */
    private val indexedOwners: suspend () -> List<IndexedAttachOwner> = { emptyList() },
) {
    private val resolver = AttachDirResolver(files, config)

    suspend fun detect(orgFile: String, baseline: List<AttachOwner>, after: OrgDocument): List<AttachDirChange> {
        val orgDir = dirOf(orgFile)
        val afterOwners = after.attachOwners()
        var others: List<Pair<String, AttachOwner>>? = null
        val out = mutableListOf<AttachDirChange>()
        val seenFrom = mutableSetOf<String>()
        for (before in baseline) {
            val now = relocateOwner(after, before) ?: continue
            val oldDirProp = before.dirProperty?.trim()?.takeIf { it.isNotEmpty() }
            val newDirProp = now.dirProperty?.trim()?.takeIf { it.isNotEmpty() }
            val oldId = before.id?.trim()?.takeIf { it.isNotEmpty() }
            val newId = now.id?.trim()?.takeIf { it.isNotEmpty() }
            // Only `:DIR:` edits move files; losing the last of `:ID:`/`:DIR:` orphans them.
            val lostBoth = newDirProp == null && newId == null && (oldId != null || oldDirProp != null)
            if (oldDirProp == newDirProp && !lostBoth) continue
            val from = resolver.readDir(orgFile, before) ?: continue
            if (from in seenFrom || files.sizeOf(from).files == 0) continue
            if (newDirProp == null && newId == null) {
                seenFrom += from
                out += AttachDirChange.Broken(now, orgFile, from)
                continue
            }
            val to = resolver.writeDir(orgFile, now)
            if (to == null) {
                // A `:DIR:` outside the vault: nowhere Bloom can move to.
                seenFrom += from
                out += AttachDirChange.Broken(now, orgFile, from)
                continue
            }
            if (to == from || !safeMove(orgDir, from, to)) continue
            val kind = when {
                oldDirProp == null -> AttachDirChange.Kind.DIR_ADDED
                newDirProp == null -> AttachDirChange.Kind.DIR_REMOVED
                else -> AttachDirChange.Kind.DIR_CHANGED
            }
            val otherOwners = others ?: (
                afterOwners.map { orgFile to it } +
                    indexedOwners().filter { it.fileName != orgFile }.map { it.fileName to it.asOwner() }
                ).also { others = it }
            val shared = otherOwners.any { (file, o) -> !o.sameAs(now) && from in resolver.candidates(file, o) } ||
                // The ID dir is also the old ID's: another heading may carry the same ID.
                (oldId != null && afterOwners.count { it.id?.trim() == oldId } > 1)
            seenFrom += from
            out += AttachDirChange.Move(now, orgFile, from, to, kind, copy = shared)
        }
        return out
    }

    private fun AttachOwner.sameAs(other: AttachOwner): Boolean = when {
        this is AttachOwner.File -> other is AttachOwner.File
        this is AttachOwner.Heading && other is AttachOwner.Heading -> lineIndex == other.lineIndex
        else -> false
    }

    private fun IndexedAttachOwner.asOwner(): AttachOwner =
        if (title == null) AttachOwner.File(orgId, dirProperty) else AttachOwner.Heading(-1, listOf(title), orgId, dirProperty)

    companion object {
        /**
         * Never move the vault root, a folder holding the org file (e.g. `:DIR: .`),
         * or between nested folders: that would carry notes along or loop.
         */
        internal fun safeMove(orgDir: String, from: String, to: String): Boolean {
            if (from.isEmpty() || to.isEmpty()) return false
            if (from == orgDir || orgDir.startsWith("$from/")) return false
            if (to.startsWith("$from/") || from.startsWith("$to/")) return false
            return true
        }
    }
}

/**
 * Detects and carries out [AttachDirChange]s for the editors. [apply] is fire
 * and forget (the app scope), so leaving the editor doesn't cancel a move.
 */
interface AttachDirMoves {
    suspend fun detect(orgFile: String, baseline: List<AttachOwner>, after: OrgDocument): List<AttachDirChange>

    fun apply(changes: List<AttachDirChange>)

    companion object {
        val None = object : AttachDirMoves {
            override suspend fun detect(orgFile: String, baseline: List<AttachOwner>, after: OrgDocument) =
                emptyList<AttachDirChange>()

            override fun apply(changes: List<AttachDirChange>) = Unit
        }
    }
}
