package com.rrajath.grove.attach

import androidx.annotation.StringRes
import com.rrajath.grove.R
import com.rrajath.grove.vault.FileEntry

/** Which attach dirs the edit-mode attach sheet lists. */
enum class AttachScope(@StringRes val labelRes: Int) {
    HEADING(R.string.attach_scope_heading),
    FILE(R.string.attach_scope_file),
    VAULT(R.string.attach_scope_vault),
}

/**
 * One listed attachment and where it lives. [ownerTitle] is null for a file-level owner.
 * [source] is the row's "where from" label, built from user data only; empty for This heading rows.
 */
data class ScopedAttachment(
    val file: FileEntry,
    val dir: String,
    val ownerOrgFile: String,
    val ownerTitle: String?,
    val source: String,
)

/** A heading can browse all three scopes; file-level content has no "This heading". */
fun scopesFor(owner: AttachOwner): List<AttachScope> = when (owner) {
    is AttachOwner.Heading -> listOf(AttachScope.HEADING, AttachScope.FILE, AttachScope.VAULT)
    is AttachOwner.File -> listOf(AttachScope.FILE, AttachScope.VAULT)
}

/** [target] (vault-relative) as a path relative to the directory holding [fromOrgFile] (vault-relative). */
fun relativeLinkPath(fromOrgFile: String, target: String): String {
    val from = fromOrgFile.split('/').dropLast(1)
    val to = target.split('/')
    var common = 0
    while (common < from.size && common < to.size - 1 && from[common] == to[common]) common++
    return (List(from.size - common) { ".." } + to.drop(common)).joinToString("/")
}

// i18n: org syntax, never translate
fun fileLinkText(path: String): String = "[[file:$path]]"

/** Org link syntax can't hold `[` or `]` in a bare path. */
fun linkableName(path: String): Boolean = '[' !in path && ']' !in path

/** Vault row source: the org file's name, then the heading title when there is one. */
fun vaultSource(orgFile: String, title: String?): String {
    val base = orgFile.substringAfterLast('/')
    // i18n: separator glyph, not translated
    return if (title.isNullOrEmpty()) base else "$base › $title"
}
