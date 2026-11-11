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
        /**
         * True: carry all of [from] (it's this owner's alone). False: only the
         * files the owner links ([linkedNames]), because [from] is shared, holds
         * the org file itself (`:DIR: .`), or contains [to].
         */
        val wholeDir: Boolean = true,
        /** Paths relative to [from] that the owner's `[[attachment:]]` links name. */
        val linkedNames: List<String> = emptyList(),
    ) : AttachDirChange

    /** The owner lost both `:ID:` and `:DIR:`: its `[[attachment:]]` links now resolve nowhere. */
    data class Broken(
        override val owner: AttachOwner,
        override val orgFile: String,
        override val from: String,
    ) : AttachDirChange
}

/** What [AttachDirMoves.apply] did: files renamed on a name clash, per move, and dirs that failed. */
data class AttachMoveOutcome(
    /** For each move with clashes: old path (relative to the dir) to the new one. */
    val renames: List<Pair<AttachDirChange.Move, Map<String, String>>> = emptyList(),
    val failed: Int = 0,
)

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
    private val config: AttachConfig,
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
            if (to == from) continue
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
            val linked = after.attachmentLinkTargets(now)
            val whole = !shared && wholeDirMovable(orgDir, from, to)
            // Copying or moving just the owner's own files: nothing to do when it links none.
            if (!whole && linked.isEmpty()) continue
            seenFrom += from
            out += AttachDirChange.Move(now, orgFile, from, to, kind, copy = shared, wholeDir = whole, linkedNames = linked)
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
         * Whether all of [from] can be carried to [to]: not the vault root or a
         * folder holding the org file (e.g. `:DIR: .`, which would carry notes
         * along), and [to] not inside [from] (the walk would chase its own
         * output). Otherwise only the owner's linked files move.
         */
        internal fun wholeDirMovable(orgDir: String, from: String, to: String): Boolean {
            if (from.isEmpty()) return false
            if (from == orgDir || orgDir.startsWith("$from/")) return false
            if (to.startsWith("$from/")) return false
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

    /**
     * Moves the files. Runs in the app scope, so leaving the editor can't cut it
     * short, but the caller waits: renamed clashes must have their links fixed.
     */
    suspend fun apply(changes: List<AttachDirChange>): AttachMoveOutcome

    companion object {
        val None = object : AttachDirMoves {
            override suspend fun detect(orgFile: String, baseline: List<AttachOwner>, after: OrgDocument) =
                emptyList<AttachDirChange>()

            override suspend fun apply(changes: List<AttachDirChange>) = AttachMoveOutcome()
        }
    }
}

/** Lines of [owner]'s own section: a heading's line through the line before the next heading; the file's preamble. */
private fun OrgDocument.sectionLines(owner: AttachOwner): IntRange? = when (owner) {
    is AttachOwner.File -> 0 until preambleEnd
    is AttachOwner.Heading -> {
        val h = headlines.firstOrNull { it.lineIndex == owner.lineIndex } ?: return null
        val next = headlines.firstOrNull { it.lineIndex > h.lineIndex }?.lineIndex ?: lines.size
        h.lineIndex until next
    }
}

// `[[attachment:NAME]]` or `[[attachment:NAME][desc]]`; group 1 is NAME (with any `::search`).
private val ATTACHMENT_LINK = Regex("""\[\[attachment:([^\]]+)\](\[|\])""")

/** Paths (relative to the owner's attach dir) its `[[attachment:]]` links name, in order, without repeats. */
fun OrgDocument.attachmentLinkTargets(owner: AttachOwner): List<String> {
    val range = sectionLines(owner) ?: return emptyList()
    return range.flatMap { i -> ATTACHMENT_LINK.findAll(lines[i]).map { it.groupValues[1].substringBefore("::") }.toList() }
        .filter { it.isNotBlank() }
        .distinct()
}

/**
 * [doc]'s text with each owner's `[[attachment:old]]` links (in its own
 * section) pointing at the renamed file instead. Line-preserving. Owners are
 * re-found by `:ID:`, then title path; one that's gone is skipped.
 */
fun renameAttachmentLinks(doc: OrgDocument, renames: List<Pair<AttachOwner, Map<String, String>>>): String {
    val lines = doc.lines.toMutableList()
    for ((owner, map) in renames) {
        if (map.isEmpty()) continue
        val current = relocateOwner(doc, owner)
            // A capture's owner came from the snippet, so its title path may lack the target's ancestors.
            ?: (owner as? AttachOwner.Heading)?.let { o ->
                doc.headlines.singleOrNull { it.title == o.title && doc.ownerOf(it).dirProperty == o.dirProperty }
                    ?.let { doc.ownerOf(it) }
            }
            ?: continue
        val range = doc.sectionLines(current) ?: continue
        for (i in range) {
            lines[i] = ATTACHMENT_LINK.replace(lines[i]) { m ->
                val target = m.groupValues[1]
                val name = target.substringBefore("::")
                val renamed = map[name] ?: return@replace m.value
                "[[attachment:" + renamed + target.removePrefix(name) + "]" + m.groupValues[2]
            }
        }
    }
    return lines.joinToString("\n")
}
