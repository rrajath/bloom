package com.rrajath.grove.attach

import com.rrajath.grove.data.IndexedAttachOwner
import com.rrajath.grove.org.OrgDocument

/**
 * Lists attachments for the attach sheet's This file and Vault scopes: resolves
 * each owner's attach dir, lists every existing dir once (the first owner to
 * claim a dir names it), newest first. Does file IO; callers run it on IO.
 *
 * Pass a resolver over [ListedDirs] for a whole-vault scan so existence checks
 * cost one listing per shard instead of one query per candidate.
 */
class AttachmentScopeScanner(
    private val files: AttachmentFiles,
    private val resolver: AttachDirResolver,
) {

    private data class Owner(val orgFile: String, val owner: AttachOwner, val title: String?, val source: String)

    /** The file-level dir plus every heading in [doc] with an `:ID:` or `:DIR:`. */
    suspend fun scanFile(orgFile: String, doc: OrgDocument): List<ScopedAttachment> {
        val base = orgFile.substringAfterLast('/')
        return scan(bufferOwners(orgFile, doc) { title -> title ?: base })
    }

    /** Every indexed owner, with [currentOrgFile]'s rows taken from the open buffer instead of the index. */
    suspend fun scanVault(
        currentOrgFile: String,
        currentDoc: OrgDocument,
        indexed: List<IndexedAttachOwner>,
    ): List<ScopedAttachment> {
        val current = bufferOwners(currentOrgFile, currentDoc) { title -> vaultSource(currentOrgFile, title) }
        val others = indexed.filter { it.fileName != currentOrgFile }.map {
            // Carry `:DIR:` too, so the resolver applies the same `:DIR:`-before-`:ID:` precedence as the buffer.
            val owner = if (it.title == null) {
                AttachOwner.File(it.orgId, it.dirProperty)
            } else {
                AttachOwner.Heading(-1, listOf(it.title), it.orgId, it.dirProperty)
            }
            Owner(it.fileName, owner, it.title, vaultSource(it.fileName, it.title))
        }
        return scan(current + others)
    }

    private fun bufferOwners(orgFile: String, doc: OrgDocument, source: (String?) -> String): List<Owner> {
        val file = doc.fileOwner()
        val headings = doc.headlines.map { doc.ownerOf(it) }.filter { it.id != null || it.dirProperty != null }
        return listOf(Owner(orgFile, file, null, source(null))) +
            headings.map { Owner(orgFile, it, it.title, source(it.title)) }
    }

    private suspend fun scan(owners: List<Owner>): List<ScopedAttachment> {
        val seen = HashSet<String>()
        val rows = mutableListOf<ScopedAttachment>()
        for (o in owners) {
            val dir = resolver.readDir(o.orgFile, o.owner) ?: continue
            if (!seen.add(dir)) continue
            files.list(dir).mapTo(rows) { ScopedAttachment(it, dir, o.orgFile, o.title, o.source) }
        }
        return rows.sortedByDescending { it.file.lastModified }
    }
}
