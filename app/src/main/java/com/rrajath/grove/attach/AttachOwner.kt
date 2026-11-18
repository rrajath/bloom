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

/** The heading whose section contains [lineIndex], or null above the first heading. Binary search. */
fun OrgDocument.headingAtLine(lineIndex: Int): OrgHeadline? {
    var lo = 0
    var hi = headlines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (headlines[mid].lineIndex <= lineIndex) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return headlines.getOrNull(found)
}

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
    // The heading, then its ancestors nearest first; one parent walk, title paths as prefixes.
    val ancestry = generateSequence(h) { doc.parent(it) }.toList()
    val rootFirstTitles = ancestry.asReversed().map { it.title }
    val chain = buildList {
        for ((k, cur) in ancestry.withIndex()) {
            val owner = AttachOwner.Heading(
                cur.lineIndex, rootFirstTitles.take(ancestry.size - k), cur.properties.prop("ID"), cur.attachDirProperty(),
            )
            add(DirSupplier(owner, cur.properties.prop("DIR"), cur.properties.prop("ATTACH_DIR")))
        }
        add(DirSupplier(doc.fileOwner(), doc.fileProp("DIR"), doc.fileProp("ATTACH_DIR")))
    }
    val own = chain.first().owner as AttachOwner.Heading
    val (supplier, value) = chain.firstNotNullOfOrNull { s -> s.dir?.let { s to it } }
        ?: chain.firstNotNullOfOrNull { s -> s.attachDir?.let { s to it } }
        ?: return own
    return own.copy(dirProperty = value, dirFrom = supplier.owner.takeIf { supplier !== chain.first() })
}

private class DirSupplier(val owner: AttachOwner, val dir: String?, val attachDir: String?)

/**
 * Every heading's owner, indexed by [OrgHeadline.index], computed in one forward
 * pass with an ancestor stack (O(headings x depth)) instead of a parent walk per
 * heading: [own] as [ownerOf] gives it, [resolved] as [resolveOwnerAtLine] does.
 */
internal class HeadingOwners(
    val own: List<AttachOwner.Heading>,
    val resolved: List<AttachOwner.Heading>,
)

internal fun OrgDocument.headingOwners(): HeadingOwners {
    val file = fileOwner()
    val fileDir = fileProp("DIR")?.let { file to it }
    val fileAttachDir = fileProp("ATTACH_DIR")?.let { file to it }

    /** A heading on the current path, with the nearest `:DIR:` / `:ATTACH_DIR:` supplier at or above it. */
    class Frame(
        val level: Int,
        val owner: AttachOwner.Heading,
        val dir: Pair<AttachOwner, String>?,
        val attachDir: Pair<AttachOwner, String>?,
    )

    val stack = ArrayDeque<Frame>()
    val own = ArrayList<AttachOwner.Heading>(headlines.size)
    val resolved = ArrayList<AttachOwner.Heading>(headlines.size)
    for (h in headlines) {
        // Same rule as [OrgDocument.parent]: the nearest earlier heading with a lower level.
        while (stack.isNotEmpty() && stack.last().level >= h.level) stack.removeLast()
        val parent = stack.lastOrNull()
        val dirProp = h.properties.prop("DIR")
        val attachDirProp = h.properties.prop("ATTACH_DIR")
        val owner = AttachOwner.Heading(
            h.lineIndex,
            parent?.owner?.titlePath.orEmpty() + h.title,
            h.properties.prop("ID"),
            dirProp ?: attachDirProp,
        )
        val dir = dirProp?.let { owner to it } ?: parent?.dir ?: fileDir
        val attachDir = attachDirProp?.let { owner to it } ?: parent?.attachDir ?: fileAttachDir
        val win = dir ?: attachDir
        own += owner
        resolved += if (win == null) owner else owner.copy(dirProperty = win.second, dirFrom = win.first.takeIf { it !== owner })
        stack.addLast(Frame(h.level, owner, dir, attachDir))
    }
    return HeadingOwners(own, resolved)
}

/**
 * Re-finds owners in [doc] by `:ID:`, then title path (see [relocateOwner]),
 * with both lookups indexed once so relocating many owners is linear overall.
 */
internal class OwnerLocator(private val doc: OrgDocument, private val owners: HeadingOwners = doc.headingOwners()) {
    private val byId = HashMap<String, Int>()
    private val byPath = HashMap<List<String>, Int>()

    init {
        // First match wins, as in a front-to-back search.
        for ((i, o) in owners.own.withIndex()) {
            o.id?.let { byId.putIfAbsent(it, i) }
            byPath.putIfAbsent(o.titlePath, i)
        }
    }

    /** The [OrgHeadline.index] [owner] now lives at, or null when it's gone (or is the file owner). */
    fun headingIndex(owner: AttachOwner.Heading): Int? =
        owner.id?.let { byId[it] } ?: byPath[owner.titlePath]

    fun relocate(owner: AttachOwner): AttachOwner? = when (owner) {
        is AttachOwner.File -> doc.fileOwner()
        is AttachOwner.Heading -> headingIndex(owner)?.let { owners.own[it] }
    }
}

/**
 * Re-find [owner] in a (possibly edited) [doc]: by `:ID:` first, then by title
 * path. Null when a heading owner can no longer be found. A file owner always
 * resolves to the current file-level owner.
 */
fun relocateOwner(doc: OrgDocument, owner: AttachOwner): AttachOwner? = when (owner) {
    is AttachOwner.File -> doc.fileOwner()
    is AttachOwner.Heading -> OwnerLocator(doc).relocate(owner)
}
