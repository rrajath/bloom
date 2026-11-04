package com.rrajath.grove.attach

import com.rrajath.grove.ui.util.pluralCount

/** `Notes/data/` (or `data/` at the vault root): where [orgFile]'s attachments live by default. */
fun dataDirLabel(orgFile: String): String {
    val dir = dirOf(orgFile)
    return if (dir.isEmpty()) "data/" else "$dir/data/"
}

/** `Archive/` (or the vault root's display, `/`). */
fun folderLabel(dir: String): String = if (dir.trim('/').isEmpty()) "/" else "${dir.trim('/')}/"

/** The 1l toast once a notebook and its attachments moved. */
fun notebookMoveMessage(leaf: String, newDir: String, result: LifecycleResult): String {
    val done = result.moved + result.copied
    return when {
        result.failed > 0 -> "Moved $leaf; ${pluralCount(result.failed, "attachment folder")} couldn't be moved"
        done == 0 -> "Moved $leaf to ${folderLabel(newDir)}"
        else -> "Moved $leaf and ${pluralCount(done, "attachment folder")} to ${folderLabel(newDir)}"
    }
}

/** Like [formatAttachmentSize], but whole megabytes from 10 MB on (`41 MB`, as 1l/1n show). */
fun formatTotalSize(bytes: Long): String {
    val mb = 1024L * 1024L
    return if (bytes >= 10 * mb) "${(bytes + mb / 2) / mb} MB" else formatAttachmentSize(bytes)
}

/** `6 folders · 23 files · 41 MB` for the delete sheets. */
fun inventoryDetail(inventory: AttachmentInventory): String =
    "${pluralCount(inventory.folders, "folder")} · ${pluralCount(inventory.files, "file")} · " +
        formatTotalSize(inventory.bytes)

/** `travel.org → Archive/ · 6 folders · 41 MB`, the 1l subline. */
fun moveSubline(leaf: String, newDir: String, inventory: AttachmentInventory): String =
    "$leaf → ${folderLabel(newDir)} · ${pluralCount(inventory.folders, "folder")} · ${formatTotalSize(inventory.bytes)}"
