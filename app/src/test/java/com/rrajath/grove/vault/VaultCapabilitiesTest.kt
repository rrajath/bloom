package com.rrajath.grove.vault

import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.vault.storage.StorageCapabilities
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageOp
import com.rrajath.grove.vault.storage.StorageResult
import com.rrajath.grove.vault.storage.VaultCapabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder operations run file by file; one file the provider protects must stop them before the first one. */
class VaultCapabilitiesTest {

    private val store = FakeFileStore(
        mapOf("work/a.org" to "* a", "work/b.org" to "* b", "home.org" to "* h"),
    )
    private val vault = Vault(store)

    private fun protect(path: String, caps: StorageCapabilities) {
        store.capabilities.value = VaultCapabilities(files = mapOf(path to caps))
    }

    @Test
    fun `deleting a folder with an undeletable file deletes nothing`() = runTest {
        protect("work/b.org", StorageCapabilities(delete = false))

        assertEquals(StorageError.Unsupported(StorageOp.Delete), vault.deleteFolder("work"))
        assertTrue(store.exists("work/a.org"))
        assertTrue(store.exists("work/b.org"))
    }

    @Test
    fun `renaming a folder with an unmovable file moves nothing`() = runTest {
        protect("work/a.org", StorageCapabilities(move = false, delete = false))

        assertEquals(StorageError.Unsupported(StorageOp.Move), vault.renameFolder("work", "job"))
        assertTrue(store.exists("work/a.org"))
        assertTrue(store.exists("work/b.org"))
    }

    @Test
    fun `a file that can only be copied and deleted doesn't block a folder rename`() = runTest {
        protect("work/a.org", StorageCapabilities(move = false))

        assertTrue(vault.renameFolder("work", "job") is StorageResult.Ok)
        assertTrue(store.exists("job/a.org"))
    }

    @Test
    fun `capabilities pass through from the store`() {
        protect("home.org", StorageCapabilities(rename = false))
        assertEquals(StorageCapabilities(rename = false), vault.capabilities.value.ofFile("home.org"))
    }
}
