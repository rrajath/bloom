package com.rrajath.grove.ui.vault

import com.rrajath.grove.vault.StorageException
import com.rrajath.grove.vault.storage.StorageError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StorageFailureHandlerTest {

    @Test
    fun `a StorageException is found directly and through its causes`() {
        val storage = StorageException(StorageError.Offline)

        assertSame(storage, findStorageException(storage))
        assertSame(storage, findStorageException(RuntimeException("wrapped", IllegalStateException(storage))))
        assertEquals(StorageError.Offline, findStorageException(RuntimeException(storage))?.error)
    }

    @Test
    fun `other failures are not swallowed`() {
        assertNull(findStorageException(IllegalStateException("a real bug")))
        assertNull(findStorageException(RuntimeException("x", NullPointerException())))
    }
}
