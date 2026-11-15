package com.rrajath.grove.ui.vault

import com.rrajath.grove.vault.storage.StorageCapabilities
import com.rrajath.grove.vault.storage.StorageOp
import com.rrajath.grove.vault.storage.VaultCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemActionBlocksTest {

    private val seafileFile = StorageCapabilities(delete = false, rename = false, move = false, copy = false)

    @Test
    fun `a fully capable notebook blocks nothing`() {
        assertEquals(ItemActionBlocks.NONE, ItemActionBlocks.forNotebook(VaultCapabilities.UNKNOWN, "a.org"))
    }

    @Test
    fun `a Seafile notebook blocks rename, move and delete with their own reasons`() {
        val caps = VaultCapabilities(files = mapOf("a.org" to seafileFile))

        assertEquals(
            ItemActionBlocks(rename = StorageOp.Rename, move = StorageOp.Move, delete = StorageOp.Delete),
            ItemActionBlocks.forNotebook(caps, "a.org"),
        )
    }

    @Test
    fun `a notebook that can be copied and deleted can still move`() {
        val caps = VaultCapabilities(files = mapOf("a.org" to StorageCapabilities(move = false)))
        assertEquals(ItemActionBlocks.NONE, ItemActionBlocks.forNotebook(caps, "a.org"))
    }

    @Test
    fun `a folder is blocked by its least capable notebook`() {
        val caps = VaultCapabilities(
            files = mapOf("work/a.org" to StorageCapabilities.ALL, "work/deep/b.org" to seafileFile, "home.org" to seafileFile),
        )

        assertEquals(
            ItemActionBlocks(rename = StorageOp.Move, delete = StorageOp.Delete),
            ItemActionBlocks.forFolder(caps, "work"),
        )
        assertEquals(ItemActionBlocks.NONE, ItemActionBlocks.forFolder(caps, "other"))
    }
}
