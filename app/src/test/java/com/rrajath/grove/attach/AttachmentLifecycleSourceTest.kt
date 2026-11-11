package com.rrajath.grove.attach

import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.JvmFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentLifecycleSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var store: FileStore? = null
    private var config = AttachConfig.Default
    private var created = 0

    private fun source(enabled: Boolean = true) = AttachmentLifecycleSource(enabled, { store }, { config }) { s, c ->
        created++
        AttachmentLifecycle(s, IdHolders.None, c)
    }

    @Test
    fun `the very first read returns the lifecycle once a store exists`() {
        // Regression: the lifecycle used to be a lazily started stateIn whose
        // first read was its initial null, so the first notebook move after
        // launch skipped the attachments sheet.
        store = JvmFileStore(tmp.newFolder("vault"))
        assertNotNull(source().current())
    }

    @Test
    fun `one lifecycle per store, a new one when the store changes`() {
        val src = source()
        store = JvmFileStore(tmp.newFolder("a"))
        val first = src.current()
        assertSame(first, src.current())
        store = JvmFileStore(tmp.newFolder("b"))
        val second = src.current()
        assertNotSame(first, second)
        assertSame(second, src.current())
        assertEquals(2, created)
    }

    @Test
    fun `a config change makes a new lifecycle with that config`() {
        val src = source()
        store = JvmFileStore(tmp.newFolder("vault"))
        val first = src.current()
        config = AttachConfig(idDir = "attach/")
        val second = src.current()
        assertNotSame(first, second)
        assertEquals("attach/", second!!.config.idDir)
        config = AttachConfig(idDir = "attach/")
        assertSame(second, src.current())
        assertEquals(2, created)
    }

    @Test
    fun `null without a store or with attachments off`() {
        assertNull(source().current())
        store = JvmFileStore(tmp.newFolder("vault"))
        assertNull(source(enabled = false).current())
    }
}
