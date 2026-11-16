package com.rrajath.grove.attach

import com.rrajath.grove.data.IndexedAttachOwner
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.newOrgId

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
        /** Paths relative to [from] that the `[[attachment:]]` links of [owner] and [carriedFor] name. */
        val linkedNames: List<String> = emptyList(),
        /**
         * Descendant headings whose folder changed with [owner]'s (they inherit
         * the `:DIR:`) and whose linked files this move also carries; their links
         * follow a clash rename too.
         */
        val carriedFor: List<AttachOwner> = emptyList(),
        /**
         * Set when [owner] (a heading) inherited the `:DIR:` that was removed and
         * has nothing left to resolve to: on OK it gets this `:ID:`, and [to] is
         * that ID's folder.
         */
        val newId: String? = null,
        /**
         * The notice doesn't list this move on its own: an earlier move for an
         * ancestor goes to the same [to] and stands for it.
         */
        val folded: Boolean = false,
    ) : AttachDirChange {
        /** Every owner whose links point into [from]: [owner], then [carriedFor]. */
        val linkOwners: List<AttachOwner> get() = listOf(owner) + carriedFor
    }

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
    /** Moves that went through and whose owner now needs its [AttachDirChange.Move.newId] written. */
    val newIds: List<AttachDirChange.Move> = emptyList(),
) {
    /** Whether the org text needs [applyMoveOutcomeText]. */
    val editsText: Boolean get() = renames.isNotEmpty() || newIds.isNotEmpty()
}

/** The changes the notice lists: folded moves ride along with an ancestor's entry. */
fun List<AttachDirChange>.shownInNotice(): List<AttachDirChange> =
    filterNot { it is AttachDirChange.Move && it.folded }

/** [to] as the notice shows it: relative to the org file's directory, like a `:DIR:` value. */
fun AttachDirChange.Move.shownTarget(): String {
    val orgDir = dirOf(orgFile)
    return if (orgDir.isNotEmpty() && to.startsWith("$orgDir/")) to.removePrefix("$orgDir/") else to
}

/**
 * Every attach owner in [doc]: the file itself, then each heading in document
 * order (ancestors before descendants), each with the `:DIR:` it resolves to
 * ([resolveOwnerAtLine]: own or inherited, [AttachOwner.Heading.dirFrom] set
 * when inherited).
 */
fun OrgDocument.attachOwners(): List<AttachOwner> =
    listOf(fileOwner()) + headlines.map { resolveOwnerAtLine(this, it.lineIndex) }

