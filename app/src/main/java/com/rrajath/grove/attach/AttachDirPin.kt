package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser

/**
 * The `:DIR:` a heading inherits (from an ancestor or the file drawer), kept
 * when the heading is refiled or archived: [of] reads it before the move,
 * [apply] writes it onto the moved subtree's root when the root would
 * otherwise resolve to another folder at the destination. No files move and
 * no `:ID:` is added; the heading simply keeps pointing where its files are.
 */
data class DirPin(
    /** The inherited value, as written at the source. */
    val value: String,
    /** The org file it was inherited in (relative values resolve next to it). */
    val sourceFile: String,
) {
    /** The folder [value] names, vault-relative; null for a `~` or absolute value (kept as written). */
    private val folder: String? get() = folderOf(sourceFile, value)

    /**
     * [subtree] (whose first line is the moved root) with `:DIR:` set to this
     * pin, rewritten relative to [destFile]'s folder, unless the root resolves
     * to the same folder there anyway. [destDoc] is the destination as the
     * subtree will be inserted into it and [destParent] the heading it goes
     * under (null: top level).
     */
    fun apply(subtree: String, destDoc: OrgDocument, destFile: String, destParent: OrgHeadline?): String {
        val there = if (destParent != null) resolveOwnerAtLine(destDoc, destParent.lineIndex).dirProperty
        else destDoc.fileAttachDirProperty()
        val src = folder
        val pinned = if (src == null) {
            if (there?.trim() == value.trim()) return subtree
            value.trim()
        } else {
            if (there != null && folderOf(destFile, there) == src) return subtree
            relativePath(dirOf(destFile), src)
        }
        val doc = OrgParser.parse(subtree, destDoc.keywords)
        val root = doc.headlines.firstOrNull() ?: return subtree
        return OrgMutations.upsertProperty(doc, root, "DIR", pinned)
    }

    companion object {
        /**
         * The pin for heading [h] of [doc] (in [file]): its inherited `:DIR:`
         * (or `:ATTACH_DIR:`), when it has no `:DIR:` of its own and its subtree
         * holds `[[attachment:]]` links that resolve through that folder. Null
         * otherwise, and the move goes ahead as before.
         */
        fun of(doc: OrgDocument, file: String, h: OrgHeadline): DirPin? {
            val owner = resolveOwnerAtLine(doc, h.lineIndex) as? AttachOwner.Heading ?: return null
            if (owner.dirFrom == null) return null
            val value = owner.dirProperty?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // Only worth pinning when something in the subtree links into that folder.
            val sub = OrgParser.parse(OrgMutations.subtreeText(doc, h), doc.keywords)
            val usesIt = sub.headlines.any { sh ->
                val o = resolveOwnerAtLine(sub, sh.lineIndex)
                // Resolves to nothing within the subtree: it inherits from outside, like the root.
                o.dirProperty == null && sub.attachmentLinkTargets(o).isNotEmpty()
            }
            return if (usesIt) DirPin(value, file) else null
        }

        private fun folderOf(orgFile: String, value: String): String? {
            val v = value.trim()
            if (v.isEmpty() || v.startsWith("~") || v.startsWith("/")) return null
            return normalizeVaultPath(joinPath(dirOf(orgFile), v))
        }

        /** [path] (vault-relative) as seen from directory [from]: `..` segments as needed, `.` for [from] itself. */
        internal fun relativePath(from: String, path: String): String {
            val a = from.split('/').filter { it.isNotEmpty() }
            val b = path.split('/').filter { it.isNotEmpty() }
            var common = 0
            while (common < a.size && common < b.size && a[common] == b[common]) common++
            val parts = List(a.size - common) { ".." } + b.drop(common)
            return parts.joinToString("/").ifEmpty { "." }
        }
    }
}
