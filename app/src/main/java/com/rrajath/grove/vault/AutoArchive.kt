package com.rrajath.grove.vault

import com.rrajath.grove.org.ArchiveLocation
import com.rrajath.grove.org.ArchiveProperties
import com.rrajath.grove.org.ArchiveTarget
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.settings.GroveSettings
import java.time.LocalDateTime

/** Outcome of [AutoArchive.apply]: either a plain state change, or a state change plus a refile. */
sealed class StateChangeResult {
    data class Plain(val fileName: String, val text: String, val doc: OrgDocument) : StateChangeResult()
    data class Archived(
        val sourceFile: String,
        val sourceText: String,
        val sourceDoc: OrgDocument,
        val destFile: String,
        val destText: String,
        /** [destFile]'s content before this write (for undo); equals the pre-mutation source text when same-file. */
        val destTextBefore: String,
        /** Line index of the moved headline within [destText]. */
        val destLineIndex: Int,
        val label: String,
    ) : StateChangeResult()
}

/** Outcome of [AutoArchive.applyToEach]. */
class BulkStateChange(
    val sourceText: String,
    val sourceDoc: OrgDocument,
    /** Other files archived subtrees went to: file name, text before, text after. */
    val destWrites: List<Triple<String, String, String>>,
    /** Headings whose keyword changed. */
    val changed: Int,
    /** Archive destination label of each heading that was refiled, in file order. */
    val archivedTo: List<String>,
    /** A heading line of the original document to that heading's line now; null once anything was archived. */
    val remap: (Int) -> Int?,
)

/** Delete-subtree + find-or-create-heading-path + insert, same-file or cross-file. */
data class RefileWrite(
    val sourceFile: String,
    val sourceText: String,
    val destFile: String,
    val destText: String,
    /** [destFile]'s content before this write (for undo); equals the source's pre-mutation text when same-file. */
    val destTextBefore: String,
    /** Line index of the moved headline within [destText]. */
    val destLineIndex: Int,
    val label: String,
)

/**
 * One shared "mark done, maybe auto-archive" mutation, used by every surface that can change a
 * TODO state (Outline, Agenda, Search, edit-mode metadata sheet) so auto-archive behaves
 * identically everywhere: any done-type keyword (not just literal `DONE`) triggers it, and the
 * destination is resolved the same way org resolves `:ARCHIVE:` — heading property, ancestor
 * property, file `#+ARCHIVE:` keyword, then (new) the Settings default.
 *
 * The state change and the refile (when it fires) are computed as one mutation before anything
 * is saved, so a caller's usual pre-mutation undo snapshot reverts both together for free.
 */
object AutoArchive {

    /**
     * The Settings-configured default archive location, or null if none is set or auto-archive
     * is off (the location belongs to that setting; `ARCHIVE` properties still resolve without it).
     */
    fun settingsFallback(settings: GroveSettings): ArchiveTarget? =
        settings.autoArchiveFile?.takeIf { settings.autoArchiveDoneItems }?.let { file ->
            ArchiveTarget(file, settings.autoArchiveHeadingPath.split('/').filter { it.isNotEmpty() })
        }

