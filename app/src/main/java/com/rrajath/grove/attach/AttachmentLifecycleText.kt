package com.rrajath.grove.attach

import com.rrajath.grove.R
import com.rrajath.grove.ui.util.UiText

/**
 * `Notes/data/` (or `data/` at the vault root): where [orgFile]'s `:ID:`
 * attachments live under [config]. A vault-root folder reads `/attach/`, so it
 * isn't mistaken for one next to the note.
 */
fun attachDirLabel(orgFile: String, config: AttachConfig): String =
    if (config.isVaultRoot) "/" + folderLabel(config.rootFor(orgFile)).trimStart('/')
    else folderLabel(config.rootFor(orgFile))

/** `Archive/` (or the vault root's display, `/`). */
fun folderLabel(dir: String): String = if (dir.trim('/').isEmpty()) "/" else "${dir.trim('/')}/"

/** The 1l toast once a notebook and its attachments moved. */
fun notebookMoveMessage(leaf: String, newDir: String, result: LifecycleResult): UiText {
    val done = result.moved + result.copied
    return when {
        result.failed > 0 -> UiText.plural(R.plurals.attach_notebook_moved_failed, result.failed, result.failed, leaf)
        done == 0 -> UiText.res(R.string.attach_notebook_moved, leaf, folderLabel(newDir))
        else -> UiText.plural(R.plurals.attach_notebook_moved_with, done, done, leaf, folderLabel(newDir))
    }
}

/** Like [formatAttachmentSize], but whole megabytes from 10 MB on (`41 MB`, as 1l/1n show). */
fun formatTotalSize(bytes: Long): UiText {
    val mb = 1024L * 1024L
    return if (bytes >= 10 * mb) UiText.res(R.string.attach_size_mb_whole, (bytes + mb / 2) / mb) else formatAttachmentSize(bytes)
}

/** `6 folders · 23 files · 41 MB` for the delete sheets. */
fun inventoryDetail(inventory: AttachmentInventory): UiText = UiText.res(
    R.string.attach_inventory_detail,
    UiText.plural(R.plurals.count_folders, inventory.folders),
    UiText.plural(R.plurals.count_files, inventory.files),
    formatTotalSize(inventory.bytes),
)

/** `travel.org → Archive/ · 6 folders · 41 MB`, the 1l subline. */
fun moveSubline(leaf: String, newDir: String, inventory: AttachmentInventory): UiText = UiText.res(
    R.string.attach_move_subline,
    leaf,
    folderLabel(newDir),
    UiText.plural(R.plurals.count_folders, inventory.folders),
    formatTotalSize(inventory.bytes),
)
