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

    /** The owner's `:DIR:` (or legacy `:ATTACH_DIR:`), if it has one. */
    val dirProperty: String?

    data class Heading(
        val lineIndex: Int,
        /** Ancestor titles then the heading's own title, so the owner can be re-found after edits. */
        val titlePath: List<String>,
        override val id: String?,
        override val dirProperty: String?,
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
 * The owner for a cursor at character [offset] in this document's text: the
 * nearest heading at or above the cursor line, or [AttachOwner.File] above the
 * first heading. With [AttachConfig.useInheritance], an owner without `:DIR:` or
 * `:ID:` defers to the nearest ancestor (then the file) that has one, `:DIR:`
 * first, the way `org-entry-get` with inheritance resolves them.
 */
fun resolveOwner(doc: OrgDocument, offset: Int, config: AttachConfig): AttachOwner =
    resolveOwnerAtLine(doc, lineOfOffset(doc.text, offset), config)

fun resolveOwnerAtLine(doc: OrgDocument, lineIndex: Int, config: AttachConfig): AttachOwner {
    val h = doc.headingAtLine(lineIndex) ?: return doc.fileOwner()
    val own = doc.ownerOf(h)
    if (!config.useInheritance || own.id != null || own.dirProperty != null) return own
    val chain = buildList<AttachOwner> {
        var p = doc.parent(h)
        while (p != null) {
            add(doc.ownerOf(p))
            p = doc.parent(p)
        }
        add(doc.fileOwner())
    }
    return chain.firstOrNull { it.dirProperty != null }
        ?: chain.firstOrNull { it.id != null }
        ?: own
}

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