/** The owner's own `:DIR:` value, not an inherited one. */
private fun AttachOwner.ownDir(): String? =
    if (this is AttachOwner.Heading && dirFrom != null) null else dirProperty?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Whether [this] is a strict ancestor of [other] (the file owner is everyone's). */
private fun AttachOwner.isAncestorOf(other: AttachOwner): Boolean = when {
    other !is AttachOwner.Heading -> false
    this is AttachOwner.File -> true
    this is AttachOwner.Heading -> other.titlePath.size > titlePath.size && other.titlePath.take(titlePath.size) == titlePath
    else -> false
}

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
 * Compares owners as they were (the editor's baseline, from [attachOwners], so
 * with inherited `:DIR:`s) with [after], the saved text, and reports each owner
 * whose attach dir moved away from a dir that holds files (design: `:DIR:` wins
 * over `:ID:` and is inherited, as in org-attach with `org-use-property-inheritance
 * '("DIR" "ATTACH_DIR")`). A `:DIR:` edit thus also moves the files of
 * descendants that inherit it: each from where it resolved before to where it
 * resolves now. Owners are re-found in [after] by `:ID:`, then title path. An
 * `:ID:`-only change isn't reported.
 */
class AttachDirChangeDetector(
    private val files: AttachmentFiles,
    private val config: AttachConfig,
    /** Owners in other files (the note index), to tell when a dir is shared. */
    private val indexedOwners: suspend () -> List<IndexedAttachOwner> = { emptyList() },
    /** The `:ID:` for a descendant left with no folder (see [AttachDirChange.Move.newId]). */
    private val newId: () -> String = ::newOrgId,
) {
    private val resolver = AttachDirResolver(files, config)

    /** One owner whose folder changed from [from] to [to], before grouping by folder. */
    private class Pending(
        val order: Int,
        val now: AttachOwner,
        val from: String,
        val to: String,
        val kind: AttachDirChange.Kind,
        val linked: List<String>,
        /** Only an ancestor's or the file's `:DIR:` changed; the owner's own properties didn't. */
        val inheritedOnly: Boolean,
        /** Another heading still carries the owner's old `:ID:`, so its ID dir is shared. */
        val idShared: Boolean,
        val newId: String? = null,
    )

    suspend fun detect(orgFile: String, baseline: List<AttachOwner>, after: OrgDocument): List<AttachDirChange> {
        val orgDir = dirOf(orgFile)
        val afterOwners = after.attachOwners()
        val fileCounts = mutableMapOf<String, Int>()
        suspend fun holdsFiles(dir: String) = fileCounts.getOrPut(dir) { files.sizeOf(dir).files } > 0

        val pending = mutableListOf<Pending>()
        val broken = mutableListOf<Pair<Int, AttachDirChange.Broken>>()
        val brokenFrom = mutableSetOf<String>()
        for ((order, before) in baseline.withIndex()) {
            val own = relocateOwner(after, before) ?: continue
            val now = if (own is AttachOwner.Heading) resolveOwnerAtLine(after, own.lineIndex) else own
            val oldDir = before.dirProperty.clean()
            val newDir = now.dirProperty.clean()
            val oldId = before.id.clean()
            val nowId = now.id.clean()
            // Only `:DIR:` edits (own or inherited) move files; losing the last of `:ID:`/`:DIR:` orphans them.
            val lostBoth = newDir == null && nowId == null && (oldId != null || oldDir != null)
            if (oldDir == newDir && !lostBoth) continue
            val inheritedOnly = before.ownDir() == now.ownDir() && oldId == nowId
            val from = resolver.readDir(orgFile, before) ?: continue
            if (!holdsFiles(from)) continue
            // The heading's own edit names the kind; a descendant's follows what it resolves to.
            val (kindOld, kindNew) = if (inheritedOnly) oldDir to newDir else before.ownDir() to now.ownDir()
            val kind = when {
                kindOld == null -> AttachDirChange.Kind.DIR_ADDED
                kindNew == null -> AttachDirChange.Kind.DIR_REMOVED
                else -> AttachDirChange.Kind.DIR_CHANGED
            }
            val linked = after.attachmentLinkTargets(now)
            if (lostBoth) {
                if (inheritedOnly && now is AttachOwner.Heading) {
                    // The `:DIR:` it inherited is gone and it has no `:ID:`: give it one so its links keep working.
                    if (linked.isEmpty()) continue
                    val id = newId()
                    val to = resolver.writeDir(orgFile, now.copy(id = id)) ?: continue
                    pending += Pending(order, now, from, to, kind, linked, inheritedOnly = true, idShared = false, newId = id)
                } else if (brokenFrom.add(from)) {
                    broken += order to AttachDirChange.Broken(now, orgFile, from)
                }
                continue
            }
            val to = resolver.writeDir(orgFile, now)
            if (to == null) {
                // A `:DIR:` outside the vault: nowhere Bloom can move to.
                if (brokenFrom.add(from)) broken += order to AttachDirChange.Broken(now, orgFile, from)
                continue
            }
            if (to == from) continue
            // The ID dir is also the old ID's: another heading may carry the same ID.
            val idShared = oldId != null && oldDir == null && afterOwners.count { it.id.clean() == oldId } > 1
            pending += Pending(order, now, from, to, kind, linked, inheritedOnly, idShared)
        }

        var others: List<Pair<String, AttachOwner>>? = null
        val moves = mutableListOf<Pair<Int, AttachDirChange.Move>>()
        for ((from, group) in pending.groupBy { it.from }) {
            val otherOwners = others ?: (
                afterOwners.map { orgFile to it } +
                    indexedOwners().filter { it.fileName != orgFile }.map { it.fileName to it.asOwner() }
                ).also { others = it }
            // Someone who isn't moving away still resolves to [from]: copy so their links keep working.
            val shared = group.any { it.idShared } || otherOwners.any { (file, o) ->
                group.none { p -> o.sameAs(p.now) } && from in resolver.candidates(file, o)
            }
            val byTo = group.groupBy { it.to }
            // A broken owner's files stay where they are, so only the movers' linked files go.
            val whole = !shared && from !in brokenFrom && byTo.size == 1 && wholeDirMovable(orgDir, from, byTo.keys.single())
            for ((to, g) in byTo) {
                val linked = g.flatMap { it.linked }.distinct()
                // Copying or moving just the owners' own files: nothing to do when they link none.
                if (!whole && linked.isEmpty()) continue
                // Owners going to different places link the same file: each needs a copy.
                val overlap = byTo.any { (t, og) -> t != to && og.any { p -> p.linked.any { it in linked } } }
                val lead = g.first()
                moves += lead.order to AttachDirChange.Move(
                    lead.now, orgFile, from, to, lead.kind,
                    copy = shared || overlap, wholeDir = whole, linkedNames = linked,
                    carriedFor = g.drop(1).map { it.now }, newId = lead.newId,
                    folded = lead.inheritedOnly,
                )
            }
        }
        // A descendant's move folds into an ancestor's entry with the same target; otherwise it's listed.
        val shown = mutableListOf<AttachDirChange.Move>()
        val folded = moves.sortedBy { it.first }.map { (order, m) ->
            val fold = m.folded && shown.any { s -> s.to == m.to && s.owner.isAncestorOf(m.owner) }
            order to m.copy(folded = fold).also { if (!fold) shown += it }
        }
        return (broken + folded).sortedBy { it.first }.map { it.second }
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

/** [applyMoveOutcomeText]'s result: the new text and, per added `:ID:`, (old heading line, lines inserted after it). */
data class MoveTextEdit(val text: String, val insertions: List<Pair<Int, Int>>)

/**
 * The org text after a dir move: clash renames rewritten in every link owner's
 * section ([renameAttachmentLinks]), then each [AttachMoveOutcome.newIds]
 * owner given its `:ID:`. Renames keep the lines; an `:ID:` adds one (or a new
 * drawer of three) right after its heading, which [MoveTextEdit.insertions]
 * records against the original line numbers.
 */
fun applyMoveOutcomeText(doc: OrgDocument, outcome: AttachMoveOutcome): MoveTextEdit {
    val renamed = renameAttachmentLinks(doc, outcome.renames.flatMap { (move, map) -> move.linkOwners.map { it to map } })
    var current = if (renamed == doc.text) doc else OrgParser.parse(renamed, doc.keywords)
    // Found in [doc] (renames keep line numbers), applied bottom-up so the earlier headings' lines stay put.
    val targets = outcome.newIds.mapNotNull { move ->
        val id = move.newId ?: return@mapNotNull null
        val h = (relocateOwner(doc, move.owner) as? AttachOwner.Heading)?.takeIf { it.id == null }
            ?: return@mapNotNull null
        h.lineIndex to id
    }.distinctBy { it.first }.sortedByDescending { it.first }
    val insertions = mutableListOf<Pair<Int, Int>>()
    for ((line, id) in targets) {
        val h = current.headlines.first { it.lineIndex == line }
        val text = OrgMutations.upsertProperty(current, h, "ID", id)
        val next = OrgParser.parse(text, doc.keywords)
        insertions += line to (next.lines.size - current.lines.size)
        current = next
    }
    return MoveTextEdit(current.text, insertions.reversed())
}
