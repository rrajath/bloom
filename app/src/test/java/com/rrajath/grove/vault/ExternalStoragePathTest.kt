package com.rrajath.grove.vault

import com.rrajath.grove.vault.storage.externalStoragePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalStoragePathTest {

    @Test
    fun `primary volume maps to internal shared storage`() {
        assertEquals(
            "/storage/emulated/0/Documents/org/work/tasks.org",
            externalStoragePath("primary:Documents/org", "work/tasks.org"),
        )
    }

    @Test
    fun `volume root tree has no extra separator`() {
        assertEquals("/storage/emulated/0/tasks.org", externalStoragePath("primary:", "tasks.org"))
    }

    @Test
    fun `removable volume mounts under its id`() {
        assertEquals("/storage/1234-ABCD/org/tasks.org", externalStoragePath("1234-ABCD:org", "tasks.org"))
    }

    @Test
    fun `id without a volume prefix has no path`() {
        assertNull(externalStoragePath("opaque-id", "tasks.org"))
    }
}
