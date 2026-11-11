package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline

/**
 * The org entry whose attachment directory a link or an attach action refers
 * to: a heading, or the file itself (content above the first heading).
 */
sealed interface AttachOwner {
    /** The owner's `:ID:`, if it has one. */
    val id: String?

    /**
     * The `:DIR:` (or legacy `:ATTACH_DIR:`) that decides the owner's folder, if
     * any. From [resolveOwnerAtLine] it can be inherited ([Heading.dirFrom]
     * names the supplier); from [ownerOf] / [fileOwner] it is the entry's own.
     */
    val dirProperty: String?

    data class Heading(
        val lineIndex: Int,
        /** Ancestor titles then the heading's own title, so the owner can be re-found after edits. */
        val titlePath: List<String>,
        override val id: String?,
        override val dirProperty: String?,
        /**
         * The ancestor heading or file whose `:DIR:` / `:ATTACH_DIR:` this heading
         * inherits as [dirProperty]; null when the value is the heading's own or absent.
         */
        val dirFrom: AttachOwner? = null,
    ) : AttachOwner {
        val title: String get() = titlePath.lastOrNull().orEmpty()
    }

    data class File(
        override val id: String?,
        override val dirProperty: String?,
    ) : AttachOwner
}

/** Case-insensitive property lookup (org property keys are case-insensitive). */
private fun Map<String, String>.prop(key: String): String? =
    entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

private fun OrgDocument.fileProp(key: String): String? =
    filePropertyDrawer.firstOrNull { it.first.equals(":$key:", ignoreCase = true) }
        ?.second?.takeIf { it.isNotBlank() }

/** The heading's own `:DIR:` (or legacy `:ATTACH_DIR:`); the index stores it for the Vault scope. */
fun OrgHeadline.attachDirProperty(): String? = properties.prop("DIR") ?: properties.prop("ATTACH_DIR")

/** The file-level `:DIR:` (or legacy `:ATTACH_DIR:`); the index stores it for the Vault scope. */
fun OrgDocument.fileAttachDirProperty(): String? = fileProp("DIR") ?: fileProp("ATTACH_DIR")

/** Titles from the root ancestor down to [h] itself. */
fun OrgDocument.titlePath(h: OrgHeadline): List<String> {
    val path = ArrayDeque<String>()
    var cur: OrgHeadline? = h
    while (cur != null) {
        path.addFirst(cur.title)
        cur = parent(cur)
    }
    return path.toList()
}

/** The owner object for heading [h] (no inheritance). */
fun OrgDocument.ownerOf(h: OrgHeadline): AttachOwner.Heading =
    AttachOwner.Heading(h.lineIndex, titlePath(h), h.properties.prop("ID"), h.attachDirProperty())

/** The file-level owner (no inheritance). */
fun OrgDocument.fileOwner(): AttachOwner.File =
    AttachOwner.File(fileProp("ID"), fileAttachDirProperty())

/** The heading whose section contains [lineIndex], or null above the first heading. */
fun OrgDocument.headingAtLine(lineIndex: Int): OrgHeadline? =
    headlines.lastOrNull { it.lineIndex <= lineIndex }

/** Zero-based line index of character [offset] in [text]. */
internal fun lineOfOffset(text: String, offset: Int): Int {
    val clamped = offset.coerceIn(0, text.length)
    var line = 0
    for (i in 0 until clamped) if (text[i] == '\n') line++
    return line
}

/**
 * The owner for a cursor at character [offset] in this document's text; see
 * [resolveOwnerAtLine].
 */
fun resolveOwner(doc: OrgDocument, offset: Int): AttachOwner =
    resolveOwnerAtLine(doc, lineOfOffset(doc.text, offset))

/**
 * The owner for line [lineIndex], following `org-attach-dir` with `:DIR:` and
 * `:ATTACH_DIR:` inherited and `:ID:` not (`org-attach-use-inheritance
 * 'selective`, `org-use-property-inheritance '("DIR" "ATTACH_DIR")`):
 *
 * 1. The heading at the line; none means the file owner.
 * 2. `:DIR:` on the heading, then its ancestors nearest first, then the file drawer.
 * 3. Else `:ATTACH_DIR:` in the same order.
 * 4. Else the heading's own `:ID:` (never an ancestor's), or nothing.
 *
 * The result is always the heading itself (so links land in its body), with
 * [AttachOwner.dirProperty] set to the winning value and [AttachOwner.Heading.dirFrom]
 * naming the ancestor or file that supplied it.
 */
fun resolveOwnerAtLine(doc: OrgDocument, lineIndex: Int): AttachOwner {
    val h = doc.headingAtLine(lineIndex) ?: return doc.fileOwner()
    val own = doc.ownerOf(h)
    val chain = buildList {
        var cur: OrgHeadline? = h
        while (cur != null) {
            add(DirSupplier(doc.ownerOf(cur), cur.properties.prop("DIR"), cur.properties.prop("ATTACH_DIR")))
            cur = doc.parent(cur)
        }
        add(DirSupplier(doc.fileOwner(), doc.fileProp("DIR"), doc.fileProp("ATTACH_DIR")))
    }
    val (supplier, value) = chain.firstNotNullOfOrNull { s -> s.dir?.let { s to it } }
        ?: chain.firstNotNullOfOrNull { s -> s.attachDir?.let { s to it } }
        ?: return own
    return own.copy(dirProperty = value, dirFrom = supplier.owner.takeIf { supplier !== chain.first() })
}

private class DirSupplier(val owner: AttachOwner, val dir: String?, val attachDir: String?)

/**
 * Re-find [owner] in a (possibly edited) [doc]: by `:ID:` first, then by title
 * path. Null when a heading owner can no longer be found. A file owner always
 * resolves to the current file-level owner.
 */
fun relocateOwner(doc: OrgDocument, owner: AttachOwner): AttachOwner? = when (owner) {
    is AttachOwner.File -> doc.fileOwner()
    is AttachOwner.Heading -> {
        val byId = owner.id?.let { id ->
            doc.headlines.firstOrNull { it.properties.prop("ID") == id }
        }
        val match = byId ?: doc.headlines.firstOrNull { doc.titlePath(it) == owner.titlePath }
        match?.let { doc.ownerOf(it) }
    }
}
