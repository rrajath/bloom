package com.rrajath.grove.vault.storage

import android.provider.DocumentsContract.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCapabilitiesTest {

    @Test
    fun `unreported flags allow everything`() {
        assertEquals(StorageCapabilities.ALL, StorageCapabilities.fromFlags(null, isDir = false))
        assertEquals(StorageCapabilities.ALL, StorageCapabilities.fromFlags(null, isDir = true))
    }

    @Test
    fun `Seafile's write-only file flags allow writing and nothing else`() {
        val caps = StorageCapabilities.fromFlags(Document.FLAG_SUPPORTS_WRITE, isDir = false)

        assertTrue(caps.write)
        assertFalse(caps.delete || caps.rename || caps.move || caps.copy || caps.createChildren)
        assertFalse(caps.canRelocate)
    }

    @Test
    fun `each flag maps to its capability`() {
        val file = StorageCapabilities.fromFlags(
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_WRITE,
            isDir = false,
        )
        assertEquals(StorageCapabilities(createChildren = false), file)

        val dir = StorageCapabilities.fromFlags(Document.FLAG_DIR_SUPPORTS_CREATE, isDir = true)
        assertEquals(StorageCapabilities(delete = false, rename = false, move = false, copy = false), dir)
    }

    @Test
    fun `a file that can be deleted can still be relocated by copy and delete`() {
        assertTrue(StorageCapabilities(move = false).canRelocate)
        assertTrue(StorageCapabilities(delete = false).canRelocate)
        assertFalse(StorageCapabilities(move = false, delete = false).canRelocate)
    }

    @Test
    fun `supports answers per operation`() {
        val caps = StorageCapabilities(rename = false)
        assertFalse(caps.supports(StorageOp.Rename))
        assertTrue(caps.supports(StorageOp.Delete))
        assertTrue(caps.supports(StorageOp.Read))
    }

    @Test
    fun `a folder's file capabilities are the least capable file under it`() {
        val vault = VaultCapabilities(
            files = mapOf(
                "a.org" to StorageCapabilities.ALL,
                "work/b.org" to StorageCapabilities(delete = false),
                "work/deep/c.org" to StorageCapabilities(rename = false),
                "workshop/d.org" to StorageCapabilities(move = false),
            ),
        )

        val work = vault.ofFilesUnder("work")
        assertFalse(work.delete)
        assertFalse(work.rename)
        assertTrue(work.move)
        assertEquals(StorageCapabilities.ALL, vault.ofFile("a.org"))
        assertEquals(StorageCapabilities.ALL, vault.ofFile("never-listed.org"))
    }
}