    suspend fun apply(
        vault: Vault,
        settings: GroveSettings,
        doc: OrgDocument,
        fileName: String,
        headline: OrgHeadline,
        newKeyword: String?,
        now: LocalDateTime,
    ): StateChangeResult {
        val plainText = OrgMutations.changeKeyword(doc, headline, newKeyword, doc.keywords, now)
        val plainDoc = OrgParser.parse(plainText, doc.keywords)
        fun plain() = StateChangeResult.Plain(fileName, plainText, plainDoc)

        val movedHeadline = plainDoc.headlines.firstOrNull { it.lineIndex == headline.lineIndex } ?: return plain()

        // Check the headline's keyword as it actually ended up after the mutation, not the
        // keyword the caller requested: a repeating SCHEDULED/DEADLINE keeps markDone() from
        // ever setting a done keyword (org semantics — it just advances the date), so archiving
        // here based on the requested newKeyword would refile a still-active recurring task.
        val shouldArchive = settings.autoArchiveDoneItems &&
            movedHeadline.keyword != null && doc.keywords.isDone(movedHeadline.keyword)
        if (!shouldArchive) return plain()

        val target = ArchiveLocation.resolve(plainDoc, movedHeadline, settingsFallback(settings), fileName) ?: return plain()
        val write = refileSubtree(vault, plainDoc, fileName, movedHeadline, target, archivedAt = now) ?: return plain()

        return StateChangeResult.Archived(
            sourceFile = write.sourceFile,
            sourceText = write.sourceText,
            sourceDoc = OrgParser.parse(write.sourceText, doc.keywords),
            destFile = write.destFile,
            destText = write.destText,
            destTextBefore = write.destTextBefore,
            destLineIndex = write.destLineIndex,
            label = write.label,
        )
    }

    /**
     * Refile [source]'s subtree out of [sourceDoc] straight to [target], creating any missing
     * destination file/heading path. Shared by [apply] and the manual per-note "Archive" quick
     * action. Returns null when the destination file doesn't exist and [createFileIfMissing] is
     * false. With [archivedAt] (an archive, not a plain refile), the moved heading gets Emacs's
     * `ARCHIVE_*` context properties stamped into its `:PROPERTIES:` drawer.
     */
    suspend fun refileSubtree(
        vault: Vault,
        sourceDoc: OrgDocument,
        sourceFile: String,
        source: OrgHeadline,
        target: ArchiveTarget,
        createFileIfMissing: Boolean = true,
        archivedAt: LocalDateTime? = null,
    ): RefileWrite? {
        val label = label(target)
        val subtree = OrgMutations.subtreeText(sourceDoc, source).let { text ->
            if (archivedAt == null) text else stamp(vault, sourceDoc, sourceFile, source, text, archivedAt)
        }

        if (target.fileName == sourceFile) {
            val afterDelete = OrgParser.parse(OrgMutations.deleteSubtree(sourceDoc, source), sourceDoc.keywords)
            val (docAfterPath, destHeadline) = ArchiveLocation.findOrCreateHeadingPath(afterDelete, target.headingPath)
            val (finalText, insertAt) = OrgMutations.refileInsert(docAfterPath, destHeadline, subtree)
            return RefileWrite(sourceFile, finalText, sourceFile, finalText, sourceDoc.text, insertAt, label)
        }

        var destDoc = vault.open(target.fileName)
        if (destDoc == null) {
            if (!createFileIfMissing) return null
            vault.createFile(target.fileName)
            destDoc = vault.open(target.fileName) ?: return null
        }
        val destTextBefore = destDoc.text
        val srcText = OrgMutations.deleteSubtree(sourceDoc, source)
        val (docAfterPath, destHeadline) = ArchiveLocation.findOrCreateHeadingPath(destDoc, target.headingPath)
        val (dstText, insertAt) = OrgMutations.refileInsert(docAfterPath, destHeadline, subtree)
        return RefileWrite(sourceFile, srcText, target.fileName, dstText, destTextBefore, insertAt, label)
    }

