package com.rrajath.grove.ui.vault

import com.rrajath.grove.vault.storage.StorageOp
import com.rrajath.grove.vault.storage.VaultCapabilities

/**
 * Which file actions the vault's provider rules out for one notebook or
 * folder row. Each field is null when the action is available, or the
 * operation the provider doesn't allow, which names the reason line ("Seafile
 * doesn't let other apps move files"). Decision 2: such actions show
 * disabled with a reason, never hidden.
 */
data class ItemActionBlocks(
    val rename: StorageOp? = null,
    val move: StorageOp? = null,
    val delete: StorageOp? = null,
) {
    companion object {
        val NONE = ItemActionBlocks()

        /** A notebook: rename stays in its folder, move goes to another folder. */
        fun forNotebook(caps: VaultCapabilities, path: String): ItemActionBlocks {
            val c = caps.ofFile(path)
            return ItemActionBlocks(
                rename = StorageOp.Rename.takeIf { !c.rename },
                move = StorageOp.Move.takeIf { !c.canRelocate },
                delete = StorageOp.Delete.takeIf { !c.delete },
            )
        }

        /**
         * A folder: renaming moves every notebook under it into the new folder,
         * and deleting deletes each one, so the least capable file decides.
         */
        fun forFolder(caps: VaultCapabilities, dir: String): ItemActionBlocks {
            val c = caps.ofFilesUnder(dir)
            return ItemActionBlocks(
                rename = StorageOp.Move.takeIf { !c.canRelocate },
                delete = StorageOp.Delete.takeIf { !c.delete },
            )
        }
    }
}