    /**
     * [apply] for several headings of one file at once: set [newKeyword] on each
     * heading at [lines] (one already there is skipped), then, with auto-archive
     * on, refile each one that ended up done-type, exactly as [apply] would one
     * at a time. All archived subtrees are lifted out bottom-up first, so no
     * removal shifts a heading still to be handled, and only then inserted at
     * their destinations, in their original order. Returns null when no keyword
     * changed. Nothing is saved; a same-file destination is part of
     * [BulkStateChange.sourceText].
     */
    suspend fun applyToEach(
        vault: Vault,
        settings: GroveSettings,
        doc: OrgDocument,
        fileName: String,
        lines: Set<Int>,
        newKeyword: String?,
        now: LocalDateTime,
    ): BulkStateChange? {
        val changedIndices = doc.headlines
            .filter { it.lineIndex in lines && it.keyword != newKeyword }
            .map { it.index }
        val edit = OrgMutations.editEach(doc, lines) { d, h ->
            if (h.keyword == newKeyword) null else OrgMutations.changeKeyword(d, h, newKeyword, d.keywords, now)
        } ?: return null
        var cur = edit.doc

        val pending = mutableListOf<Pair<ArchiveTarget, String>>()
        // Other destination files: text before, document with the inserts so far.
        val dests = linkedMapOf<String, Pair<String, OrgDocument>>()
        if (settings.autoArchiveDoneItems) {
            val fallback = settingsFallback(settings)
            // Descending: removing a subtree only renumbers headings after it.
            for (index in changedIndices.sortedDescending()) {
                val h = cur.headlines.getOrNull(index) ?: continue
                // As in [apply]: a repeating task keeps an active keyword, so it never archives.
                if (h.keyword == null || !doc.keywords.isDone(h.keyword)) continue
                val target = ArchiveLocation.resolve(cur, h, fallback, fileName) ?: continue
                // Open the destination before lifting the subtree out, so a file
                // that can't be opened leaves the heading where it is.
                if (target.fileName != fileName && target.fileName !in dests) {
                    val destDoc = openOrCreate(vault, target.fileName) ?: continue
                    dests[target.fileName] = destDoc.text to destDoc
                }
                pending += target to stamp(vault, cur, fileName, h, OrgMutations.subtreeText(cur, h), now)
                cur = OrgParser.parse(OrgMutations.deleteSubtree(cur, h), doc.keywords)
            }
        }
        if (pending.isEmpty()) {
            return BulkStateChange(cur.text, cur, emptyList(), edit.applied, emptyList(), edit.remap)
        }

        for ((target, subtree) in pending.asReversed()) {
            if (target.fileName == fileName) {
                cur = insertAtPath(cur, target.headingPath, subtree)
            } else {
                val (before, destDoc) = dests.getValue(target.fileName)
                dests[target.fileName] = before to insertAtPath(destDoc, target.headingPath, subtree)
            }
        }
        return BulkStateChange(
            sourceText = cur.text,
            sourceDoc = cur,
            destWrites = dests.map { (file, texts) -> Triple(file, texts.first, texts.second.text) },
            changed = edit.applied,
            archivedTo = pending.asReversed().map { label(it.first) },
            remap = { null },
        )
    }

    private fun label(target: ArchiveTarget): String =
        (listOf(target.fileName.removeSuffix(".org")) + target.headingPath).joinToString(" › ")

    /** Emacs's `ARCHIVE_*` context properties, stamped into [subtree]'s `:PROPERTIES:` drawer. */
    private fun stamp(
        vault: Vault,
        sourceDoc: OrgDocument,
        sourceFile: String,
        source: OrgHeadline,
        subtree: String,
        archivedAt: LocalDateTime,
    ): String = ArchiveProperties.stamp(
        subtree,
        sourceDoc.keywords,
        ArchiveProperties.of(sourceDoc, source, sourceFile, vault.absolutePath(sourceFile), archivedAt),
    )

    /** [subtree] as the last child of [path] in [doc], creating any missing heading on the way. */
    private fun insertAtPath(doc: OrgDocument, path: List<String>, subtree: String): OrgDocument {
        val (docAfterPath, destHeadline) = ArchiveLocation.findOrCreateHeadingPath(doc, path)
        val (text, _) = OrgMutations.refileInsert(docAfterPath, destHeadline, subtree)
        return OrgParser.parse(text, doc.keywords)
    }

    private suspend fun openOrCreate(vault: Vault, fileName: String): OrgDocument? =
        vault.open(fileName) ?: run {
            vault.createFile(fileName)
            vault.open(fileName)
        }
}
